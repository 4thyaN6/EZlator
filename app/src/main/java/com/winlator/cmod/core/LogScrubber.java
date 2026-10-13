package com.winlator.cmod.core;

import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hides private values in log text before it is shown or shared: the Steam
 * sign-in token, account name and SteamID, the token's decoded contents that
 * the Steam agent writes, and e-mail addresses.
 * Game names, exe names and game paths are kept (needed to read a report).
 * Pure Java (no Android classes) so it can be tested off the device.
 * 로그 글을 보여 주거나 공유하기 전에 개인 값을 가림: 스팀 로그인 토큰, 계정
 * 이름, SteamID, 스팀 에이전트가 쓰는 토큰 풀이 내용, 이메일 주소. 게임 이름·exe
 * 이름·게임 경로는 남김(제보를 읽는 데 필요함).
 * 안드로이드 클래스를 쓰지 않는 순수 Java라 기기 밖에서 시험할 수 있음
 */
public final class LogScrubber {
    private LogScrubber() {}

    public static final String HIDDEN = "***";

    // JSON Web Token (Steam refresh tokens are JWTs: three base64url parts)
    // JSON 웹 토큰(스팀 refresh token은 JWT임: base64url 세 부분)
    private static final Pattern JWT = Pattern.compile("eyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}");
    // SteamID64 of individual accounts: 7656119 + 10 digits
    // 개인 계정의 SteamID64: 7656119 + 숫자 10자리
    private static final Pattern STEAM_ID64 = Pattern.compile("(?<![0-9])7656119[0-9]{10}(?![0-9])");
    // The agent prints the decoded token ("token JWT payload: {...}"): all of it goes
    // 에이전트가 풀어 쓴 토큰 내용("token JWT payload: {...}")은 통째로 가림
    private static final Pattern JWT_PAYLOAD = Pattern.compile("(token JWT payload:)[^\\r\\n]*");
    // Environment lines that carry the session ("WN_STEAM_TOKEN=..." etc.)
    // 세션 값이 든 환경변수 줄("WN_STEAM_TOKEN=..." 등)
    private static final Pattern SESSION_ENV = Pattern.compile("(WN_STEAM_(?:TOKEN|USERNAME|STEAMID)\\s*[=:]\\s*)[^\\s\"';,]+");
    // E-mail addresses (Android and Steam logs can print account mails)
    // 이메일 주소(안드로이드와 스팀 로그에 계정 메일이 찍힐 수 있음)
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    /**
     * Returns text with every secret replaced by "***". Secrets shorter than 3
     * characters are ignored (they would hide unrelated text).
     * 모든 비밀 값을 "***"로 바꾼 글을 돌려줌. 3글자보다 짧은 값은 무시함
     * (상관없는 글까지 가리게 되므로)
     */
    public static String scrub(String text, Collection<String> secrets) {
        if (text == null || text.isEmpty()) return text;
        String out = text;
        if (secrets != null) {
            for (String s : secrets) {
                if (s == null || s.trim().length() < 3) continue;
                out = replaceIgnoreCase(out, s.trim());
            }
        }
        out = JWT_PAYLOAD.matcher(out).replaceAll("$1 " + HIDDEN);
        out = SESSION_ENV.matcher(out).replaceAll("$1" + HIDDEN);
        out = JWT.matcher(out).replaceAll(HIDDEN);
        out = STEAM_ID64.matcher(out).replaceAll("<steamid>");
        out = EMAIL.matcher(out).replaceAll("<email>");
        return out;
    }

    // Whole words only, so an account name like "game" does not hide "Game.exe"
    // 단어 단위로만 바꿈. 그래서 "game" 같은 계정 이름이 "Game.exe"를 가리지 않음
    private static String replaceIgnoreCase(String text, String secret) {
        Matcher m = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(secret) + "(?![A-Za-z0-9_.])",
                Pattern.CASE_INSENSITIVE).matcher(text);
        return m.find() ? m.replaceAll(Matcher.quoteReplacement(HIDDEN)) : text;
    }
}
