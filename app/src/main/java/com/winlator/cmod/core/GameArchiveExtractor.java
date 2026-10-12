package com.winlator.cmod.core;

import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry;
import org.apache.commons.compress.archivers.sevenz.SevenZFile;
import org.apache.commons.compress.archivers.sevenz.SevenZFileOptions;
import org.apache.commons.compress.archivers.sevenz.SevenZMethod;
import org.apache.commons.compress.archivers.sevenz.SevenZMethodConfiguration;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;
import org.apache.commons.compress.archivers.zip.ZipSplitReadOnlySeekableByteChannel;
import org.apache.commons.compress.utils.MultiReadOnlySeekableByteChannel;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * Unpacks a game archive (.zip / .7z, also split into volumes) next to itself so a
 * shortcut can be made from an exe inside. Plain Java (no Android classes) so it
 * can be tested off the device.
 * Volumes: name.7z.001, .002 ... / name.zip.001, .002 ... (plain byte split, as
 * 7-Zip makes them) / name.z01, .z02 ... + name.zip (WinZip / Info-ZIP split).
 * 게임 압축 파일(.zip / .7z, 분할 압축 포함)을 그 파일이 있는 폴더에 풀어서 안의
 * exe로 바로가기를 만들 수 있게 함. 기기 밖에서 시험할 수 있게 순수 Java로만 씀
 * (안드로이드 클래스 없음).
 * 분할: name.7z.001, .002 ... / name.zip.001, .002 ...(7-Zip이 만드는 단순 바이트
 * 분할) / name.z01, .z02 ... + name.zip(WinZip / Info-ZIP 분할)
 */
public final class GameArchiveExtractor {

    /** Extensions for the file picker / 파일 선택기에 넘길 확장자 */
    public static final String[] PICKER_EXTENSIONS = {".zip", ".7z", ".7z.001", ".zip.001"};

    public enum Kind { ZIP, SEVEN_Z }

    /** Why an archive cannot be unpacked / 풀 수 없는 이유 */
    public enum Problem {
        ENCRYPTED,          // password-protected / 암호가 걸림
        UNSUPPORTED,        // method this library cannot read (e.g. 7z BCJ2, zip PPMd) / 이 라이브러리가 못 읽는 방식
        MEMORY,             // 7z dictionary bigger than the memory limit / 7z 사전 크기가 메모리 상한보다 큼
        MISSING_VOLUME,     // a split volume is missing / 분할 파일 일부가 없음
        UNSAFE_PATH,        // entry escapes the target folder (../, absolute) / 대상 폴더 밖을 가리키는 항목
        CORRUPT             // anything else while reading / 그 밖의 읽기 오류
    }

    /** IOException carrying a Problem so the screen can explain it / 화면에서 설명할 수 있게 Problem을 담은 IOException */
    public static final class ArchiveException extends IOException {
        public final Problem problem;

        public ArchiveException(Problem problem, String message) {
            super(message);
            this.problem = problem;
        }

        public ArchiveException(Problem problem, String message, Throwable cause) {
            super(message, cause);
            this.problem = problem;
        }
    }

    /** Result of scan() / scan() 결과 */
    public static final class Info {
        public Kind kind;
        public List<File> volumes = new ArrayList<>();
        // Total size after unpacking / 풀었을 때 전체 크기
        public long totalBytes;
        // Bytes of the archive file(s) themselves / 압축 파일 자체 크기
        public long archiveBytes;
        public int fileCount;
        public int exeCount;
        // Single top folder shared by every entry, or null / 모든 항목이 공유하는 최상위 폴더 1개, 없으면 null
        public String topDir;
        // Charset used for zip names without the UTF-8 flag (null for 7z) / UTF-8 표시 없는 zip 이름에 쓴 문자셋(7z는 null)
        public String zipCharset;
    }

    /** Progress callback, called from the worker thread / 진행 콜백, 작업 스레드에서 불림 */
    public interface Progress {
        void onProgress(long doneBytes, long totalBytes, int doneFiles, int totalFiles, String currentName);
    }

    // Some extra room beyond the unpacked size (file system blocks, the shortcut icon ...)
    // 푼 크기 외 여유 공간(파일 시스템 블록, 바로가기 아이콘 등)
    private static final long SPACE_MARGIN_BYTES = 64L * 1024 * 1024;
    // Every file costs at least about one block on the target file system
    // 파일마다 대상 파일 시스템에서 최소 블록 하나 정도를 씀
    private static final long PER_FILE_OVERHEAD = 4096;
    private static final int BUFFER_SIZE = 256 * 1024;
    // Folder made by macOS's archiver, not part of the game / macOS 압축기가 넣는 폴더, 게임 파일 아님
    private static final String MACOS_META_DIR = "__MACOSX";
    // Hidden work folder; renamed to the final name only after a full success
    // 숨김 작업 폴더임. 전부 성공한 뒤에만 최종 이름으로 바꿈
    private static final String WORK_PREFIX = ".ezlator-unpack-";

    private GameArchiveExtractor() {}

    // ------------------------------------------------------------------ type / volumes

    /** True when the picked file is an archive this class handles / 이 클래스가 다루는 압축 파일이면 true */
    public static boolean isArchive(File file) {
        return kindOf(file) != null;
    }

    static Kind kindOf(File file) {
        String n = file.getName().toLowerCase(Locale.ENGLISH);
        if (n.endsWith(".7z") || n.endsWith(".7z.001")) return Kind.SEVEN_Z;
        if (n.endsWith(".zip") || n.endsWith(".zip.001")) return Kind.ZIP;
        return null;
    }

