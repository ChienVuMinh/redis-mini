package com.redismini.persistence;

import com.redismini.command.CommandRegistry;
import com.redismini.protocol.RespParser;
import com.redismini.protocol.RespProtocolException;
import com.redismini.protocol.RespValue.BulkString;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * Đọc file AOF khi server khởi động và chạy lại các lệnh để khôi phục trạng thái RAM.
 * Nếu lệnh cuối bị ghi dở (do server crash / kill -9 giữa chừng), phần hỏng ở cuối file
 * sẽ bị bỏ qua và cắt gọn (truncate) để các lần ghi tiếp theo không bị lỗi.
 */
public final class AofLoader {

    private AofLoader() {
    }

    /**
     * Đọc và chạy lại toàn bộ lệnh hợp lệ từ file AOF.
     *
     * @param path     đường dẫn file AOF
     * @param registry bảng lệnh dùng để replay (không ghi ngược lại vào AOF)
     * @return số lượng lệnh đã khôi phục thành công
     */
    public static int load(Path path, CommandRegistry registry) throws IOException {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(registry, "registry");

        if (!Files.exists(path)) {
            return 0;
        }

        int replayed = 0;
        long lastValidOffset = 0;
        boolean truncated = false;

        try (CountingInputStream in = new CountingInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            RespParser parser = new RespParser(in);
            while (true) {
                lastValidOffset = in.getBytesRead();
                List<BulkString> command;
                try {
                    command = parser.readCommand();
                } catch (EOFException | RespProtocolException e) {
                    System.err.println("Incomplete or corrupted AOF tail at byte " + lastValidOffset
                            + ", truncating: " + e.getMessage());
                    truncated = true;
                    break;
                }
                if (command == null) {
                    break;
                }
                registry.replay(command);
                replayed++;
            }
        }

        if (truncated) {
            try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
                channel.truncate(lastValidOffset);
            }
        }

        return replayed;
    }

    /** Đếm chính xác số byte đã được parser tiêu thụ. */
    private static final class CountingInputStream extends FilterInputStream {

        private long bytesRead;

        CountingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                bytesRead++;
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int count = super.read(b, off, len);
            if (count > 0) {
                bytesRead += count;
            }
            return count;
        }

        long getBytesRead() {
            return bytesRead;
        }
    }
}

