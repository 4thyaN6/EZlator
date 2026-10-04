    package com.winlator.cmod.container;

    import android.graphics.Bitmap;
    import android.graphics.BitmapFactory;
    import android.util.Log;

    import com.winlator.cmod.core.FileUtils;
    import com.winlator.cmod.core.ExeIconExtractor;
    import com.winlator.cmod.core.StringUtils;

import java.io.IOException;
import java.nio.file.Files;
    import org.json.JSONException;
    import org.json.JSONObject;

    import java.io.File;
    import java.util.ArrayList;
    import java.util.Iterator;
    import java.util.List;
    import java.util.UUID;

    public class Shortcut {
        public final Container container;
        public final String name;
        public final String path;
        public Bitmap icon;
        public final File file;
        public File iconFile;
        // Raw Icon= value from the .desktop file ("" when none was written)
        // .desktop 파일의 Icon= 값 원문임(써 둔 게 없으면 "")
        private String iconName = "";
        public final String wmClass;
        private final JSONObject extraData = new JSONObject();

        // Custom tile image shown on the shortcuts grid/list. null means the
        // tile falls back to the exe icon
        // 숏컷 격자/목록에 보이는 사용자 지정 타일 이미지임. null이면 타일이
        // exe 아이콘으로 대체됨
        private Bitmap tileImage;
        private String customTileImagePath;

        // Background cover for the detail sheet. Loaded lazily on first use -
        // covers are large, so decoding one per shortcut at list time would
        // waste memory for sheets that are never opened
        // 상세창 배경 커버임. 처음 쓸 때 지연 로딩함 - 커버는 크기가 커서 목록을
        // 만들 때 숏컷마다 디코딩하면 열지도 않을 창 때문에 메모리를 낭비함
        private Bitmap coverArt;
        private String customCoverArtPath;
        private boolean coverArtLoaded = false;

        // .desktop [Extra Data] keys. customCoverArtPath keeps its old name so
        // values already written by earlier builds stay readable
        // .desktop [Extra Data] 키임. customCoverArtPath는 이전 빌드가 이미 쓴
        // 값을 그대로 읽을 수 있도록 기존 이름을 유지함
        public static final String EXTRA_TILE_IMAGE = "customTileImagePath";
        public static final String EXTRA_COVER_ART = "customCoverArtPath";

        // Per-container folder holding copies of user-picked images. Copies,
        // not links: the picked file may live on removable storage or be
        // deleted by the user later
        // 사용자가 고른 이미지의 사본을 두는 컨테이너별 폴더임. 링크가 아니라
        // 사본인 이유: 고른 파일이 착탈식 저장소에 있거나 나중에 사용자가
        // 지울 수 있음
        private static final String IMAGE_DIR = "app_data/shortcut_images";

        public Shortcut(Container container, File file) {

            this.container = container;
            this.file      = file;

            String execArgs = "";
            Bitmap icon     = null;
            File   iconFile = null;
            String wmClass  = "";

            File[] iconDirs = { container.getIconsDir(64),
                    container.getIconsDir(48),
                    container.getIconsDir(32),
                    container.getIconsDir(16) };

            /* --- NEW: flag so we know we actually parsed the header --- */
            boolean seenDesktopEntry = false;

            String section = "";
            int index;

            for (String line : FileUtils.readLines(file)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;

                if (line.startsWith("[")) {
                    section = line.substring(1, line.indexOf(']'));
                    if ("Desktop Entry".equals(section)) seenDesktopEntry = true;   // <-- NEW
                    continue;
                }

                index = line.indexOf('=');
                if (index == -1) continue;
                String key   = line.substring(0, index);
                String value = line.substring(index + 1);

                if ("Desktop Entry".equals(section)) {
                    if (key.equals("Exec"))  execArgs = value;
                    if (key.equals("Icon")) {
                        iconName = value.trim();
                        for (File d : iconDirs) {
                            iconFile = new File(d, value + ".png");
                            if (iconFile.isFile()) { icon = BitmapFactory.decodeFile(iconFile.getPath()); break; }
                        }
                    }
                    if (key.equals("StartupWMClass")) wmClass = value;
                }
                else if ("Extra Data".equals(section)) {
                    try { extraData.put(key, value); } catch (JSONException ignored) {}
                }
            }

            /* --- NEW: quick bail-out if header was missing or no meaningful data --- */
            if (!seenDesktopEntry) {
                Log.w("Shortcut", "Ignoring malformed shortcut (no [Desktop Entry]): "
                        + file.getName());
                throw new IllegalArgumentException("Malformed .desktop file");
            }

            /* ------------------------------------------------------------------ */
            /*                 SAFE handling of Exec → path                       */
            /* ------------------------------------------------------------------ */
            int winePos = execArgs.lastIndexOf("wine ");
            if (winePos != -1) {
                // +5 because "wine " is five chars (w i n e ␠)
                this.path = StringUtils.unescape(execArgs.substring(winePos + 5).trim());
            } else {
                Log.w("Shortcut", "Exec line missing or has no \"wine \" prefix: "
                        + file.getName());
                this.path = "";        // or leave null / handle however you prefer
            }

            /* --- everything else unchanged ------------------------------------ */
            this.name     = FileUtils.getBasename(file.getPath());
            this.icon     = icon;
            this.iconFile = iconFile;
            this.wmClass  = wmClass;

            // Tile image is decoded now because every visible tile needs it;
            // the cover only remembers its path until getCoverArt() is called
            // 타일 이미지는 보이는 타일마다 필요하므로 지금 디코딩함. 커버는
            // getCoverArt()가 불릴 때까지 경로만 기억함
            this.customTileImagePath = getExtra(EXTRA_TILE_IMAGE);
            this.tileImage = decodeIfFile(customTileImagePath);
            this.customCoverArtPath = getExtra(EXTRA_COVER_ART);

            Container.checkObsoleteOrMissingProperties(extraData);
        }

        // Decode a bitmap only if the path points at an existing file
        // 경로가 실제 파일을 가리킬 때만 비트맵을 디코딩함
        private static Bitmap decodeIfFile(String path) {
            if (path == null || path.isEmpty()) return null;
            File f = new File(path);
            if (!f.isFile()) return null;
            try {
                return BitmapFactory.decodeFile(f.getPath());
            } catch (OutOfMemoryError e) {
                Log.e("Shortcut", "Image too large to decode: " + path, e);
                return null;
            }
        }

        // Custom tile image, or null when the tile should show the icon
        // 사용자 지정 타일 이미지임. 타일이 아이콘을 보여야 하면 null임
        public Bitmap getTileImage() {
            return tileImage;
        }

        public boolean hasCustomTileImage() {
            return tileImage != null;
        }

        // Background cover for the detail sheet, decoded on first call
        // 상세창 배경 커버임. 처음 호출할 때 디코딩함
        public Bitmap getCoverArt() {
            if (!coverArtLoaded) {
                coverArtLoaded = true;
                coverArt = decodeIfFile(customCoverArtPath);
            }
            return coverArt;
        }

        public boolean hasCustomCoverArt() {
            return getCoverArt() != null;
        }

        // Folder for this shortcut's image copies (inside its container)
        // 이 숏컷의 이미지 사본 폴더임(소속 컨테이너 안)
        public File getCustomImagesDir() {
            return new File(container.getRootDir(), IMAGE_DIR);
        }

        // New unique file in the images folder. The timestamp keeps names
        // unique even after a rename, and \w is ASCII-only in Java so
        // non-Latin names simply turn into underscores
        // 이미지 폴더 안의 새 고유 파일임. 시간값 덕분에 이름을 바꿔도 겹치지
        // 않음. 자바의 \w는 ASCII 전용이라 한글 등은 밑줄로 바뀌기만 함
        public File newCustomImageFile(String kind, String extension) {
            File dir = getCustomImagesDir();
            if (!dir.isDirectory()) dir.mkdirs();
            String safeName = name.replaceAll("[^\\w.-]", "_");
            return new File(dir, safeName + "_" + kind + "_" + System.currentTimeMillis() + extension);
        }

        // Set (or clear with null) the tile image. The previous copy is
        // deleted only when it lives in this shortcut's own images folder
        // 타일 이미지를 지정함(null이면 해제). 이전 사본은 이 숏컷의 이미지
        // 폴더 안에 있을 때만 지움
        public void setCustomTileImage(File imageFile) {
            deleteOwnedImage(customTileImagePath, imageFile);
            customTileImagePath = imageFile != null ? imageFile.getAbsolutePath() : null;
            tileImage = decodeIfFile(customTileImagePath);
            putExtra(EXTRA_TILE_IMAGE, customTileImagePath);
            saveData();
        }

        // Set (or clear with null) the background cover. Same ownership rule
        // 배경 커버를 지정함(null이면 해제). 소유 규칙은 위와 같음
        public void setCustomCoverArt(File imageFile) {
            deleteOwnedImage(customCoverArtPath, imageFile);
            customCoverArtPath = imageFile != null ? imageFile.getAbsolutePath() : null;
            coverArt = null;
            coverArtLoaded = false;
            putExtra(EXTRA_COVER_ART, customCoverArtPath);
            saveData();
        }

        // Remove both image copies from disk. Called when the shortcut itself
        // is deleted so no orphan files are left behind
        // 이미지 사본 두 개를 디스크에서 지움. 숏컷 자체를 지울 때 호출해서
        // 고아 파일이 남지 않게 함
        public void deleteCustomImages() {
            deleteOwnedImage(customTileImagePath, null);
            deleteOwnedImage(customCoverArtPath, null);
        }

        // True when the file sits directly in this shortcut's images folder.
        // A clone made before the images were copied can point into another
        // container's folder; such files belong to the original and stay
        // 파일이 이 숏컷의 이미지 폴더 바로 아래에 있으면 true임. 사본을
        // 만들기 전 복제본은 다른 컨테이너 폴더를 가리킬 수 있고, 그 파일은
        // 원본 것이므로 지우지 않음
        private boolean isOwnedImage(File f) {
            File parent = f.getParentFile();
            return parent != null && parent.getAbsolutePath().equals(getCustomImagesDir().getAbsolutePath());
        }

        private void deleteOwnedImage(String oldPath, File keep) {
            if (oldPath == null || oldPath.isEmpty()) return;
            File old = new File(oldPath);
            if (keep != null && old.getAbsolutePath().equals(keep.getAbsolutePath())) return;
            if (isOwnedImage(old) && old.isFile() && !old.delete()) {
                Log.w("Shortcut", "Could not delete old image: " + oldPath);
            }
        }

        // After cloneToContainer() the new .desktop still points at the
        // source container's image copies. Copy them into this container so
        // deleting the source container (or changing its images) does not
        // blank the clone. copy.isFile() is checked too because
        // FileUtils.copy() returns true even when a file copy fails
        // cloneToContainer() 직후 새 .desktop은 원본 컨테이너의 이미지 사본을
        // 가리킴. 이 컨테이너로 복사해서 원본 컨테이너를 지우거나 이미지를
        // 바꿔도 복제본이 비지 않게 함. FileUtils.copy()는 파일 복사가 실패해도
        // true를 돌려주므로 copy.isFile()도 함께 확인함
        public void adoptForeignImages() {
            File tile = foreignImage(customTileImagePath);
            if (tile != null) {
                File copy = newCustomImageFile("tile", extensionOf(tile));
                if (FileUtils.copy(tile, copy) && copy.isFile()) setCustomTileImage(copy);
            }
            File cover = foreignImage(customCoverArtPath);
            if (cover != null) {
                File copy = newCustomImageFile("cover", extensionOf(cover));
                if (FileUtils.copy(cover, copy) && copy.isFile()) setCustomCoverArt(copy);
            }
        }

        // After a move: delete this (old) shortcut's own image copies, but only
        // those the moved shortcut no longer points at. If copying into the
        // new container failed, the moved shortcut still uses the old file and
        // it must stay
        // 이동 뒤에 호출함: 이 (옛) 숏컷의 이미지 사본을 지우되, 옮겨진 숏컷이 더
        // 이상 가리키지 않는 것만 지움. 새 컨테이너로 복사가 실패했다면 옮겨진
        // 숏컷이 아직 옛 파일을 쓰므로 남겨야 함
        public void releaseImagesAfterMove(Shortcut moved) {
            if (!samePath(customTileImagePath, moved.customTileImagePath)) deleteOwnedImage(customTileImagePath, null);
            if (!samePath(customCoverArtPath, moved.customCoverArtPath)) deleteOwnedImage(customCoverArtPath, null);
        }

        private static boolean samePath(String a, String b) {
            if (a == null || a.isEmpty()) return b == null || b.isEmpty();
            return b != null && new File(a).getAbsolutePath().equals(new File(b).getAbsolutePath());
        }

        // Windows exe path -> real Android file through this container's
        // dosdevices links (e.g. D:\Game\game.exe -> /storage/.../Game/game.exe).
        // Anything after ".exe" (launch arguments) is cut off first.
        // Returns null when the path cannot be resolved
        // Windows exe 경로를 이 컨테이너의 dosdevices 링크를 거쳐 실제 안드로이드
        // 파일로 바꿈(예: D:\Game\game.exe -> /storage/.../Game/game.exe).
        // ".exe" 뒤에 붙은 것(실행 인자)은 먼저 잘라 냄. 못 바꾸면 null임
        public File resolveExeFile() {
            try {
                String p = path != null ? path.trim() : "";
                if (p.startsWith("\"")) p = p.substring(1);
                int quote = p.indexOf('"');
                if (quote >= 0) p = p.substring(0, quote);
                int exeEnd = p.toLowerCase(java.util.Locale.ENGLISH).indexOf(".exe");
                if (exeEnd > 0) p = p.substring(0, exeEnd + 4);
                // Wine-made .lnk shortcuts launch through start.exe; its icon is
                // Wine's own, not the game's, so it is never used
                // Wine이 만든 .lnk 바로가기는 start.exe를 거쳐 실행됨. 그 아이콘은
                // 게임이 아니라 Wine 것이므로 쓰지 않음
                if (p.toLowerCase(java.util.Locale.ENGLISH).replace('\\', '/').endsWith("windows/command/start.exe")) return null;
                if (p.length() < 3 || p.charAt(1) != ':') return null;
                String drive = Character.toLowerCase(p.charAt(0)) + ":";
                String rest = p.substring(2).replace('\\', '/');
                File exe = new File(new File(container.getRootDir(), ".wine/dosdevices/" + drive), rest);
                return exe.getCanonicalFile();
            } catch (Exception e) {
                return null;
            }
        }

        // Icon backfill for shortcuts that have no icon (made before icon
        // extraction existed, or whose exe was unreadable at creation time).
        // On success the PNG is written under icons/64 and the Icon= line of
        // the .desktop file is set, so the next load finds it normally
        // 아이콘이 없는 숏컷용 아이콘 보충임(아이콘 추출 기능 이전에 만들었거나,
        // 만들 때 exe를 못 읽은 경우). 성공하면 PNG를 icons/64에 쓰고 .desktop
        // 파일의 Icon= 줄을 채워서, 다음 로딩부터는 평소처럼 찾게 함
        public String getIconName() {
            return iconName;
        }

        // Extracts the exe icon into icons/64/<name>.png and points Icon= at it
        // (used to give shortcuts that shared one icon file their own)
        // exe 아이콘을 icons/64/<name>.png로 뽑고 Icon=이 그것을 가리키게 함
        // (아이콘 파일 하나를 같이 쓰던 바로가기에 각자 아이콘을 줄 때 씀)
        public synchronized boolean extractIconAs(String name) {
            File exe = resolveExeFile();
            if (exe == null || !exe.isFile()) return false;
            File out = new File(container.getIconsDir(64), name + ".png");
            if (!ExeIconExtractor.extractLargestIconToFile(exe, out)) return false;
            Bitmap bmp = decodeIfFile(out.getPath());
            if (bmp == null || !writeIconLine(name)) return false;
            iconName = name;
            icon = bmp;
            iconFile = out;
            return true;
        }

        public synchronized boolean tryExtractIcon() {
            if (icon != null) return true;
            File exe = resolveExeFile();
            if (exe == null || !exe.isFile()) return false;
            String name = !iconName.isEmpty() ? iconName : FileUtils.getBasename(file.getPath());
            File out = new File(container.getIconsDir(64), name + ".png");
            if (!ExeIconExtractor.extractLargestIconToFile(exe, out)) return false;
            Bitmap bmp = decodeIfFile(out.getPath());
            if (bmp == null) return false;
            if (!name.equals(iconName) && !writeIconLine(name)) return false;
            iconName = name;
            icon = bmp;
            iconFile = out;
            return true;
        }

        // Replace (or add) the Icon= line inside [Desktop Entry], keeping every
        // other line exactly as it was
        // [Desktop Entry] 안의 Icon= 줄을 바꾸거나 추가함. 나머지 줄은 그대로 둠
        private boolean writeIconLine(String name) {
            StringBuilder out = new StringBuilder();
            boolean inDesktop = false;
            boolean written = false;
            for (String line : FileUtils.readLines(file)) {
                String t = line.trim();
                if (t.startsWith("[")) {
                    if (inDesktop && !written) {
                        out.append("Icon=").append(name).append("\n");
                        written = true;
                    }
                    inDesktop = t.equals("[Desktop Entry]");
                    out.append(line).append("\n");
                    continue;
                }
                if (inDesktop && t.startsWith("Icon=")) {
                    if (!written) {
                        out.append("Icon=").append(name).append("\n");
                        written = true;
                    }
                    continue;
                }
                out.append(line).append("\n");
            }
            if (!written) out.append("Icon=").append(name).append("\n");
            // Written to a temp file and renamed over the original: this runs on
            // a background thread, and a list reload reading a half-written
            // .desktop would treat it as corrupt and rename it to .bad
            // 임시 파일에 쓴 뒤 원본 위로 이름을 바꿈: 백그라운드 스레드에서 돌기
            // 때문에, 목록 재로딩이 반쯤 쓰인 .desktop을 읽으면 손상으로 보고
            // .bad로 이름을 바꿔 버림
            File tmp = new File(file.getPath() + ".tmp");
            if (!FileUtils.writeString(tmp, out.toString())) return false;
            if (tmp.renameTo(file)) return true;
            tmp.delete();
            return false;
        }

        private File foreignImage(String path) {
            if (path == null || path.isEmpty()) return null;
            File f = new File(path);
            return (f.isFile() && !isOwnedImage(f)) ? f : null;
        }

        private static String extensionOf(File f) {
            String n = f.getName();
            int dot = n.lastIndexOf('.');
            return dot >= 0 ? n.substring(dot) : ".png";
        }

        public String getExtra(String name) {
            return getExtra(name, "");
        }

        public String getExtra(String name, String fallback) {
            try {
                return extraData.has(name) ? extraData.getString(name) : fallback;
            }
            catch (JSONException e) {
                return fallback;
            }
        }

        public void putExtra(String name, String value) {
            try {
                if (value != null) {
                    extraData.put(name, value);
                }
                else extraData.remove(name);
            }
            catch (JSONException e) {}
        }

        public void saveData() {
            String content = "[Desktop Entry]\n";
            for (String line : FileUtils.readLines(file)) {
                if (line.contains("[Extra Data]")) break;
                if (!line.contains("[Desktop Entry]") && !line.isEmpty()) content += line + "\n";
            }

            if (extraData.length() > 0) {
                content += "\n[Extra Data]\n";
                Iterator<String> keys = extraData.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    try {
                        content += key + "=" + extraData.getString(key) + "\n";
                    } catch (JSONException e) {}
                }
            }

            // Verify that the file reference is correct
            if (!file.getName().endsWith(".desktop")) {
                Log.e("Shortcut", "Incorrect file reference before saving: " + file.getPath());
                return; // Prevent saving to an incorrect file
            }

            FileUtils.writeString(file, content);
        }


        public void genUUID() {
            if (getExtra("uuid").equals("")) {
                putExtra("uuid", UUID.randomUUID().toString());
                saveData();
            }
        }

        public boolean cloneToContainer(Container newContainer) {
            try {
                // Define the path for the new .desktop file in the new container
                File newShortcutFile = new File(newContainer.getDesktopDir(), this.file.getName());

                // Read the existing .desktop file
                ArrayList<String> lines = FileUtils.readLines(this.file);

                // Prepare the content for the new .desktop file with updated container_id
                StringBuilder updatedContent = new StringBuilder();
                boolean containerIdFound = false;

                for (String line : lines) {
                    if (line.startsWith("container_id:")) {
                        // Update the container_id to the new container
                        updatedContent.append("container_id:").append(newContainer.id).append("\n");
                        containerIdFound = true;
                    } else {
                        updatedContent.append(line).append("\n");
                    }
                }

                // If the container_id wasn't found in the original file, add it
                if (!containerIdFound) {
                    updatedContent.append("container_id:").append(newContainer.id).append("\n");
                }

                // Write the updated content to the new .desktop file
                FileUtils.writeString(newShortcutFile, updatedContent.toString());

                // Optionally copy the icon if it exists
                if (this.iconFile != null && this.iconFile.isFile()) {
                    File newIconFile = new File(newContainer.getIconsDir(64), this.iconFile.getName());
                    FileUtils.copy(this.iconFile, newIconFile);
                }

                // Give the clone its own copies of the tile image / cover
                // 복제본에 타일 이미지 / 커버 사본을 따로 만들어 줌
                try {
                    new Shortcut(newContainer, newShortcutFile).adoptForeignImages();
                } catch (Exception e) {
                    Log.w("Shortcut", "Clone created, but image copy failed", e);
                }

                return true;
            } catch (Exception e) {
                Log.e("Shortcut", "Failed to clone shortcut to new container", e);
                return false;
            }
        }

        public int getContainerId() {
            return container.id;
        }
         
        public String getExecutable() {
            String exe = "";
            try {
                List<String> lines = Files.readAllLines(file.toPath());
                for (String line : lines) {
                    if (line.startsWith("Exec")) {
                        exe = line.substring(line.lastIndexOf("\\") + 1, line.length()).replaceAll("\\s+$", "");
                        break;
                    }
                }
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        
            return exe;
        }

        public boolean hasExtra(String name) {
            return this.extraData != null && this.extraData.has(name);
        }
    }
