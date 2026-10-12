package com.winlator.cmod.core.cab;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * Reads a Microsoft cabinet (MSCF) that starts at any offset of a file, so a
 * cabinet embedded in an .exe can be read in place. Single-cabinet sets only;
 * compression NONE, MSZIP (java.util.zip) and LZX (DecompressLzx).
 * Layout follows the [MS-CAB] specification.
 * 파일의 어느 위치에서 시작하든 Microsoft 캐비닛(MSCF)을 읽음. 그래서 .exe 안에 들어
 * 있는 캐비닛도 꺼내지 않고 바로 읽을 수 있음. 캐비닛 1개짜리만 지원함. 압축은
 * NONE, MSZIP(java.util.zip), LZX(DecompressLzx). 구조는 [MS-CAB] 명세를 따름
 */
public final class CabReader implements Closeable {
    private static final int SIGNATURE = 0x4643534D; // "MSCF" little endian
    private static final int HEADER_SIZE = 36;
    private static final int FLAG_PREV_CABINET = 0x0001;
    private static final int FLAG_NEXT_CABINET = 0x0002;
    private static final int FLAG_RESERVE_PRESENT = 0x0004;
    private static final int ATTR_NAME_IS_UTF = 0x80;
    private static final int COMPRESSION_NONE = 0;
    private static final int COMPRESSION_MSZIP = 1;
    private static final int COMPRESSION_LZX = 3;
    // Folder index values for files continued from / to another cabinet of a set
    // 캐비닛 묶음의 다른 캐비닛에서 이어지는 파일의 폴더 번호 값
    private static final int IFOLD_CONTINUED_MIN = 0xFFFD;

    /** One file stored in the cabinet / 캐비닛에 든 파일 하나 */
    public static final class Entry {
        public final String name;
        public final long size;
        final int folder;
        final long folderOffset;

        Entry(String name, long size, int folder, long folderOffset) {
            this.name = name;
            this.size = size;
            this.folder = folder;
            this.folderOffset = folderOffset;
        }
    }

    /** Where an entry is written; null skips it / 파일을 쓸 곳. null이면 건너뜀 */
    public interface Sink {
        OutputStream open(Entry entry) throws IOException;

        // Called for each entry of a folder that could not be decoded; what was
        // written for it is incomplete
        // 풀지 못한 폴더의 파일마다 불림. 그 파일에 쓴 내용은 불완전함
        default void onFailed(Entry entry, IOException error) {}
    }

    private static final class Folder {
        long dataStart;   // absolute file offset of the first CFDATA / 첫 CFDATA의 파일 내 절대 위치
        int blockCount;
        int compression;
    }

    private final RandomAccessFile raf;
    private final long base;
    private final long cabinetSize;
    private final int dataReserve;
    private final List<Folder> folders = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();

