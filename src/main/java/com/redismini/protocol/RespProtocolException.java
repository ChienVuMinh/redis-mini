package com.redismini.protocol;

import java.io.IOException;

/**
 * Client gửi dữ liệu sai định dạng RESP. Sau lỗi này không còn biết lệnh tiếp theo bắt đầu
 * ở byte nào, nên server phải báo lỗi rồi đóng kết nối (Redis thật cũng làm vậy).
 */
public class RespProtocolException extends IOException {

    public RespProtocolException(String message) {
        super(message);
    }
}
