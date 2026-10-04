package com.winlator.cmod.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Builds substitute fonts that carry the names of the Korean Windows fonts
 * (Malgun Gothic, Gulim, Batang ...) out of open-licensed (SIL OFL 1.1) fonts.
 * Some games (Unity titles in particular) open the files in C:\windows\Fonts
 * and pick a font by the family name stored inside the file, so a font with
 * another name is never used even when the registry points to it.
 * The base font gives the Hangul and Latin glyphs; glyphs it lacks in the CJK
 * ranges (Hanja, kana, CJK symbols) are taken from a supplement font. The
 * supplement may be a variable font: its outlines are moved to the requested
 * weight with the font's own variation data (gvar) first.
 * The output is built on the device and never shipped with the app; the
 * copyright and license records of both source fonts are kept in the result.
 * 한국어 Windows 글꼴 이름(맑은 고딕, 굴림, 바탕 등)을 가진 대체 글꼴을 공개
 * 라이선스(SIL OFL 1.1) 글꼴로 만듦. 일부 게임(특히 Unity 게임)은
 * C:\windows\Fonts의 파일을 직접 열고 파일 안에 적힌 글꼴 이름으로 고르므로,
 * 레지스트리가 가리켜도 이름이 다른 글꼴은 쓰지 않음.
 * 한글·영문 글자는 기본 글꼴에서 가져오고, 기본 글꼴에 없는 CJK 영역 글자(한자,
 * 가나, CJK 기호)는 보충 글꼴에서 가져옴. 보충 글꼴이 가변 글꼴이면 글꼴 자체의
 * 변형 데이터(gvar)로 원하는 굵기의 윤곽선을 먼저 만듦.
 * 결과물은 기기에서 만들고 앱에 담아 배포하지 않음. 두 원본 글꼴의 저작권·라이선스
 * 기록은 결과물에 그대로 남김
 */
public final class KoreanFontBuilder {
    private KoreanFontBuilder() {}

    /**
     * Names written into one face of the output font
     * 출력 글꼴의 한 글꼴(face)에 기록할 이름들
     */
    public static final class Face {
        public final String family;
        public final String familyKo;
        public final String subfamily;
        public final String postScript;

        public Face(String family, String familyKo, String subfamily, String postScript) {
            this.family = family;
            this.familyKo = familyKo;
            this.subfamily = subfamily;
            this.postScript = postScript;
        }
    }

    // Tables that are dropped from the output: the signature becomes invalid,
    // and the per-glyph device/vertical tables no longer match the glyph count
    // 출력에서 빼는 테이블: 서명은 무효가 되고, 글자별 장치·세로쓰기 테이블은
    // 글자 수가 달라져서 맞지 않게 됨
    private static final String[] DROPPED_TABLES = {
            "DSIG", "hdmx", "LTSH", "VDMX", "vhea", "vmtx",
            "fvar", "gvar", "avar", "HVAR", "VVAR", "MVAR", "STAT", "cvar"
    };

    /**
     * Builds the font. supplement may be null (no extra glyphs are added then).
     * weight is the "wght" value used when the supplement is a variable font.
     * One face writes a .ttf, several faces a .ttc collection sharing every
     * table except the name table.
     * 글꼴을 만듦. supplement는 null이어도 됨(그때는 글자를 추가하지 않음).
     * weight는 보충 글꼴이 가변 글꼴일 때 쓸 "wght" 값.
     * 글꼴 하나면 .ttf, 여러 개면 name 테이블만 다르고 나머지는 공유하는 .ttc 묶음을 씀
     */
    public static void build(File baseFile, File supplementFile, int weight, Face[] faces, File out) throws IOException {
        Font base = new Font(readAll(baseFile));
        Font supp = supplementFile != null ? new Font(readAll(supplementFile)) : null;

        int baseUpm = base.u16(base.off("head") + 18);
        int baseNum = base.u16(base.off("maxp") + 4);
        TreeMap<Integer, Integer> cmap = base.cmap();

        // Supplement glyphs to add: CJK code points missing from the base font
        // 추가할 보충 글자: 기본 글꼴에 없는 CJK 코드 포인트
        List<byte[]> addedGlyphs = new ArrayList<>();
        List<int[]> addedMetrics = new ArrayList<>();   // {advance, lsb, xMin, yMin, xMax, yMax, points, contours}
        if (supp != null) {
            int suppUpm = supp.u16(supp.off("head") + 18);
            double scale = (double) baseUpm / suppUpm;
            double[] coords = supp.normalizedCoords(weight);
            HashMap<Integer, Integer> suppToNew = new HashMap<>();
            for (Map.Entry<Integer, Integer> e : supp.cmap().entrySet()) {
                int cp = e.getKey();
                if (!isCjkSupplementRange(cp) || cmap.containsKey(cp)) continue;
                int sgid = e.getValue();
                Integer newGid = suppToNew.get(sgid);
                if (newGid == null) {
                    if (baseNum + addedGlyphs.size() >= 0xFFFF) break;
                    Outline o = supp.outline(sgid);
                    // Composite glyphs are skipped (none in the CJK ranges of the supported fonts)
                    // 복합 글자는 건너뜀(지원하는 글꼴의 CJK 영역에는 없음)
                    if (o == null) continue;
                    if (coords != null) supp.applyVariations(sgid, o, coords);
                    int[] m = new int[8];
                    m[0] = (int) Math.floor(supp.advance(sgid) * scale + 0.5);
                    addedGlyphs.add(encodeGlyph(o, scale, m));
                    addedMetrics.add(m);
                    newGid = baseNum + addedGlyphs.size() - 1;
                    suppToNew.put(sgid, newGid);
                }
                cmap.put(cp, newGid);
            }
        }

        // Shared tables
        // 공유 테이블
        Map<String, byte[]> tables = new TreeMap<>();
        for (String tag : base.tags()) {
            boolean drop = false;
            for (String d : DROPPED_TABLES) if (d.equals(tag)) { drop = true; break; }
            if (!drop) tables.put(tag, base.copy(tag));
        }
        int newNum = baseNum + addedGlyphs.size();
        buildGlyphTables(base, baseNum, addedGlyphs, addedMetrics, tables);
        tables.put("cmap", buildCmap(cmap));
        tables.put("post", buildPost(base));
        patchOs2(tables.get("OS/2"));

        // Copyright and license records of the source fonts
        // 원본 글꼴들의 저작권·라이선스 기록
        String copyright = base.name(0);
        String license = base.name(13);
        String licenseUrl = base.name(14);
        String sources = base.name(1);
        if (supp != null && !addedGlyphs.isEmpty()) {
            String c2 = supp.name(0);
            if (c2 != null && !c2.isEmpty() && (copyright == null || !copyright.contains(c2))) {
                copyright = (copyright != null ? copyright + "\n" : "") + c2;
            }
            sources += " + " + supp.name(1);
        }
        if (license == null || license.isEmpty()) {
            license = "This Font Software is licensed under the SIL Open Font License, Version 1.1.";
        }
        if (licenseUrl == null || licenseUrl.isEmpty()) licenseUrl = "https://openfontlicense.org";

        List<Map<String, byte[]>> fonts = new ArrayList<>();
        for (Face face : faces) {
            Map<String, byte[]> t = new TreeMap<>(tables);
            t.put("name", buildName(face, copyright, license, licenseUrl, sources));
            fonts.add(t);
        }
        writeFonts(fonts, out);
        android.util.Log.i("KoreanFontBuilder", out.getName() + ": " + baseNum + " + " + (newNum - baseNum) + " glyphs");
    }

