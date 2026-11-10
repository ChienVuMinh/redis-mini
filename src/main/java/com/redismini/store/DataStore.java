package com.redismini.store;

import java.time.Clock;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Nơi chứa toàn bộ dữ liệu: key -> Entry. Một DataStore dùng chung cho mọi client.
 * <p>
 * Mỗi client chạy trên một thread riêng, nên nhiều thread có thể đọc/ghi map cùng lúc.
 * ConcurrentHashMap đảm bảo từng thao tác (get, put, remove...) an toàn khi chạy song song.
 * Nhưng ghép hai thao tác ("đọc rồi ghi") thì không còn an toàn, xem bước INCR.
 * <p>
 * Key là String (giải mã UTF-8) cho đơn giản. Redis thật cho key là byte bất kỳ;
 * byte[] không làm key của map được vì equals/hashCode của mảng so địa chỉ, không so nội dung.
 */
public class DataStore {

    private final Clock clock;
    private final ConcurrentHashMap<String, Entry> data = new ConcurrentHashMap<>();
    private final Set<String> expires = ConcurrentHashMap.newKeySet();

    public DataStore() {
        this(Clock.systemUTC());
    }

    public DataStore(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Clock getClock() {
        return clock;
    }

    /**
     * @return giá trị của key, hoặc null nếu key không tồn tại hoặc đã hết hạn (Lazy eviction)
     */
    public byte[] get(String key) {
        Entry entry = data.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.isExpired(clock.millis())) {
            // Lazy eviction: chỉ xoá nếu mapping trong map vẫn là entry này
            if (data.remove(key, entry)) {
                expires.remove(key);
            }
            return null;
        }
        return entry.value();
    }

    /** Ghi đè nếu key đã có, giống SET của Redis (xoá bỏ TTL cũ nếu có). */
    public void set(String key, byte[] value) {
        set(key, value, null);
    }

    /** Ghi đè key kèm theo thời điểm hết hạn tính theo epoch millis. */
    public void set(String key, byte[] value, Long expireAt) {
        data.put(key, new Entry(value, expireAt));
        if (expireAt != null) {
            expires.add(key);
        } else {
            expires.remove(key);
        }
    }

    /** @return true nếu key tồn tại, chưa hết hạn và đã bị xoá */
    public boolean delete(String key) {
        long now = clock.millis();
        Entry removed = data.remove(key);
        if (removed != null) {
            expires.remove(key);
            return !removed.isExpired(now);
        }
        return false;
    }

    /** Kiểm tra key có tồn tại và chưa hết hạn không (xoá nếu đã hết hạn). */
    public boolean exists(String key) {
        Entry entry = data.get(key);
        if (entry == null) {
            return false;
        }
        if (entry.isExpired(clock.millis())) {
            if (data.remove(key, entry)) {
                expires.remove(key);
            }
            return false;
        }
        return true;
    }

    /** Số key hiện có, giống DBSIZE. */
    public int size() {
        return data.size();
    }

    /**
     * Đọc giá trị cũ, tính giá trị mới và ghi lại như MỘT thao tác: trong lúc function chạy,
     * thread khác không đọc-ghi xen vào giữa key này được. Dùng cho lệnh kiểu INCR.
     * <p>
     * Nếu key cũ đã hết hạn, function sẽ nhận {@code null} và ghi giá trị mới không có TTL.
     *
     * @param function nhận giá trị hiện tại (null nếu chưa có key hoặc đã hết hạn), trả giá trị mới (null để xoá key).
     *                 Ném exception thì không có gì bị thay đổi, exception bay ra cho người gọi.
     * @return giá trị mới
     */
    public byte[] update(String key, UnaryOperator<byte[]> function) {
        long now = clock.millis();
        Entry updated = data.compute(key, (k, old) -> {
            byte[] oldValue = (old == null || old.isExpired(now)) ? null : old.value();
            byte[] value = function.apply(oldValue);
            return value == null ? null : new Entry(value);
        });
        expires.remove(key);
        return updated == null ? null : updated.value();
    }

    /**
     * Đặt thời điểm hết hạn cho key (dùng cho lệnh EXPIRE / PEXPIRE).
     *
     * @return true nếu đặt thành công, false nếu key không tồn tại hoặc đã hết hạn
     */
    public boolean setExpire(String key, long expireAtMillis) {
        long now = clock.millis();
        boolean[] updated = new boolean[1];
        data.compute(key, (k, old) -> {
            if (old == null || old.isExpired(now)) {
                return null; // xoá key nếu đã hết hạn
            }
            updated[0] = true;
            return new Entry(old.value(), expireAtMillis);
        });
        if (updated[0]) {
            expires.add(key);
            return true;
        } else {
            expires.remove(key);
            return false;
        }
    }

    /**
     * Lấy TTL còn lại của key tính bằng giây (dùng cho lệnh TTL).
     *
     * @return số giây còn lại (>= 1), -1 nếu key không có TTL, -2 nếu key không tồn tại hoặc đã hết hạn
     */
    public long getTtlSeconds(String key) {
        Entry entry = data.get(key);
        if (entry == null) {
            return -2;
        }
        long now = clock.millis();
        if (entry.isExpired(now)) {
            if (data.remove(key, entry)) {
                expires.remove(key);
            }
            return -2;
        }
        if (entry.expireAt() == null) {
            return -1;
        }
        long remainingMillis = entry.expireAt() - now;
        long remainingSeconds = (remainingMillis + 999) / 1000;
        return Math.max(1, remainingSeconds);
    }

    /**
     * Xoá thời điểm hết hạn, biến key thành tồn tại vĩnh viễn (dùng cho lệnh PERSIST).
     *
     * @return true nếu xoá TTL thành công, false nếu key không có TTL hoặc không tồn tại
     */
    public boolean persist(String key) {
        long now = clock.millis();
        boolean[] persisted = new boolean[1];
        data.compute(key, (k, old) -> {
            if (old == null || old.isExpired(now)) {
                return null;
            }
            if (old.expireAt() != null) {
                persisted[0] = true;
                return new Entry(old.value(), null);
            }
            return old;
        });
        if (persisted[0]) {
            expires.remove(key);
            return true;
        } else {
            if (!data.containsKey(key)) {
                expires.remove(key);
            }
            return false;
        }
    }

    /** Trả về danh sách tất cả các key hiện có. */
    public Set<String> keySet() {
        return data.keySet();
    }

    /** Trả về danh sách tất cả các key có cấu hình TTL. Giống db->expires trong Redis. */
    public Set<String> expiresKeySet() {
        return Collections.unmodifiableSet(expires);
    }

    /**
     * Kiểm tra và xoá key nếu đã hết hạn tại thời điểm hiện tại.
     * Dùng cho Active ExpiryManager.
     *
     * @return true nếu key đã hết hạn và bị xoá
     */
    public boolean evictIfExpired(String key) {
        Entry entry = data.get(key);
        if (entry != null && entry.isExpired(clock.millis())) {
            if (data.remove(key, entry)) {
                expires.remove(key);
                return true;
            }
        }
        return false;
    }
}

