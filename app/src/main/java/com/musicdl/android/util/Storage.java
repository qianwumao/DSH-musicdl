package com.musicdl.android.util;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 保存歌曲与歌词。
 * 默认写入 Download/MusicDownload（歌曲进媒体库，歌词同目录）；
 * 用户选择目录后通过 SAF 写入所选目录，歌曲和歌词始终放在一起。
 */
public final class Storage {

    public static final String FOLDER = "MusicDownload";
    private static final String DEFAULT_RELATIVE_PATH =
            Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/";
    private static final String LEGACY_MUSIC_PATH =
            Environment.DIRECTORY_MUSIC + "/" + FOLDER + "/";
    private static final String PREFS = "musicdl";
    private static final String KEY_TREE_URI = "save_tree_uri";
    private static final Set<String> AUDIO_EXTS = new HashSet<>();

    static {
        AUDIO_EXTS.add("mp3");
        AUDIO_EXTS.add("flac");
        AUDIO_EXTS.add("wav");
        AUDIO_EXTS.add("m4a");
        AUDIO_EXTS.add("aac");
        AUDIO_EXTS.add("ogg");
        AUDIO_EXTS.add("oga");
        AUDIO_EXTS.add("ape");
        AUDIO_EXTS.add("wma");
        AUDIO_EXTS.add("opus");
        AUDIO_EXTS.add("aiff");
        AUDIO_EXTS.add("aif");
    }

    /** 返回 false 表示用户取消下载。 */
    public interface Progress {
        boolean onProgress(int percent);
    }

    private Storage() {
    }

