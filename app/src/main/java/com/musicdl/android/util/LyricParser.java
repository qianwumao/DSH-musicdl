package com.musicdl.android.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** LRC 歌词解析：合并同一时间点的歌词行，并按播放位置取当前行。 */
public final class LyricParser {

    public static final class Line {
        public final int timeMs;
        public final String text;

        public Line(int timeMs, String text) {
            this.timeMs = timeMs;
            this.text = text;
        }
    }

    private static final Pattern TIMESTAMP =
            Pattern.compile("\\[(\\d{1,2}):(\\d{1,2})(?:[.:](\\d{1,3}))?]");
    private static final Pattern OFFSET = Pattern.compile("\\[offset:(-?\\d+)]");
    private static final Pattern QRC_TAG = Pattern.compile("<(-?\\d+),(-?\\d+)>");
    private static final Pattern NUMERIC_ENTITY =
            Pattern.compile("&#(x?[0-9a-fA-F]+);");

    private LyricParser() {
    }

    public static List<Line> parse(String raw) {
        List<Line> lines = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return lines;
        }
        String content = decodeEntities(raw);
        int offset = 0;
        Matcher offsetMatcher = OFFSET.matcher(content);
        if (offsetMatcher.find()) {
            try {
                offset = Integer.parseInt(offsetMatcher.group(1));
            } catch (NumberFormatException ignored) {
                // 非法 offset 忽略
            }
        }

        TreeMap<Integer, String> merged = new TreeMap<>();
        for (String row : content.split("\\r?\\n")) {
            Matcher matcher = TIMESTAMP.matcher(row);
            List<Integer> times = new ArrayList<>();
            int textStart = 0;
            while (matcher.find()) {
                int minute = parseInt(matcher.group(1));
                int second = parseInt(matcher.group(2));
                int millis = parseMillis(matcher.group(3));
                times.add(minute * 60000 + second * 1000 + millis);
                textStart = matcher.end();
            }
            if (times.isEmpty()) {
                continue;
            }
            String text = QRC_TAG.matcher(row.substring(textStart)).replaceAll("").trim();
            if (text.isEmpty()) {
                continue;
            }
            for (int time : times) {
                int adjusted = Math.max(0, time + offset);
                String old = merged.get(adjusted);
                if (old == null || old.equals(text)) {
                    merged.put(adjusted, text);
                } else {
                    merged.put(adjusted, old + " / " + text);
                }
            }
        }
        for (Map.Entry<Integer, String> entry : merged.entrySet()) {
            lines.add(new Line(entry.getKey(), entry.getValue()));
        }
        return lines;
    }

    /** 返回当前位置对应的歌词行；尚未到第一句时返回 null。 */
    public static String lineAt(List<Line> lines, int positionMs) {
        int index = indexAt(lines, positionMs);
        return index < 0 ? null : lines.get(index).text;
    }

    /** 返回当前位置对应的歌词行下标；尚未到第一句时返回 -1。 */
    public static int indexAt(List<Line> lines, int positionMs) {
        if (lines == null || lines.isEmpty()) {
            return -1;
        }
        int low = 0;
        int high = lines.size() - 1;
        int found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            if (lines.get(mid).timeMs <= positionMs) {
                found = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return found;
    }

    private static int parseMillis(String fraction) {
        if (fraction == null || fraction.isEmpty()) {
            return 0;
        }
        int value = parseInt(fraction);
        if (fraction.length() == 1) {
            return value * 100;
        }
        if (fraction.length() == 2) {
            return value * 10;
        }
        return value;
    }

    private static int parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String decodeEntities(String text) {
        String decoded = text
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&nbsp;", " ");
        Matcher matcher = NUMERIC_ENTITY.matcher(decoded);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String code = matcher.group(1);
            int codePoint;
            try {
                codePoint = code.startsWith("x")
                        ? Integer.parseInt(code.substring(1), 16)
                        : Integer.parseInt(code);
            } catch (NumberFormatException e) {
                continue;
            }
            String replacement;
            try {
                replacement = new String(Character.toChars(codePoint));
            } catch (IllegalArgumentException e) {
                continue;
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
