package org.haaasaki.financeagent.common;

/**
 * 展示层格式化工具。
 *
 * <p>只用于视图模型拼装展示字符串，不改变任何存储值（见 docs/FRONTEND_GUIDE.md 第 3 节）。
 */
public final class DisplayFormat {

    private DisplayFormat() {
    }

    /**
     * ISO 本地时间戳截断到秒：{@code 2026-09-13T01:31:59.667016} → {@code 2026-09-13 01:31:59}。
     *
     * @param timestamp ISO 本地时间字符串，可为 null
     */
    public static String timestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return null;
        }
        String value = timestamp.length() >= 19 ? timestamp.substring(0, 19) : timestamp;
        return value.replace('T', ' ');
    }
}
