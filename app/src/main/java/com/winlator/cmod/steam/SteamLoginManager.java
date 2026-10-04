package com.winlator.cmod.steam;

import android.util.Log;

import java.util.concurrent.CompletableFuture;

import in.dragonbra.javasteam.steam.authentication.AuthPollResult;
import in.dragonbra.javasteam.steam.authentication.AuthSessionDetails;
import in.dragonbra.javasteam.steam.authentication.CredentialsAuthSession;
import in.dragonbra.javasteam.steam.authentication.IAuthenticator;
import in.dragonbra.javasteam.steam.authentication.QrAuthSession;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;

/**
 * Phase 1 of the Steam headless-agent path: sign in with account name +
 * password (+ Steam Guard) through JavaSteam and keep only the resulting
 * refresh token. Nothing is downloaded or launched here; later phases hand the
 * stored session to The412Banner's SteamLite agent at game launch.
 * Steam 헤드리스 에이전트 경로 1단계: JavaSteam으로 계정 이름 + 비밀번호
 * (+ Steam Guard) 로그인을 하고, 결과로 받은 refresh token만 보관함. 여기서는
 * 다운로드도 실행도 안 함. 이후 단계에서 게임 실행 시 저장된 세션을
 * The412Banner의 SteamLite 에이전트에 넘김
 *
 * Runs on its own thread; every UI callback is delivered on that thread, so
 * the caller must post to the main thread itself.
 * 자체 스레드에서 돌고, 모든 UI 콜백도 그 스레드에서 호출되므로 호출부가 직접
 * 메인 스레드로 넘겨야 함
 */
public final class SteamLoginManager {
    private static final String TAG = "SteamLoginManager";

    /** UI hooks for the login flow / 로그인 흐름용 UI 연결부 */
    public interface Listener {
        /** Steam Guard code (mobile app or e-mail). Complete the future with the code.
         *  Steam Guard 코드(모바일 앱 또는 이메일). 코드로 future를 완료할 것 */
        void onCodeRequired(boolean email, boolean previousWasWrong, CompletableFuture<String> code);
        /** Approve-in-app prompt was sent to the Steam mobile app
         *  Steam 모바일 앱으로 승인 요청이 전송됨 */
        void onConfirmInMobileApp();
        void onSuccess(String accountName, long steamId64);
        void onFailed(String message);
    }

    private volatile boolean running;

    /** QR flow hooks / QR 흐름용 연결부 */
    public interface QrListener {
        /** New or refreshed challenge URL - redraw the QR code.
         *  Steam refreshes it periodically while waiting.
         *  새로 받았거나 갱신된 challenge URL - QR 코드를 다시 그릴 것.
         *  대기 중에 Steam이 주기적으로 바꿈 */
        void onQrUrl(String url);
        void onSuccess(String accountName, long steamId64);
        void onFailed(String message);
    }

