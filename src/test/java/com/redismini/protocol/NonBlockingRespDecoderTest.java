package com.redismini.protocol;

import com.redismini.protocol.RespValue.BulkString;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NonBlockingRespDecoderTest {

    private static ByteBuffer bufferOf(String s) {
        return ByteBuffer.wrap(s.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void decodesCompleteMultiBulkCommand() throws RespProtocolException {
        ByteBuffer buf = bufferOf("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$3\r\nval\r\n");

        List<BulkString> cmd = NonBlockingRespDecoder.tryReadCommand(buf);

        assertThat(cmd).containsExactly(BulkString.of("SET"), BulkString.of("k"), BulkString.of("val"));
        assertThat(buf.hasRemaining()).isFalse();
    }

    @Test
    void decodesPipelinedCommandsSequentially() throws RespProtocolException {
        ByteBuffer buf = bufferOf("*1\r\n$4\r\nPING\r\n*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n");

        assertThat(NonBlockingRespDecoder.tryReadCommand(buf))
                .containsExactly(BulkString.of("PING"));
        assertThat(NonBlockingRespDecoder.tryReadCommand(buf))
                .containsExactly(BulkString.of("ECHO"), BulkString.of("hi"));
        assertThat(NonBlockingRespDecoder.tryReadCommand(buf)).isNull();
    }

    @Test
    void returnsNullAndRestoresPositionAtAnyPartialBoundary() throws RespProtocolException {
        byte[] full = "*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n".getBytes(StandardCharsets.UTF_8);

        for (int len = 1; len < full.length; len++) {
            ByteBuffer partial = ByteBuffer.wrap(full, 0, len);
            assertThat(NonBlockingRespDecoder.tryReadCommand(partial)).isNull();
            // Position phải được khôi phục về 0 để chờ phần còn lại
            assertThat(partial.position()).isZero();
        }

        ByteBuffer complete = ByteBuffer.wrap(full);
        assertThat(NonBlockingRespDecoder.tryReadCommand(complete))
                .containsExactly(BulkString.of("SET"), BulkString.of("key"), BulkString.of("value"));
    }

    @Test
    void decodesInlineCommandsAndSkipsBlankLines() throws RespProtocolException {
        ByteBuffer buf = bufferOf("\r\n\nPING xin-chao\r\nGET k\n");

        assertThat(NonBlockingRespDecoder.tryReadCommand(buf))
                .containsExactly(BulkString.of("PING"), BulkString.of("xin-chao"));
        assertThat(NonBlockingRespDecoder.tryReadCommand(buf))
                .containsExactly(BulkString.of("GET"), BulkString.of("k"));
        assertThat(NonBlockingRespDecoder.tryReadCommand(buf)).isNull();
    }

    @Test
    void returnsNullForIncompleteInlineCommand() throws RespProtocolException {
        ByteBuffer buf = bufferOf("PING");

        assertThat(NonBlockingRespDecoder.tryReadCommand(buf)).isNull();
        assertThat(buf.position()).isZero();
    }

    @Test
    void decodesBinaryBulkStringContainingCrlf() throws RespProtocolException {
        byte[] header = "*2\r\n$3\r\nGET\r\n$4\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] payload = {'a', '\r', '\n', 'b'};
        byte[] tail = "\r\n".getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(header.length + payload.length + tail.length);
        buf.put(header).put(payload).put(tail).flip();

        assertThat(NonBlockingRespDecoder.tryReadCommand(buf))
                .containsExactly(BulkString.of("GET"), new BulkString(payload));
    }

    @Test
    void rejectsMalformedRespProtocol() {
        assertThatThrownBy(() -> NonBlockingRespDecoder.tryReadCommand(bufferOf("*abc\r\n")))
                .isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> NonBlockingRespDecoder.tryReadCommand(bufferOf("*1\r\n+PING\r\n")))
                .isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> NonBlockingRespDecoder.tryReadCommand(bufferOf("*1\r\n$-1\r\n")))
                .isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> NonBlockingRespDecoder.tryReadCommand(bufferOf("*1\r\n$2\r\nhiXX")))
                .isInstanceOf(RespProtocolException.class);
    }
}
