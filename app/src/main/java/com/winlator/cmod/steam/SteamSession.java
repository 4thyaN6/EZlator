package com.winlator.cmod.steam;

import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import in.dragonbra.javasteam.enums.EResult;
import in.dragonbra.javasteam.steam.handlers.steamapps.License;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSProductInfo;
import in.dragonbra.javasteam.steam.handlers.steamapps.PICSRequest;
import in.dragonbra.javasteam.steam.handlers.steamapps.SteamApps;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.LicenseListCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSProductInfoCallback;
import in.dragonbra.javasteam.steam.handlers.steamapps.callback.PICSTokensCallback;
import in.dragonbra.javasteam.steam.handlers.steamuser.LogOnDetails;
import in.dragonbra.javasteam.steam.handlers.steamuser.SteamUser;
import in.dragonbra.javasteam.steam.handlers.steamuser.callback.LoggedOnCallback;
import in.dragonbra.javasteam.steam.steamclient.SteamClient;
import in.dragonbra.javasteam.steam.steamclient.callbackmgr.CallbackManager;
import in.dragonbra.javasteam.steam.steamclient.callbacks.ConnectedCallback;
import in.dragonbra.javasteam.steam.steamclient.callbacks.DisconnectedCallback;
import in.dragonbra.javasteam.steam.handlers.steamuserstats.AchievementBlocks;
import in.dragonbra.javasteam.steam.handlers.steamuserstats.SteamUserStats;
import in.dragonbra.javasteam.steam.handlers.steamuserstats.callback.UserStatsCallback;
import in.dragonbra.javasteam.types.AsyncJobMultiple;
import in.dragonbra.javasteam.types.KeyValue;
import in.dragonbra.javasteam.types.SteamID;

/**
 * Phase 3: a logged-on Steam connection using the SAVED refresh token (no
 * password), plus the owned-games lookup the download needs. Every call here
 * was checked against the javasteam 1.8.0 sources.
 * 3단계: 저장된 refresh token으로(비밀번호 없이) 로그온한 Steam 연결과, 다운로드에
 * 필요한 보유 게임 조회를 담당함. 여기 쓴 호출은 전부 javasteam 1.8.0 소스로 확인함
 *
 * Blocking; use off the UI thread. Nothing fetched here is written to storage.
 * 블로킹 호출이므로 UI 스레드 밖에서 쓸 것. 여기서 받은 정보는 저장하지 않음
 */
public final class SteamSession implements AutoCloseable {
    private static final String TAG = "SteamSession";

    public static final class OwnedGame {
        public final int appId;
        public final String name;
        OwnedGame(int appId, String name) { this.appId = appId; this.name = name; }
    }

    /** What the appmanifest and shortcut need / appmanifest와 바로가기에 필요한 정보 */
    public static final class AppDetails {
        public int appId;
        public String name = "";
        public String installDir = "";
        public String buildId = "0";
        public String exePath = "";   // relative, backslashes / 상대 경로, 역슬래시
        public List<Integer> depotIds = new ArrayList<>();
        // depotId -> owning app for shared depots ("depotfromapp", e.g. 228989 -> 228980)
        // 공유 depot의 depotId -> 소유 앱("depotfromapp", 예: 228989 -> 228980)
        public Map<Integer, Integer> sharedDepots = new HashMap<>();
    }

    private final SteamClient client = new SteamClient();
    private final CallbackManager manager = new CallbackManager(client);
    private volatile boolean running;
    private volatile EResult logOnResult;
    private volatile List<License> licenses;
    private final CountDownLatch loggedOn = new CountDownLatch(1);
    private final CountDownLatch licensesReady = new CountDownLatch(1);

    /**
     * Steam refused the saved refresh token (expired, revoked, password changed).
     * Only signing in again helps; a network failure is NOT this exception.
     * 스팀이 저장된 refresh token을 거부함(만료, 취소, 비밀번호 변경). 다시 로그인하는
     * 것만 해결책임. 네트워크 실패는 이 예외가 아님
     */
    public static final class TokenRejectedException extends IllegalStateException {
        public final EResult result;
        TokenRejectedException(EResult result) {
            super("Steam refused the saved sign-in: " + result);
            this.result = result;
        }
    }

    public SteamClient getClient() { return client; }
    public List<License> getLicenses() { return licenses; }

