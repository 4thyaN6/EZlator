package com.winlator.cmod.core;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

// Extracts an application icon straight out of a Windows .exe file's PE
// resources (RT_GROUP_ICON / RT_ICON), so shortcuts created from an .exe
// don't have to ship with a blank Icon= line. No external library: this
// walks the PE section table and resource directory tree by hand, the
// same way MSBitmap/MSLink already parse their own Windows formats here.
// Windows .exe 파일(PE 포맷)의 리소스(RT_GROUP_ICON/RT_ICON)에서 곧바로
// 아이콘을 뽑아냄. 이걸로 exe에서 만든 숏컷의 Icon= 값이 항상 빈 채로
// 남는 문제를 해결함. 외부 라이브러리 없이, 이 프로젝트의 MSBitmap/MSLink가
// 각자의 윈도우 포맷을 직접 파싱하는 것과 같은 방식으로 PE 섹션 테이블과
// 리소스 디렉터리 트리를 손으로 순회함
public abstract class ExeIconExtractor {
    private static final int RT_ICON = 3;
    private static final int RT_GROUP_ICON = 14;

    private static class Section {
        int virtualAddress;
        int virtualSize;
        int pointerToRawData;
    }

    // Returns the largest icon embedded in the exe, or null if the file
    // isn't a PE, has no icon resource, or uses an icon format this class
    // doesn't understand - callers should treat null as "no icon available"
    // and fall back to a blank Icon=, never crash the shortcut creation flow
    // for it.
    // exe 안에 있는 아이콘 중 가장 큰 것을 돌려줌. PE가 아니거나, 아이콘
    // 리소스가 없거나, 이 클래스가 모르는 아이콘 형식이면 null을 돌려줌 -
    // 호출하는 쪽은 null을 "아이콘 없음"으로 받아들이고 빈 Icon=으로
    // 넘어가야 하며, 이것 때문에 숏컷 생성 흐름이 죽으면 안 됨
    // synchronized: resourceSectionBase below is a shared static, and icons
    // are now also extracted from a background thread (icon backfill in
    // ShortcutsFragment) while the UI thread may be creating a shortcut
    // synchronized임: 아래 resourceSectionBase가 공용 static인데, 이제 아이콘을
    // 백그라운드 스레드(ShortcutsFragment의 아이콘 보충)에서도 뽑으므로 UI
    // 스레드의 숏컷 생성과 겹칠 수 있음
    public static synchronized Bitmap extractLargestIcon(File exeFile) {
        // The exe is memory-mapped instead of read into a byte[]: game exes
        // can be hundreds of MB, and FileUtils.read() put the whole file on
        // the Java heap. An OutOfMemoryError is an Error, not an Exception,
        // so the catch below never saw it
        // exe를 byte[]로 읽지 않고 메모리 매핑함: 게임 exe는 수백 MB일 수 있는데
        // FileUtils.read()는 파일 전체를 자바 힙에 올렸음. OutOfMemoryError는
        // Exception이 아니라 Error라서 아래 catch가 잡지도 못했음
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(exeFile, "r");
             java.nio.channels.FileChannel channel = raf.getChannel()) {
            long fileSize = channel.size();
            if (fileSize < 64 || fileSize > Integer.MAX_VALUE) return null;
            ByteBuffer buf = channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, 0, fileSize)
                    .order(ByteOrder.LITTLE_ENDIAN);
            final int limit = (int) fileSize;
            if (buf.getShort(0) != 0x5A4D) return null; // "MZ"

            int peOffset = buf.getInt(0x3C);
            if (peOffset <= 0 || peOffset + 24 > limit) return null;
            if (buf.getInt(peOffset) != 0x00004550) return null; // "PE\0\0"

            int numberOfSections = buf.getShort(peOffset + 6) & 0xFFFF;
            int sizeOfOptionalHeader = buf.getShort(peOffset + 20) & 0xFFFF;
            int optionalHeaderOffset = peOffset + 24;
            if (sizeOfOptionalHeader < 96) return null; // too small to hold data directories

