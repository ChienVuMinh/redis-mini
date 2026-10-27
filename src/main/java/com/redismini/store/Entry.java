package com.redismini.store;

/**
 * Giá trị của một key. Hiện chỉ có kiểu String của Redis (một dãy byte bất kỳ).
 * Giai đoạn 4 sẽ thêm thời điểm hết hạn vào đây, nên không lưu thẳng byte[] trong map.
 * Bất biến: muốn đổi giá trị thì thay cả Entry, không sửa tại chỗ.
 */
public record Entry(byte[] value) {
}