    /** Connect + log on with the stored token. / 저장된 토큰으로 연결 + 로그온 */
    public void open(SteamAccountStore store) throws Exception {
        if (!store.isLoggedIn()) throw new IllegalStateException("Not signed in");
        // JavaSteam's own log -> logcat "SteamSession" while connecting, so a failed
        // log on shows its real cause (it printed nowhere before)
        // 연결하는 동안 JavaSteam 자체 로그를 로그캣 "SteamSession"으로 옮김. 로그온이
        // 실패하면 실제 원인이 보이게 하기 위함(예전엔 어디에도 안 찍혔음)
        in.dragonbra.javasteam.util.log.LogListener bridge = new in.dragonbra.javasteam.util.log.LogListener() {
            @Override public void onLog(Class<?> c, String m, Throwable t) { Log.i(TAG, c.getSimpleName() + ": " + m, t); }
            @Override public void onError(Class<?> c, String m, Throwable t) { Log.e(TAG, c.getSimpleName() + ": " + m, t); }
        };
        in.dragonbra.javasteam.util.log.LogManager.addListener(bridge);
        try {
            openInternal(store);
        } catch (TokenRejectedException e) {
            // Leave the mark so every part of the app (both processes) treats the
            // account as signed out until the next sign-in
            // 표시를 남겨서 앱의 모든 부분(두 프로세스 모두)이 다음 로그인 전까지
            // 이 계정을 로그아웃 상태로 보게 함
            store.markTokenRejected();
            throw e;
        } finally {
            in.dragonbra.javasteam.util.log.LogManager.removeListener(bridge);
        }
    }

    private void openInternal(SteamAccountStore store) throws Exception {
        final String account = store.getAccountName();
        final String token = store.getRefreshToken();

        manager.subscribe(ConnectedCallback.class, cb -> {
            LogOnDetails d = new LogOnDetails();
            d.setUsername(account);
            d.setAccessToken(token); // javasteam: "This is actually your refresh token."
            d.setShouldRememberPassword(true);
            // loginID removed again: added earlier together with the provider
            // swap, and log on broke right after - both reverted to the last
            // working state to rule them out
            // loginID는 다시 뺌: 예전에 공급자 교체와 함께 넣었고 그 직후 로그온이
            // 깨졌음 - 원인에서 배제하려고 둘 다 마지막 정상 상태로 되돌림
            SteamUser user = client.getHandler(SteamUser.class);
            if (user != null) user.logOn(d);
        });
        manager.subscribe(LoggedOnCallback.class, cb -> {
            logOnResult = cb.getResult();
            loggedOn.countDown();
        });
        manager.subscribe(LicenseListCallback.class, cb -> {
            licenses = cb.getLicenseList();
            licensesReady.countDown();
        });
        manager.subscribe(DisconnectedCallback.class, cb -> {
            loggedOn.countDown();
            licensesReady.countDown();
        });

        running = true;
        Thread pump = new Thread(() -> {
            while (running) manager.runWaitCallbacks(1000L);
        }, "SteamSessionPump");
        pump.setDaemon(true);
        pump.start();

        client.connect();
        boolean answered = loggedOn.await(60, TimeUnit.SECONDS);
        // Steam answered and refused the token -> sign-in needed (see isTokenRefusal)
        // 스팀이 응답했고 토큰을 거부함 -> 다시 로그인 필요(isTokenRefusal 참고)
        if (answered && isTokenRefusal(logOnResult)) throw new TokenRejectedException(logOnResult);
        if (!answered || logOnResult != EResult.OK)
            throw new IllegalStateException(logOnResult == null
                    ? "Disconnected before log on (see logcat SteamSession)"
                    : "Steam log on failed: " + logOnResult);
        if (!licensesReady.await(30, TimeUnit.SECONDS) || licenses == null)
            throw new IllegalStateException("No license list from Steam");
        Log.i(TAG, "logged on, licenses=" + licenses.size());
    }

    // Log-on results that mean the saved token itself is no good. Anything else
    // (no answer, busy, try another server, ...) keeps the token for a retry.
    // The list is the one DepotDownloader uses to drop a refused token, plus
    // CachedCredentialInvalid (GameNative); all six exist in javasteam 1.8.0
    // 저장된 토큰 자체가 쓸 수 없다는 뜻의 로그온 결과임. 그 밖의 결과(응답 없음,
    // 바쁨, 다른 서버로 재시도 등)는 다시 시도하도록 토큰을 남김.
    // 목록은 DepotDownloader가 거부된 토큰을 지울 때 쓰는 것에 CachedCredentialInvalid
    // (GameNative)를 더한 것임. 6개 모두 javasteam 1.8.0에 있음
    static boolean isTokenRefusal(EResult r) {
        if (r == null) return false;
        switch (r) {
            case InvalidPassword:
            case InvalidSignature:
            case AccessDenied:
            case Expired:
            case Revoked:
            case CachedCredentialInvalid:
                return true;
            default:
                return false;
        }
    }