    /**
     * Copyright string (name ID 0) of a font file, or null. Used to tell a real
     * Windows font copied in by the user (copyright of Microsoft) from ours.
     * 글꼴 파일의 저작권 문자열(name ID 0), 없으면 null. 사용자가 넣은 진짜
     * Windows 글꼴(Microsoft 저작권)과 우리가 만든 글꼴을 구별할 때 씀
     */
    public static String readCopyright(File file) {
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            byte[] head = new byte[16];
            raf.readFully(head);
            int fontOff = 0;
            if (head[0] == 't' && head[1] == 't' && head[2] == 'c' && head[3] == 'f') {
                fontOff = (int) u32(head, 12);
            }
            raf.seek(fontOff);
            byte[] hdr = new byte[12];
            raf.readFully(hdr);
            int numTables = u16(hdr, 4);
            byte[] dir = new byte[16 * numTables];
            raf.readFully(dir);
            for (int i = 0; i < numTables; i++) {
                if (dir[i * 16] == 'n' && dir[i * 16 + 1] == 'a' && dir[i * 16 + 2] == 'm' && dir[i * 16 + 3] == 'e') {
                    int off = (int) u32(dir, i * 16 + 8);
                    int len = (int) u32(dir, i * 16 + 12);
                    byte[] name = new byte[len];
                    raf.seek(off);
                    raf.readFully(name);
                    return nameString(name, 0, 0);
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    // Code points taken from the supplement: CJK radicals to CJK ideographs
    // (incl. CJK symbols, kana, extension A), compatibility ideographs,
    // half/full-width forms and the supplementary ideograph planes. Their
    // advance widths do not change with the weight, so no HVAR is needed
    // 보충 글꼴에서 가져오는 코드 포인트: CJK 부수 ~ CJK 한자(CJK 기호, 가나,
    // 확장 A 포함), 호환 한자, 반각·전각 형태, 보조 한자 평면. 이 영역은 굵기가
    // 바뀌어도 글자 폭이 같아서 HVAR가 필요 없음
    private static boolean isCjkSupplementRange(int cp) {
        return (cp >= 0x2E80 && cp <= 0x9FFF) || (cp >= 0xF900 && cp <= 0xFAFF)
                || (cp >= 0xFF00 && cp <= 0xFFEF) || (cp >= 0x20000 && cp <= 0x3FFFF);
    }

    // ---------------------------------------------------------------------
    // Reading
    // 읽기
    // ---------------------------------------------------------------------

    private static byte[] readAll(File f) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            byte[] d = new byte[(int) raf.length()];
            raf.readFully(d);
            return d;
        }
    }

    private static int u8(byte[] d, int p) { return d[p] & 0xFF; }
    private static int u16(byte[] d, int p) { return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF); }
    private static int s16(byte[] d, int p) { return (short) u16(d, p); }
    private static long u32(byte[] d, int p) { return ((long) u16(d, p) << 16) | u16(d, p + 2); }
    private static void w16(byte[] d, int p, int v) { d[p] = (byte) (v >> 8); d[p + 1] = (byte) v; }
    private static void w32(byte[] d, int p, long v) { w16(d, p, (int) (v >>> 16)); w16(d, p + 2, (int) v); }

    // Name string from a name table: Windows Unicode English first, then any
    // Windows Unicode record, then Macintosh Roman
    // name 테이블의 문자열: Windows 유니코드 영어를 먼저, 그다음 아무 Windows
    // 유니코드 기록, 마지막으로 Macintosh Roman
    private static String nameString(byte[] d, int t, int nameId) {
        int count = u16(d, t + 2);
        int strOff = t + u16(d, t + 4);
        String any = null, mac = null;
        for (int i = 0; i < count; i++) {
            int r = t + 6 + 12 * i;
            int platform = u16(d, r), encoding = u16(d, r + 2), lang = u16(d, r + 4), id = u16(d, r + 6);
            int len = u16(d, r + 8), off = u16(d, r + 10);
            if (id != nameId) continue;
            if (platform == 3 && (encoding == 1 || encoding == 10)) {
                String s = new String(d, strOff + off, len, StandardCharsets.UTF_16BE);
                if (lang == 0x0409) return s;
                if (any == null) any = s;
            } else if (platform == 1 && encoding == 0 && mac == null) {
                mac = new String(d, strOff + off, len, StandardCharsets.ISO_8859_1);
            }
        }
        return any != null ? any : mac;
    }

    /**
     * A glyph outline: contour end points, coordinates and on-curve flags
     * 글자 윤곽선: 윤곽 끝점, 좌표, 곡선 위 점 여부
     */
    private static final class Outline {
        int[] ends;
        double[] x;
        double[] y;
        boolean[] on;
    }

