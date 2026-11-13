package com.redismini.protocol;

import com.redismini.protocol.RespValue.BulkString;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Bộ giải mã lệnh RESP không chặn (non-blocking) làm việc trực tiếp trên {@link ByteBuffer}.
 * <p>
 * Dùng cho {@code NioServer}: nếu buffer chưa đủ byte cho một lệnh hoàn chỉnh (gói tin bị cắt đôi),
 * {@link #tryReadCommand(ByteBuffer)} khôi phục {@code position} về đầu lệnh và trả về {@code null}
 * để chờ dữ liệu tiếp theo từ {@code Selector}.
 */
public final class NonBlockingRespDecoder {

    private NonBlockingRespDecoder() {
    }

    /**
     * Cố gắng đọc một lệnh hoàn chỉnh từ vị trí hiện tại của {@code buffer}.
     *
     * @param buffer buffer đang ở chế độ đọc ({@code position} → {@code limit})
     * @return danh sách phần tử của lệnh (luôn có ít nhất 1 phần tử), hoặc {@code null} nếu chưa đủ byte
     * @throws RespProtocolException nếu dữ liệu vi phạm giao thức RESP
     */
    public static List<BulkString> tryReadCommand(ByteBuffer buffer) throws RespProtocolException {
        while (buffer.hasRemaining()) {
            int startPos = buffer.position();
            byte first = buffer.get();
            List<BulkString> args;
            if (first == '*') {
                args = tryReadMultiBulk(buffer);
            } else {
                buffer.position(startPos);
                args = tryReadInline(buffer);
            }

            if (args == null) {
                buffer.position(startPos);
                return null;
            }
            if (!args.isEmpty()) {
                return args;
            }
            // Dòng trống hoặc mảng rỗng (*0, *-1) bị bỏ qua, đọc tiếp lệnh sau
        }
        return null;
    }

    private static List<BulkString> tryReadMultiBulk(ByteBuffer buffer) throws RespProtocolException {
        String lengthLine = readLine(buffer);
        if (lengthLine == null) {
            return null;
        }
        long length = parseLong(lengthLine);
        if (length <= 0) {
            return List.of();
        }
        if (length > RespParser.MAX_ARRAY_LENGTH) {
            throw new RespProtocolException("Invalid multibulk length: " + length);
        }

        List<BulkString> args = new ArrayList<>(Math.min((int) length, 16));
        for (int i = 0; i < length; i++) {
            if (!buffer.hasRemaining()) {
                return null;
            }
            byte type = buffer.get();
            if (type != '$') {
                throw new RespProtocolException("Expected '$' but got " + describe(type & 0xFF));
            }
            String bulkLenLine = readLine(buffer);
            if (bulkLenLine == null) {
                return null;
            }
            long bulkLen = parseLong(bulkLenLine);
            if (bulkLen == -1) {
                throw new RespProtocolException("Null bulk string is not allowed in a command");
            }
            if (bulkLen < 0 || bulkLen > RespParser.MAX_BULK_LENGTH) {
                throw new RespProtocolException("Invalid bulk length: " + bulkLen);
            }
            if (buffer.remaining() < bulkLen + 2) {
                return null;
            }

            byte[] data = new byte[(int) bulkLen];
            buffer.get(data);
            expectByte(buffer, '\r');
            expectByte(buffer, '\n');
            args.add(new BulkString(data));
        }
        return args;
    }

    private static List<BulkString> tryReadInline(ByteBuffer buffer) throws RespProtocolException {
        String line = readInlineLine(buffer);
        if (line == null) {
            return null;
        }
        String text = line.strip();
        if (text.isEmpty()) {
            return List.of();
        }
        List<BulkString> args = new ArrayList<>();
        for (String word : text.split("\\s+")) {
            args.add(BulkString.of(word));
        }
        return args;
    }

    /** Đọc đến \r\n, trả về null nếu chưa gặp đủ \r\n. */
    private static String readLine(ByteBuffer buffer) throws RespProtocolException {
        int lineStart = buffer.position();
        int count = 0;
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            if (b == '\r') {
                if (!buffer.hasRemaining()) {
                    return null;
                }
                byte next = buffer.get();
                if (next != '\n') {
                    throw new RespProtocolException("Expected \\n but got " + describe(next & 0xFF));
                }
                byte[] bytes = new byte[count];
                buffer.get(lineStart, bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            }
            count++;
            if (count >= RespParser.MAX_LINE_LENGTH) {
                throw new RespProtocolException("Line too long");
            }
        }
        return null;
    }

    private static String readInlineLine(ByteBuffer buffer) throws RespProtocolException {
        int lineStart = buffer.position();
        int count = 0;
        while (buffer.hasRemaining()) {
            byte b = buffer.get();
            if (b == '\n') {
                byte[] bytes = new byte[count];
                buffer.get(lineStart, bytes);
                return new String(bytes, StandardCharsets.UTF_8);
            }
            count++;
            if (count >= RespParser.MAX_LINE_LENGTH) {
                throw new RespProtocolException("Inline command too long");
            }
        }
        return null;
    }

    private static void expectByte(ByteBuffer buffer, char expected) throws RespProtocolException {
        byte actual = buffer.get();
        if (actual != expected) {
            throw new RespProtocolException(
                    "Expected " + describe(expected) + " but got " + describe(actual & 0xFF));
        }
    }

    private static long parseLong(String text) throws RespProtocolException {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException e) {
            throw new RespProtocolException("Invalid integer: '" + text + "'");
        }
    }

    private static String describe(int b) {
        return switch (b) {
            case '\r' -> "\\r";
            case '\n' -> "\\n";
            default -> (b >= 32 && b < 127) ? "'" + (char) b + "'" : "byte " + b;
        };
    }
}