    /**
     * Every file that makes up the archive, in order. Throws MISSING_VOLUME when a
     * numbered volume in the middle is missing.
     * 압축을 이루는 모든 파일을 순서대로 돌려줌. 중간 번호 파일이 없으면
     * MISSING_VOLUME을 던짐
     */
    public static List<File> volumesOf(File file) throws ArchiveException {
        List<File> list = new ArrayList<>();
        String name = file.getName();
        String lower = name.toLowerCase(Locale.ENGLISH);
        File dir = file.getAbsoluteFile().getParentFile();
        if (lower.endsWith(".001")) {
            // name.7z.001, name.7z.002 ...: same width, counting up until one is missing
            // name.7z.001, name.7z.002 ...: 자릿수 그대로 번호를 올리며 없을 때까지 모음
            String stem = name.substring(0, name.length() - 3);
            for (int i = 1; i < 10000; i++) {
                File part = new File(dir, stem + String.format(Locale.ENGLISH, "%03d", i));
                if (!part.isFile()) break;
                list.add(part);
            }
            return list;
        }
        if (lower.endsWith(".zip")) {
            // WinZip split: name.z01 ... name.zNN, then name.zip as the last segment
            // WinZip 분할: name.z01 ... name.zNN 뒤에 name.zip이 마지막 조각임
            String stem = name.substring(0, name.length() - 4);
            File first = findIgnoreCase(dir, stem + ".z01");
            if (first != null) {
                for (int i = 1; i < 10000; i++) {
                    File part = findIgnoreCase(dir, stem + String.format(Locale.ENGLISH, ".z%02d", i));
                    if (part == null) break;
                    list.add(part);
                }
            }
        }
        list.add(file);
        return list;
    }

    private static File findIgnoreCase(File dir, String name) {
        File exact = new File(dir, name);
        if (exact.isFile()) return exact;
        File[] files = dir != null ? dir.listFiles() : null;
        if (files == null) return null;
        for (File f : files) if (f.isFile() && f.getName().equalsIgnoreCase(name)) return f;
        return null;
    }

    private static SeekableByteChannel openChannel(File file, List<File> volumes) throws IOException {
        String lower = file.getName().toLowerCase(Locale.ENGLISH);
        if (lower.endsWith(".001")) {
            return volumes.size() == 1
                    ? FileChannel.open(volumes.get(0).toPath(), StandardOpenOption.READ)
                    : MultiReadOnlySeekableByteChannel.forFiles(volumes.toArray(new File[0]));
        }
        if (volumes.size() > 1) {
            // Last segment first, then the .z01 ... in order (library's argument order)
            // 마지막 조각을 먼저, 그다음 .z01 ...을 순서대로 넘김(라이브러리 인자 순서)
            return ZipSplitReadOnlySeekableByteChannel.forFiles(file, volumes.subList(0, volumes.size() - 1));
        }
        return FileChannel.open(file.toPath(), StandardOpenOption.READ);
    }

    // ------------------------------------------------------------------ scan

    /**
     * Reads the archive's table of contents without unpacking anything.
     * memoryLimitKb caps the 7z decoder dictionary (heap is limited on Android).
     * 아무것도 풀지 않고 압축 목록만 읽음. memoryLimitKb는 7z 해제기의 사전
     * 크기 상한임(안드로이드는 힙이 제한됨)
     */
    public static Info scan(File archive, int memoryLimitKb) throws IOException {
        Info info = new Info();
        info.kind = kindOf(archive);
        if (info.kind == null) throw new ArchiveException(Problem.UNSUPPORTED, "not an archive: " + archive.getName());
        info.volumes = volumesOf(archive);
        if (info.volumes.isEmpty()) throw new ArchiveException(Problem.MISSING_VOLUME, "no volume found: " + archive.getName());
        for (File v : info.volumes) info.archiveBytes += v.length();

        List<String> names = new ArrayList<>();
        if (info.kind == Kind.ZIP) {
            info.zipCharset = detectZipCharset(archive, info.volumes);
            try (ZipFile zip = openZip(archive, info.volumes, info.zipCharset)) {
                Enumeration<ZipArchiveEntry> en = zip.getEntries();
                while (en.hasMoreElements()) {
                    ZipArchiveEntry e = en.nextElement();
                    String path = safeRelativePath(e.getName());
                    if (path == null) continue;
                    names.add(e.isDirectory() ? path + "/" : path);
                    if (e.isDirectory() || e.isUnixSymlink()) continue;
                    if (e.getGeneralPurposeBit().usesEncryption()) {
                        throw new ArchiveException(Problem.ENCRYPTED, "encrypted entry: " + e.getName());
                    }
                    if (!zip.canReadEntryData(e)) {
                        throw new ArchiveException(Problem.UNSUPPORTED,
                                "unsupported method " + e.getMethod() + ": " + e.getName());
                    }
                    countFile(info, path, e.getSize());
                }
            }
        } else {
            try (SevenZFile sz = openSevenZ(archive, info.volumes, memoryLimitKb)) {
                for (SevenZArchiveEntry e : sz.getEntries()) {
                    String path = safeRelativePath(e.getName());
                    if (path == null) continue;
                    names.add(e.isDirectory() ? path + "/" : path);
                    if (e.isDirectory() || e.isAntiItem()) continue;
                    countFile(info, path, e.getSize());
                }
                // Walk the entries without reading data: this builds each block's decoder,
                // so filters the library lacks (BCJ2, ARM64 ...), the memory limit and
                // encryption show up now, before anything is written. Content methods are
                // only filled in by getNextEntry().
                // 데이터는 읽지 않고 항목만 차례로 넘김: 블록마다 해제기를 만들기 때문에
                // 라이브러리에 없는 필터(BCJ2, ARM64 등), 메모리 상한, 암호가 아무것도
                // 쓰기 전에 지금 드러남. 압축 방식 정보는 getNextEntry()가 채워 줌
                try {
                    SevenZArchiveEntry e;
                    while ((e = sz.getNextEntry()) != null) {
                        if (e.hasStream()) checkSevenZMethods(e);
                    }
                } catch (IOException | RuntimeException ex) {
                    throw classify(ex);
                }
            }
        }
        info.topDir = commonTopDir(names);
        return info;
    }