    /**
     * An sfnt font held in memory with its table directory
     * 테이블 목록과 함께 메모리에 올린 sfnt 글꼴
     */
    private static final class Font {
        final byte[] d;
        final Map<String, int[]> tables = new TreeMap<>();

        Font(byte[] d) throws IOException {
            this.d = d;
            int off = 0;
            if (d.length > 16 && d[0] == 't' && d[1] == 't' && d[2] == 'c' && d[3] == 'f') off = (int) KoreanFontBuilder.u32(d, 12);
            int numTables = KoreanFontBuilder.u16(d, off + 4);
            for (int i = 0; i < numTables; i++) {
                int r = off + 12 + 16 * i;
                String tag = new String(d, r, 4, StandardCharsets.ISO_8859_1);
                tables.put(tag, new int[]{(int) KoreanFontBuilder.u32(d, r + 8), (int) KoreanFontBuilder.u32(d, r + 12)});
            }
            for (String need : new String[]{"head", "maxp", "hhea", "hmtx", "loca", "glyf", "cmap", "name"}) {
                if (!tables.containsKey(need)) throw new IOException("not a TrueType font (no " + need + ")");
            }
        }

        Iterable<String> tags() { return tables.keySet(); }
        boolean has(String tag) { return tables.containsKey(tag); }
        int off(String tag) { return tables.get(tag)[0]; }
        int len(String tag) { return tables.get(tag)[1]; }
        int u16(int p) { return KoreanFontBuilder.u16(d, p); }
        int s16(int p) { return KoreanFontBuilder.s16(d, p); }
        long u32(int p) { return KoreanFontBuilder.u32(d, p); }

        byte[] copy(String tag) {
            int[] t = tables.get(tag);
            byte[] r = new byte[t[1]];
            System.arraycopy(d, t[0], r, 0, t[1]);
            return r;
        }

        String name(int id) { return nameString(d, off("name"), id); }

        int numHMetrics() { return u16(off("hhea") + 34); }

        int advance(int gid) {
            int n = numHMetrics();
            return u16(off("hmtx") + 4 * Math.min(gid, n - 1));
        }

        int lsb(int gid) {
            int n = numHMetrics();
            return gid < n ? s16(off("hmtx") + 4 * gid + 2) : s16(off("hmtx") + 4 * n + 2 * (gid - n));
        }

        long loca(int gid) {
            int l = off("loca");
            return s16(off("head") + 50) == 0 ? 2L * u16(l + 2 * gid) : u32(l + 4 * gid);
        }

        // Unicode cmap: format 12 (full Unicode) when present, otherwise format 4
        // 유니코드 cmap: format 12(전체 유니코드)가 있으면 그것을, 없으면 format 4를 읽음
        TreeMap<Integer, Integer> cmap() {
            int c = off("cmap");
            int n = u16(c + 2);
            int best = -1, bestRank = 0;
            for (int i = 0; i < n; i++) {
                int r = c + 4 + 8 * i;
                int platform = u16(r), encoding = u16(r + 2);
                int sub = c + (int) u32(r + 4);
                int format = u16(sub);
                int rank = 0;
                if (format == 12 && (platform == 3 && encoding == 10 || platform == 0)) rank = 4;
                else if (format == 4 && platform == 3 && encoding == 1) rank = 3;
                else if (format == 4 && platform == 0) rank = 2;
                if (rank > bestRank) { bestRank = rank; best = sub; }
            }
            TreeMap<Integer, Integer> map = new TreeMap<>();
            if (best < 0) return map;
            if (u16(best) == 12) {
                long groups = u32(best + 12);
                for (int g = 0; g < groups; g++) {
                    int r = best + 16 + 12 * g;
                    long start = u32(r), end = u32(r + 4), gid = u32(r + 8);
                    for (long cp = start; cp <= end; cp++) map.put((int) cp, (int) (gid + cp - start));
                }
            } else {
                int segX2 = u16(best + 6);
                int ends = best + 14, starts = ends + segX2 + 2, deltas = starts + segX2, ranges = deltas + segX2;
                for (int s = 0; s < segX2 / 2; s++) {
                    int end = u16(ends + 2 * s), start = u16(starts + 2 * s);
                    int delta = u16(deltas + 2 * s), ro = u16(ranges + 2 * s);
                    for (int cp = start; cp <= end && cp != 0xFFFF; cp++) {
                        int gid;
                        if (ro == 0) gid = (cp + delta) & 0xFFFF;
                        else {
                            gid = u16(ranges + 2 * s + ro + 2 * (cp - start));
                            if (gid != 0) gid = (gid + delta) & 0xFFFF;
                        }
                        if (gid != 0) map.put(cp, gid);
                    }
                }
            }
            return map;
        }

        // Simple glyph outline; null for a composite glyph
        // 단순 글자의 윤곽선. 복합 글자면 null
        Outline outline(int gid) {
            long start = loca(gid), end = loca(gid + 1);
            Outline o = new Outline();
            if (end <= start) {
                o.ends = new int[0]; o.x = new double[0]; o.y = new double[0]; o.on = new boolean[0];
                return o;
            }
            int p = off("glyf") + (int) start;
            int contours = s16(p);
            if (contours < 0) return null;
            o.ends = new int[contours];
            for (int i = 0; i < contours; i++) o.ends[i] = u16(p + 10 + 2 * i);
            int points = contours > 0 ? o.ends[contours - 1] + 1 : 0;
            p += 10 + 2 * contours;
            p += 2 + u16(p);
            int[] flags = new int[points];
            for (int i = 0; i < points; ) {
                int f = d[p++] & 0xFF;
                flags[i++] = f;
                if ((f & 0x08) != 0) {
                    int rep = d[p++] & 0xFF;
                    while (rep-- > 0 && i < points) flags[i++] = f;
                }
            }
            o.x = new double[points]; o.y = new double[points]; o.on = new boolean[points];
            int v = 0;
            for (int i = 0; i < points; i++) {
                int f = flags[i];
                if ((f & 0x02) != 0) { int b = d[p++] & 0xFF; v += (f & 0x10) != 0 ? b : -b; }
                else if ((f & 0x10) == 0) { v += s16(p); p += 2; }
                o.x[i] = v;
                o.on[i] = (f & 0x01) != 0;
            }
            v = 0;
            for (int i = 0; i < points; i++) {
                int f = flags[i];
                if ((f & 0x04) != 0) { int b = d[p++] & 0xFF; v += (f & 0x20) != 0 ? b : -b; }
                else if ((f & 0x20) == 0) { v += s16(p); p += 2; }
                o.y[i] = v;
            }
            return o;
        }

