package com.redismini.store;

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

    private final ConcurrentHashMap<String, Entry> data = new ConcurrentHashMap<>();

    /** @return giá trị của key, hoặc null nếu key không tồn tại */
    public byte[] get(String key) {
        Entry entry = data.get(key);
        return entry == null ? null : entry.value();
    }

    /** Ghi đè nếu key đã có, giống SET của Redis. */
    public void set(String key, byte[] value) {
        data.put(key, new Entry(value));
    }

    /** @return true nếu key tồn tại và đã bị xoá */
    public boolean delete(String key) {
        return data.remove(key) != null;
    }

    public boolean exists(String key) {
        return data.containsKey(key);
    }

    /** Số key hiện có, giống DBSIZE. */
    public int size() {
        return data.size();
    }

    /**
     * Đọc giá trị cũ, tính giá trị mới và ghi lại như MỘT thao tác: trong lúc function chạy,
     * thread khác không đọc-ghi xen vào giữa key này được. Dùng cho lệnh kiểu INCR.
     * <p>
     * Trong lúc function chạy, key đang bị khoá: function phải ngắn và không được gọi lại DataStore.
     *
     * @param function nhận giá trị hiện tại (null nếu chưa có key), trả giá trị mới (null để xoá key).
     *                 Ném exception thì không có gì bị thay đổi, exception bay ra cho người gọi.
     * @return giá trị mới
     */
    public byte[] update(String key, UnaryOperator<byte[]> function) {
        Entry updated = data.compute(key, (k, old) -> {
            byte[] value = function.apply(old == null ? null : old.value());
            return value == null ? null : new Entry(value);
        });
        return updated == null ? null : updated.value();
    }

    
}
