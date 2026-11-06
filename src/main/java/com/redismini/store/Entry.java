package com.redismini.store;

/**
 * Giá trị của một key cùng thời điểm hết hạn (nếu có).
 * Bất biến: muốn đổi giá trị hoặc TTL thì thay cả Entry, không sửa tại chỗ.
 *
 * @param value    dãy byte dữ liệu của key
 * @param expireAt thời điểm hết hạn tính theo epoch milliseconds (null nếu key không có TTL)
 */
public record Entry(byte[] value, Long expireAt) {

    public Entry(byte[] value) {
        this(value, null);
    }

    /** Kiểm tra xem entry đã hết hạn tại thời điểm {@code nowMillis} hay chưa. */
    public boolean isExpired(long nowMillis) {
        return expireAt != null && nowMillis >= expireAt;
    }

    /** Key có thời hạn hết hạn hay không. */
    public boolean hasTtl() {
        return expireAt != null;
    }
}