            short magic = buf.getShort(optionalHeaderOffset);
            // PE32 (0x10B) keeps the 16 data directories at +96; PE32+ (0x20B)
            // has one extra 8-byte field (a 64-bit BaseOfData/ImageBase slot)
            // pushing them to +112
            // PE32(0x10B)은 데이터 디렉터리 16개가 +96부터 시작하고,
            // PE32+(0x20B)는 8바이트 필드가 하나 더 있어서 +112부터 시작함
            int dataDirOffset = optionalHeaderOffset + (magic == (short) 0x20B ? 112 : 96);
            int resourceDirRVA = buf.getInt(dataDirOffset + 2 * 8);
            int resourceDirSize = buf.getInt(dataDirOffset + 2 * 8 + 4);
            if (resourceDirRVA == 0 || resourceDirSize == 0) return null;

            int sectionTableOffset = optionalHeaderOffset + sizeOfOptionalHeader;
            Section[] sections = new Section[numberOfSections];
            for (int i = 0; i < numberOfSections; i++) {
                int off = sectionTableOffset + i * 40;
                if (off + 40 > limit) return null;
                Section s = new Section();
                s.virtualSize = buf.getInt(off + 8);
                s.virtualAddress = buf.getInt(off + 12);
                s.pointerToRawData = buf.getInt(off + 20);
                sections[i] = s;
            }

            int resourceSectionFileOffset = rvaToFileOffset(sections, resourceDirRVA);
            if (resourceSectionFileOffset < 0) return null;
            // Every offset inside the resource directory tree is relative to
            // this same base, so it's captured once here before any walk
            // 리소스 디렉터리 트리 안의 모든 오프셋은 이 기준값 하나를
            // 공유하므로, 어떤 순회도 시작하기 전에 여기서 한 번만 잡아 둠
            resourceSectionBase = resourceSectionFileOffset;

            int groupIconTypeDirOffset = findTypeDirectory(buf, limit, resourceSectionFileOffset, RT_GROUP_ICON);
            if (groupIconTypeDirOffset < 0) return null;