    private SteamApps apps() {
        SteamApps a = client.getHandler(SteamApps.class);
        if (a == null) throw new IllegalStateException("SteamApps handler missing");
        return a;
    }

    /** Owned games (type "game"), sorted by name. / 보유 게임("game" 타입), 이름순 */
    public List<OwnedGame> listOwnedGames() throws Exception {
        // licenses -> packages -> app ids / 라이선스 -> 패키지 -> 앱 ID
        List<PICSRequest> pkgReq = new ArrayList<>();
        for (License l : licenses) pkgReq.add(new PICSRequest(l.getPackageID(), l.getAccessToken()));
        LinkedHashSet<Integer> appIds = new LinkedHashSet<>();
        for (List<PICSRequest> chunk : chunks(pkgReq, 100)) {
            for (PICSProductInfoCallback cb : productInfo(Collections.emptyList(), chunk)) {
                for (PICSProductInfo p : cb.getPackages().values()) {
                    for (KeyValue kv : p.getKeyValues().get("appids").getChildren()) {
                        int id = kv.asInteger(0);
                        if (id > 0) appIds.add(id);
                    }
                }
            }
        }

        List<OwnedGame> out = new ArrayList<>();
        for (Map.Entry<Integer, PICSProductInfo> e : appInfo(new ArrayList<>(appIds)).entrySet()) {
            KeyValue common = e.getValue().getKeyValues().get("common");
            String type = common.get("type").asString();
            String name = common.get("name").asString();
            if (type != null && type.equalsIgnoreCase("game") && name != null) out.add(new OwnedGame(e.getKey(), name));
        }
        Collections.sort(out, (a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    /** Name / installdir / public buildid / Windows exe / depots of one app.
     *  앱 하나의 이름 / installdir / public buildid / Windows exe / depot 목록 */
    public AppDetails getAppDetails(int appId) throws Exception {
        PICSProductInfo info = appInfo(Collections.singletonList(appId)).get(appId);
        if (info == null) throw new IllegalStateException("No app info for " + appId);
        KeyValue root = info.getKeyValues();
        AppDetails d = new AppDetails();
        d.appId = appId;
        String n = root.get("common").get("name").asString();
        if (n != null) d.name = n;
        String dir = root.get("config").get("installdir").asString();
        if (dir != null) d.installDir = dir;
        String b = root.get("depots").get("branches").get("public").get("buildid").asString();
        if (b != null) d.buildId = b;

        // First launch entry that runs on Windows / Windows에서 도는 첫 실행 항목
        for (KeyValue launch : root.get("config").get("launch").getChildren()) {
            String exe = launch.get("executable").asString();
            String os = launch.get("config").get("oslist").asString();
            if (exe == null || exe.isEmpty()) continue;
            if (os != null && !os.isEmpty() && !os.toLowerCase(java.util.Locale.ENGLISH).contains("windows")) continue;
            d.exePath = exe.replace('/', '\\');
            break;
        }
        for (KeyValue depot : root.get("depots").getChildren()) {
            try {
                int id = Integer.parseInt(depot.getName());
                d.depotIds.add(id);
                String from = depot.get("depotfromapp").asString();
                if (from != null && !from.isEmpty()) d.sharedDepots.put(id, Integer.parseInt(from.trim()));
            } catch (Exception ignored) {}
        }
        return d;
    }

    private Map<Integer, PICSProductInfo> appInfo(List<Integer> appIds) throws Exception {
        Map<Integer, PICSProductInfo> out = new HashMap<>();
        for (List<Integer> chunk : chunks(appIds, 100)) {
            // Access tokens first (owned apps may need them) / 먼저 접근 토큰(보유 앱에 필요할 수 있음)
            PICSTokensCallback tokens = apps().picsGetAccessTokens(chunk, Collections.emptyList())
                    .toFuture().get(60, TimeUnit.SECONDS);
            List<PICSRequest> req = new ArrayList<>();
            for (int id : chunk) {
                Long t = tokens.getAppTokens().get(id);
                req.add(new PICSRequest(id, t != null ? t : 0L));
            }
            for (PICSProductInfoCallback cb : productInfo(req, Collections.emptyList())) out.putAll(cb.getApps());
        }
        return out;
    }

    private List<PICSProductInfoCallback> productInfo(List<PICSRequest> appReq, List<PICSRequest> pkgReq) throws Exception {
        AsyncJobMultiple.ResultSet<PICSProductInfoCallback> rs =
                apps().picsGetProductInfo(appReq, pkgReq).toFuture().get(90, TimeUnit.SECONDS);
        return rs.getResults();
    }

    /**
     * Achievements of one app for one account: names, descriptions and icons
     * from the game's stats schema, unlock times from the account's stats
     * (SteamUserStats.getUserStats, javasteam 1.8.0). Names and descriptions use
     * the given Steam language ("koreana", "english" ...) and fall back to
     * English, then to the internal name. Empty list = the game has none.
     * 한 계정의 한 게임 도전과제임: 이름·설명·아이콘은 게임 통계 스키마에서, 해금
     * 시각은 계정 통계에서 가져옴(SteamUserStats.getUserStats, javasteam 1.8.0).
     * 이름·설명은 주어진 스팀 언어("koreana", "english" 등)로 쓰고, 없으면 영어,
     * 그것도 없으면 내부 이름을 씀. 빈 목록 = 도전과제가 없는 게임
     */
    public List<SteamAchievements.Achievement> getAchievements(int appId, long steamId64, String language) throws Exception {
        SteamUserStats userStats = client.getHandler(SteamUserStats.class);
        if (userStats == null) throw new IllegalStateException("SteamUserStats handler missing");
        UserStatsCallback cb = userStats.getUserStats(appId, new SteamID(steamId64)).toFuture().get(30, TimeUnit.SECONDS);
        // Diagnosis of what Steam sent: raw unlock times in the blocks before the
        // schema walk. raw > 0 with 0 unlocked after the walk = mapping problem here;
        // raw = 0 = Steam returned no unlocks for this account and game
        // 스팀이 보낸 내용 진단: 스키마 순회 전 블록 안의 원본 해금 시각 수. 원본 > 0인데
        // 순회 뒤 달성이 0이면 여기 대응 문제, 원본 = 0이면 스팀이 이 계정·게임의
        // 해금을 보내지 않은 것임
        int rawUnlocked = 0;
        for (AchievementBlocks blk : cb.getAchievementBlocks())
            for (Integer t : blk.getUnlockTime()) if (t != null && t != 0) rawUnlocked++;
        Log.i(TAG, "user stats app=" + appId + " result=" + cb.getResult() + " gameId=" + cb.getGameId()
                // No schema size here: getSchema() returns protobuf's ByteString, which
                // JavaSteam keeps off the app's compile classpath (build error)
                // 스키마 크기는 찍지 않음: getSchema()는 protobuf의 ByteString을 돌려주는데,
                // JavaSteam이 이 라이브러리를 앱 컴파일 경로에 내놓지 않음(빌드 오류)
                + " crc=" + cb.getCrcStats() + " schemaEntries=" + cb.getSchemaKeyValues().getChildren().size()
                + " blocks=" + cb.getAchievementBlocks().size() + " rawUnlocked=" + rawUnlocked
                + " steamId=" + steamId64);
        if (cb.getResult() != EResult.OK) throw new IllegalStateException("user stats: " + cb.getResult());
        // Own walk over the schema instead of JavaSteam's getExpandedAchievements():
        // on the device that one gave 0 unlocked although Steam sent 9 unlock times
        // (app 2273430: rawUnlocked=9, unlocked=0). Schema layout: stats/<statId>/bits/
        // <key>/{bit, name, display/{name/<lang>, desc/<lang>, icon, icon_gray,
        // hidden}}. The unlock time of an achievement is unlock_time[bit] of the
        // block whose achievement_id equals statId. The bit index is the "bit"
        // value, or the key itself when "bit" is missing
        // JavaSteam의 getExpandedAchievements() 대신 스키마를 직접 순회함: 기기에서
        // 그 함수는 스팀이 해금 시각 9개를 보냈는데도 달성 0을 돌려줬음(앱 2273430:
        // rawUnlocked=9, unlocked=0). 스키마 구조: stats/<statId>/bits/<키>/{bit, name,
        // display/{name/<언어>, desc/<언어>, icon, icon_gray, hidden}}. 도전과제의 해금
        // 시각은 achievement_id가 statId인 블록의 unlock_time[bit]임. bit 번호는 "bit"
        // 값, 없으면 키 자체를 씀
        java.util.Map<Integer, List<Integer>> times = new HashMap<>();
        for (AchievementBlocks blk : cb.getAchievementBlocks()) {
            times.put(blk.getAchievementId(), blk.getUnlockTime());
            // Which indices of this block carry an unlock time (diagnosis)
            // 이 블록에서 해금 시각이 있는 번호(진단용)
            StringBuilder idx = new StringBuilder();
            List<Integer> ut = blk.getUnlockTime();
            for (int i = 0; i < ut.size(); i++) if (ut.get(i) != null && ut.get(i) != 0) idx.append(i).append(' ');
            Log.i(TAG, "block id=" + blk.getAchievementId() + " size=" + ut.size() + " unlockedIdx=[" + idx.toString().trim() + "]");
        }
        KeyValue schema = cb.getSchemaKeyValues();
        KeyValue stats = schema.get("stats");
        // Root names seen, for the case where "stats" is not at the top level
        // "stats"가 맨 위에 없을 때를 대비해 맨 위 이름을 남김
        StringBuilder rootNames = new StringBuilder();
        for (KeyValue c : schema.getChildren()) rootNames.append(c.getName()).append(' ');
        Log.i(TAG, "schema root=[" + rootNames.toString().trim() + "] name=" + schema.getName()
                + " stats=" + stats.getChildren().size());

        List<SteamAchievements.Achievement> out = new ArrayList<>();
        for (KeyValue stat : stats.getChildren()) {
            KeyValue bits = stat.get("bits");
            if (bits.getChildren().isEmpty()) continue;
            int statId = parseIntOr(stat.getName(), -1);
            List<Integer> ut = times.get(statId);
            int logged = 0;
            for (KeyValue bit : bits.getChildren()) {
                int index = parseIntOr(bit.get("bit").getValue(), parseIntOr(bit.getName(), -1));
                KeyValue display = bit.get("display");
                SteamAchievements.Achievement a = new SteamAchievements.Achievement();
                a.apiName = firstText(bit.get("name").getValue(), stat.getName() + "_" + index);
                a.name = firstText(text(display.get("name"), language), text(display.get("name"), "english"), a.apiName);
                a.description = firstText(text(display.get("desc"), language), text(display.get("desc"), "english"), "");
                a.icon = display.get("icon").getValue();
                a.iconGray = display.get("icon_gray").getValue();
                a.hidden = "1".equals(display.get("hidden").getValue());
                Integer t = ut != null && index >= 0 && index < ut.size() ? ut.get(index) : null;
                // Unix seconds (0 = locked); the int is read unsigned
                // 유닉스 초(0 = 미달성). int를 부호 없는 값으로 읽음
                a.unlockTime = t != null ? (t & 0xffffffffL) : 0L;
                if (logged++ < 3)
                    Log.i(TAG, "map stat=" + statId + " key=" + bit.getName() + " bit=" + bit.get("bit").getValue()
                            + " -> index " + index + " unlock=" + a.unlockTime + " (" + a.apiName + ")");
                out.add(a);
            }
        }
        int unlocked = 0;
        for (SteamAchievements.Achievement a : out) if (a.isUnlocked()) unlocked++;
        Log.i(TAG, "achievements app=" + appId + " total=" + out.size() + " unlocked=" + unlocked + " lang=" + language);
        return out;
    }

    // Text of a schema field: per-language child ("koreana", "english"), or the
    // field's own value when it is a plain string. null when there is none
    // 스키마 필드의 글자: 언어별 하위 값("koreana", "english"), 일반 문자열이면 그
    // 필드 자체의 값. 없으면 null
    private static String text(KeyValue field, String language) {
        String v = field.get(language).getValue();
        if (v != null) return v;
        return field.getChildren().isEmpty() ? field.getValue() : null;
    }

    private static int parseIntOr(String s, int fallback) {
        if (s == null) return fallback;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return fallback; }
    }

    // First non-empty text of the candidates
    // 후보 중 처음으로 비어 있지 않은 글자
    private static String firstText(String... candidates) {
        for (String c : candidates) if (c != null && !c.trim().isEmpty()) return c;
        return "";
    }

    private static <T> List<List<T>> chunks(List<T> list, int size) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < list.size(); i += size) out.add(list.subList(i, Math.min(list.size(), i + size)));
        return out;
    }

    @Override
    public void close() {
        running = false;
        try { client.disconnect(); } catch (Exception ignored) {}
    }
}