        // Normalized design coordinates (fvar + avar) for the given weight;
        // null when the font is not variable
        // 주어진 굵기의 정규화 좌표(fvar + avar). 가변 글꼴이 아니면 null
        double[] normalizedCoords(int weight) {
            if (!has("fvar") || !has("gvar")) return null;
            int f = off("fvar");
            int axesOff = f + u16(f + 4), axisCount = u16(f + 8), axisSize = u16(f + 10);
            double[] coords = new double[axisCount];
            for (int a = 0; a < axisCount; a++) {
                int r = axesOff + a * axisSize;
                String tag = new String(d, r, 4, StandardCharsets.ISO_8859_1);
                double min = (int) u32(r + 4) / 65536.0, def = (int) u32(r + 8) / 65536.0, max = (int) u32(r + 12) / 65536.0;
                double v = tag.equals("wght") ? Math.max(min, Math.min(max, weight)) : def;
                double n = v < def ? (v - def) / (def - min) : v > def ? (v - def) / (max - def) : 0;
                coords[a] = Math.round(n * 16384) / 16384.0;
            }
            if (has("avar")) {
                int p = off("avar") + 8;
                for (int a = 0; a < Math.min(axisCount, u16(off("avar") + 6)); a++) {
                    int count = u16(p);
                    double v = coords[a];
                    for (int k = 0; k + 1 < count; k++) {
                        double f0 = s16(p + 2 + 4 * k) / 16384.0, t0 = s16(p + 4 + 4 * k) / 16384.0;
                        double f1 = s16(p + 6 + 4 * k) / 16384.0, t1 = s16(p + 8 + 4 * k) / 16384.0;
                        if (v >= f0 && v <= f1) {
                            coords[a] = f1 == f0 ? t0 : t0 + (v - f0) * (t1 - t0) / (f1 - f0);
                            break;
                        }
                    }
                    coords[a] = Math.round(coords[a] * 16384) / 16384.0;
                    p += 2 + 4 * count;
                }
            }
            return coords;
        }

        // Moves the outline to the given design coordinates with the gvar data
        // (OpenType "TrueType glyph variations": tuple scalars, packed point
        // numbers, packed deltas and IUP for points without explicit deltas)
        // gvar 데이터로 윤곽선을 주어진 좌표로 옮김(OpenType 글리프 변형: 튜플
        // 배율, 압축된 점 번호, 압축된 변화량, 변화량이 없는 점의 보간(IUP))
        void applyVariations(int gid, Outline o, double[] coords) {
            int g = off("gvar");
            int axisCount = u16(g + 4);
            int sharedTuples = g + (int) u32(g + 8);
            int glyphCount = u16(g + 12);
            int flags = u16(g + 14);
            int dataArray = g + (int) u32(g + 16);
            if (gid >= glyphCount) return;
            long start, end;
            if ((flags & 1) != 0) { start = u32(g + 20 + 4 * gid); end = u32(g + 24 + 4 * gid); }
            else { start = 2L * u16(g + 20 + 2 * gid); end = 2L * u16(g + 22 + 2 * gid); }
            if (end <= start) return;
            int base = dataArray + (int) start;
            int tupleField = u16(base);
            int count = tupleField & 0x0FFF;
            int[] cur = {base + u16(base + 2)};
            int[] sharedPoints = (tupleField & 0x8000) != 0 ? readPoints(cur) : null;
            boolean sharedAll = (tupleField & 0x8000) != 0 && sharedPoints == null;
            int hdr = base + 4;
            int points = o.x.length;
            int total = points + 4;
            double[] sumX = new double[points], sumY = new double[points];

            for (int t = 0; t < count; t++) {
                int dataSize = u16(hdr), index = u16(hdr + 2);
                hdr += 4;
                double[] peak = new double[axisCount];
                if ((index & 0x8000) != 0) {
                    for (int a = 0; a < axisCount; a++) peak[a] = s16(hdr + 2 * a) / 16384.0;
                    hdr += 2 * axisCount;
                } else {
                    int s = sharedTuples + (index & 0x0FFF) * axisCount * 2;
                    for (int a = 0; a < axisCount; a++) peak[a] = s16(s + 2 * a) / 16384.0;
                }
                double[] lo = null, hi = null;
                if ((index & 0x4000) != 0) {
                    lo = new double[axisCount]; hi = new double[axisCount];
                    for (int a = 0; a < axisCount; a++) lo[a] = s16(hdr + 2 * a) / 16384.0;
                    hdr += 2 * axisCount;
                    for (int a = 0; a < axisCount; a++) hi[a] = s16(hdr + 2 * a) / 16384.0;
                    hdr += 2 * axisCount;
                }
                int dataStart = cur[0];
                cur[0] += dataSize;
                double scalar = 1;
                for (int a = 0; a < axisCount && scalar != 0; a++) {
                    double pk = peak[a], c = coords[a];
                    if (pk == 0) continue;
                    if (c == 0) { scalar = 0; break; }
                    if (lo != null) {
                        if (c < lo[a] || c > hi[a]) { scalar = 0; break; }
                        if (c < pk) { if (pk != lo[a]) scalar *= (c - lo[a]) / (pk - lo[a]); }
                        else if (c > pk) { if (hi[a] != pk) scalar *= (hi[a] - c) / (hi[a] - pk); }
                    } else {
                        if (c < Math.min(0, pk) || c > Math.max(0, pk)) { scalar = 0; break; }
                        scalar *= c / pk;
                    }
                }
                if (scalar == 0) continue;

                int[] dc = {dataStart};
                int[] pts;
                boolean all;
                if ((index & 0x2000) != 0) { pts = readPoints(dc); all = pts == null; }
                else { pts = sharedPoints; all = sharedAll || (tupleField & 0x8000) == 0; }
                int n = all ? total : pts.length;
                int[] dx = readDeltas(dc, n), dy = readDeltas(dc, n);
                if (all) {
                    for (int i = 0; i < points; i++) { sumX[i] += scalar * dx[i]; sumY[i] += scalar * dy[i]; }
                } else {
                    double[] tx = new double[points], ty = new double[points];
                    boolean[] touched = new boolean[points];
                    for (int k = 0; k < n; k++) {
                        int pi = pts[k];
                        if (pi >= points) continue;
                        tx[pi] += dx[k]; ty[pi] += dy[k]; touched[pi] = true;
                    }
                    iup(o, tx, ty, touched);
                    for (int i = 0; i < points; i++) { sumX[i] += scalar * tx[i]; sumY[i] += scalar * ty[i]; }
                }
            }
            for (int i = 0; i < points; i++) {
                o.x[i] = Math.floor(o.x[i] + sumX[i] + 0.5);
                o.y[i] = Math.floor(o.y[i] + sumY[i] + 0.5);
            }
        }