    private static void countFile(Info info, String path, long size) {
        info.fileCount++;
        if (size > 0) info.totalBytes += size;
        if (path.toLowerCase(Locale.ENGLISH).endsWith(".exe")) info.exeCount++;
    }

    private static void checkSevenZMethods(SevenZArchiveEntry e) throws ArchiveException {
        Iterable<? extends SevenZMethodConfiguration> methods;
        try {
            methods = e.getContentMethods();
        } catch (RuntimeException ex) {
            throw new ArchiveException(Problem.UNSUPPORTED, "unknown method: " + e.getName(), ex);
        }
        if (methods == null) return;
        for (SevenZMethodConfiguration m : methods) {
            if (m.getMethod() == SevenZMethod.AES256SHA256) {
                throw new ArchiveException(Problem.ENCRYPTED, "encrypted entry: " + e.getName());
            }
        }
    }

    /**
     * Space needed on the target volume: unpacked size + one block per file + margin.
     * 대상 볼륨에 필요한 공간: 푼 크기 + 파일당 블록 하나 + 여유분
     */
    public static long requiredBytes(Info info) {
        return info.totalBytes + (long) info.fileCount * PER_FILE_OVERHEAD + SPACE_MARGIN_BYTES;
    }

    // ------------------------------------------------------------------ zip names

    private static ZipFile openZip(File archive, List<File> volumes, String charset) throws IOException {
        SeekableByteChannel ch;
        try {
            ch = openChannel(archive, volumes);
        } catch (IOException e) {
            throw new ArchiveException(Problem.MISSING_VOLUME, e.getMessage(), e);
        }
        try {
            // Unicode extra fields (0x7075) win over the guessed charset when present
            // 유니코드 확장 필드(0x7075)가 있으면 추정한 문자셋보다 우선함
            return new ZipFile(ch, archive.getName(), charset, true);
        } catch (IOException e) {
            closeQuietly(ch);
            throw new ArchiveException(Problem.CORRUPT, e.getMessage(), e);
        }
    }

    /**
     * Charset for names stored without the UTF-8 flag. Zips made by Windows'
     * built-in tool or older archivers keep names in the PC's ANSI code page
     * (CP949 in Korea, CP932 in Japan), with no record of which one. Order:
     * plain ASCII / valid UTF-8 -> UTF-8; otherwise the Korean or Japanese
     * reading that scores higher (see languageScore); CP437 (the zip default) when
     * neither decodes.
     * UTF-8 표시 없이 저장된 이름의 문자셋임. Windows 기본 압축 기능이나 예전
     * 압축 프로그램은 PC의 ANSI 코드 페이지(한국 CP949, 일본 CP932)로 이름을 넣고
     * 어느 것인지 기록하지 않음. 순서: ASCII뿐이거나 올바른 UTF-8이면 UTF-8,
     * 아니면 한국어/일본어 해석 중 점수가 높은 쪽(languageScore 참고), 둘 다
     * 안 풀리면 CP437(zip 기본값)
     */
    static String detectZipCharset(File archive, List<File> volumes) throws IOException {
        List<byte[]> raw = new ArrayList<>();
        try (ZipFile zip = openZip(archive, volumes, "UTF-8")) {
            Enumeration<ZipArchiveEntry> en = zip.getEntries();
            while (en.hasMoreElements()) {
                ZipArchiveEntry e = en.nextElement();
                if (e.getGeneralPurposeBit().usesUTF8ForNames()) continue;
                byte[] name = e.getRawName();
                if (name != null && !isAscii(name)) raw.add(name);
            }
        }
        return chooseCharset(raw);
    }

    static String chooseCharset(List<byte[]> rawNames) {
        if (rawNames.isEmpty() || decodesAll(rawNames, StandardCharsets.UTF_8)) return "UTF-8";
        Charset ko = firstSupported("x-windows-949", "MS949", "windows-949", "EUC-KR");
        Charset ja = firstSupported("windows-31j", "MS932", "Shift_JIS");
        String koText = ko != null ? decodeAll(rawNames, ko) : null;
        String jaText = ja != null ? decodeAll(rawNames, ja) : null;
        if (koText != null && jaText == null) return ko.name();
        if (jaText != null && koText == null) return ja.name();
        if (koText != null) {
            int koScore = languageScore(koText, true);
            int jaScore = languageScore(jaText, false);
            if (koScore > jaScore) return ko.name();
            if (jaScore > koScore) return ja.name();
            // Tie (e.g. only Kanji/Hanja): follow the device language
            // 동점(예: 한자만 있음): 기기 언어를 따름
            return "ja".equals(Locale.getDefault().getLanguage()) ? ja.name() : ko.name();
        }
        Charset cp437 = firstSupported("IBM437", "Cp437");
        return cp437 != null ? cp437.name() : "ISO-8859-1";
    }

