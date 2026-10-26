package com.redismini.protocol;

import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespArray;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Đọc RespValue từ InputStream, mỗi lần gọi read() lấy đúng một giá trị.
 * Server dùng readCommand() (chấp nhận cả inline command), còn read() đọc RESP thuần
 * (ví dụ client đọc câu trả lời của server).
 * Đọc từng byte, nên với socket hãy truyền vào BufferedInputStream để tránh gọi xuống OS cho mỗi byte.
 */
public class RespParser {

    // Dữ liệu từ client không đáng tin: giới hạn kích thước để một lệnh xấu không làm cạn RAM.
    // Giá trị giống mặc định của Redis thật.
    static final int MAX_LINE_LENGTH = 64 * 1024;
    static final int MAX_BULK_LENGTH = 512 * 1024 * 1024;
    static final int MAX_ARRAY_LENGTH = 1024 * 1024;

    private final InputStream in;

    public RespParser(InputStream in) {
        this.in = in;
    }

    /**
     * Đọc một giá trị. Chặn cho đến khi đủ byte (kể cả khi giá trị tới thành nhiều mẩu).
     *
     * @return null nếu stream kết thúc đúng lúc chưa bắt đầu giá trị mới (client đóng kết nối bình thường)
     * @throws EOFException          stream kết thúc giữa chừng một giá trị
     * @throws RespProtocolException dữ liệu sai định dạng
     */
    public RespValue read() throws IOException {
        int type = in.read();
        if (type == -1) {
            return null;
        }
        return readValue(type);
    }

    /**
     * Đọc một lệnh từ client, cách Redis thật làm:
     * <ul>
     *   <li>byte đầu là {@code *}: lệnh RESP, mảng các Bulk String (redis-cli, Jedis gửi dạng này)</li>
     *   <li>byte khác: inline command, một dòng chữ tách bằng khoảng trắng (gõ tay qua telnet/nc)</li>
     * </ul>
     * Dòng trống và mảng rỗng ({@code *0}, {@code *-1}) bị bỏ qua, đọc tiếp lệnh sau.
     *
     * @return các tham số của lệnh, phần tử đầu là tên lệnh, luôn có ít nhất 1 phần tử;
     *         null nếu client đóng kết nối giữa hai lệnh
     */
    public List<BulkString> readCommand() throws IOException {
        while (true) {
            int first = in.read();
            if (first == -1) {
                return null;
            }
            List<BulkString> args = first == '*' ? readMultiBulk() : readInline(first);
            if (!args.isEmpty()) {
                return args;
            }
        }
    }

    /** Phần sau {@code *}: số phần tử, rồi đúng chừng đó Bulk String. Kiểu khác ở trong là lỗi. */
    private List<BulkString> readMultiBulk() throws IOException {
        long length = parseLong(readLine());
        if (length <= 0) {
            return List.of();
        }
        if (length > MAX_ARRAY_LENGTH) {
            throw new RespProtocolException("Invalid multibulk length: " + length);
        }
        List<BulkString> args = new ArrayList<>((int) length);
        for (int i = 0; i < length; i++) {
            expectByte('$');
            BulkString arg = readBulk();
            if (arg.isNull()) {
                throw new RespProtocolException("Null bulk string is not allowed in a command");
            }
            args.add(arg);
        }
        return args;
    }

    /**
     * Inline command: cả dòng (gồm byte đầu đã đọc) đến \n, bỏ \r cuối nếu có (telnet gửi \r\n,
     * nc có thể chỉ gửi \n), rồi tách theo khoảng trắng.
     * Không xử lý dấu nháy như Redis thật, nên không gửi được tham số chứa khoảng trắng.
     */
    private List<BulkString> readInline(int first) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        int b = first;
        while (b != '\n') {
            if (line.size() >= MAX_LINE_LENGTH) {
                throw new RespProtocolException("Inline command too long");
            }
            line.write(b);
            b = in.read();
            if (b == -1) {
                throw new EOFException("Stream ended inside inline command");
            }
        }
        String text = line.toString(StandardCharsets.UTF_8).strip();
        if (text.isEmpty()) {
            return List.of();
        }
        List<BulkString> args = new ArrayList<>();
        for (String word : text.split("\\s+")) {
            args.add(BulkString.of(word));
        }
        return args;
    }

    private RespValue readValue(int type) throws IOException {
        return switch (type) {
            case '+' -> new SimpleString(readLine());
            case '-' -> new SimpleError(readLine());
            case ':' -> new RespInteger(parseLong(readLine()));
            case '$' -> readBulk();
            case '*' -> readArray();
            default -> throw new RespProtocolException("Unknown type byte: " + describe(type));
        };
    }

    private BulkString readBulk() throws IOException {
        long length = parseLong(readLine());
        if (length == -1) {
            return BulkString.NULL;
        }
        if (length < 0 || length > MAX_BULK_LENGTH) {
            throw new RespProtocolException("Invalid bulk length: " + length);
        }
        // readNBytes đọc lặp cho đến khi đủ length byte, dù chúng tới thành nhiều mẩu
        byte[] data = in.readNBytes((int) length);
        if (data.length < length) {
            throw new EOFException("Stream ended inside bulk string");
        }
        expectCrlf();
        return new BulkString(data);
    }

    private RespArray readArray() throws IOException {
        long length = parseLong(readLine());
        if (length < 0 || length > MAX_ARRAY_LENGTH) {
            throw new RespProtocolException("Invalid array length: " + length);
        }
        List<RespValue> elements = new ArrayList<>((int) length);
        for (int i = 0; i < length; i++) {
            int type = in.read();
            if (type == -1) {
                throw new EOFException("Stream ended inside array");
            }
            // Đệ quy: phần tử có thể là bất kỳ kiểu nào, kể cả một mảng khác
            elements.add(readValue(type));
        }
        return new RespArray(elements);
    }

    /** Đọc đến \r\n, trả về phần trước nó (không gồm \r\n). */
    private String readLine() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        while (true) {
            int b = in.read();
            if (b == -1) {
                throw new EOFException("Stream ended before CRLF");
            }
            if (b == '\r') {
                expectByte('\n');
                return line.toString(StandardCharsets.UTF_8);
            }
            if (line.size() >= MAX_LINE_LENGTH) {
                throw new RespProtocolException("Line too long");
            }
            line.write(b);
        }
    }

    private void expectCrlf() throws IOException {
        expectByte('\r');
        expectByte('\n');
    }

    private void expectByte(char expected) throws IOException {
        int b = in.read();
        if (b == -1) {
            throw new EOFException("Stream ended, expected " + describe(expected));
        }
        if (b != expected) {
            throw new RespProtocolException("Expected " + describe(expected) + " but got " + describe(b));
        }
    }

    private static long parseLong(String text) throws RespProtocolException {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new RespProtocolException("Invalid integer: '" + text + "'");
        }
    }

    /** In byte cho dễ đọc trong thông báo lỗi: 'A', \r, \n hoặc mã số. */
    private static String describe(int b) {
        return switch (b) {
            case '\r' -> "\\r";
            case '\n' -> "\\n";
            default -> (b >= 32 && b < 127) ? "'" + (char) b + "'" : "byte " + b;
        };
    }
}
