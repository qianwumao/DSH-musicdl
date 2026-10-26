package com.musicdl.android.source.kuwo;

import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.Inflater;

/**
 * 酷我接口参数加密 / 歌词解密。
 * 算法与桌面端 musicdl 的 KuwoMusicClientUtils 保持一致（自定义 DES + XOR）。
 */
public final class KuwoCrypt {

    private static final byte[] SECRET_KEY_SONG = "ylzsxkwm".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SECRET_KEY_LYRIC = "yeelion".getBytes(StandardCharsets.UTF_8);
    private static final Charset GB18030 = Charset.forName("GB18030");

    private static final int[] ARRAYLS =
            {1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1};
    private static final long[] ARRAYLSMASK = {0, 0x100001L, 0x300003L};
    private static final int[] ARRAYE = {
            31, 0, 1, 2, 3, 4, -1, -1,
            3, 4, 5, 6, 7, 8, -1, -1,
            7, 8, 9, 10, 11, 12, -1, -1,
            11, 12, 13, 14, 15, 16, -1, -1,
            15, 16, 17, 18, 19, 20, -1, -1,
            19, 20, 21, 22, 23, 24, -1, -1,
            23, 24, 25, 26, 27, 28, -1, -1,
            27, 28, 29, 30, 31, 30, -1, -1};
    private static final int[] ARRAYIP1 = {
            39, 7, 47, 15, 55, 23, 63, 31,
            38, 6, 46, 14, 54, 22, 62, 30,
            37, 5, 45, 13, 53, 21, 61, 29,
            36, 4, 44, 12, 52, 20, 60, 28,
            35, 3, 43, 11, 51, 19, 59, 27,
            34, 2, 42, 10, 50, 18, 58, 26,
            33, 1, 41, 9, 49, 17, 57, 25,
            32, 0, 40, 8, 48, 16, 56, 24};
    private static final int[] ARRAYIP2 = {
            57, 49, 41, 33, 25, 17, 9, 1,
            59, 51, 43, 35, 27, 19, 11, 3,
            61, 53, 45, 37, 29, 21, 13, 5,
            63, 55, 47, 39, 31, 23, 15, 7,
            56, 48, 40, 32, 24, 16, 8, 0,
            58, 50, 42, 34, 26, 18, 10, 2,
            60, 52, 44, 36, 28, 20, 12, 4,
            62, 54, 46, 38, 30, 22, 14, 6};
    private static final int[] ARRAYP = {
            15, 6, 19, 20, 28, 11, 27, 16,
            0, 14, 22, 25, 4, 17, 30, 9,
            1, 7, 23, 13, 31, 26, 2, 8,
            18, 12, 29, 5, 21, 10, 3, 24};
    private static final int[] ARRAYPC1 = {
            56, 48, 40, 32, 24, 16, 8, 0,
            57, 49, 41, 33, 25, 17, 9, 1,
            58, 50, 42, 34, 26, 18, 10, 2,
            59, 51, 43, 35, 62, 54, 46, 38,
            30, 22, 14, 6, 61, 53, 45, 37,
            29, 21, 13, 5, 60, 52, 44, 36,
            28, 20, 12, 4, 27, 19, 11, 3};
    private static final int[] ARRAYPC2 = {
            13, 16, 10, 23, 0, 4, -1, -1,
            2, 27, 14, 5, 20, 9, -1, -1,
            22, 18, 11, 3, 25, 7, -1, -1,
            15, 6, 26, 19, 12, 1, -1, -1,
            40, 51, 30, 36, 46, 54, -1, -1,
            29, 39, 50, 44, 32, 47, -1, -1,
            43, 48, 38, 55, 33, 52, -1, -1,
            45, 41, 49, 35, 28, 31, -1, -1};
    private static final int[][] MATRIXNSBOX = {
            {14, 4, 3, 15, 2, 13, 5, 3, 13, 14, 6, 9, 11, 2, 0, 5, 4, 1, 10, 12, 15, 6, 9, 10,
                    1, 8, 12, 7, 8, 11, 7, 0, 0, 15, 10, 5, 14, 4, 9, 10, 7, 8, 12, 3, 13, 1, 3,
                    6, 15, 12, 6, 11, 2, 9, 5, 0, 4, 2, 11, 14, 1, 7, 8, 13},
            {15, 0, 9, 5, 6, 10, 12, 9, 8, 7, 2, 12, 3, 13, 5, 2, 1, 14, 7, 8, 11, 4, 0, 3,
                    14, 11, 13, 6, 4, 1, 10, 15, 3, 13, 12, 11, 15, 3, 6, 0, 4, 10, 1, 7, 8, 4,
                    11, 14, 13, 8, 0, 6, 2, 15, 9, 5, 7, 1, 10, 12, 14, 2, 5, 9},
            {10, 13, 1, 11, 6, 8, 11, 5, 9, 4, 12, 2, 15, 3, 2, 14, 0, 6, 13, 1, 3, 15, 4, 10,
                    14, 9, 7, 12, 5, 0, 8, 7, 13, 1, 2, 4, 3, 6, 12, 11, 0, 13, 5, 14, 6, 8, 15,
                    2, 7, 10, 8, 15, 4, 9, 11, 5, 9, 0, 14, 3, 10, 7, 1, 12},
            {7, 10, 1, 15, 0, 12, 11, 5, 14, 9, 8, 3, 9, 7, 4, 8, 13, 6, 2, 1, 6, 11, 12, 2,
                    3, 0, 5, 14, 10, 13, 15, 4, 13, 3, 4, 9, 6, 10, 1, 12, 11, 0, 2, 5, 0, 13, 14,
                    2, 8, 15, 7, 4, 15, 1, 10, 7, 5, 6, 12, 11, 3, 8, 9, 14},
            {2, 4, 8, 15, 7, 10, 13, 6, 4, 1, 3, 12, 11, 7, 14, 0, 12, 2, 5, 9, 10, 13, 0, 3,
                    1, 11, 15, 5, 6, 8, 9, 14, 14, 11, 5, 6, 4, 1, 3, 10, 2, 12, 15, 0, 13, 2, 8,
                    5, 11, 8, 0, 15, 7, 14, 9, 4, 12, 7, 10, 9, 1, 13, 6, 3},
            {12, 9, 0, 7, 9, 2, 14, 1, 10, 15, 3, 4, 6, 12, 5, 11, 1, 14, 13, 0, 2, 8, 7, 13,
                    15, 5, 4, 10, 8, 3, 11, 6, 10, 4, 6, 11, 7, 9, 0, 6, 4, 2, 13, 1, 9, 15, 3,
                    8, 15, 3, 1, 14, 12, 5, 11, 0, 2, 12, 14, 7, 5, 10, 8, 13},
            {4, 1, 3, 10, 15, 12, 5, 0, 2, 11, 9, 6, 8, 7, 6, 9, 11, 4, 12, 15, 0, 3, 10, 5,
                    14, 13, 7, 8, 13, 14, 1, 2, 13, 6, 14, 9, 4, 1, 2, 14, 11, 13, 5, 0, 1, 10,
                    8, 3, 0, 11, 3, 5, 9, 4, 15, 2, 7, 8, 12, 15, 10, 7, 6, 12},
            {13, 7, 10, 0, 6, 9, 5, 15, 8, 4, 3, 10, 11, 14, 12, 5, 2, 11, 9, 6, 15, 12, 0, 3,
                    4, 1, 14, 13, 1, 2, 7, 8, 1, 2, 12, 15, 10, 4, 0, 3, 13, 14, 6, 9, 7, 8, 9,
                    6, 15, 1, 5, 12, 3, 10, 14, 5, 8, 7, 11, 0, 4, 13, 2, 11}};