        // Packed point numbers; null means "all points"
        // 압축된 점 번호. null이면 "모든 점"
        int[] readPoints(int[] cur) {
            int b = d[cur[0]++] & 0xFF;
            if (b == 0) return null;
            int n = (b & 0x80) != 0 ? ((b & 0x7F) << 8) | (d[cur[0]++] & 0xFF) : b;
            int[] r = new int[n];
            int i = 0, last = 0;
            while (i < n) {
                int ctrl = d[cur[0]++] & 0xFF;
                int run = (ctrl & 0x7F) + 1;
                boolean words = (ctrl & 0x80) != 0;
                for (int k = 0; k < run && i < n; k++) {
                    if (words) { last += u16(cur[0]); cur[0] += 2; }
                    else last += d[cur[0]++] & 0xFF;
                    r[i++] = last;
                }
            }
            return r;
        }

        // Packed deltas
        // 압축된 변화량
        int[] readDeltas(int[] cur, int n) {
            int[] r = new int[n];
            int i = 0;
            while (i < n) {
                int ctrl = d[cur[0]++] & 0xFF;
                int run = (ctrl & 0x3F) + 1;
                if ((ctrl & 0x80) != 0) i += run;
                else if ((ctrl & 0x40) != 0) {
                    for (int k = 0; k < run && i < n; k++) { r[i++] = s16(cur[0]); cur[0] += 2; }
                } else {
                    for (int k = 0; k < run && i < n; k++) r[i++] = d[cur[0]++];
                }
            }
            return r;
        }
    }

    // Interpolation of untouched points (IUP), per contour and per axis
    // 변화량이 지정되지 않은 점의 보간(IUP). 윤곽마다, 축마다 계산함
    private static void iup(Outline o, double[] dx, double[] dy, boolean[] touched) {
        int s = 0;
        for (int end : o.ends) {
            List<Integer> refs = new ArrayList<>();
            for (int i = s; i <= end; i++) if (touched[i]) refs.add(i);
            if (refs.size() == 1) {
                int r = refs.get(0);
                for (int i = s; i <= end; i++) { dx[i] = dx[r]; dy[i] = dy[r]; }
            } else if (refs.size() > 1) {
                int len = end - s + 1;
                for (int k = 0; k < refs.size(); k++) {
                    int a = refs.get(k), b = refs.get((k + 1) % refs.size());
                    for (int j = a + 1; ; j++) {
                        if (j > end) j = s;
                        if (j == b) break;
                        dx[j] = interp(o.x[j], o.x[a], o.x[b], dx[a], dx[b]);
                        dy[j] = interp(o.y[j], o.y[a], o.y[b], dy[a], dy[b]);
                        if (--len < 0) break;
                    }
                }
            }
            s = end + 1;
        }
    }

    private static double interp(double v, double v1, double v2, double d1, double d2) {
        if (v1 == v2) return d1 == d2 ? d1 : 0;
        if (v1 > v2) { double t = v1; v1 = v2; v2 = t; t = d1; d1 = d2; d2 = t; }
        if (v <= v1) return d1;
        if (v >= v2) return d2;
        return d1 + (v - v1) * (d2 - d1) / (v2 - v1);
    }

    // ---------------------------------------------------------------------
    // Writing
    // 쓰기
    // ---------------------------------------------------------------------

