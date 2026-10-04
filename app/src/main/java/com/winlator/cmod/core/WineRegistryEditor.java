package com.winlator.cmod.core;

import com.winlator.cmod.math.Mathf;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Locale;

public class WineRegistryEditor implements Closeable {
    private final File file;
    private final File cloneFile;
    private boolean modified = false;
    private boolean createKeyIfNotExist = true;
    private int lastParentKeyPosition = 0;
    private String lastParentKey = "";

    public static class Location {
        public final int offset;
        public final int start;
        public final int end;

        public Location(int offset, int start, int end) {
            this.offset = offset;
            this.start = start;
            this.end = end;
        }

        public int length() {
            return end - start;
        }
    }

    public WineRegistryEditor(File file) {
        this.file = file;
        cloneFile = FileUtils.createTempFile(file.getParentFile(), FileUtils.getBasename(file.getPath()));
        if (!file.isFile()) {
            try {
                cloneFile.createNewFile();
            } catch (IOException e) {
            }
        } else FileUtils.copy(file, cloneFile);
    }

    // Wine registry string escaping. Backslash and quote as before; characters
    // outside printable ASCII (e.g. Korean font names) are written as \xhhhh, the
    // form Wine itself saves and reads (up to 4 hex digits, lowercase). Before this,
    // non-ASCII text was written as raw bytes, which Wine does not read correctly.
    // Wine 레지스트리 문자열 이스케이프. 역슬래시와 따옴표는 기존과 같음. 출력 가능한
    // ASCII 밖의 문자(예: 한글 글꼴 이름)는 \xhhhh로 씀. Wine이 직접 저장하고 읽는
    // 형식임(16진수 최대 4자리, 소문자). 예전에는 비ASCII 문자를 원래 바이트 그대로
    // 써서 Wine이 제대로 읽지 못했음
    private static String escape(String str) {
        String s = str.replace("\\", "\\\\").replace("\"", "\\\"");
        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c > 0x7e) {
                // Lazily copy only when an escape is actually needed
                // 실제로 이스케이프가 필요할 때만 복사를 시작함
                if (sb == null) sb = new StringBuilder(s.substring(0, i));
                sb.append(String.format(Locale.ENGLISH, "\\x%04x", (int)c));
            }
            else if (sb != null) sb.append(c);
        }
        return sb != null ? sb.toString() : s;
    }

    // Reverse of escape(): \\ -> \, \" -> ", \xh..h (1-4 hex digits) -> character.
    // Any other backslash sequence is kept as it is.
    // escape()의 반대: \\ -> \, \" -> ", \xh..h(16진수 1~4자리) -> 문자.
    // 그 밖의 역슬래시 조합은 그대로 둠
    private static String unescape(String str) {
        if (str.indexOf('\\') == -1) return str;
        StringBuilder sb = new StringBuilder(str.length());
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (c != '\\' || i + 1 >= str.length()) { sb.append(c); continue; }
            char n = str.charAt(i + 1);
            if (n == '\\' || n == '"') { sb.append(n); i++; }
            else if (n == 'x') {
                int j = i + 2, v = 0, digits = 0;
                while (j < str.length() && digits < 4 && Character.digit(str.charAt(j), 16) != -1) {
                    v = v * 16 + Character.digit(str.charAt(j), 16);
                    j++; digits++;
                }
                if (digits > 0) { sb.append((char)v); i = j - 1; }
                else sb.append(c);
            }
            else sb.append(c);
        }
        return sb.toString();
    }

    private static boolean lineHasName(String line) {
        int index;
        return (index = line.indexOf('"')) != -1 &&
                (index = line.indexOf('"', index)) != -1 &&
                (index = line.indexOf('=', index)) != -1;
    }

    @Override
    public void close() {
        if (modified && cloneFile.exists()) {
            cloneFile.renameTo(file);
        } else cloneFile.delete();
    }

    private void resetLastParentKeyPositionIfNeed(String newKey) {
        int lastIndex = newKey.lastIndexOf("\\");
        if (lastIndex == -1) {
            lastParentKeyPosition = 0;
            lastParentKey = "";
            return;
        }

        String parentKey = newKey.substring(0, lastIndex);
        if (!parentKey.equals(lastParentKey)) lastParentKeyPosition = 0;
        lastParentKey = parentKey;
    }

    public void setCreateKeyIfNotExist(boolean createKeyIfNotExist) {
        this.createKeyIfNotExist = createKeyIfNotExist;
    }

    private Location createKey(String key) {
        lastParentKeyPosition = 0;
        Location location = getParentKeyLocation(key);
        boolean success = false;
        int offset = 0;
        int totalLength = 0;

        char[] buffer = new char[StreamUtils.BUFFER_SIZE];
        File tempFile = FileUtils.createTempFile(file.getParentFile(), FileUtils.getBasename(file.getPath()));

        try (BufferedReader reader = new BufferedReader(new FileReader(cloneFile), StreamUtils.BUFFER_SIZE);
             BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile), StreamUtils.BUFFER_SIZE)) {

            int length;
            for (int i = 0, end = location != null ? location.end + 1 : (int) cloneFile.length(); i < end; i += length) {
                length = Math.min(buffer.length, end - i);
                reader.read(buffer, 0, length);
                writer.write(buffer, 0, length);
                totalLength += length;
            }

            offset = totalLength;
            long ticks1601To1970 = 86400L * (369 * 365 + 89) * 10000000;
            long currentTime = System.currentTimeMillis() + ticks1601To1970;
            String content = "\n[" + escape(key) + "] " + ((currentTime - ticks1601To1970) / 1000) +
                    String.format(Locale.ENGLISH, "\n#time=%x%08x", currentTime >> 32, (int) currentTime) + "\n";
            writer.write(content);
            totalLength += content.length() - 1;

            while ((length = reader.read(buffer)) != -1) writer.write(buffer, 0, length);
            success = true;
        } catch (IOException e) {
        }

        if (success) {
            modified = true;
            tempFile.renameTo(cloneFile);
            return new Location(offset, totalLength, totalLength);
        } else {
            tempFile.delete();
            return null;
        }
    }

    public String getStringValue(String key, String name) {
        return getStringValue(key, name, null);
    }

    public String getStringValue(String key, String name, String fallback) {
        String value = getRawValue(key, name);
        return value != null ? value.substring(1, value.length() - 1) : fallback;
    }

    public void setStringValue(String key, String name, String value) {
        setRawValue(key, name, value != null ? "\"" + escape(value) + "\"" : "\"\"");
    }

    /**
     * REG_MULTI_SZ in Wine's native .reg text form: str(7):"a\0b\0".
     * (setHexValue() writes REG_BINARY, which Wine will not read as a string list.)
     * Wine 자체 .reg 텍스트 형식의 REG_MULTI_SZ임: str(7):"a\0b\0".
     * (setHexValue()는 REG_BINARY로 쓰는데, Wine은 이걸 문자열 목록으로 읽지 않음)
     */
    public void setMultiStringValue(String key, String name, String... values) {
        StringBuilder sb = new StringBuilder("str(7):\"");
        for (String v : values) sb.append(escape(v)).append("\\0");
        sb.append("\"");
        setRawValue(key, name, sb.toString());
    }

    public Integer getDwordValue(String key, String name) {
        return getDwordValue(key, name, null);
    }

    public Integer getDwordValue(String key, String name, Integer fallback) {
        String value = getRawValue(key, name);
        return value != null ? Integer.decode("0x" + value.substring(6)) : fallback;
    }

    public void setDwordValue(String key, String name, int value) {
        setRawValue(key, name, "dword:" + String.format("%08x", value));
    }

    public void setHexValue(String key, String name, String value) {
        int start = (int) Mathf.roundTo(name.length(), 2) + 7;
        StringBuilder lines = new StringBuilder();
        for (int i = 0, j = start; i < value.length(); i++) {
            if (i > 0 && (i % 2) == 0) lines.append(",");
            if (j++ > 56) {
                lines.append("\\\n  ");
                j = 8;
            }
            lines.append(value.charAt(i));
        }
        setRawValue(key, name, "hex:" + lines);
    }

    public void setHexValue(String key, String name, byte[] bytes) {
        StringBuilder data = new StringBuilder();
        for (byte b : bytes)
            data.append(String.format(Locale.ENGLISH, "%02x", Byte.toUnsignedInt(b)));
        setHexValue(key, name, data.toString());
    }

    private String getRawValue(String key, String name) {
        lastParentKeyPosition = 0;
        Location keyLocation = getKeyLocation(key);
        if (keyLocation == null) return null;

        Location valueLocation = getValueLocation(keyLocation, name);
        if (valueLocation == null) return null;
        boolean success = false;
        char[] buffer = new char[valueLocation.length()];

        try (BufferedReader reader = new BufferedReader(new FileReader(cloneFile), StreamUtils.BUFFER_SIZE)) {
            reader.skip(valueLocation.start);
            success = reader.read(buffer) == buffer.length;
        } catch (IOException e) {
        }
        return success ? unescape(new String(buffer)) : null;
    }

    private void setRawValue(String key, String name, String value) {
        resetLastParentKeyPositionIfNeed(key);

        Location keyLocation = getKeyLocation(key);
        if (keyLocation == null) {
            if (createKeyIfNotExist) {
                keyLocation = createKey(key);
            } else return;
        }

        Location valueLocation = getValueLocation(keyLocation, name);
        char[] buffer = new char[StreamUtils.BUFFER_SIZE];
        boolean success = false;

        File tempFile = FileUtils.createTempFile(file.getParentFile(), FileUtils.getBasename(file.getPath()));

        try (BufferedReader reader = new BufferedReader(new FileReader(cloneFile), StreamUtils.BUFFER_SIZE);
             BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile), StreamUtils.BUFFER_SIZE)) {

            int length;
            for (int i = 0, end = valueLocation != null ? valueLocation.start : keyLocation.end; i < end; i += length) {
                length = Math.min(buffer.length, end - i);
                reader.read(buffer, 0, length);
                writer.write(buffer, 0, length);
            }

            if (valueLocation == null) {
                writer.write("\n" + (name != null ? "\"" + escape(name) + "\"" : "@") + "=" + value);
            } else {
                writer.write(value);
                reader.skip(valueLocation.length());
            }

            while ((length = reader.read(buffer)) != -1) writer.write(buffer, 0, length);
            success = true;
        } catch (IOException e) {
        }

        if (success) {
            modified = true;
            tempFile.renameTo(cloneFile);
        } else tempFile.delete();
    }

    public void removeValue(String key, String name) {
        lastParentKeyPosition = 0;
        Location keyLocation = getKeyLocation(key);
        if (keyLocation == null) return;

        Location valueLocation = getValueLocation(keyLocation, name);
        if (valueLocation == null) return;
        removeRegion(valueLocation);
    }

    public boolean removeKey(String key) {
        return removeKey(key, false);
    }

    public boolean removeKey(String key, boolean removeTree) {
        lastParentKeyPosition = 0;
        boolean removed = false;
        if (removeTree) {
            Location location;
            while ((location = getKeyLocation(key, true)) != null) {
                if (removeRegion(location)) removed = true;
            }
        } else {
            Location location = getKeyLocation(key, false);
            if (location != null && removeRegion(location)) removed = true;
        }
        return removed;
    }

    private boolean removeRegion(Location location) {
        char[] buffer = new char[StreamUtils.BUFFER_SIZE];
        boolean success = false;

        File tempFile = FileUtils.createTempFile(file.getParentFile(), FileUtils.getBasename(file.getPath()));

        try (BufferedReader reader = new BufferedReader(new FileReader(cloneFile), StreamUtils.BUFFER_SIZE);
             BufferedWriter writer = new BufferedWriter(new FileWriter(tempFile), StreamUtils.BUFFER_SIZE)) {

            int length = 0;
            for (int i = 0; i < location.offset; i += length) {
                length = Math.min(buffer.length, location.offset - i);
                reader.read(buffer, 0, length);
                writer.write(buffer, 0, length);
            }

            boolean skipLine = length > 1 && buffer[length - 1] == '\n';
            reader.skip(location.end - location.offset + (skipLine ? 1 : 0));
            while ((length = reader.read(buffer)) != -1) writer.write(buffer, 0, length);
            success = true;
        } catch (IOException e) {
        }

        if (success) {
            modified = true;
            tempFile.renameTo(cloneFile);
        } else tempFile.delete();
        return success;
    }

    private Location getKeyLocation(String key) {
        return getKeyLocation(key, false);
    }

    private Location getKeyLocation(String key, boolean keyAsPrefix) {
        try (BufferedReader reader = new BufferedReader(new FileReader(cloneFile), StreamUtils.BUFFER_SIZE)) {
            int lastIndex = key.lastIndexOf("\\");
            String parentKey = lastParentKeyPosition == 0 && lastIndex != -1 ? "[" + escape(key.substring(0, lastIndex)) : null;

            if (lastParentKeyPosition > 0) reader.skip(lastParentKeyPosition);
            key = "[" + escape(key) + (!keyAsPrefix ? "]" : "");
            int totalLength = lastParentKeyPosition;
            int start = -1;
            int end = -1;
            int emptyLines = 0;
            int offset = 0;

            String line;
            while ((line = reader.readLine()) != null) {
                if (start == -1) {
                    if (parentKey != null && line.startsWith(parentKey)) {
                        lastParentKeyPosition = totalLength;
                        parentKey = null;
                    }

                    if (parentKey == null && line.startsWith(key)) {
                        offset = totalLength - 1;
                        start = totalLength + line.length() + 1;
                    }
                } else {
                    if (line.startsWith("[")) {
                        end = Math.max(-1, totalLength - emptyLines - 1);
                        break;
                    } else emptyLines = line.isEmpty() ? emptyLines + 1 : 0;
                }
                totalLength += line.length() + 1;
            }

            if (end == -1) end = totalLength - 1;
            return start != -1 ? new Location(offset, start, end) : null;
        } catch (IOException e) {
            return null;
        }
    }

    private Location getParentKeyLocation(String key) {
        String[] parts = key.split("\\\\");
        ArrayList<String> stack = new ArrayList<>(Arrays.asList(parts).subList(0, parts.length - 1));

        while (!stack.isEmpty()) {
            String currentKey = String.join("\\", stack);
            Location location = getKeyLocation(currentKey, true);
            if (location != null) return location;
            stack.remove(stack.size() - 1);
        }

        return null;
    }

    private Location getValueLocation(Location keyLocation, String name) {
        if (keyLocation.start == keyLocation.end) return null;
        try (BufferedReader reader = new BufferedReader(new FileReader(cloneFile), StreamUtils.BUFFER_SIZE)) {
            reader.skip(keyLocation.start);
            name = name != null ? "\"" + escape(name) + "\"=" : "@=";
            int totalLength = 0;
            int start = -1;
            int end = -1;
            int offset = 0;

            String line;
            while ((line = reader.readLine()) != null && totalLength < keyLocation.length()) {
                if (start == -1) {
                    if (line.startsWith(name)) {
                        offset = totalLength - 1;
                        start = totalLength + name.length();
                    }
                } else {
                    if (line.isEmpty() || lineHasName(line)) {
                        end = totalLength - 1;
                        break;
                    }
                }
                totalLength += line.length() + 1;
            }

            if (end == -1) end = totalLength - 1;
            return start != -1 ? new Location(keyLocation.start + offset, keyLocation.start + start, keyLocation.start + end) : null;
        } catch (IOException e) {
            return null;
        }
    }

    public void importReg(String regFile) {
        try {
            JSONObject jobj = new JSONObject(regFile);
            Iterator<String> iterator = jobj.keys();
            while (iterator.hasNext()) {
                String key = iterator.next();
                JSONArray entries = jobj.getJSONArray(key);
                for (int i = 0; i < entries.length(); i++) {
                    JSONObject entry = entries.getJSONObject(i);
                    String type = entry.getString("type");
                    String name = (entry.getString("name").isEmpty()) ? null : entry.getString("name");
                    String value = entry.getString("value");
                    switch (type) {
                        case "String":
                            setStringValue(key, name, value);
                            break;
                        case "Dword":
                            setDwordValue(key, name, Integer.parseInt(value));
                            break;
                        default:
                            break;
                    }
                }
            }
        } catch (JSONException e) {
            throw new RuntimeException(e);
        }
    }
}
