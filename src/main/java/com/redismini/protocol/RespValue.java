package com.redismini.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Một giá trị RESP. "sealed" giới hạn đúng 5 kiểu dưới đây, nên switch trên RespValue
 * được compiler kiểm tra đã xử lý đủ mọi trường hợp.
 */
public sealed interface RespValue {

    /** {@code +OK\r\n}: chuỗi ngắn, không được chứa \r hoặc \n. */
    record SimpleString(String value) implements RespValue {
    }

    /** {@code -ERR message\r\n}: báo lỗi cho client. */
    record SimpleError(String message) implements RespValue {
    }

    /** {@code :42\r\n}: số nguyên có dấu 64 bit. */
    record RespInteger(long value) implements RespValue {
    }

    /**
     * {@code $5\r\nhello\r\n}: chuỗi có độ dài báo trước, chứa được mọi byte (kể cả \r\n).
     * Lưu byte[] chứ không lưu String vì độ dài tính theo byte, và dữ liệu có thể không phải chữ.
     * data == null là Null Bulk String {@code $-1\r\n}, Redis dùng nó cho (nil), ví dụ GET key không tồn tại.
     */
    record BulkString(byte[] data) implements RespValue {

        public static final BulkString NULL = new BulkString(null);

        public static BulkString of(String text) {
            return new BulkString(text.getBytes(StandardCharsets.UTF_8));
        }

        public boolean isNull() {
            return data == null;
        }

        public String asString() {
            return data == null ? null : new String(data, StandardCharsets.UTF_8);
        }

        // Record so sánh mảng bằng địa chỉ bộ nhớ, không so nội dung. Phải tự viết lại
        // để hai BulkString cùng nội dung được coi là bằng nhau (cần cho test).
        @Override
        public boolean equals(Object o) {
            return o instanceof BulkString other && Arrays.equals(data, other.data);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(data);
        }

        @Override
        public String toString() {
            return data == null ? "BulkString[null]" : "BulkString[" + asString() + "]";
        }
    }

    /** {@code *2\r\n...}: mảng các RespValue. Lệnh từ client luôn là mảng các BulkString. */
    record RespArray(List<RespValue> elements) implements RespValue {

        public RespArray {
            // Sao chép thành list bất biến: bên ngoài không sửa được nội dung sau khi tạo
            elements = List.copyOf(elements);
        }

        public static RespArray of(RespValue... elements) {
            return new RespArray(List.of(elements));
        }
    }
}