    private static final long[] MASK = new long[64];

    static {
        for (int i = 0; i < 63; i++) {
            MASK[i] = 1L << i;
        }
        MASK[63] = Long.MIN_VALUE;
    }

    private KuwoCrypt() {
    }

    public static String encryptQuery(String query) {
        byte[] encrypted = crypt(query.getBytes(StandardCharsets.UTF_8), SECRET_KEY_SONG, 0);
        return Base64.getEncoder().encodeToString(encrypted);
    }

    public static String buildLyricParams(String musicId) {
        String params = "user=12345,web,web,web&requester=localhost&req=1&rid=MUSIC_"
                + musicId + "&lrcx=1";
        return Base64.getEncoder().encodeToString(
                xor(params.getBytes(StandardCharsets.UTF_8), SECRET_KEY_LYRIC));
    }

    public static String decodeLyric(byte[] buffer) {
        if (buffer == null || buffer.length < 10) {
            return null;
        }
        String head = new String(buffer, 0, Math.min(10, buffer.length), StandardCharsets.UTF_8);
        if (!head.startsWith("tp=content")) {
            return null;
        }
        int split = indexOf(buffer, new byte[]{'\r', '\n', '\r', '\n'});
        if (split < 0) {
            return null;
        }
        try {
            byte[] inflated = inflate(buffer, split + 4, buffer.length - split - 4);
            String base64 = new String(inflated, StandardCharsets.UTF_8);
            byte[] decoded = Base64.getMimeDecoder().decode(base64.trim());
            byte[] plain = xor(decoded, SECRET_KEY_LYRIC);
            return new String(plain, GB18030);
        } catch (Exception e) {
            return null;
        }
    }