    /**
     * Sign in by scanning a QR code with the Steam mobile app - no password or
     * Steam Guard code typed on this device.
     * Steam 모바일 앱으로 QR 코드를 찍어 로그인함 - 이 기기에서 비밀번호나
     * Steam Guard 코드를 입력하지 않음
     */
    public void loginWithQr(final SteamAccountStore store, final QrListener listener) {
        running = true;
        new Thread(() -> {
            final SteamClient client = new SteamClient();
            final CallbackManager manager = new CallbackManager(client);

            manager.subscribe(DisconnectedCallback.class, cb -> {
                if (running) {
                    running = false;
                    listener.onFailed("Disconnected from Steam");
                }
            });
            manager.subscribe(ConnectedCallback.class, cb -> new Thread(() -> {
                try {
                    AuthSessionDetails details = new AuthSessionDetails();
                    details.persistentSession = true;
                    details.deviceFriendlyName = "EZlator (" + android.os.Build.MODEL + ")";

                    QrAuthSession session = client.getAuthentication().beginAuthSessionViaQR(details).get();
                    session.setChallengeUrlChanged(s -> {
                        if (s != null) listener.onQrUrl(s.getChallengeUrl());
                    });
                    listener.onQrUrl(session.getChallengeUrl());

                    // Interval unit differs between JavaSteam versions (seconds or ms);
                    // small values are treated as seconds
                    // JavaSteam 버전마다 간격 단위가 다름(초 또는 ms). 작은 값은 초로 봄
                    double iv = session.getPollingInterval();
                    long sleepMs = iv < 100 ? (long) (iv * 1000) : (long) iv;
                    if (sleepMs < 1000) sleepMs = 1000;

                    AuthPollResult result = null;
                    while (running && result == null) {
                        result = session.pollAuthSessionStatus().get();
                        if (result == null) Thread.sleep(sleepMs);
                    }
                    if (result == null) return; // cancelled / 취소됨

                    String account = result.getAccountName();
                    String token = result.getRefreshToken();
                    long steamId = SteamAccountStore.steamIdFromToken(token);
                    if (account == null || account.isEmpty() || token == null || token.isEmpty() || steamId <= 0)
                        throw new IllegalStateException("Steam returned no session");

                    store.save(account, token, steamId);
                    running = false;
                    listener.onSuccess(account, steamId);
                } catch (Exception e) {
                    Log.w(TAG, "QR login failed", e);
                    if (running) {
                        running = false;
                        Throwable cause = e.getCause() != null ? e.getCause() : e;
                        listener.onFailed(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
                    }
                } finally {
                    client.disconnect();
                }
            }).start());

            client.connect();
            while (running) manager.runWaitCallbacks(1000L);
        }, "SteamQrLogin").start();
    }

    public void login(final String username, final String password, final SteamAccountStore store, final Listener listener) {
        running = true;
        new Thread(() -> {
            final SteamClient client = new SteamClient();
            final CallbackManager manager = new CallbackManager(client);

            manager.subscribe(DisconnectedCallback.class, cb -> {
                if (running) {
                    running = false;
                    listener.onFailed("Disconnected from Steam");
                }
            });
            manager.subscribe(ConnectedCallback.class, cb -> new Thread(() -> {
                try {
                    AuthSessionDetails details = new AuthSessionDetails();
                    details.username = username.trim();
                    details.password = password; // not trimmed: passwords may start/end with spaces / 앞뒤 공백도 비밀번호일 수 있어 자르지 않음
                    details.persistentSession = true;
                    details.deviceFriendlyName = "EZlator (" + android.os.Build.MODEL + ")";
                    details.authenticator = new IAuthenticator() {
                        @Override
                        public CompletableFuture<String> getDeviceCode(boolean previousCodeWasIncorrect) {
                            CompletableFuture<String> f = new CompletableFuture<>();
                            listener.onCodeRequired(false, previousCodeWasIncorrect, f);
                            return f;
                        }

                        @Override
                        public CompletableFuture<String> getEmailCode(String email, boolean previousCodeWasIncorrect) {
                            CompletableFuture<String> f = new CompletableFuture<>();
                            listener.onCodeRequired(true, previousCodeWasIncorrect, f);
                            return f;
                        }

                        @Override
                        public CompletableFuture<Boolean> acceptDeviceConfirmation() {
                            listener.onConfirmInMobileApp();
                            return CompletableFuture.completedFuture(true);
                        }
                    };

                    CredentialsAuthSession session = client.getAuthentication()
                            .beginAuthSessionViaCredentials(details).get();
                    AuthPollResult result = session.pollingWaitForResult().get();

                    String account = result.getAccountName();
                    String token = result.getRefreshToken();
                    long steamId = SteamAccountStore.steamIdFromToken(token);
                    if (account == null || account.isEmpty() || token == null || token.isEmpty() || steamId <= 0)
                        throw new IllegalStateException("Steam returned no session");

                    store.save(account, token, steamId);
                    running = false;
                    listener.onSuccess(account, steamId);
                } catch (Exception e) {
                    Log.w(TAG, "login failed", e);
                    running = false;
                    Throwable cause = e.getCause() != null ? e.getCause() : e;
                    listener.onFailed(cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName());
                } finally {
                    client.disconnect();
                }
            }).start());

            client.connect();
            // Pump callbacks until the flow ends / 흐름이 끝날 때까지 콜백을 돌림
            while (running) manager.runWaitCallbacks(1000L);
        }, "SteamLogin").start();
    }

    public void cancel() {
        running = false;
    }
}
