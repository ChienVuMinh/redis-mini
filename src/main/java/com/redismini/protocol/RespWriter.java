package com.redismini.protocol;

import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespArray;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Ghi RespValue ra OutputStream theo đúng định dạng RESP.
 * Không tự flush: gọi flush() sau khi ghi xong một câu trả lời.
 */
public class RespWriter {

    private static final byte[] CRLF = {'\r', '\n'};

    private final OutputStream out;

    public RespWriter(OutputStream out) {
        this.out = out;
    }

    /** Tiện cho test: chuyển RespValue thành mảng byte. */
    public static byte[] encode(RespValue value) {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            new RespWriter(buffer).write(value);
        } catch (IOException e) {
            // ByteArrayOutputStream ghi vào RAM, không bao giờ ném IOException
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    public void write(RespValue value) throws IOException {
        // sealed interface: compiler biết chỉ có 5 kiểu, thiếu case nào là báo lỗi biên dịch
        switch (value) {
            case SimpleString s -> writeSimple('+', s.value());
            case SimpleError e -> writeSimple('-', e.message());
            case RespInteger i -> writeLine(':', Long.toString(i.value()));
            case BulkString b -> writeBulk(b);
            case RespArray a -> {
                writeLine('*', Integer.toString(a.elements().size()));
                for (RespValue element : a.elements()) {
                    // Đệ quy: phần tử có thể là bất kỳ kiểu nào, kể cả một mảng khác
                    write(element);
                }
            }
        }
    }

    public void flush() throws IOException {
        out.flush();
    }

    /** Simple String / Simple Error kết thúc bằng \r\n, nên nội dung không được chứa \r hoặc \n. */
    private void writeSimple(char prefix, String text) throws IOException {
        if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("Simple string must not contain CR or LF: " + text);
        }
        writeLine(prefix, text);
    }

    private void writeBulk(BulkString bulk) throws IOException {
        if (bulk.isNull()) {
            writeLine('$', "-1");
            return;
        }
        byte[] data = bulk.data();
        // Độ dài là số byte, không phải số ký tự
        writeLine('$', Integer.toString(data.length));
        out.write(data);
        out.write(CRLF);
    }

    /** Ghi: ký tự đầu + nội dung + \r\n */
    private void writeLine(char prefix, String text) throws IOException {
        out.write(prefix);
        out.write(text.getBytes(StandardCharsets.UTF_8));
        out.write(CRLF);
    }
}