    // Encodes a simple glyph without hinting instructions, scaled to the base
    // font's units per em. m receives {advance(kept), lsb, xMin, yMin, xMax, yMax, points, contours}
    // 힌팅 명령 없이 단순 글자로 인코딩함. 기본 글꼴의 em 단위로 맞춤.
    // m에는 {폭(유지), lsb, xMin, yMin, xMax, yMax, 점 수, 윤곽 수}를 채움
    private static byte[] encodeGlyph(Outline o, double scale, int[] m) {
        int n = o.x.length;
        m[6] = n;
        m[7] = o.ends.length;
        if (n == 0) return new byte[0];
        int[] x = new int[n], y = new int[n];
        int xMin = Integer.MAX_VALUE, yMin = Integer.MAX_VALUE, xMax = Integer.MIN_VALUE, yMax = Integer.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            x[i] = (int) Math.floor(o.x[i] * scale + 0.5);
            y[i] = (int) Math.floor(o.y[i] * scale + 0.5);
            xMin = Math.min(xMin, x[i]); xMax = Math.max(xMax, x[i]);
            yMin = Math.min(yMin, y[i]); yMax = Math.max(yMax, y[i]);
        }
        m[1] = xMin; m[2] = xMin; m[3] = yMin; m[4] = xMax; m[5] = yMax;
        ByteArrayOutputStream flags = new ByteArrayOutputStream(), xs = new ByteArrayOutputStream(), ys = new ByteArrayOutputStream();
        int[] fl = new int[n];
        int px = 0, py = 0;
        for (int i = 0; i < n; i++) {
            int f = o.on[i] ? 0x01 : 0;
            int dxv = x[i] - px, dyv = y[i] - py;
            px = x[i]; py = y[i];
            if (dxv == 0) f |= 0x10;
            else if (Math.abs(dxv) < 256) { f |= 0x02; if (dxv > 0) f |= 0x10; xs.write(Math.abs(dxv)); }
            else { xs.write(dxv >> 8); xs.write(dxv); }
            if (dyv == 0) f |= 0x20;
            else if (Math.abs(dyv) < 256) { f |= 0x04; if (dyv > 0) f |= 0x20; ys.write(Math.abs(dyv)); }
            else { ys.write(dyv >> 8); ys.write(dyv); }
            fl[i] = f;
        }
        // Contours of the source may overlap (variable fonts); flag it for the rasterizer
        // 원본 윤곽이 겹칠 수 있음(가변 글꼴). 래스터라이저에 알리는 플래그를 둠
        fl[0] |= 0x40;
        for (int i = 0; i < n; ) {
            int f = fl[i];
            int rep = 0;
            while (i + 1 + rep < n && fl[i + 1 + rep] == f && rep < 255) rep++;
            if (rep > 0) { flags.write(f | 0x08); flags.write(rep); }
            else flags.write(f);
            i += 1 + rep;
        }
        ByteArrayOutputStream g = new ByteArrayOutputStream();
        int contours = o.ends.length;
        writeShort(g, contours);
        writeShort(g, xMin); writeShort(g, yMin); writeShort(g, xMax); writeShort(g, yMax);
        for (int e : o.ends) writeShort(g, e);
        writeShort(g, 0);
        byte[] fb = flags.toByteArray(), xb = xs.toByteArray(), yb = ys.toByteArray();
        g.write(fb, 0, fb.length);
        g.write(xb, 0, xb.length);
        g.write(yb, 0, yb.length);
        while (g.size() % 4 != 0) g.write(0);
        return g.toByteArray();
    }

    private static void writeShort(ByteArrayOutputStream o, int v) { o.write(v >> 8); o.write(v); }
    private static void writeInt(ByteArrayOutputStream o, long v) { writeShort(o, (int) (v >>> 16)); writeShort(o, (int) v); }

    // glyf/loca (long offsets), hmtx (all long metrics), hhea, maxp and head
    // glyf/loca(긴 오프셋), hmtx(모두 긴 형식), hhea, maxp, head
    private static void buildGlyphTables(Font base, int baseNum, List<byte[]> added, List<int[]> metrics, Map<String, byte[]> tables) {
        int newNum = baseNum + added.size();
        long baseGlyfLen = base.loca(baseNum);
        int baseGlyfPadded = (int) ((baseGlyfLen + 3) & ~3L);
        long total = baseGlyfPadded;
        for (byte[] g : added) total += g.length;
        byte[] glyf = new byte[(int) total];
        System.arraycopy(base.d, base.off("glyf"), glyf, 0, (int) baseGlyfLen);
        byte[] loca = new byte[4 * (newNum + 1)];
        for (int i = 0; i <= baseNum; i++) w32(loca, 4 * i, i == baseNum ? baseGlyfPadded : base.loca(i));
        int pos = baseGlyfPadded;
        for (int k = 0; k < added.size(); k++) {
            byte[] g = added.get(k);
            System.arraycopy(g, 0, glyf, pos, g.length);
            pos += g.length;
            w32(loca, 4 * (baseNum + k + 1), pos);
        }
        tables.put("glyf", glyf);
        tables.put("loca", loca);

        byte[] hmtx = new byte[4 * newNum];
        int advMax = 0;
        for (int i = 0; i < baseNum; i++) {
            int adv = base.advance(i);
            advMax = Math.max(advMax, adv);
            w16(hmtx, 4 * i, adv);
            w16(hmtx, 4 * i + 2, base.lsb(i));
        }
        byte[] head = tables.get("head");
        int xMin = s16(head, 36), yMin = s16(head, 38), xMax = s16(head, 40), yMax = s16(head, 42);
        byte[] maxp = tables.get("maxp");
        int maxPoints = maxp.length >= 10 ? u16(maxp, 6) : 0, maxContours = maxp.length >= 10 ? u16(maxp, 8) : 0;
        for (int k = 0; k < added.size(); k++) {
            int[] m = metrics.get(k);
            int i = baseNum + k;
            advMax = Math.max(advMax, m[0]);
            w16(hmtx, 4 * i, m[0]);
            w16(hmtx, 4 * i + 2, m[1]);
            if (m[6] > 0) {
                xMin = Math.min(xMin, m[2]); yMin = Math.min(yMin, m[3]);
                xMax = Math.max(xMax, m[4]); yMax = Math.max(yMax, m[5]);
                maxPoints = Math.max(maxPoints, m[6]);
                maxContours = Math.max(maxContours, m[7]);
            }
        }
        tables.put("hmtx", hmtx);

        byte[] hhea = tables.get("hhea");
        w16(hhea, 10, advMax);
        w16(hhea, 34, newNum);

        w16(maxp, 4, newNum);
        if (maxp.length >= 10) { w16(maxp, 6, maxPoints); w16(maxp, 8, maxContours); }

        w32(head, 8, 0);
        w16(head, 36, xMin); w16(head, 38, yMin); w16(head, 40, xMax); w16(head, 42, yMax);
        w16(head, 50, 1);
    }

    // cmap with format 4 (BMP) and format 12 (all of Unicode). Format 4 uses
    // delta segments, or a glyph id array where that is smaller; segments that
    // do not fit its 64 KB limit are left to format 12, which Windows and
    // FreeType read first
    // format 4(BMP)와 format 12(전체 유니코드)로 cmap을 만듦. format 4는 델타
    // 구간을 쓰되 글자 번호 배열이 더 작으면 그것을 씀. 64KB 한도를 넘는 구간은
    // format 12에 맡김(Windows와 FreeType은 format 12를 먼저 읽음)
    private static byte[] buildCmap(TreeMap<Integer, Integer> map) {
        // Format 12 groups
        // format 12 그룹
        List<long[]> groups = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            long cp = e.getKey(), gid = e.getValue();
            long[] last = groups.isEmpty() ? null : groups.get(groups.size() - 1);
            if (last != null && last[1] + 1 == cp && last[2] + (cp - last[0]) == gid) last[1] = cp;
            else groups.add(new long[]{cp, cp, gid});
        }
        ByteArrayOutputStream f12 = new ByteArrayOutputStream();
        writeShort(f12, 12); writeShort(f12, 0);
        writeInt(f12, 16 + 12L * groups.size()); writeInt(f12, 0); writeInt(f12, groups.size());
        for (long[] g : groups) { writeInt(f12, g[0]); writeInt(f12, g[1]); writeInt(f12, g[2]); }

        // Format 4 segments: {start, end, delta or -1 for array}
        // format 4 구간: {시작, 끝, 델타 또는 배열이면 -1}
        List<int[]> runs = new ArrayList<>();
        for (Map.Entry<Integer, Integer> e : map.entrySet()) {
            int cp = e.getKey();
            if (cp >= 0xFFFF) break;
            int[] last = runs.isEmpty() ? null : runs.get(runs.size() - 1);
            if (last != null && last[1] + 1 == cp) last[1] = cp;
            else runs.add(new int[]{cp, cp});
        }
        List<int[]> segs = new ArrayList<>();
        for (int[] run : runs) {
            List<int[]> deltaSegs = new ArrayList<>();
            for (int cp = run[0]; cp <= run[1]; cp++) {
                int delta = map.get(cp) - cp;
                int[] last = deltaSegs.isEmpty() ? null : deltaSegs.get(deltaSegs.size() - 1);
                if (last != null && last[2] == delta) last[1] = cp;
                else deltaSegs.add(new int[]{cp, cp, delta});
            }
            int len = run[1] - run[0] + 1;
            if (8 * deltaSegs.size() > 8 + 2 * len) segs.add(new int[]{run[0], run[1], Integer.MIN_VALUE});
            else segs.addAll(deltaSegs);
        }
        // Keep within the 16-bit length field (header 16 + 8 per segment + arrays)
        // 16비트 길이 필드 안에 들어가게 자름(머리 16 + 구간당 8 + 배열)
        int size = 16 + 8;
        int keep = 0;
        for (int[] s : segs) {
            int add = 8 + (s[2] == Integer.MIN_VALUE ? 2 * (s[1] - s[0] + 1) : 0);
            if (size + add > 0xFFF0) break;
            size += add;
            keep++;
        }
        segs = new ArrayList<>(segs.subList(0, keep));
        segs.add(new int[]{0xFFFF, 0xFFFF, 1});
        int segCount = segs.size();
        int searchRange = 2 * Integer.highestOneBit(segCount);
        int entrySelector = Integer.numberOfTrailingZeros(Integer.highestOneBit(segCount));
        ByteArrayOutputStream arr = new ByteArrayOutputStream();
        int[] rangeOffsets = new int[segCount];
        for (int i = 0; i < segCount; i++) {
            int[] s = segs.get(i);
            if (s[2] == Integer.MIN_VALUE) {
                rangeOffsets[i] = 2 * (segCount - i) + arr.size();
                for (int cp = s[0]; cp <= s[1]; cp++) writeShort(arr, map.get(cp));
            }
        }
        ByteArrayOutputStream f4 = new ByteArrayOutputStream();
        writeShort(f4, 4);
        writeShort(f4, 16 + 8 * segCount + arr.size());
        writeShort(f4, 0);
        writeShort(f4, 2 * segCount);
        writeShort(f4, searchRange);
        writeShort(f4, entrySelector);
        writeShort(f4, 2 * segCount - searchRange);
        for (int[] s : segs) writeShort(f4, s[1]);
        writeShort(f4, 0);
        for (int[] s : segs) writeShort(f4, s[0]);
        for (int[] s : segs) writeShort(f4, s[2] == Integer.MIN_VALUE ? 0 : s[2]);
        for (int r : rangeOffsets) writeShort(f4, r);
        byte[] ab = arr.toByteArray();
        f4.write(ab, 0, ab.length);

        byte[] b4 = f4.toByteArray(), b12 = f12.toByteArray();
        ByteArrayOutputStream c = new ByteArrayOutputStream();
        int o4 = 4 + 8 * 4, o12 = o4 + b4.length;
        writeShort(c, 0); writeShort(c, 4);
        writeShort(c, 0); writeShort(c, 3); writeInt(c, o4);
        writeShort(c, 0); writeShort(c, 4); writeInt(c, o12);
        writeShort(c, 3); writeShort(c, 1); writeInt(c, o4);
        writeShort(c, 3); writeShort(c, 10); writeInt(c, o12);
        c.write(b4, 0, b4.length);
        c.write(b12, 0, b12.length);
        return c.toByteArray();
    }

    // post table version 3 (no glyph names), header fields kept
    // post 테이블 버전 3(글자 이름 없음). 머리 값은 그대로 둠
    private static byte[] buildPost(Font base) {
        byte[] p = new byte[32];
        if (base.has("post")) System.arraycopy(base.d, base.off("post"), p, 0, Math.min(32, base.len("post")));
        w32(p, 0, 0x00030000L);
        return p;
    }

    // OS/2: marks Korean (Wansung code page, bit 19) and the CJK ideograph
    // Unicode range (bit 59) as supported
    // OS/2: 한국어(완성형 코드 페이지, 19번 비트)와 CJK 한자 유니코드 영역(59번 비트)을
    // 지원한다고 표시함
    private static void patchOs2(byte[] os2) {
        if (os2 == null || os2.length < 82) return;
        w32(os2, 46, u32(os2, 46) | (1L << (59 - 32)));
        w32(os2, 78, u32(os2, 78) | (1L << 19));
    }

    // name table: English (0x0409) records plus the Korean (0x0412) family and
    // full names, as in the Korean Windows fonts
    // name 테이블: 영어(0x0409) 기록과 한국어(0x0412) 글꼴 이름·전체 이름.
    // 한국어 Windows 글꼴과 같은 구성
    private static byte[] buildName(Face face, String copyright, String license, String licenseUrl, String sources) {
        boolean regular = face.subfamily.equalsIgnoreCase("Regular");
        String full = regular ? face.family : face.family + " " + face.subfamily;
        String fullKo = regular ? face.familyKo : face.familyKo + " " + face.subfamily;
        String ps = regular ? face.postScript : face.postScript + "-" + face.subfamily.replace(" ", "");
        TreeMap<Integer, String> en = new TreeMap<>();
        en.put(0, copyright != null ? copyright : "");
        en.put(1, face.family);
        en.put(2, face.subfamily);
        en.put(3, "EZlator substitute: " + full);
        en.put(4, full);
        en.put(5, "Version 1.00");
        en.put(6, ps);
        en.put(10, "Substitute font generated on the device by EZlator from " + sources
                + " (SIL Open Font License 1.1). Not a Microsoft font.");
        en.put(13, license);
        en.put(14, licenseUrl);
        TreeMap<Integer, String> ko = new TreeMap<>();
        ko.put(1, face.familyKo);
        ko.put(4, fullKo);

        List<Object[]> recs = new ArrayList<>();
        for (Map.Entry<Integer, String> e : en.entrySet()) recs.add(new Object[]{0x0409, e.getKey(), e.getValue()});
        for (Map.Entry<Integer, String> e : ko.entrySet()) recs.add(new Object[]{0x0412, e.getKey(), e.getValue()});
        ByteArrayOutputStream strings = new ByteArrayOutputStream();
        ByteArrayOutputStream n = new ByteArrayOutputStream();
        writeShort(n, 0);
        writeShort(n, recs.size());
        writeShort(n, 6 + 12 * recs.size());
        for (Object[] r : recs) {
            byte[] s = ((String) r[2]).getBytes(StandardCharsets.UTF_16BE);
            writeShort(n, 3); writeShort(n, 1); writeShort(n, (Integer) r[0]); writeShort(n, (Integer) r[1]);
            writeShort(n, s.length); writeShort(n, strings.size());
            strings.write(s, 0, s.length);
        }
        byte[] sb = strings.toByteArray();
        n.write(sb, 0, sb.length);
        return n.toByteArray();
    }

    private static long checksum(byte[] d) {
        long sum = 0;
        int n = d.length;
        for (int i = 0; i < n; i += 4) {
            long v = 0;
            for (int k = 0; k < 4; k++) v = (v << 8) | (i + k < n ? (d[i + k] & 0xFF) : 0);
            sum = (sum + v) & 0xFFFFFFFFL;
        }
        return sum;
    }

    // Writes one font (.ttf) or a collection (.ttc). In a collection a table
    // object used by several fonts is stored once and shared
    // 글꼴 하나(.ttf) 또는 묶음(.ttc)을 씀. 묶음에서는 여러 글꼴이 쓰는 같은
    // 테이블을 한 번만 저장하고 공유함
    private static void writeFonts(List<Map<String, byte[]>> fonts, File out) throws IOException {
        boolean collection = fonts.size() > 1;
        int pos = collection ? 12 + 4 * fonts.size() : 0;
        int[] dirOffsets = new int[fonts.size()];
        for (int i = 0; i < fonts.size(); i++) {
            dirOffsets[i] = pos;
            pos += 12 + 16 * fonts.get(i).size();
        }
        IdentityHashMap<byte[], Integer> placed = new IdentityHashMap<>();
        List<byte[]> order = new ArrayList<>();
        for (Map<String, byte[]> f : fonts) {
            for (byte[] t : f.values()) {
                if (placed.containsKey(t)) continue;
                pos = (pos + 3) & ~3;
                placed.put(t, pos);
                order.add(t);
                pos += t.length;
            }
        }
        int totalLen = (pos + 3) & ~3;
        byte[] file = new byte[totalLen];
        if (collection) {
            file[0] = 't'; file[1] = 't'; file[2] = 'c'; file[3] = 'f';
            w32(file, 4, 0x00010000L);
            w32(file, 8, fonts.size());
            for (int i = 0; i < fonts.size(); i++) w32(file, 12 + 4 * i, dirOffsets[i]);
        }
        for (byte[] t : order) System.arraycopy(t, 0, file, placed.get(t), t.length);
        for (int i = 0; i < fonts.size(); i++) {
            Map<String, byte[]> f = fonts.get(i);
            int d = dirOffsets[i];
            int numTables = f.size();
            int searchRange = 16 * Integer.highestOneBit(numTables);
            w32(file, d, 0x00010000L);
            w16(file, d + 4, numTables);
            w16(file, d + 6, searchRange);
            w16(file, d + 8, Integer.numberOfTrailingZeros(Integer.highestOneBit(numTables)));
            w16(file, d + 10, 16 * numTables - searchRange);
            int r = d + 12;
            for (Map.Entry<String, byte[]> e : f.entrySet()) {
                byte[] tag = e.getKey().getBytes(StandardCharsets.ISO_8859_1);
                System.arraycopy(tag, 0, file, r, 4);
                w32(file, r + 4, checksum(e.getValue()));
                w32(file, r + 8, placed.get(e.getValue()));
                w32(file, r + 12, e.getValue().length);
                r += 16;
            }
        }
        // head.checkSumAdjustment, computed for the first font (shared head in a collection)
        // head.checkSumAdjustment. 첫 글꼴 기준으로 계산함(묶음에서는 head를 공유함)
        Map<String, byte[]> first = fonts.get(0);
        long sum = 0;
        byte[] dir = new byte[12 + 16 * first.size()];
        System.arraycopy(file, dirOffsets[0], dir, 0, dir.length);
        sum += checksum(dir);
        for (byte[] t : first.values()) sum += checksum(t);
        int headPos = placed.get(first.get("head"));
        w32(file, headPos + 8, (0xB1B0AFBAL - sum) & 0xFFFFFFFFL);

        File part = new File(out.getPath() + ".part");
        try (OutputStream os = new FileOutputStream(part)) {
            os.write(file);
        }
        if (out.exists()) out.delete();
        if (!part.renameTo(out)) throw new IOException("rename failed: " + out);
    }
}