            // Walk every RT_GROUP_ICON entry (name, then language) down to
            // its data, pick the GRPICONDIRENTRY with the largest width,
            // then resolve that entry's referenced RT_ICON id to real pixels
            // 모든 RT_GROUP_ICON 항목을 이름 단계, 언어 단계까지 따라
            // 내려가서 데이터를 찾고, 그 안에서 폭이 가장 큰
            // GRPICONDIRENTRY를 고른 뒤, 그 항목이 가리키는 RT_ICON id로
            // 실제 픽셀을 찾아감
            Bitmap best = null;
            int bestWidth = -1;
            for (int dataEntryOffset : collectDataEntries(buf, limit, groupIconTypeDirOffset)) {
                int dataRVA = buf.getInt(dataEntryOffset);
                int dataSize = buf.getInt(dataEntryOffset + 4);
                int fileOffset = rvaToFileOffset(sections, dataRVA);
                if (fileOffset < 0 || dataSize < 0 || fileOffset + dataSize > limit) continue;

                GroupIcon group = parseGroupIcon(buf, fileOffset, dataSize);
                if (group == null) continue;

                for (GroupIcon.Entry entry : group.entries) {
                    int width = entry.width == 0 ? 256 : entry.width; // 0 means 256 in ICO format
                    if (width <= bestWidth) continue;

                    int iconTypeDirOffset = findTypeDirectory(buf, limit, resourceSectionFileOffset, RT_ICON);
                    if (iconTypeDirOffset < 0) continue;
                    int iconDataEntryOffset = findDataEntryById(buf, limit, iconTypeDirOffset, entry.id);
                    if (iconDataEntryOffset < 0) continue;

                    int iconRVA = buf.getInt(iconDataEntryOffset);
                    int iconSize = buf.getInt(iconDataEntryOffset + 4);
                    int iconFileOffset = rvaToFileOffset(sections, iconRVA);
                    if (iconFileOffset < 0 || iconSize <= 0 || iconFileOffset + iconSize > limit) continue;

                    // Only this one icon is copied out of the mapping
                    // 매핑에서 이 아이콘 하나만 복사해 옴
                    byte[] iconBytes = new byte[iconSize];
                    ByteBuffer slice = buf.duplicate();
                    slice.position(iconFileOffset);
                    slice.get(iconBytes, 0, iconSize);
                    Bitmap bitmap = decodeIconImage(iconBytes, 0, iconSize);
                    if (bitmap != null) {
                        best = bitmap;
                        bestWidth = width;
                    }
                }
            }
            return best;
        } catch (Exception | OutOfMemoryError e) {
            // Any malformed/unexpected PE layout falls back to "no icon"
            // rather than surfacing a crash from what is a cosmetic feature
            // 예상 못한 PE 구조를 만나면, 겉모습만 담당하는 기능 때문에
            // 앱이 죽지 않도록 "아이콘 없음"으로 처리함
            return null;
        }
    }

    public static boolean extractLargestIconToFile(File exeFile, File outputPngFile) {
        Bitmap bitmap = extractLargestIcon(exeFile);
        if (bitmap == null) return false;
        try {
            outputPngFile.getParentFile().mkdirs();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outputPngFile)) {
                return bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
        } catch (Exception e) {
            return false;
        } finally {
            bitmap.recycle();
        }
    }

    private static int rvaToFileOffset(Section[] sections, int rva) {
        for (Section s : sections) {
            if (rva >= s.virtualAddress && rva < s.virtualAddress + Math.max(s.virtualSize, 1)) {
                return s.pointerToRawData + (rva - s.virtualAddress);
            }
        }
        return -1;
    }

    // IMAGE_RESOURCE_DIRECTORY is 16 bytes, followed by
    // (NumberOfNamedEntries + NumberOfIdEntries) x 8-byte
    // IMAGE_RESOURCE_DIRECTORY_ENTRY records
    // IMAGE_RESOURCE_DIRECTORY는 16바이트고, 그 뒤에
    // (NumberOfNamedEntries + NumberOfIdEntries)개의 8바이트짜리
    // IMAGE_RESOURCE_DIRECTORY_ENTRY 레코드가 옴
    private static int findTypeDirectory(ByteBuffer buf, int limit, int resourceSectionFileOffset, int typeId) {
        return findChildDirectoryById(buf, limit, resourceSectionFileOffset, typeId);
    }

    private static int findChildDirectoryById(ByteBuffer buf, int limit, int dirOffset, int id) {
        if (dirOffset < 0 || dirOffset + 16 > limit) return -1;
        int namedCount = buf.getShort(dirOffset + 12) & 0xFFFF;
        int idCount = buf.getShort(dirOffset + 14) & 0xFFFF;
        int entriesOffset = dirOffset + 16;

        for (int i = 0; i < namedCount + idCount; i++) {
            int entryOffset = entriesOffset + i * 8;
            if (entryOffset + 8 > limit) break;
            int nameOrId = buf.getInt(entryOffset);
            int offsetToData = buf.getInt(entryOffset + 4);
            // Only id-form entries matter here: icon resources are always
            // looked up by numeric type/id, never by name
            // 여기서는 id 형태 항목만 신경 씀: 아이콘 리소스는 항상
            // 이름이 아니라 숫자 type/id로 찾음
            if (i >= namedCount && nameOrId == id && (offsetToData & 0x80000000) != 0) {
                return resourceSectionBase + (offsetToData & 0x7FFFFFFF);
            }
        }
        return -1;
    }

    // Set once per extractLargestIcon() call before any directory walk, since
    // every offset above needs it and threading it through every method
    // signature would only hurt readability for a single-threaded, one-shot
    // extraction
    // extractLargestIcon() 호출마다 디렉터리 순회 전에 한 번만 설정함. 위의
    // 모든 오프셋 계산이 이 값을 필요로 하는데, 어차피 단일 스레드에서
    // 한 번만 도는 추출 과정이라 모든 메서드 시그니처에 일일이 끼워 넣는
    // 것보다 이렇게 두는 게 가독성에 낫다고 판단함
    private static int resourceSectionBase;

    private static List<Integer> collectDataEntries(ByteBuffer buf, int limit, int typeDirOffset) {
        List<Integer> result = new java.util.ArrayList<>();
        if (typeDirOffset < 0 || typeDirOffset + 16 > limit) return result;
        int namedCount = buf.getShort(typeDirOffset + 12) & 0xFFFF;
        int idCount = buf.getShort(typeDirOffset + 14) & 0xFFFF;
        int entriesOffset = typeDirOffset + 16;

        for (int i = 0; i < namedCount + idCount; i++) {
            int entryOffset = entriesOffset + i * 8;
            if (entryOffset + 8 > limit) break;
            int offsetToData = buf.getInt(entryOffset + 4);
            if ((offsetToData & 0x80000000) == 0) continue; // should always be a subdirectory here
            int nameDirOffset = resourceSectionBase + (offsetToData & 0x7FFFFFFF);
            result.addAll(collectLanguageDataEntries(buf, limit, nameDirOffset));
        }
        return result;
    }

    private static List<Integer> collectLanguageDataEntries(ByteBuffer buf, int limit, int nameDirOffset) {
        List<Integer> result = new java.util.ArrayList<>();
        if (nameDirOffset < 0 || nameDirOffset + 16 > limit) return result;
        int namedCount = buf.getShort(nameDirOffset + 12) & 0xFFFF;
        int idCount = buf.getShort(nameDirOffset + 14) & 0xFFFF;
        int entriesOffset = nameDirOffset + 16;

        for (int i = 0; i < namedCount + idCount; i++) {
            int entryOffset = entriesOffset + i * 8;
            if (entryOffset + 8 > limit) break;
            int offsetToData = buf.getInt(entryOffset + 4);
            if ((offsetToData & 0x80000000) != 0) {
                // Points at a language subdirectory - take its first entry,
                // which is all a single-language icon resource ever has
                // 언어 하위 디렉터리를 가리킴 - 아이콘 리소스는 보통 언어가
                // 하나뿐이라 그 첫 항목만 가져오면 됨
                int langDirOffset = resourceSectionBase + (offsetToData & 0x7FFFFFFF);
                if (langDirOffset + 16 <= limit) {
                    int langNamed = buf.getShort(langDirOffset + 12) & 0xFFFF;
                    int langId = buf.getShort(langDirOffset + 14) & 0xFFFF;
                    if (langNamed + langId > 0) {
                        int firstEntryOffset = langDirOffset + 16;
                        int firstOffsetToData = buf.getInt(firstEntryOffset + 4);
                        if ((firstOffsetToData & 0x80000000) == 0) {
                            result.add(resourceSectionBase + firstOffsetToData);
                        }
                    }
                }
            } else {
                // Already points straight at an IMAGE_RESOURCE_DATA_ENTRY
                // 이미 IMAGE_RESOURCE_DATA_ENTRY를 바로 가리킴
                result.add(resourceSectionBase + offsetToData);
            }
        }
        return result;
    }

    private static int findDataEntryById(ByteBuffer buf, int limit, int typeDirOffset, int id) {
        int nameDirOffset = findChildDirectoryById(buf, limit, typeDirOffset, id);
        List<Integer> entries = collectLanguageDataEntries(buf, limit, nameDirOffset);
        return entries.isEmpty() ? -1 : entries.get(0);
    }

    private static class GroupIcon {
        static class Entry {
            int width;
            int id;
        }
        List<Entry> entries = new java.util.ArrayList<>();
    }

    // RT_GROUP_ICON data is a GRPICONDIR: 3 header shorts (reserved, type,
    // count) then `count` x 14-byte GRPICONDIRENTRY records
    // RT_GROUP_ICON 데이터는 GRPICONDIR임: 헤더용 short 3개(예약, 타입,
    // 개수)에 이어서 count개의 14바이트 GRPICONDIRENTRY 레코드가 옴
    private static GroupIcon parseGroupIcon(ByteBuffer buf, int offset, int size) {
        if (size < 6) return null;
        int count = buf.getShort(offset + 4) & 0xFFFF;
        if (6 + count * 14 > size) return null;

        GroupIcon group = new GroupIcon();
        for (int i = 0; i < count; i++) {
            int entryOffset = offset + 6 + i * 14;
            GroupIcon.Entry entry = new GroupIcon.Entry();
            entry.width = buf.get(entryOffset) & 0xFF;
            entry.id = buf.getShort(entryOffset + 12) & 0xFFFF;
            group.entries.add(entry);
        }
        return group;
    }

    // The raw RT_ICON data is either a modern PNG-encoded icon image (Vista+
    // 256x256 icons) or a legacy DIB: a BITMAPINFOHEADER with no preceding
    // BITMAPFILEHEADER, whose height is doubled to include a trailing 1bpp
    // AND mask below the XOR color data
    // RT_ICON 원본 데이터는 최신 PNG 인코딩 아이콘 이미지(Vista+ 256x256
    // 아이콘)이거나, 예전 방식 DIB임: BITMAPFILEHEADER 없이
    // BITMAPINFOHEADER만 있고, height 값이 두 배로 되어 있어서 XOR 컬러
    // 데이터 아래에 1비트 AND 마스크가 따라붙음
    private static Bitmap decodeIconImage(byte[] bytes, int offset, int size) {
        if (size >= 8 && (bytes[offset] & 0xFF) == 0x89 && bytes[offset + 1] == 'P'
                && bytes[offset + 2] == 'N' && bytes[offset + 3] == 'G') {
            return BitmapFactory.decodeByteArray(bytes, offset, size);
        }
        return decodeIconDib(bytes, offset, size);
    }

    private static Bitmap decodeIconDib(byte[] bytes, int offset, int size) {
        if (size < 40) return null;
        ByteBuffer buf = ByteBuffer.wrap(bytes, offset, size).order(ByteOrder.LITTLE_ENDIAN).slice();
        buf.order(ByteOrder.LITTLE_ENDIAN);

        int headerSize = buf.getInt(0);
        int width = buf.getInt(4);
        int height = buf.getInt(8) / 2; // halved: XOR image height only, AND mask excluded
        int bitCount = buf.getShort(14) & 0xFFFF;
        int compression = buf.getInt(16);
        if (width <= 0 || height <= 0 || compression != 0 /* BI_RGB only */) return null;

        int colorTableEntries = 0;
        if (bitCount <= 8) {
            int biClrUsed = headerSize >= 36 ? buf.getInt(32) : 0;
            colorTableEntries = biClrUsed != 0 ? biClrUsed : (1 << bitCount);
        }
        int pixelDataOffset = headerSize + colorTableEntries * 4;

        // AND mask (1bpp transparency) follows the colour rows in every DIB icon
        // AND 마스크(1비트 투명도)는 모든 DIB 아이콘에서 색상 줄 뒤에 붙어 있음
        int colorRowSize = ((width * bitCount + 31) / 32) * 4; // rows padded to 4 bytes
        int andRowSize = ((width + 31) / 32) * 4;
        int andMaskOffset = pixelDataOffset + colorRowSize * height;
        boolean hasAndMask = andMaskOffset + andRowSize * height <= size;

        int[] pixels = new int[width * height];
        try {
            switch (bitCount) {
                case 32: {
                    boolean anyAlpha = false;
                    for (int y = 0; y < height; y++) {
                        int srcRow = pixelDataOffset + (height - 1 - y) * colorRowSize;
                        for (int x = 0; x < width; x++) {
                            int p = srcRow + x * 4;
                            int b = buf.get(p) & 0xFF, g = buf.get(p + 1) & 0xFF,
                                    r = buf.get(p + 2) & 0xFF, a = buf.get(p + 3) & 0xFF;
                            if (a != 0) anyAlpha = true;
                            pixels[y * width + x] = Color.argb(a, r, g, b);
                        }
                    }
                    // XP-era 32bpp icons often leave the alpha byte at 0 and
                    // rely on the AND mask; taken literally they come out fully
                    // transparent (an invisible icon)
                    // XP 시절 32비트 아이콘은 알파 바이트를 0으로 두고 AND 마스크에
                    // 기대는 경우가 많음. 그대로 읽으면 완전히 투명한(안 보이는)
                    // 아이콘이 됨
                    if (!anyAlpha) {
                        for (int y = 0; y < height; y++) {
                            for (int x = 0; x < width; x++) {
                                int i = y * width + x;
                                pixels[i] = (pixels[i] & 0x00FFFFFF)
                                        | (maskAlpha(buf, hasAndMask, andMaskOffset, andRowSize, height, x, y) << 24);
                            }
                        }
                    }
                    break;
                }
                case 24: {
                    for (int y = 0; y < height; y++) {
                        int srcRow = pixelDataOffset + (height - 1 - y) * colorRowSize;
                        for (int x = 0; x < width; x++) {
                            int p = srcRow + x * 3;
                            int b = buf.get(p) & 0xFF, g = buf.get(p + 1) & 0xFF, r = buf.get(p + 2) & 0xFF;
                            int a = maskAlpha(buf, hasAndMask, andMaskOffset, andRowSize, height, x, y);
                            pixels[y * width + x] = Color.argb(a, r, g, b);
                        }
                    }
                    break;
                }
                case 8:
                case 4:
                case 1: {
                    // Palette icons (common in older and doujin games): each
                    // pixel is an index into the BGRA colour table after the
                    // header. Bits are packed MSB first within each byte
                    // 팔레트 아이콘(옛 게임, 동인 게임에 흔함): 각 픽셀이 헤더 뒤의
                    // BGRA 색상표 번호임. 바이트 안에서 높은 비트부터 채워짐
                    int paletteOffset = headerSize;
                    int pixelsPerByte = 8 / bitCount;
                    int mask = (1 << bitCount) - 1;
                    for (int y = 0; y < height; y++) {
                        int srcRow = pixelDataOffset + (height - 1 - y) * colorRowSize;
                        for (int x = 0; x < width; x++) {
                            int byteVal = buf.get(srcRow + x / pixelsPerByte) & 0xFF;
                            int shift = (pixelsPerByte - 1 - (x % pixelsPerByte)) * bitCount;
                            int index = (byteVal >> shift) & mask;
                            if (index >= colorTableEntries) index = 0;
                            int c = paletteOffset + index * 4;
                            int b = buf.get(c) & 0xFF, g = buf.get(c + 1) & 0xFF, r = buf.get(c + 2) & 0xFF;
                            int a = maskAlpha(buf, hasAndMask, andMaskOffset, andRowSize, height, x, y);
                            pixels[y * width + x] = Color.argb(a, r, g, b);
                        }
                    }
                    break;
                }
                default:
                    // 16bpp icons are practically non-existent; skip them
                    // 16비트 아이콘은 사실상 없으므로 건너뜀
                    return null;
            }
        } catch (Exception e) {
            return null;
        }

        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels, 0, width, 0, 0, width, height);
        return bitmap;
    }

    // Alpha from the AND mask: bit 1 = transparent, 0 = opaque. Without a
    // mask the pixel is opaque
    // AND 마스크에서 알파를 구함: 비트 1 = 투명, 0 = 불투명. 마스크가 없으면
    // 불투명임
    private static int maskAlpha(ByteBuffer buf, boolean hasAndMask, int andMaskOffset,
                                 int andRowSize, int height, int x, int y) {
        if (!hasAndMask) return 255;
        int andRow = andMaskOffset + (height - 1 - y) * andRowSize;
        int bit = (buf.get(andRow + x / 8) >> (7 - (x % 8))) & 1;
        return bit == 1 ? 0 : 255;
    }
}
