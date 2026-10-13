package com.winlator.cmod.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Release names ("EZlator-0.2.0", "EZlator-0.2.0-beta") compared for the
 * update check, and the choice of the newest release to offer. A beta build
 * (even versionCode) is offered betas and releases; a release build (odd
 * versionCode) only releases. Pure Java (no Android classes) so it can be
 * tested off the device.
 * 업데이트 확인에 쓰는 릴리즈 이름("EZlator-0.2.0", "EZlator-0.2.0-beta") 비교와
 * 알려 줄 가장 새 릴리즈 고르기. 베타 빌드(versionCode 짝수)에는 베타와 정식을,
 * 정식 빌드(versionCode 홀수)에는 정식만 알려 줌. 안드로이드 클래스를 쓰지 않는
 * 순수 Java라 기기 밖에서 시험할 수 있음
 */
public final class ReleaseVersion implements Comparable<ReleaseVersion> {
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?(?:[-_ ]?([A-Za-z]+)\\d*)?");

    public final int major, minor, patch;
    // A name with a suffix ("-beta", "-rc") counts below the same number without one
    // 꼬리말("-beta", "-rc")이 있는 이름은 같은 번호의 꼬리말 없는 이름보다 낮게 셈
    public final boolean prerelease;

    private ReleaseVersion(int major, int minor, int patch, boolean prerelease) {
        this.major = major;
        this.minor = minor;
        this.patch = patch;
        this.prerelease = prerelease;
    }

    /** null when the text has no version number / 버전 번호가 없으면 null */
    public static ReleaseVersion parse(String text) {
        if (text == null) return null;
        Matcher m = VERSION.matcher(text);
        if (!m.find()) return null;
        int patch = m.group(3) != null ? Integer.parseInt(m.group(3)) : 0;
        String suffix = m.group(4);
        return new ReleaseVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), patch,
                suffix != null && !suffix.isEmpty());
    }

    @Override
    public int compareTo(ReleaseVersion o) {
        if (major != o.major) return Integer.compare(major, o.major);
        if (minor != o.minor) return Integer.compare(minor, o.minor);
        if (patch != o.patch) return Integer.compare(patch, o.patch);
        if (prerelease != o.prerelease) return prerelease ? -1 : 1;
        return 0;
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (prerelease ? "-beta" : "");
    }

    /** A beta build has an even versionCode / 베타 빌드는 versionCode가 짝수임 */
    public static boolean isBetaBuild(long versionCode) {
        return versionCode % 2 == 0;
    }

    /** One release from the list / 목록의 릴리즈 하나 */
    public static final class Release {
        public final String tag, url;
        public final boolean prerelease, draft;

        public Release(String tag, String url, boolean prerelease, boolean draft) {
            this.tag = tag;
            this.url = url;
            this.prerelease = prerelease;
            this.draft = draft;
        }
    }

    /**
     * The newest release newer than currentName, or null. Drafts are skipped;
     * pre-releases (GitHub flag or a suffix in the name) only when includeBeta.
     * currentName보다 새로운 가장 새 릴리즈, 없으면 null. 초안은 건너뜀. 프리릴리즈
     * (GitHub 표시 또는 이름의 꼬리말)는 includeBeta일 때만 셈
     */
    public static Release pickNewer(List<Release> releases, String currentName, boolean includeBeta) {
        ReleaseVersion current = parse(currentName);
        if (current == null || releases == null) return null;
        Release best = null;
        ReleaseVersion bestVer = null;
        for (Release r : releases) {
            if (r == null || r.draft) continue;
            ReleaseVersion v = parse(r.tag);
            if (v == null) continue;
            boolean pre = r.prerelease || v.prerelease;
            if (pre && !includeBeta) continue;
            if (v.compareTo(current) <= 0) continue;
            if (bestVer == null || v.compareTo(bestVer) > 0) {
                best = r;
                bestVer = v;
            }
        }
        return best;
    }

    // Lower-case name without the app prefix, for messages ("0.2.0-beta")
    // 안내 문구용으로 앱 이름을 뺀 이름("0.2.0-beta")
    public static String shortName(String tag) {
        if (tag == null) return "";
        String t = tag.trim();
        if (t.toLowerCase(Locale.ENGLISH).startsWith("ezlator-")) t = t.substring(8);
        return t;
    }
}
