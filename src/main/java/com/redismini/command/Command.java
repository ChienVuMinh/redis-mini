package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;

import java.util.List;

/**
 * Một lệnh Redis (pattern Command): mỗi lệnh là một class, CommandRegistry tra tên để chọn class.
 * Thêm lệnh mới = viết class mới rồi đăng ký, không phải sửa code ở chỗ khác.
 */
public interface Command {

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
}
