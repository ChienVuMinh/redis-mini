package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Một lệnh Redis (pattern Command): mỗi lệnh là một class, CommandRegistry tra tên để chọn class.
 * Thêm lệnh mới = viết class mới rồi đăng ký, không phải sửa code ở chỗ khác.
 */
public interface Command {

    /**
     * Dạng số Redis chấp nhận: "0" hoặc số không có số 0 ở đầu, có thể có dấu trừ.
     * Không chấp nhận "+5", "007", "-0", " 5" (Long.parseLong lại chấp nhận "+5" và "007").
     */
    Pattern INTEGER_PATTERN = Pattern.compile("0|-?[1-9][0-9]*");

    /** Tên lệnh viết hoa, ví dụ "PING". Client gửi hoa hay thường đều được. */
    String name();

    /**
     * Chạy lệnh. Được gọi đồng thời từ nhiều thread client, nên class lệnh không được giữ
     * trạng thái riêng của một lần gọi trong field.
     *
     * @param args các tham số sau tên lệnh: {@code SET k v} cho ra {@code [k, v]}
     * @return câu trả lời gửi cho client, lỗi cũng là một câu trả lời (SimpleError)
     */
    RespValue execute(List<BulkString> args);

    /** Lỗi sai số tham số, cùng định dạng với Redis thật. */
    static SimpleError wrongNumberOfArguments(String name) {
        return new SimpleError("ERR wrong number of arguments for '" + name.toLowerCase() + "' command");
    }

    /**
     * Parse chuỗi thành số nguyên 64-bit theo chuẩn Redis.
     *
     * @throws NumberFormatException nếu chuỗi không hợp lệ hoặc vượt khoảng của long
     */
    static long parseInteger(String text) {
        if (text == null || !INTEGER_PATTERN.matcher(text).matches()) {
            throw new NumberFormatException(text);
        }
        return Long.parseLong(text);
    }
}
