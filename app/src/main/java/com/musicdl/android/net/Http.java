package com.musicdl.android.net;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/** 轻量 HTTP 工具：固定 UA、手动跟随重定向，供各音乐源复用。 */
public final class Http {

    public static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36";

    private Http() {
    }

    public static Map<String, String> headers(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        map.put("User-Agent", UA);
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            map.put(keyValues[i], keyValues[i + 1]);
        }
        return map;
    }

    public static String encode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static String buildQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return sb.toString();
    }

    public static String get(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = open(url, headers);
        try {
            InputStream in = stream(conn);
            return readAll(in);
        } finally {
            conn.disconnect();
        }
    }

    public static String get(String url) throws IOException {
        return get(url, headers());
    }

    public static byte[] getBytes(String url, Map<String, String> headers) throws IOException {
        HttpURLConnection conn = open(url, headers);
        try {
            return readBytes(stream(conn));
        } finally {
            conn.disconnect();
        }
    }

    public static String postForm(String url, Map<String, String> form,
                                  Map<String, String> headers) throws IOException {
        String body = buildQuery(form);
        return post(url, body, "application/x-www-form-urlencoded; charset=UTF-8", headers);
    }

    public static String postJson(String url, String json,
                                  Map<String, String> headers) throws IOException {
        return post(url, json, "application/json; charset=UTF-8", headers);
    }

    public static String post(String url, String body, String contentType,
                              Map<String, String> headers) throws IOException {
        URL current = new URL(url);
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            conn.setRequestProperty("Content-Type", contentType);
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            try (OutputStream out = conn.getOutputStream()) {
                out.write(body.getBytes(StandardCharsets.UTF_8));
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("重定向缺少 Location");
                }
                current = new URL(current, location);
                continue;
            }
            try {
                return readAll(stream(conn));
            } finally {
                conn.disconnect();
            }
        }
        throw new IOException("重定向次数过多");
    }

    /** 打开连接并手动跟随 3xx 跳转，调用方负责 disconnect。 */
    public static HttpURLConnection open(String url, Map<String, String> headers) throws IOException {
        URL current = new URL(url);
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection conn = (HttpURLConnection) current.openConnection();
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(25000);
            conn.setRequestProperty("Accept", "*/*");
            conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9");
            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
            int code = conn.getResponseCode();
            if (code == HttpURLConnection.HTTP_MOVED_PERM
                    || code == HttpURLConnection.HTTP_MOVED_TEMP
                    || code == HttpURLConnection.HTTP_SEE_OTHER
                    || code == 307 || code == 308) {
                String location = conn.getHeaderField("Location");
                conn.disconnect();
                if (location == null || location.isEmpty()) {
                    throw new IOException("重定向缺少 Location");
                }
                current = new URL(current, location);
                continue;
            }
            return conn;
        }
        throw new IOException("重定向次数过多");
    }

    public static InputStream stream(HttpURLConnection conn) throws IOException {
        int code = conn.getResponseCode();
        if (code >= 400) {
            throw new IOException("HTTP " + code);
        }
        return conn.getInputStream();
    }

    public static String readAll(InputStream in) throws IOException {
        if (in == null) {
            return "";
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[8192];
            int len;
            while ((len = reader.read(buffer)) >= 0) {
                sb.append(buffer, 0, len);
            }
            return sb.toString();
        }
    }

    public static byte[] readBytes(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int len;
        while ((len = in.read(buffer)) >= 0) {
            out.write(buffer, 0, len);
        }
        return out.toByteArray();
    }

    public static String extOfUrl(String url) {
        if (url == null) {
            return "";
        }
        String path = url;
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