    /**
     * How natural a decoded name looks. Korean: Hangul syllables that exist in
     * KS X 1001 (the 2,350 everyday ones; Japanese bytes read as CP949 land in the
     * rare extended syllables instead). Japanese: full-width kana; half-width kana
     * count against it (Korean bytes read as CP932 turn into half-width kana).
     * 풀어 낸 이름이 얼마나 자연스러운지임. 한국어: KS X 1001에 있는 한글 음절
     * (자주 쓰는 2,350자. 일본어 바이트를 CP949로 읽으면 드문 확장 음절이 나옴).
     * 일본어: 전각 가나. 반각 가나는 감점(한국어 바이트를 CP932로 읽으면 반각
     * 가나가 됨)
     */
    static int languageScore(String text, boolean korean) {
        int score = 0;
        if (korean) {
            Charset euc = firstSupported("EUC-KR");
            CharsetEncoder enc = euc != null ? euc.newEncoder() : null;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c >= 0xAC00 && c <= 0xD7A3) {
                    if (enc == null || enc.canEncode(c)) score++;
                    else score--;
                }
            }
        } else {
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if ((c >= 0x3040 && c <= 0x309F) || (c >= 0x30A0 && c <= 0x30FF)) score++;
                else if (c >= 0xFF61 && c <= 0xFF9F) score--;
            }
        }
        return score;
    }

    private static boolean isAscii(byte[] b) {
        for (byte x : b) if (x < 0) return false;
        return true;
    }

    private static boolean decodesAll(List<byte[]> names, Charset cs) {
        return decodeAll(names, cs) != null;
    }

    // Strict decode of every name joined by '/', or null when any byte is invalid
    // 모든 이름을 '/'로 이어 엄격하게 풂. 잘못된 바이트가 하나라도 있으면 null
    private static String decodeAll(List<byte[]> names, Charset cs) {
        CharsetDecoder dec = cs.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        StringBuilder sb = new StringBuilder();
        try {
            for (byte[] n : names) {
                sb.append(dec.decode(ByteBuffer.wrap(n))).append('/');
                dec.reset();
            }
        } catch (CharacterCodingException e) {
            return null;
        }
        return sb.toString();
    }

    private static Charset firstSupported(String... names) {
        for (String n : names) {
            try {
                if (Charset.isSupported(n)) return Charset.forName(n);
            } catch (Exception ignored) {
                // illegal name on this runtime / 이 런타임에서 쓸 수 없는 이름
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ 7z

    private static SevenZFile openSevenZ(File archive, List<File> volumes, int memoryLimitKb) throws IOException {
        SeekableByteChannel ch;
        try {
            ch = openChannel(archive, volumes);
        } catch (IOException e) {
            throw new ArchiveException(Problem.MISSING_VOLUME, e.getMessage(), e);
        }
        SevenZFileOptions options = SevenZFileOptions.builder()
                .withMaxMemoryLimitInKb(memoryLimitKb)
                .build();
        try {
            return new SevenZFile(ch, archive.getName(), options);
        } catch (IOException e) {
            closeQuietly(ch);
            throw classify(e);
        }
    }

    // Maps library exceptions to a Problem (class names keep this free of version-specific types)
    // 라이브러리 예외를 Problem으로 바꿈(버전별 타입에 묶이지 않게 클래스 이름으로 봄)
    public static ArchiveException classify(Throwable e) {
        if (e instanceof ArchiveException) return (ArchiveException) e;
        String cls = e.getClass().getSimpleName();
        String msg = String.valueOf(e.getMessage());
        if (cls.contains("PasswordRequired") || msg.contains("password")) {
            return new ArchiveException(Problem.ENCRYPTED, msg, e);
        }
        if (cls.contains("MemoryLimit")) return new ArchiveException(Problem.MEMORY, msg, e);
        // "Multi input/output stream coders are not yet supported" = 7z BCJ2 filter
        // "Multi input/output stream coders are not yet supported" = 7z BCJ2 필터임
        if (cls.contains("UnsupportedZipFeature") || e instanceof UnsupportedOperationException
                || msg.contains("Unsupported compression method") || msg.contains("not yet supported")) {
            return new ArchiveException(Problem.UNSUPPORTED, msg, e);
        }
        return new ArchiveException(Problem.CORRUPT, msg, e);
    }

    // ------------------------------------------------------------------ paths

    /**
     * Entry name -> safe relative path with '/' separators, or null for entries to
     * skip (empty, macOS metadata). Throws UNSAFE_PATH for "..", which would write
     * outside the target folder. A leading "/" or "C:" is dropped. Characters that
     * Windows and FAT/exFAT SD cards cannot hold are replaced with '_'.
     * 항목 이름을 '/' 구분의 안전한 상대 경로로 바꿈. 건너뛸 항목(빈 이름, macOS
     * 메타 정보)은 null. 대상 폴더 밖에 쓰게 되는 ".."은 UNSAFE_PATH를 던짐. 앞의
     * "/"나 "C:"는 뗌. Windows와 FAT/exFAT SD카드가 담을 수 없는 글자는 '_'로 바꿈
     */
    static String safeRelativePath(String entryName) throws ArchiveException {
        if (entryName == null) return null;
        String n = entryName.replace('\\', '/');
        if (n.length() >= 2 && n.charAt(1) == ':' && Character.isLetter(n.charAt(0))) n = n.substring(2);
        StringBuilder sb = new StringBuilder();
        for (String part : n.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) throw new ArchiveException(Problem.UNSAFE_PATH, "unsafe entry: " + entryName);
            if (sb.length() == 0 && part.equals(MACOS_META_DIR)) return null;
            String clean = part.replaceAll("[:*?\"<>|\\x00-\\x1F]", "_");
            // Windows drops trailing dots/spaces; keep the name reachable from Wine
            // Windows는 끝의 점·공백을 버림. Wine에서 찾을 수 있게 맞춤
            clean = clean.replaceAll("[. ]+$", "");
            if (clean.isEmpty()) clean = "_";
            if (sb.length() > 0) sb.append('/');
            sb.append(clean);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // The one first-level folder every entry sits under, or null
    // 모든 항목이 들어 있는 1단계 폴더 하나, 없으면 null
    static String commonTopDir(List<String> names) {
        String top = null;
        boolean hasNested = false;
        for (String n : names) {
            int slash = n.indexOf('/');
            if (slash < 0) return null; // a file right at the root / 최상위에 바로 있는 파일
            String first = n.substring(0, slash);
            if (top == null) top = first;
            else if (!top.equals(first)) return null;
            if (slash < n.length() - 1) hasNested = true;
        }
        return hasNested ? top : null;
    }

    /**
     * Final folder for the unpacked game: the archive's own top folder if it has
     * one, else the archive name without extension. " (2)", " (3)" ... is added
     * when that name is taken, so nothing is ever merged into an existing folder.
     * 풀린 게임의 최종 폴더: 압축 안에 최상위 폴더가 하나 있으면 그 이름, 없으면
     * 확장자를 뺀 압축 파일 이름. 이미 있는 이름이면 " (2)", " (3)" ...을 붙여서
     * 기존 폴더에 섞어 넣는 일이 없게 함
     */
    public static File targetFolderFor(File archive, Info info) {
        return targetFolderFor(archive.getAbsoluteFile().getParentFile(), archive, info);
    }

    /**
     * Same as targetFolderFor(archive, info), inside the given folder instead of
     * next to the archive (e.g. the archive on internal storage, the game on E:)
     * targetFolderFor(archive, info)와 같지만 압축 파일 옆이 아니라 주어진 폴더 안에
     * 만듦(예: 압축 파일은 내장 메모리, 게임은 E:)
     */
    public static File targetFolderFor(File parent, File archive, Info info) {
        String base = folderBaseName(archive, info);
        File target = new File(parent, base);
        for (int i = 2; target.exists(); i++) target = new File(parent, base + " (" + i + ")");
        return target;
    }

    // Name of the game folder: the archive's top folder, else the archive name
    // 게임 폴더 이름: 압축 안의 최상위 폴더, 없으면 압축 파일 이름
    private static String folderBaseName(File archive, Info info) {
        return info.topDir != null ? info.topDir : archiveBaseName(archive);
    }

    /**
     * The game folder this archive would update: a folder in parent with the
     * same name (an update or the same download again), or null when there is none.
     * 이 압축 파일이 업데이트할 게임 폴더: parent 안의 같은 이름 폴더(업데이트나 같은
     * 파일을 다시 받은 경우). 없으면 null
     */
    public static File existingFolderFor(File parent, File archive, Info info) {
        File f = new File(parent, folderBaseName(archive, info));
        return f.isDirectory() ? f : null;
    }

    static String archiveBaseName(File archive) {
        String n = archive.getName();
        String lower = n.toLowerCase(Locale.ENGLISH);
        for (String ext : new String[]{".7z.001", ".zip.001", ".7z", ".zip"}) {
            if (lower.endsWith(ext)) {
                n = n.substring(0, n.length() - ext.length());
                break;
            }
        }
        n = n.trim();
        return n.isEmpty() ? "Game" : n;
    }

    // ------------------------------------------------------------------ extract

    /**
     * Unpacks into targetFolderFor()'s folder. Work happens in a hidden folder next
     * to it, renamed only after every file is written; on failure or cancel the
     * hidden folder is deleted and nothing is left behind. The archive itself is
     * not touched. Returns the final folder.
     * targetFolderFor()의 폴더에 풂. 작업은 그 옆의 숨김 폴더에서 하고, 모든
     * 파일을 쓴 뒤에만 이름을 바꿈. 실패하거나 취소하면 숨김 폴더를 지워서 아무것도
     * 남기지 않음. 압축 파일 자체는 건드리지 않음. 최종 폴더를 돌려줌
     */
    public static File extract(File archive, Info info, int memoryLimitKb,
                               AtomicBoolean cancel, Progress progress) throws IOException {
        return extract(archive, info, archive.getAbsoluteFile().getParentFile(), memoryLimitKb, cancel, progress);
    }

    /**
     * Unpacks into a folder made inside destParent (see targetFolderFor). The work
     * folder is made there too, so the final rename stays on the same storage.
     * Reading the archive from one storage and writing to another (internal ->
     * SD card) is faster than reading and writing the same SD card at once.
     * destParent 안에 만든 폴더에 풂(targetFolderFor 참고). 작업 폴더도 그 안에 만들어서
     * 마지막 이름 바꾸기가 같은 저장소 안에서 일어남. 압축 파일을 한 저장소에서 읽고 다른
     * 저장소에 쓰는 것(내장 -> SD카드)이 같은 SD카드에서 읽기와 쓰기를 함께 하는 것보다 빠름
     */
    public static File extract(File archive, Info info, File destParent, int memoryLimitKb,
                               AtomicBoolean cancel, Progress progress) throws IOException {
        return extract(archive, info, destParent, null, memoryLimitKb, cancel, progress);
    }

    /**
     * updateInto != null: the archive is unpacked over that existing game folder
     * (an update). Everything is first unpacked into the hidden work folder, so a
     * cancel or an error leaves the game as it was; only then are the files moved
     * in, replacing those with the same name. Files the archive does not have
     * (saves, files the new version dropped) stay.
     * updateInto != null: 그 기존 게임 폴더 위에 풂(업데이트). 먼저 숨김 작업 폴더에 모두
     * 풀어서, 취소하거나 오류가 나면 게임은 그대로 남음. 그다음에야 파일을 옮겨 넣고 같은
     * 이름 파일을 바꿈. 압축에 없는 파일(세이브, 새 버전에서 빠진 파일)은 남음
     */
    public static File extract(File archive, Info info, File destParent, File updateInto, int memoryLimitKb,
                               AtomicBoolean cancel, Progress progress) throws IOException {
        File target = updateInto != null ? updateInto : targetFolderFor(destParent, archive, info);
        File work = new File(target.getParentFile(), WORK_PREFIX + System.currentTimeMillis());
        if (!work.mkdirs()) throw new IOException("cannot create " + work);
        try {
            Counter counter = new Counter(info, progress);
            if (info.kind == Kind.ZIP) extractZip(archive, info, work, cancel, counter);
            else extractSevenZ(archive, info, memoryLimitKb, work, cancel, counter);
            if (cancel.get()) return null;

            File content = work;
            if (info.topDir != null) {
                // The archive's own top folder becomes the game folder (no doubled folder)
                // 압축 안의 최상위 폴더가 게임 폴더가 됨(폴더가 두 겹이 되지 않게)
                content = new File(work, sanitizeSegment(info.topDir));
            }
            if (updateInto != null) {
                mergeInto(content, target);
            } else if (!content.renameTo(target)) {
                throw new IOException("cannot rename " + content + " -> " + target);
            }
            return target;
        } finally {
            // After a successful rename only the empty work folder (or nothing) is left
            // 이름 바꾸기에 성공하면 빈 작업 폴더만 남거나 아무것도 안 남음
            deleteTree(work);
        }
    }

    /**
     * Moves everything in src into dst (same storage, so each move is a rename):
     * folders are merged, a file replaces the one with the same name. A file and
     * a folder with the same name: the archive's entry wins.
     * src의 모든 것을 dst로 옮김(같은 저장소라 옮기기는 이름 바꾸기임): 폴더는 합치고, 파일은
     * 같은 이름 파일을 바꿈. 같은 이름의 파일과 폴더가 겹치면 압축 쪽을 씀
     */
    static void mergeInto(File src, File dst) throws IOException {
        File[] children = src.listFiles();
        if (children == null) return;
        mkdirsOrThrow(dst);
        for (File from : children) {
            File to = new File(dst, from.getName());
            if (from.isDirectory() && !isSymlink(from)) {
                if (to.exists() && (!to.isDirectory() || isSymlink(to))) deleteTree(to);
                if (!to.exists()) {
                    if (!from.renameTo(to)) throw new IOException("cannot move " + from + " -> " + to);
                } else {
                    mergeInto(from, to);
                }
            } else {
                if (to.isDirectory() && !isSymlink(to)) deleteTree(to);
                else if (to.exists() || isSymlink(to)) to.delete();
                if (!from.renameTo(to)) throw new IOException("cannot move " + from + " -> " + to);
            }
        }
    }

    private static String sanitizeSegment(String s) {
        try {
            String p = safeRelativePath(s);
            return p != null ? p : s;
        } catch (ArchiveException e) {
            return s;
        }
    }

    // Progress shared by the writer threads / 쓰기 스레드들이 같이 쓰는 진행 정보
    private static final class Counter {
        final Info info;
        final Progress progress;
        final java.util.concurrent.atomic.AtomicLong doneBytes = new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicInteger doneFiles = new java.util.concurrent.atomic.AtomicInteger();

        Counter(Info info, Progress progress) {
            this.info = info;
            this.progress = progress;
        }

        synchronized void report(String name) {
            if (progress != null) progress.onProgress(doneBytes.get(), info.totalBytes, doneFiles.get(), info.fileCount, name);
        }
    }

    /*
     * Speed with many small files: the time went into work done once per file,
     * not into the data. Per file there were two canonical-path lookups (one
     * system call per folder level, slow on shared storage), a folder check and a
     * single writer thread waiting on each file open/close. Now: the target path
     * is checked by text only (safeRelativePath already refuses "..", absolute
     * paths and drive letters, and nothing here creates symbolic links), folders
     * already made are remembered, and zip files are written by several threads.
     * 7z stays on one thread: its solid blocks have to be decoded in order.
     * 작은 파일이 많을 때의 속도: 시간은 데이터가 아니라 파일마다 한 번씩 하는 일에
     * 들었음. 파일마다 실제 경로 확인 2번(폴더 단계마다 시스템 호출, 공유 저장소에서
     * 느림), 폴더 확인, 그리고 파일 열기/닫기를 기다리는 쓰기 스레드 하나였음. 이제:
     * 대상 경로는 글자로만 확인함(safeRelativePath가 이미 "..", 절대 경로, 드라이브
     * 글자를 거부하고, 여기서는 심볼릭 링크를 만들지 않음), 이미 만든 폴더는 기억하고,
     * zip 파일은 여러 스레드로 씀. 7z는 솔리드 블록을 차례로 풀어야 해서 스레드 하나로 둠
     */
    private static final int ZIP_WRITERS = Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));

    // Entries this big are written one at a time (see extractZip)
    // 이 크기 이상인 항목은 하나씩 씀(extractZip 참고)
    private static final long LARGE_ENTRY = 16L * 1024 * 1024;

    // Waits for the queued writes; they report their own errors (failure / stop)
    // 대기 중인 쓰기가 끝나기를 기다림. 오류는 각 작업이 직접 알림(failure / stop)
    private static void waitAll(List<java.util.concurrent.Future<?>> pending) throws IOException {
        try {
            for (java.util.concurrent.Future<?> f : pending) f.get();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new java.io.InterruptedIOException("interrupted");
        } catch (java.util.concurrent.ExecutionException ee) {
            throw new IOException(ee.getCause());
        } finally {
            pending.clear();
        }
    }

    private static void extractZip(File archive, Info info, File work, AtomicBoolean cancel, Counter c) throws IOException {
        try (ZipFile zip = openZip(archive, info.volumes, info.zipCharset)) {
            Dirs dirs = new Dirs();
            // Physical order reads the archive front to back; folders are made first.
            // A name stored twice keeps the later entry, as when written one by one
            // 실제 저장 순서로 앞에서 뒤로 읽음. 폴더를 먼저 만듦. 같은 이름이 두 번 있으면
            // 하나씩 쓸 때처럼 나중 항목을 씀
            java.util.LinkedHashMap<String, ZipArchiveEntry> files = new java.util.LinkedHashMap<>();
            Enumeration<ZipArchiveEntry> en = zip.getEntriesInPhysicalOrder();
            while (en.hasMoreElements()) {
                if (cancel.get()) return;
                ZipArchiveEntry e = en.nextElement();
                String path = safeRelativePath(e.getName());
                if (path == null) continue;
                File out = resolveInside(work, path);
                if (e.isDirectory()) {
                    dirs.make(out);
                    continue;
                }
                // Symbolic links are not made: a game needs none, and one could point outside
                // 심볼릭 링크는 만들지 않음: 게임에 필요 없고 밖을 가리킬 수 있음
                if (e.isUnixSymlink()) continue;
                files.remove(path);
                files.put(path, e);
            }

            // Several writers; the first error stops the rest and is thrown here
            // 여러 스레드가 씀. 처음 난 오류가 나머지를 멈추고 여기서 던져짐
            final AtomicBoolean stop = new AtomicBoolean(false);
            final java.util.concurrent.atomic.AtomicReference<IOException> failure = new java.util.concurrent.atomic.AtomicReference<>();
            java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(ZIP_WRITERS);
            // Small files since the last large one (waited for before a large one)
            // 마지막 큰 파일 이후의 작은 파일 작업(큰 파일 전에 끝나기를 기다림)
            List<java.util.concurrent.Future<?>> pending = new ArrayList<>();
            try {
                for (java.util.Map.Entry<String, ZipArchiveEntry> job : files.entrySet()) {
                    if (stop.get() || cancel.get()) break;
                    final String path = job.getKey();
                    final ZipArchiveEntry e = job.getValue();
                    Runnable write = () -> {
                        if (stop.get() || cancel.get()) return;
                        try {
                            File out = resolveInside(work, path);
                            dirs.make(out.getParentFile());
                            c.report(path);
                            InputStream raw;
                            // Opening is serialized; reading runs in parallel (positioned reads)
                            // 열기는 하나씩, 읽기는 동시에 함(위치 지정 읽기)
                            synchronized (zip) {
                                raw = zip.getInputStream(e);
                            }
                            try (InputStream in = raw) {
                                writeEntry(in, out, e.getSize(), e.getCrc(), cancel, c, path);
                            }
                            setTime(out, e.getLastModifiedDate());
                            c.doneFiles.incrementAndGet();
                        } catch (ArchiveException ex) {
                            if (failure.compareAndSet(null, ex)) stop.set(true);
                        } catch (IOException | RuntimeException ex) {
                            if (failure.compareAndSet(null, classify(ex))) stop.set(true);
                        }
                    };
                    if (e.getSize() >= LARGE_ENTRY) {
                        // A large file is written alone, after the small ones before it:
                        // several large files written at once interleave on the card and
                        // an SD card writes that much slower than one stream
                        // 큰 파일은 앞의 작은 파일들이 끝난 뒤 혼자 씀: 큰 파일 여러 개를 동시에
                        // 쓰면 카드 위에서 섞여 기록되고, SD카드는 그때 한 줄로 쓸 때보다 훨씬 느림
                        waitAll(pending);
                        write.run();
                    } else {
                        pending.add(pool.submit(write));
                    }
                }
                waitAll(pending);
            } finally {
                pool.shutdownNow();
            }
            if (failure.get() != null) throw failure.get();
            c.report(null);
        }
    }

    private static void extractSevenZ(File archive, Info info, int memoryLimitKb, File work,
                                      AtomicBoolean cancel, Counter c) throws IOException {
        try (SevenZFile sz = openSevenZ(archive, info.volumes, memoryLimitKb)) {
            Dirs dirs = new Dirs();
            // Sequential reading: solid blocks are decoded once, front to back
            // 차례대로 읽음: 솔리드 블록을 앞에서 뒤로 한 번만 풂
            SevenZArchiveEntry e;
            while (true) {
                if (cancel.get()) return;
                try {
                    e = sz.getNextEntry();
                } catch (IOException | RuntimeException ex) {
                    throw classify(ex);
                }
                if (e == null) break;
                String path = safeRelativePath(e.getName());
                if (path == null || e.isAntiItem()) continue;
                File out = resolveInside(work, path);
                if (e.isDirectory()) {
                    dirs.make(out);
                    continue;
                }
                dirs.make(out.getParentFile());
                c.report(path);
                if (!e.hasStream()) {
                    // Empty file / 빈 파일
                    new FileOutputStream(out).close();
                } else {
                    try {
                        // 7z checks its own CRC while decoding / 7z는 풀면서 CRC를 직접 확인함
                        writeEntry(new SevenZEntryStream(sz), out, e.getSize(), -1, cancel, c, path);
                    } catch (ArchiveException ex) {
                        throw ex;
                    } catch (IOException | RuntimeException ex) {
                        throw classify(ex);
                    }
                }
                if (e.getHasLastModifiedDate()) setTime(out, e.getLastModifiedDate());
                c.doneFiles.incrementAndGet();
            }
            c.report(null);
        }
    }

    // Reads the current 7z entry through SevenZFile.read() / SevenZFile.read()로 현재 7z 항목을 읽음
    private static final class SevenZEntryStream extends InputStream {
        private final SevenZFile sz;

        SevenZEntryStream(SevenZFile sz) {
            this.sz = sz;
        }

        @Override
        public int read() throws IOException {
            return sz.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return sz.read(b, off, len);
        }
    }

    /**
     * Writes one entry. The folder must already exist (see Dirs). expectedCrc
     * (-1 = unknown) is compared at the end: the zip library does not check it,
     * so damaged data inside an archive was written without any error before
     * 항목 하나를 씀. 폴더는 이미 있어야 함(Dirs 참고). expectedCrc(-1 = 모름)는 끝에
     * 비교함: zip 라이브러리가 확인하지 않아서, 전에는 압축 안의 데이터가 손상돼도
     * 오류 없이 그대로 쓰였음
     */
    private static void writeEntry(InputStream in, File out, long declaredSize, long expectedCrc,
                                   AtomicBoolean cancel, Counter c, String name) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        java.util.zip.CRC32 crc = expectedCrc >= 0 ? new java.util.zip.CRC32() : null;
        long written = 0;
        long lastReport = System.nanoTime();
        try (OutputStream os = new FileOutputStream(out)) {
            int n;
            while ((n = in.read(buf)) > 0) {
                if (cancel.get()) return;
                written += n;
                // More data than the table of contents promised: broken or a zip bomb
                // 목록에 적힌 크기보다 데이터가 많음: 손상됐거나 압축 폭탄임
                if (declaredSize >= 0 && written > declaredSize) {
                    throw new ArchiveException(Problem.CORRUPT, "entry larger than declared: " + name);
                }
                os.write(buf, 0, n);
                if (crc != null) crc.update(buf, 0, n);
                c.doneBytes.addAndGet(n);
                long now = System.nanoTime();
                if (now - lastReport > 200_000_000L) {
                    lastReport = now;
                    c.report(name);
                }
            }
        }
        if (crc != null && crc.getValue() != expectedCrc) {
            throw new ArchiveException(Problem.CORRUPT, "CRC mismatch: " + name);
        }
    }

    /**
     * Target file for a relative path, checked by text. The path comes from
     * safeRelativePath() (no "..", no absolute path, no drive letter) and nothing
     * under the work folder is a symbolic link (none are created), so it cannot
     * leave root; the text check guards against a caller passing anything else.
     * 상대 경로의 대상 파일. 글자로 확인함. 경로는 safeRelativePath()를 거친 것이고
     * ("..", 절대 경로, 드라이브 글자 없음) 작업 폴더 아래에는 심볼릭 링크가 없으므로
     * (만들지 않음) root 밖으로 나갈 수 없음. 다른 값이 들어오는 경우를 글자 확인으로 막음
     */
    private static File resolveInside(File root, String relative) throws IOException {
        if (relative.startsWith("/") || ("/" + relative + "/").contains("/../")) {
            throw new ArchiveException(Problem.UNSAFE_PATH, "unsafe entry: " + relative);
        }
        return new File(root, relative);
    }

    // Folders already made in this run, so each is checked/created once (thread-safe)
    // 이번 작업에서 이미 만든 폴더. 폴더마다 한 번만 확인/생성함(여러 스레드에서 안전)
    private static final class Dirs {
        private final java.util.Set<String> made = java.util.concurrent.ConcurrentHashMap.newKeySet();

        void make(File dir) throws IOException {
            if (dir == null || made.contains(dir.getPath())) return;
            mkdirsOrThrow(dir);
            made.add(dir.getPath());
        }
    }

    private static void mkdirsOrThrow(File dir) throws IOException {
        if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) throw new IOException("cannot create " + dir);
    }

    private static void setTime(File f, Date date) {
        try {
            if (date != null && date.getTime() > 0) f.setLastModified(date.getTime());
        } catch (Exception ignored) {
            // best effort / 실패해도 무시
        }
    }

    /**
     * Deletes a folder tree without following symbolic links.
     * 심볼릭 링크를 따라가지 않고 폴더 트리를 지움
     */
    public static void deleteTree(File f) {
        if (f == null || !f.exists() && !isSymlink(f)) return;
        if (f.isDirectory() && !isSymlink(f)) {
            File[] children = f.listFiles();
            if (children != null) for (File ch : children) deleteTree(ch);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    private static boolean isSymlink(File f) {
        return java.nio.file.Files.isSymbolicLink(f.toPath());
    }

    // ------------------------------------------------------------------ exe pick

    // Helper programs shipped with games, never the game itself
    // 게임과 함께 오는 보조 프로그램. 게임 본체가 아님
    private static final Pattern HELPER_EXE = Pattern.compile(
            "^(unins\\d*|uninstall.*|unitycrashhandler(32|64)?|crashreportclient|crashpad_handler"
                    + "|dxsetup|dxwebsetup|vc_?redist.*|vcredist.*|dotnetfx.*|ndp\\d.*|oalinst"
                    + "|ue4prereqsetup.*|ueprereqsetup.*|physx.*|xnafx.*|easyanticheat.*"
                    + "|steamerrorreporter.*|notification_helper|setup|install|installer)\\.exe$");
    // Folders that only hold redistributables / 재배포 패키지만 담는 폴더
    private static final Pattern HELPER_DIR = Pattern.compile(
            "^(_commonredist|redist|redistributables?|directx|__installer|installers?|easyanticheat|prereqs?)$");

    /**
     * Every exe under dir (depth limit 8), helpers left out. One result means the
     * shortcut can be made without asking.
     * dir 아래의 모든 exe(깊이 8단계까지), 보조 프로그램은 뺌. 결과가 하나면 묻지
     * 않고 바로가기를 만들 수 있음
     */
    public static List<File> gameExeCandidates(File dir) {
        List<File> out = new ArrayList<>();
        collectExes(dir, 0, out);
        Collections.sort(out);
        return out;
    }

    private static void collectExes(File dir, int depth, List<File> out) {
        if (depth > 8 || isSymlink(dir)) return;
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String lower = f.getName().toLowerCase(Locale.ENGLISH);
            if (f.isDirectory()) {
                if (!HELPER_DIR.matcher(lower).matches()) collectExes(f, depth + 1, out);
            } else if (lower.endsWith(".exe") && !HELPER_EXE.matcher(lower).matches()) {
                out.add(f);
            }
        }
    }

    // ------------------------------------------------------------------ misc

    /**
     * 7z decoder memory cap from the heap the app may use: 60% of maxMemory, at
     * least 64 MiB (7-Zip's "Ultra" dictionary).
     * 앱이 쓸 수 있는 힙으로 정한 7z 해제기 메모리 상한: maxMemory의 60%,
     * 최소 64MiB(7-Zip "최고" 압축의 사전 크기)
     */
    public static int defaultMemoryLimitKb() {
        long kb = Runtime.getRuntime().maxMemory() / 1024L * 6 / 10;
        return (int) Math.max(64L * 1024, Math.min(kb, Integer.MAX_VALUE));
    }

    private static void closeQuietly(java.io.Closeable c) {
        try {
            if (c != null) c.close();
        } catch (IOException ignored) {
            // nothing to do / 할 일 없음
        }
    }
}
