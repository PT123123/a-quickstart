package com.quickstart.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.zip.CRC32;

/**
 * 配置传送：全量导出/导入 "settings" SharedPreferences（界面设置、数字键绑定、隐藏应用等所有键），
 * 并实现二维码分帧协议（发送端 encodeFrames 轮播，接收端 parseFrame + Reassembler 拼包）。
 *
 * 帧格式（二维码文本内容，纯 ASCII）：
 *   QCFG1|session|idx|total|crc32|base64url(分片)
 * session 为随机 4 位 hex，用于区分两次传输；crc32 覆盖完整载荷，收齐后校验。
 */
public final class ConfigTransfer {

    public static final String PREFS_NAME = "settings";

    private static final String FRAME_MAGIC = "QCFG1";
    /** 每帧载荷字节数：Base64 后约 800 字符，二维码用纠错级别 M 仍易识别 */
    private static final int CHUNK_SIZE = 600;

    private ConfigTransfer() {
    }

    /** 全量导出配置为 JSON 字符串（按 SP 实际值类型序列化，新增设置项自动纳入） */
    public static String buildConfigJson(Context context) {
        SharedPreferences sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSONObject json = new JSONObject();
        for (Map.Entry<String, ?> entry : sp.getAll().entrySet()) {
            String key = entry.getKey();
            Object v = entry.getValue();
            try {
                if (v instanceof String) {
                    json.put(key, (String) v);
                } else if (v instanceof Boolean) {
                    json.put(key, ((Boolean) v).booleanValue());
                } else if (v instanceof Integer) {
                    json.put(key, ((Integer) v).intValue());
                } else if (v instanceof Long) {
                    json.put(key, ((Long) v).longValue());
                } else if (v instanceof Float) {
                    json.put(key, ((Float) v).doubleValue());
                } else if (v instanceof Set) {
                    json.put(key, new JSONArray((Set<?>) v));
                }
            } catch (Exception ignored) {
            }
        }
        return json.toString();
    }

    /** 将 JSON 配置写回 SharedPreferences，返回写入的键数量；JSON 非法时抛异常 */
    public static int applyConfigJson(Context context, String jsonStr) throws Exception {
        JSONObject json = new JSONObject(jsonStr);
        SharedPreferences.Editor editor =
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit();
        int count = 0;
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object v = json.get(key);
            if (v instanceof String) {
                editor.putString(key, (String) v);
            } else if (v instanceof Boolean) {
                editor.putBoolean(key, (Boolean) v);
            } else if (v instanceof Integer) {
                editor.putInt(key, (Integer) v);
            } else if (v instanceof Long) {
                editor.putLong(key, (Long) v);
            } else if (v instanceof Double) {
                editor.putFloat(key, (float) (double) (Double) v);
            } else if (v instanceof JSONArray) {
                JSONArray arr = (JSONArray) v;
                Set<String> set = new HashSet<>();
                for (int i = 0; i < arr.length(); i++) {
                    set.add(arr.optString(i));
                }
                editor.putStringSet(key, set);
            } else {
                continue;
            }
            count++;
        }
        editor.apply();
        return count;
    }

    // ===== 二维码分帧协议 =====

    /** 将载荷编码为若干帧，发送端按序轮播；单帧装得下时只有一帧（静态码） */
    public static String[] encodeFrames(String payload) {
        byte[] data = payload.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32();
        crc.update(data);
        String session = String.format(Locale.US, "%04X", new Random().nextInt(0x10000));
        int total = Math.max(1, (data.length + CHUNK_SIZE - 1) / CHUNK_SIZE);
        String[] frames = new String[total];
        for (int i = 0; i < total; i++) {
            int from = i * CHUNK_SIZE;
            int to = Math.min(from + CHUNK_SIZE, data.length);
            String chunk = Base64.encodeToString(
                    Arrays.copyOfRange(data, from, to),
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            frames[i] = FRAME_MAGIC + "|" + session + "|" + i + "|" + total + "|"
                    + Long.toHexString(crc.getValue()) + "|" + chunk;
        }
        return frames;
    }

    /** 解析一帧二维码文本；不是本协议的帧返回 null */
    public static Frame parseFrame(String text) {
        if (text == null || !text.startsWith(FRAME_MAGIC + "|")) return null;
        String[] parts = text.split("\\|", -1);
        if (parts.length != 6) return null;
        try {
            String session = parts[1];
            int idx = Integer.parseInt(parts[2]);
            int total = Integer.parseInt(parts[3]);
            long crc = Long.parseLong(parts[4], 16);
            byte[] chunk = Base64.decode(parts[5],
                    Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            if (session.isEmpty() || idx < 0 || total <= 0 || idx >= total) return null;
            return new Frame(session, idx, total, crc, chunk);
        } catch (Exception e) {
            return null;
        }
    }

    /** 一帧二维码携带的数据 */
    public static final class Frame {
        public final String session;
        public final int idx;
        public final int total;
        public final long crc;
        public final byte[] chunk;

        Frame(String session, int idx, int total, long crc, byte[] chunk) {
            this.session = session;
            this.idx = idx;
            this.total = total;
            this.crc = crc;
            this.chunk = chunk;
        }
    }

    /**
     * 接收端拼包器：同一 session 的帧去重收集，集齐后 assemble() 校验并还原载荷。
     * 非线程安全，仅在解码线程内使用。
     */
    public static final class Reassembler {
        private final String session;
        private final int total;
        private final long crc;
        private final byte[][] parts;
        private int received = 0;

        public Reassembler(Frame first) {
            session = first.session;
            total = first.total;
            crc = first.crc;
            parts = new byte[total][];
        }

        /** 该帧是否属于本次传输（session/total/crc 一致），否则视为新一轮传送 */
        public boolean belongsTo(Frame frame) {
            return session.equals(frame.session) && total == frame.total && crc == frame.crc;
        }

        /** 接收一帧（按 idx 自动去重） */
        public void offer(Frame frame) {
            if (parts[frame.idx] == null) {
                parts[frame.idx] = frame.chunk;
                received++;
            }
        }

        public int getReceived() {
            return received;
        }

        public int getTotal() {
            return total;
        }

        public boolean isComplete() {
            return received == total;
        }

        /** 拼接全部分帧并校验 CRC32，失败抛 IllegalStateException */
        public String assemble() {
            int len = 0;
            for (byte[] p : parts) {
                if (p == null) throw new IllegalStateException("缺少分帧");
                len += p.length;
            }
            byte[] data = new byte[len];
            int off = 0;
            for (byte[] p : parts) {
                System.arraycopy(p, 0, data, off, p.length);
                off += p.length;
            }
            CRC32 actual = new CRC32();
            actual.update(data);
            if (actual.getValue() != crc) {
                throw new IllegalStateException("数据校验失败");
            }
            return new String(data, StandardCharsets.UTF_8);
        }
    }
}