    /**
     * Opens the cabinet whose header starts at "base" in the file.
     * 파일의 base 위치에서 헤더가 시작하는 캐비닛을 엶
     */
    public CabReader(File file, long base) throws IOException {
        this.raf = new RandomAccessFile(file, "r");
        this.base = base;
        try {
            byte[] h = readAt(base, HEADER_SIZE);
            if (le32(h, 0) != SIGNATURE) throw new CabException("No MSCF signature");
            if ((h[25] & 0xFF) != 1 || (h[24] & 0xFF) != 3) throw new CabException("Unsupported cabinet version");
            cabinetSize = le32u(h, 8);
            long filesOffset = le32u(h, 16);
            int folderCount = le16(h, 26);
            int fileCount = le16(h, 28);
            int flags = le16(h, 30);
            if (base + cabinetSize > raf.length()) throw new CabException("Cabinet is cut short");

            // Optional reserve sizes and previous / next cabinet names
            // 선택 항목인 예약 영역 크기와 이전 / 다음 캐비닛 이름
            long pos = base + HEADER_SIZE;
            int headerReserve = 0, folderReserve = 0, dataReserveSize = 0;
            if ((flags & FLAG_RESERVE_PRESENT) != 0) {
                byte[] r = readAt(pos, 4);
                headerReserve = le16(r, 0);
                folderReserve = r[2] & 0xFF;
                dataReserveSize = r[3] & 0xFF;
                pos += 4 + headerReserve;
            }
            dataReserve = dataReserveSize;
            if ((flags & FLAG_PREV_CABINET) != 0) pos = skipString(skipString(pos));
            if ((flags & FLAG_NEXT_CABINET) != 0) pos = skipString(skipString(pos));

            // CFFOLDER records
            // CFFOLDER 기록
            for (int i = 0; i < folderCount; i++) {
                byte[] f = readAt(pos, 8);
                Folder folder = new Folder();
                folder.dataStart = base + le32u(f, 0);
                folder.blockCount = le16(f, 4);
                folder.compression = le16(f, 6);
                folders.add(folder);
                pos += 8 + folderReserve;
            }

            // CFFILE records
            // CFFILE 기록
            pos = base + filesOffset;
            for (int i = 0; i < fileCount; i++) {
                byte[] f = readAt(pos, 16);
                long size = le32u(f, 0);
                long offset = le32u(f, 4);
                int folder = le16(f, 8);
                int attribs = le16(f, 14);
                pos += 16;
                byte[] nameBytes = readString(pos);
                pos += nameBytes.length + 1;
                String name = new String(nameBytes, (attribs & ATTR_NAME_IS_UTF) != 0
                        ? StandardCharsets.UTF_8 : StandardCharsets.ISO_8859_1);
                // Pieces continued across cabinets are not supported (single cabinets only)
                // 캐비닛 사이에 걸친 조각은 지원하지 않음(캐비닛 1개짜리만)
                if (folder >= IFOLD_CONTINUED_MIN || folder >= folders.size()) continue;
                entries.add(new Entry(name, size, folder, offset));
            }
        } catch (IOException | RuntimeException e) {
            raf.close();
            throw e instanceof IOException ? (IOException) e : new CabException(String.valueOf(e));
        }
    }

    public long getCabinetSize() {
        return cabinetSize;
    }

    public List<Entry> getEntries() {
        return Collections.unmodifiableList(entries);
    }

    /**
     * Decompresses every folder that holds a wanted entry and streams the
     * entries to the sink. Streams returned by the sink are closed here. A
     * folder that fails is reported through Sink.onFailed and the others go on;
     * returns the number of failed entries.
     * 원하는 파일이 든 폴더만 풀어서 그 파일을 sink로 보냄. sink가 준 스트림은 여기서
     * 닫음. 실패한 폴더는 Sink.onFailed로 알리고 나머지 폴더는 계속 진행함. 실패한
     * 파일 수를 돌려줌
     */
    public int extract(Sink sink) throws IOException {
        int failed = 0;
        for (int fi = 0; fi < folders.size(); fi++) {
            // Entries of this folder that the sink wants, in data order
            // 이 폴더에서 sink가 원하는 파일들(데이터 순서대로)
            List<Entry> wanted = new ArrayList<>();
            List<OutputStream> outs = new ArrayList<>();
            for (Entry e : entries) {
                if (e.folder != fi) continue;
                OutputStream os = sink.open(e);
                if (os == null) continue;
                wanted.add(e);
                outs.add(os);
            }
            if (wanted.isEmpty()) continue;
            IOException error = null;
            try {
                long need = 0;
                for (Entry e : wanted) need = Math.max(need, e.folderOffset + e.size);
                decodeFolder(folders.get(fi), need, wanted, outs);
            } catch (IOException e) {
                error = e;
            } finally {
                for (OutputStream os : outs) {
                    try { os.close(); } catch (IOException ignored) {}
                }
            }
            if (error != null) {
                for (Entry e : wanted) sink.onFailed(e, error);
                failed += wanted.size();
            }
        }
        return failed;
    }

