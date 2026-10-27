package com.redismini.store;

import java.util.concurrent.ConcurrentHashMap;

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

    
}