    private static int indexOf(byte[] data, byte[] target) {
        outer:
        for (int i = 0; i <= data.length - target.length; i++) {
            for (int j = 0; j < target.length; j++) {
                if (data[i + j] != target[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static byte[] inflate(byte[] data, int offset, int length) throws Exception {
        Inflater inflater = new Inflater();
        inflater.setInput(data, offset, length);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        try {
            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                if (count == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        break;
                    }
                } else {
                    out.write(buffer, 0, count);
                }
            }
            return out.toByteArray();
        } finally {
            inflater.end();
        }
    }

    private static byte[] xor(byte[] data, byte[] key) {
        byte[] out = new byte[data.length];
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] ^ key[i % key.length]);
        }
        return out;
    }

    private static long bitTransform(int[] indexes, int count, long value) {
        long out = 0;
        for (int i = 0; i < count; i++) {
            int index = indexes[i];
            if (index >= 0 && (value & MASK[index]) != 0) {
                out |= MASK[i];
            }
        }
        return out;
    }

    private static long des64(long[] subKeys, long block) {
        long first = bitTransform(ARRAYIP2, 64, block);
        long[] parts = {first & 0xFFFFFFFFL, (first & 0xFFFFFFFF00000000L) >>> 32};
        int[] bytes = new int[8];
        for (int i = 0; i < 16; i++) {
            long r = bitTransform(ARRAYE, 64, parts[1]) ^ subKeys[i];
            for (int j = 0; j < 8; j++) {
                bytes[j] = (int) ((r >>> (j * 8)) & 0xFF);
            }
            long sOut = 0;
            for (int sbi = 7; sbi >= 0; sbi--) {
                sOut = (sOut << 4) | (MATRIXNSBOX[sbi][bytes[sbi]] & 0xF);
            }
            long next = parts[0] ^ bitTransform(ARRAYP, 32, sOut);
            parts[0] = parts[1];
            parts[1] = next & 0xFFFFFFFFL;
        }
        long high = parts[0];
        long low = parts[1];
        long out = ((high << 32) & 0xFFFFFFFF00000000L) | (low & 0xFFFFFFFFL);
        return bitTransform(ARRAYIP1, 64, out);
    }

    private static void subKeys(long key, long[] subKeys, int mode) {
        long permuted = bitTransform(ARRAYPC1, 56, key);
        long[] states = new long[17];
        states[0] = permuted;
        for (int i = 0; i < 16; i++) {
            int r = ARRAYLS[i];
            long mask = ARRAYLSMASK[r];
            long x = states[i];
            states[i + 1] = ((x & mask) << (28 - r)) | ((x & ~mask) >>> r);
        }
        for (int i = 0; i < 16; i++) {
            subKeys[i] = bitTransform(ARRAYPC2, 64, states[i + 1]);
        }
        if (mode == 1) {
            for (int i = 0; i < 8; i++) {
                long tmp = subKeys[i];
                subKeys[i] = subKeys[15 - i];
                subKeys[15 - i] = tmp;
            }
        }
    }

    private static byte[] crypt(byte[] message, byte[] key, int mode) {
        long keyLong = 0;
        for (int i = 0; i < 8; i++) {
            keyLong |= ((long) (key[i] & 0xFF)) << (i * 8);
        }
        int blocks = message.length / 8;
        long[] subKeys = new long[16];
        subKeys(keyLong, subKeys, mode);

        long[] values = new long[blocks + 1];
        for (int i = 0; i < blocks; i++) {
            long value = 0;
            for (int j = 0; j < 8; j++) {
                value |= ((long) (message[i * 8 + j] & 0xFF)) << (j * 8);
            }
            values[i] = des64(subKeys, value);
        }
        int rest = message.length % 8;
        if (rest != 0 || mode == 0) {
            long value = 0;
            for (int j = 0; j < rest; j++) {
                value |= ((long) (message[blocks * 8 + j] & 0xFF)) << (j * 8);
            }
            values[blocks] = des64(subKeys, value);
        }
        byte[] out = new byte[(blocks + 1) * 8];
        for (int i = 0; i < values.length; i++) {
            for (int j = 0; j < 8; j++) {
                out[i * 8 + j] = (byte) ((values[i] >>> (j * 8)) & 0xFF);
            }
        }
        return out;
    }
}
