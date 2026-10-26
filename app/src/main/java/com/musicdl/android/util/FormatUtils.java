package com.musicdl.android.util;

import java.util.Locale;

public final class FormatUtils {

    private FormatUtils() {
    }

    public static String sizeOfBytes(long bytes) {
        if (bytes <= 0) {
            return "";
        }
        double mb = bytes / 1024.0 / 1024.0;
        if (mb >= 0.1) {
            return String.format(Locale.CHINA, "%.1f MB", mb);
        }
        return String.format(Locale.CHINA, "%d KB", Math.max(1, bytes / 1024));
    }

    /** 酷我 MINFO 里的 "52.83Mb" -> "52.8 MB"。 */
    public static String sizeOfMbText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String value = text.replaceAll("(?i)mb$", "").trim();
        try {
            double mb = Double.parseDouble(value);
            return String.format(Locale.CHINA, "%.1f MB", mb);
        } catch (NumberFormatException e) {
            return text;
        }
    }

    public static String sanitizeFileName(String name) {
        String out = name == null ? "" : name;
        out = out.replaceAll("[\\\\/*?:\"<>|\\r\\n\\t]", "_").trim();
        out = out.replaceAll("\\s+", " ");
        while (out.startsWith(".")) {
            out = out.substring(1);
        }
        if (out.length() > 150) {
            out = out.substring(0, 150);
        }
        return out.isEmpty() ? "music" : out;
    }

    public static String mimeForExt(String ext) {
        if (ext == null) {
            return "audio/mpeg";
        }
        switch (ext.toLowerCase(Locale.ROOT)) {
            case "flac":
                return "audio/flac";
            case "wav":
                return "audio/wav";
            case "m4a":
            case "mp4":
                return "audio/mp4";
            case "aac":
                return "audio/aac";
            case "ogg":
            case "oga":
                return "audio/ogg";
            case "ape":
                return "audio/x-ape";
            case "wma":
                return "audio/x-ms-wma";
            default:
                return "audio/mpeg";
        }
    }
}