    public static boolean needsLegacyPermission() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Uri getCustomTree(Context context) {
        String raw = prefs(context).getString(KEY_TREE_URI, "");
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            Uri uri = Uri.parse(raw);
            return hasPersistedWritePermission(context, uri) ? uri : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasPersistedWritePermission(Context context, Uri uri) {
        try {
            for (android.content.UriPermission permission
                    : context.getContentResolver().getPersistedUriPermissions()) {
                if (permission.isWritePermission() && permission.getUri().equals(uri)) {
                    return true;
                }
            }
        } catch (Exception ignored) {
            // 查询失败按不可写处理
        }
        return false;
    }

    public static void setCustomTree(Context context, Uri uri) {
        prefs(context).edit()
                .putString(KEY_TREE_URI, uri == null ? "" : uri.toString())
                .apply();
    }

    public static boolean hasCustomFolder(Context context) {
        return getCustomTree(context) != null;
    }

    /** 显示用目录标签，如 Download/MusicDownload/ 或 Music/。 */
    public static String folderLabel(Context context) {
        Uri tree = getCustomTree(context);
        if (tree == null) {
            return Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/";
        }
        try {
            String docId = DocumentsContract.getTreeDocumentId(tree);
            int colon = docId.indexOf(':');
            String path = colon >= 0 ? docId.substring(colon + 1) : docId;
            path = path.replaceAll("/+$", "");
            if (path.isEmpty()) {
                return "存储根目录/";
            }
            String volume = colon >= 0 ? docId.substring(0, colon) : "primary";
            String prefix = "primary".equalsIgnoreCase(volume) ? "" : "SD卡/";
            return prefix + path + "/";
        } catch (Exception e) {
            return "已选目录/";
        }
    }

    public static String saveAudio(Context context, String displayName, String ext,
                                   InputStream in, long totalBytes, Progress progress)
            throws IOException {
        return saveAudio(context, null, displayName, ext, in, totalBytes, progress);
    }

    /** folder 为保存根目录下的子文件夹（歌手名），为空时直接写入根目录。 */
    public static String saveAudio(Context context, String folder, String displayName, String ext,
                                   InputStream in, long totalBytes, Progress progress)
            throws IOException {
        String mime = FormatUtils.mimeForExt(ext);
        String dir = safeFolder(folder);
        Uri tree = getCustomTree(context);
        if (tree != null) {
            return saveFileToTree(context, tree, dir, displayName, mime, in, totalBytes, progress);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveAudioToMediaStore(context, relativePath(dir), displayName, mime,
                    in, totalBytes, progress);
        }
        return saveToLegacyDir(dir, displayName, in, totalBytes, progress);
    }

    private static String saveAudioToMediaStore(Context context, String relativePath,
                                                String displayName, String mime,
                                                InputStream in, long totalBytes, Progress progress)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        deleteMediaRow(context, relativePath, displayName);

        // Download 目录不能插入 Audio 集合，改用 Files 集合写入；音频仍会进入媒体库
        Uri collection = MediaStore.Files.getContentUri("external");
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IOException("无法创建音乐文件");
        }
        try {
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("无法写入音乐文件");
                }
                copy(in, out, totalBytes, progress);
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return displayName;
        } catch (IOException | RuntimeException e) {
            try {
                resolver.delete(uri, null, null);
            } catch (Exception ignored) {
                // 清理失败忽略
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
    }

    private static String saveFileToTree(Context context, Uri tree, String folder,
                                         String displayName, String mime, InputStream in,
                                         long totalBytes, Progress progress) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        deleteTreeChild(context, tree, folder, displayName);
        Uri document = createTreeChild(context, tree, folder, mime, displayName);
        try {
            try (OutputStream out = resolver.openOutputStream(document)) {
                if (out == null) {
                    throw new IOException("无法写入所选目录");
                }
                copy(in, out, totalBytes, progress);
            }
            return displayName;
        } catch (IOException | RuntimeException e) {
            try {
                resolver.delete(document, null, null);
            } catch (Exception ignored) {
                // 清理失败忽略
            }
            throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
        }
    }

    private static String saveToLegacyDir(String folder, String displayName, InputStream in,
                                          long totalBytes, Progress progress)
            throws IOException {
        File dir = legacyDir(folder);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("无法创建保存目录");
        }
        File target = new File(dir, displayName);
        File temp = new File(dir, displayName + ".part");
        try {
            try (OutputStream out = new FileOutputStream(temp)) {
                copy(in, out, totalBytes, progress);
            }
            if (target.exists() && !target.delete()) {
                throw new IOException("无法覆盖旧文件");
            }
            if (!temp.renameTo(target)) {
                throw new IOException("无法写入音乐文件");
            }
            return displayName;
        } finally {
            if (temp.exists()) {
                temp.delete();
            }
        }
    }

    private static void copy(InputStream in, OutputStream out, long totalBytes, Progress progress)
            throws IOException {
        byte[] buffer = new byte[32 * 1024];
        long written = 0;
        int lastPercent = -1;
        long lastReport = 0;
        int len;
        while ((len = in.read(buffer)) >= 0) {
            out.write(buffer, 0, len);
            written += len;
            int percent = totalBytes > 0
                    ? (int) Math.min(99, written * 100 / totalBytes)
                    : -1;
            long now = System.currentTimeMillis();
            if (percent != lastPercent || now - lastReport > 400) {
                lastPercent = percent;
                lastReport = now;
                if (progress != null && !progress.onProgress(percent)) {
                    throw new IOException("已取消");
                }
            }
        }
        out.flush();
        if (progress != null) {
            progress.onProgress(100);
        }
    }

    public static void saveLyric(Context context, String displayName, String text)
            throws IOException {
        saveLyric(context, null, displayName, text);
    }

    /** folder 为保存根目录下的子文件夹（歌手名），为空时直接写入根目录。 */
    public static void saveLyric(Context context, String folder, String displayName, String text)
            throws IOException {
        // text/plain 会被 MediaProvider 改名为 .txt，使用专门的 lrc 类型保留扩展名
        String mime = "text/x-lrc";
        String dir = safeFolder(folder);
        Uri tree = getCustomTree(context);
        if (tree != null) {
            saveTextToTree(context, tree, dir, displayName, mime, text);
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            saveTextToMediaStore(context, relativePath(dir), displayName, mime, text);
        } else {
            File target = legacyDir(dir);
            if (!target.exists() && !target.mkdirs()) {
                throw new IOException("无法创建歌词目录");
            }
            try (FileOutputStream out = new FileOutputStream(new File(target, displayName))) {
                out.write(text.getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    public static Uri saveImage(Context context, String displayName, String mime, byte[] data)
            throws IOException {
        return saveImage(context, null, displayName, mime, data);
    }

    /** folder 为保存根目录下的子文件夹（歌手名），为空时直接写入根目录。 */
    public static Uri saveImage(Context context, String folder, String displayName,
                                String mime, byte[] data) throws IOException {
        if (data == null || data.length == 0) {
            throw new IOException("封面为空");
        }
        String dir = safeFolder(folder);
        Uri tree = getCustomTree(context);
        if (tree != null) {
            ContentResolver resolver = context.getContentResolver();
            deleteTreeChild(context, tree, dir, displayName);
            Uri document = createTreeChild(context, tree, dir, mime, displayName);
            try (OutputStream out = resolver.openOutputStream(document)) {
                if (out == null) {
                    throw new IOException("无法写入封面");
                }
                out.write(data);
            } catch (IOException | RuntimeException e) {
                try {
                    resolver.delete(document, null, null);
                } catch (Exception ignored) {
                    // 清理失败忽略
                }
                throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
            }
            return document;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentResolver resolver = context.getContentResolver();
            String relativePath = relativePath(dir);
            deleteMediaRow(context, relativePath, displayName);
            Uri collection = MediaStore.Files.getContentUri("external");
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(collection, values);
            if (uri == null) {
                throw new IOException("无法创建封面文件");
            }
            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IOException("无法写入封面文件");
                }
                out.write(data);
            } catch (IOException | RuntimeException e) {
                try {
                    resolver.delete(uri, null, null);
                } catch (Exception ignored) {
                    // 清理失败忽略
                }
                throw e instanceof IOException ? (IOException) e : new IOException(e.getMessage());
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, done, null, null);
            return uri;
        }
        File targetDir = legacyDir(dir);
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            throw new IOException("无法创建封面目录");
        }
        File target = new File(targetDir, displayName);
        try (FileOutputStream out = new FileOutputStream(target)) {
            out.write(data);
        }
        return Uri.fromFile(target);
    }

    private static void saveTextToMediaStore(Context context, String relativePath,
                                             String displayName, String mime,
                                             String text) throws IOException {
        ContentResolver resolver = context.getContentResolver();
        Uri collection = MediaStore.Files.getContentUri("external");
        deleteMediaRow(context, relativePath, displayName);
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IOException("无法创建歌词文件");
        }
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) {
                throw new IOException("无法写入歌词文件");
            }
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void saveTextToTree(Context context, Uri tree, String folder,
                                       String displayName, String mime, String text)
            throws IOException {
        ContentResolver resolver = context.getContentResolver();
        deleteTreeChild(context, tree, folder, displayName);
        Uri document = createTreeChild(context, tree, folder, mime, displayName);
        try (OutputStream out = resolver.openOutputStream(document)) {
            if (out == null) {
                throw new IOException("无法写入所选目录");
            }
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 保存目录中已有的歌曲名（归一化），用于“已存在置灰”。 */
    public static Set<String> existingNames(Context context) {
        Set<String> names = new HashSet<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? OR "
                        + MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ?";
                String[] args = {DEFAULT_RELATIVE_PATH + "%", LEGACY_MUSIC_PATH + "%"};
                try (Cursor cursor = context.getContentResolver().query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        new String[]{MediaStore.MediaColumns.DISPLAY_NAME},
                        selection, args, null)) {
                    if (cursor != null) {
                        while (cursor.moveToNext()) {
                            addNormalized(names, cursor.getString(0));
                        }
                    }
                }
            } catch (Exception ignored) {
                // 查询失败不影响下载
            }
        }
        addTreeNames(context, names);
        try {
            addDirNames(legacyDir(), names);
            addDirNames(legacyMusicDir(), names);
            File appDir = context.getExternalFilesDir(Environment.DIRECTORY_MUSIC);
            if (appDir != null) {
                addDirNames(new File(appDir, FOLDER), names);
            }
        } catch (Exception ignored) {
            // 目录不可读忽略
        }
        return names;
    }

    private static void addDirNames(File dir, Set<String> names) {
        addDirNames(dir, names, 0);
    }

    private static void addDirNames(File dir, Set<String> names, int depth) {
        if (dir == null || !dir.isDirectory()) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isDirectory()) {
                if (depth < 2) {
                    addDirNames(file, names, depth + 1);
                }
            } else {
                addNormalized(names, file.getName());
            }
        }
    }

    private static void addTreeNames(Context context, Set<String> names) {
        Uri tree = getCustomTree(context);
        if (tree == null) {
            return;
        }
        try {
            collectTreeNames(context, tree, DocumentsContract.getTreeDocumentId(tree), names, 0);
        } catch (Exception ignored) {
            // 目录不可读忽略
        }
    }

    private static void collectTreeNames(Context context, Uri tree, String parentId,
                                         Set<String> names, int depth) {
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
            List<String> dirs = new ArrayList<>();
            try (Cursor cursor = context.getContentResolver().query(children,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null)) {
                if (cursor == null) {
                    return;
                }
                while (cursor.moveToNext()) {
                    String docId = cursor.getString(0);
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    if (docId == null || name == null) {
                        continue;
                    }
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        if (depth < 2) {
                            dirs.add(docId);
                        }
                        continue;
                    }
                    String ext = extOf(name);
                    if ((mime != null && mime.startsWith("audio/"))
                            || AUDIO_EXTS.contains(ext)) {
                        addNormalized(names, name);
                    }
                }
            }
            for (String dirId : dirs) {
                collectTreeNames(context, tree, dirId, names, depth + 1);
            }
        } catch (Exception ignored) {
            // 目录不可读忽略
        }
    }

    private static void addNormalized(Set<String> names, String fileName) {
        if (fileName == null) {
            return;
        }
        String normalized = normalize(fileName);
        if (normalized.isEmpty()) {
            return;
        }
        names.add(normalized);
        for (String part : normalized.split("-")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                names.add(trimmed);
            }
        }
    }

    /** 去掉音频扩展名并转小写，与桌面端归一化规则一致。 */
    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String value = name.trim();
        int dot = value.lastIndexOf('.');
        if (dot > 0) {
            String ext = value.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (AUDIO_EXTS.contains(ext)) {
                value = value.substring(0, dot);
            }
        }
        return value.trim().toLowerCase(Locale.ROOT);
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }

    private static void deleteMediaRow(Context context, String relativePath, String displayName) {
        try {
            String selection = MediaStore.MediaColumns.RELATIVE_PATH + " LIKE ? AND "
                    + MediaStore.MediaColumns.DISPLAY_NAME + " = ?";
            String[] args = {relativePath + "%", displayName};
            Uri collection = MediaStore.Files.getContentUri("external");
            Cursor cursor = context.getContentResolver().query(collection,
                    new String[]{MediaStore.MediaColumns._ID}, selection, args, null);
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(0);
                    Uri uri = android.content.ContentUris.withAppendedId(collection, id);
                    context.getContentResolver().delete(uri, null, null);
                }
                cursor.close();
            }
        } catch (Exception ignored) {
            // 删除失败时后续插入可能产生重复，但不影响播放
        }
    }

    private static void deleteTreeChild(Context context, Uri tree, String folder, String displayName) {
        try {
            String parentId = findTreeFolderId(context, tree, folder);
            if (parentId == null) {
                return;
            }
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
            try (Cursor cursor = context.getContentResolver().query(children,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null)) {
                if (cursor == null) {
                    return;
                }
                while (cursor.moveToNext()) {
                    String name = cursor.getString(1);
                    String mime = cursor.getString(2);
                    if (displayName.equals(name)
                            && !DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                        Uri fileUri = DocumentsContract.buildDocumentUriUsingTree(
                                tree, cursor.getString(0));
                        context.getContentResolver().delete(fileUri, null, null);
                    }
                }
            }
        } catch (Exception ignored) {
            // 删除失败时后续创建可能重名，不影响主要流程
        }
    }

    private static Uri createTreeChild(Context context, Uri tree, String folder, String mime,
                                       String displayName) throws IOException {
        try {
            Uri parent = ensureTreeFolder(context, tree, folder);
            Uri document = DocumentsContract.createDocument(
                    context.getContentResolver(), parent, mime, displayName);
            if (document == null) {
                throw new IOException("无法在所选目录创建文件");
            }
            return document;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("所选目录不可写：" + e.getMessage());
        }
    }

    /** 找到（或创建）根目录下的歌手文件夹，已有同名文件夹直接复用。 */
    private static Uri ensureTreeFolder(Context context, Uri tree, String folder)
            throws IOException {
        String rootId = DocumentsContract.getTreeDocumentId(tree);
        Uri root = DocumentsContract.buildDocumentUriUsingTree(tree, rootId);
        String dir = safeFolder(folder);
        if (dir.isEmpty()) {
            return root;
        }
        String existingId = findTreeFolderId(context, tree, dir);
        if (existingId != null) {
            return DocumentsContract.buildDocumentUriUsingTree(tree, existingId);
        }
        Uri created = null;
        try {
            created = DocumentsContract.createDocument(context.getContentResolver(), root,
                    DocumentsContract.Document.MIME_TYPE_DIR, dir);
        } catch (Exception ignored) {
            // 可能已被其他任务创建，下面重新查找
        }
        if (created != null) {
            return created;
        }
        String retryId = findTreeFolderId(context, tree, dir);
        if (retryId != null) {
            return DocumentsContract.buildDocumentUriUsingTree(tree, retryId);
        }
        throw new IOException("无法创建歌手文件夹");
    }

    private static String findTreeFolderId(Context context, Uri tree, String folder) {
        String dir = safeFolder(folder);
        if (dir.isEmpty()) {
            return DocumentsContract.getTreeDocumentId(tree);
        }
        try {
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId);
            try (Cursor cursor = context.getContentResolver().query(children,
                    new String[]{
                            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                            DocumentsContract.Document.COLUMN_MIME_TYPE},
                    null, null, null)) {
                if (cursor != null) {
                    while (cursor.moveToNext()) {
                        String name = cursor.getString(1);
                        String mime = cursor.getString(2);
                        if (dir.equalsIgnoreCase(name)
                                && DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                            return cursor.getString(0);
                        }
                    }
                }
            }
        } catch (Exception ignored) {
            // 查询失败按文件夹不存在处理
        }
        return null;
    }

    private static String relativePath(String folder) {
        String dir = safeFolder(folder);
        return dir.isEmpty() ? DEFAULT_RELATIVE_PATH : DEFAULT_RELATIVE_PATH + dir + "/";
    }

    private static String safeFolder(String folder) {
        if (folder == null) {
            return "";
        }
        String out = folder.replaceAll("[\\\\/*?:\"<>|\\r\\n\\t]", "_").trim();
        out = out.replaceAll("\\s+", " ");
        while (out.startsWith(".")) {
            out = out.substring(1);
        }
        if (out.length() > 80) {
            out = out.substring(0, 80);
        }
        return out.trim();
    }

    private static File legacyDir() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS), FOLDER);
    }

    private static File legacyDir(String folder) {
        String dir = safeFolder(folder);
        return dir.isEmpty() ? legacyDir() : new File(legacyDir(), dir);
    }

    private static File legacyMusicDir() {
        return new File(Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_MUSIC), FOLDER);
    }
}