    // Walks the CFDATA blocks of one folder and hands each decoded range to the
    // entries that overlap it. Stops once "need" bytes are decoded
    // 폴더 하나의 CFDATA 블록을 차례로 풀고, 풀린 구간과 겹치는 파일에 그 부분을 넘김.
    // need 바이트까지 풀리면 멈춤
    private void decodeFolder(Folder folder, long need, List<Entry> wanted, List<OutputStream> outs) throws IOException {
        Decompressor lzx = null;
        Inflater inflater = null;
        byte[] window = null; // MSZIP history (last 32 KB) / MSZIP 이전 내용(마지막 32KB)
        int windowLen = 0;
        int type = folder.compression & 0x000F;
        if (type == COMPRESSION_LZX) {
            lzx = new DecompressLzx();
            lzx.init((folder.compression >>> 8) & 0x1F);
        } else if (type == COMPRESSION_MSZIP) {
            window = new byte[Decompressor.CAB_BLOCK_SIZE];
        } else if (type != COMPRESSION_NONE) {
            throw new CabException("Unsupported compression type " + type);
        }

        int maxGrowth = lzx != null ? lzx.getMaxGrowth() : 0;
        // cbData is a 16-bit field: incompressible data can make a block larger than 32 KB
        // cbData는 16비트 값임: 압축이 안 되는 데이터는 블록이 32KB보다 커질 수 있음
        byte[] in = new byte[0x10000 + maxGrowth + 8];
        byte[] out = new byte[Decompressor.CAB_BLOCK_SIZE + maxGrowth + 260];
        long pos = folder.dataStart;
        long done = 0;
        try {
            for (int b = 0; b < folder.blockCount && done < need; b++) {
                byte[] dh = readAt(pos, 8);
                int cbData = le16(dh, 4);
                int cbUncomp = le16(dh, 6);
                pos += 8 + dataReserve;
                if (cbData > in.length || cbUncomp > Decompressor.CAB_BLOCK_SIZE + maxGrowth)
                    throw new CorruptCabException("Data block too large");
                raf.seek(pos);
                raf.readFully(in, 0, cbData);
                pos += cbData;

                if (type == COMPRESSION_NONE) {
                    System.arraycopy(in, 0, out, 0, cbData);
                    cbUncomp = cbData;
                } else if (type == COMPRESSION_LZX) {
                    lzx.decompress(in, out, cbData, cbUncomp);
                } else {
                    // MSZIP: "CK" then a raw deflate stream whose history is the
                    // previous block's output
                    // MSZIP: "CK" 다음에 raw deflate 스트림이 옴. 이전 블록의 출력이
                    // 이어지는 내용(사전)이 됨
                    if (cbData < 2 || in[0] != 'C' || in[1] != 'K') throw new CorruptCabException("MSZIP block without CK");
                    if (inflater == null) inflater = new Inflater(true);
                    else inflater.reset();
                    if (windowLen > 0) inflater.setDictionary(window, 0, windowLen);
                    inflater.setInput(in, 2, cbData - 2);
                    int got = 0;
                    try {
                        while (got < cbUncomp && !inflater.finished()) {
                            int n = inflater.inflate(out, got, cbUncomp - got);
                            if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break;
                            got += n;
                        }
                    } catch (DataFormatException e) {
                        throw new CorruptCabException("MSZIP: " + e.getMessage());
                    }
                    if (got != cbUncomp) throw new CorruptCabException("MSZIP block size mismatch");
                    System.arraycopy(out, 0, window, 0, cbUncomp);
                    windowLen = cbUncomp;
                }

                // Hand the decoded range [done, done + cbUncomp) to overlapping entries
                // 풀린 구간 [done, done + cbUncomp)을 겹치는 파일에 넘김
                long blockEnd = done + cbUncomp;
                for (int i = 0; i < wanted.size(); i++) {
                    Entry e = wanted.get(i);
                    long from = Math.max(done, e.folderOffset);
                    long to = Math.min(blockEnd, e.folderOffset + e.size);
                    if (from < to) outs.get(i).write(out, (int) (from - done), (int) (to - from));
                }
                done = blockEnd;
            }
        } finally {
            if (inflater != null) inflater.end();
        }
        if (done < need) throw new CorruptCabException("Folder data ended early");
    }

