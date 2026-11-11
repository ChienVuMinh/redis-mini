package com.redismini.persistence;

import java.util.Locale;

/**
 * Chế độ đồng bộ dữ liệu AOF xuống đĩa cứng (fsync).
 * <ul>
 *   <li>{@link #ALWAYS}: fsync ngay sau mỗi lệnh ghi (an toàn nhất, chậm nhất).</li>
 *   <li>{@link #EVERYSEC}: fsync định kỳ mỗi giây (cân bằng tốc độ và an toàn, tối đa mất 1 giây dữ liệu).</li>
 *   <li>{@link #NO}: không chủ động fsync, để hệ điều hành tự quyết định (nhanh nhất, dễ mất dữ liệu khi mất điện).</li>
 * </ul>
 */
public enum FsyncPolicy {
    ALWAYS,
    EVERYSEC,
    NO;

    public static FsyncPolicy fromString(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Fsync policy must not be null");
        }
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "always" -> ALWAYS;
            case "everysec" -> EVERYSEC;
            case "no" -> NO;
            default -> throw new IllegalArgumentException("Unknown fsync policy: " + value);
        };
    }
}