    @Override
    public void close() throws IOException {
        raf.close();
    }

    /**
     * Offsets of every cabinet header in a file (for example an installer .exe).
     * A candidate counts only if its header is sane and it fits in the file;
     * the scan skips over each cabinet it accepts.
     * 파일(예: 설치 .exe) 안에 있는 모든 캐비닛 헤더의 위치를 찾음. 헤더 값이 말이
     * 되고 파일 안에 다 들어가는 것만 인정하며, 인정한 캐비닛 구간은 건너뜀
     */
    public static List<Long> findCabinets(File file) throws IOException {
        List<Long> found = new ArrayList<>();
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long length = f.length();
            byte[] buf = new byte[1 << 20];
            long chunkStart = 0;
            while (chunkStart < length) {
                f.seek(chunkStart);
                int n = (int) Math.min(buf.length, length - chunkStart);
                f.readFully(buf, 0, n);
                long next = chunkStart + Math.max(1, n - 3);
                for (int i = 0; i + 3 < n; i++) {
                    if (buf[i] != 'M' || buf[i + 1] != 'S' || buf[i + 2] != 'C' || buf[i + 3] != 'F') continue;
                    long at = chunkStart + i;
                    long size = validHeader(f, at, length);
                    if (size <= 0) continue;
                    found.add(at);
                    next = at + size;
                    break;
                }
                if (n < buf.length && next >= chunkStart + n - 3) break;
                chunkStart = next;
            }
        }
        return found;
    }

    // Cabinet size when the header at "at" looks real, otherwise 0
    // at 위치의 헤더가 진짜로 보이면 캐비닛 크기, 아니면 0
    private static long validHeader(RandomAccessFile f, long at, long length) throws IOException {
        if (at + HEADER_SIZE > length) return 0;
        byte[] h = new byte[HEADER_SIZE];
        f.seek(at);
        f.readFully(h);
        if (le32(h, 4) != 0) return 0;                                  // reserved1
        if ((h[25] & 0xFF) != 1 || (h[24] & 0xFF) != 3) return 0;      // version 1.3
        long size = le32u(h, 8);
        long filesOffset = le32u(h, 16);
        int folders = le16(h, 26);
        int files = le16(h, 28);
        if (size < HEADER_SIZE || at + size > length) return 0;
        if (filesOffset < HEADER_SIZE || filesOffset >= size) return 0;
        if (folders == 0 || files == 0) return 0;
        return size;
    }

    // ----- small readers / 작은 읽기 도우미 -----

    private byte[] readAt(long pos, int len) throws IOException {
        byte[] b = new byte[len];
        raf.seek(pos);
        raf.readFully(b);
        return b;
    }

    // Bytes of a NUL-terminated string at pos (at most 256 bytes as in [MS-CAB])
    // pos 위치의 NUL로 끝나는 문자열 바이트([MS-CAB]처럼 최대 256바이트)
    private byte[] readString(long pos) throws IOException {
        raf.seek(pos);
        byte[] b = new byte[257];
        int n = 0;
        while (true) {
            int c = raf.read();
            if (c < 0) throw new CabException("Name cut short");
            if (c == 0) break;
            if (n >= 256) throw new CabException("Name too long");
            b[n++] = (byte) c;
        }
        byte[] r = new byte[n];
        System.arraycopy(b, 0, r, 0, n);
        return r;
    }

    private long skipString(long pos) throws IOException {
        return pos + readString(pos).length + 1;
    }

    private static int le16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private static long le32u(byte[] b, int o) {
        return le32(b, o) & 0xFFFFFFFFL;
    }
}
