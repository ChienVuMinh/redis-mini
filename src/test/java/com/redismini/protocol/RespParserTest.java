package com.redismini.protocol;

import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespArray;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.EOFException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RespParserTest {

    private static RespParser parserOf(String data) {
        return new RespParser(new ByteArrayInputStream(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static RespValue parse(String data) throws IOException {
        return parserOf(data).read();
    }

    // ---------- Từng kiểu dữ liệu ----------

    @Test
    void readsSimpleString() throws IOException {
        assertThat(parse("+OK\r\n")).isEqualTo(new SimpleString("OK"));
    }

    @Test
    void readsSimpleError() throws IOException {
        assertThat(parse("-ERR bad\r\n")).isEqualTo(new SimpleError("ERR bad"));
    }

    @Test
    void readsIntegers() throws IOException {
        assertThat(parse(":42\r\n")).isEqualTo(new RespInteger(42));
        assertThat(parse(":-7\r\n")).isEqualTo(new RespInteger(-7));
    }

    @Test
    void readsBulkString() throws IOException {
        assertThat(parse("$5\r\nhello\r\n")).isEqualTo(BulkString.of("hello"));
    }

    @Test
    void readsBulkStringLengthInBytes() throws IOException {
        // $5 vì "chào" là 5 byte. Nếu đếm theo ký tự sẽ đọc thiếu 1 byte và lệch toàn bộ
        assertThat(parse("$5\r\nchào\r\n")).isEqualTo(BulkString.of("chào"));
    }

    @Test
    void readsBulkStringContainingNewlines() throws IOException {
        assertThat(parse("$4\r\na\r\nb\r\n")).isEqualTo(BulkString.of("a\r\nb"));
    }

    @Test
    void readsEmptyAndNullBulkString() throws IOException {
        assertThat(parse("$0\r\n\r\n")).isEqualTo(BulkString.of(""));
        assertThat(parse("$-1\r\n")).isEqualTo(BulkString.NULL);
    }

    @Test
    void readsCommandSentByRedisCli() throws IOException {
        assertThat(parse("*1\r\n$4\r\nPING\r\n")).isEqualTo(RespArray.of(BulkString.of("PING")));
    }

    @Test
    void readsNestedArray() throws IOException {
        assertThat(parse("*2\r\n:1\r\n*2\r\n+OK\r\n$-1\r\n")).isEqualTo(
                RespArray.of(new RespInteger(1), RespArray.of(new SimpleString("OK"), BulkString.NULL)));
    }

    @Test
    void readsEmptyArray() throws IOException {
        assertThat(parse("*0\r\n")).isEqualTo(new RespArray(List.of()));
    }

    @Test
    void readsBackWhatWriterWrites() throws IOException {
        RespValue original = RespArray.of(
                BulkString.of("SET"), BulkString.of("tên"), BulkString.of("dòng 1\r\ndòng 2"),
                new RespInteger(-1), new SimpleString("OK"), BulkString.NULL);

        byte[] bytes = RespWriter.encode(original);

        assertThat(new RespParser(new ByteArrayInputStream(bytes)).read()).isEqualTo(original);
    }

    // ---------- Dữ liệu tới qua mạng ----------

    @Test
    void readsCommandArrivingOneByteAtATime() throws IOException {
        byte[] bytes = "*2\r\n$4\r\nECHO\r\n$5\r\nchào\r\n".getBytes(StandardCharsets.UTF_8);
        RespParser parser = new RespParser(new OneByteAtATimeInputStream(new ByteArrayInputStream(bytes)));

        assertThat(parser.read()).isEqualTo(RespArray.of(BulkString.of("ECHO"), BulkString.of("chào")));
    }

    @Test
    void readsPipelinedCommandsOneAtATime() throws IOException {
        RespParser parser = parserOf("*1\r\n$4\r\nPING\r\n*2\r\n$4\r\nECHO\r\n$2\r\nhi\r\n");

        assertThat(parser.read()).isEqualTo(RespArray.of(BulkString.of("PING")));
        assertThat(parser.read()).isEqualTo(RespArray.of(BulkString.of("ECHO"), BulkString.of("hi")));
        assertThat(parser.read()).isNull();
    }

    @Test
    void returnsNullWhenStreamEndsBetweenValues() throws IOException {
        assertThat(parse("")).isNull();
    }

    @Test
    void throwsEofWhenStreamEndsInsideValue() {
        assertThatThrownBy(() -> parse("*2\r\n$4\r\nPING\r\n")).isInstanceOf(EOFException.class);
        assertThatThrownBy(() -> parse("$5\r\nhel")).isInstanceOf(EOFException.class);
        assertThatThrownBy(() -> parse("+OK")).isInstanceOf(EOFException.class);
    }

    // ---------- Dữ liệu sai định dạng ----------

    @Test
    void rejectsUnknownTypeByte() {
        // read() là RESP thuần. Chữ thường như "PING\r\n" chỉ được chấp nhận qua readCommand()
        assertThatThrownBy(() -> parse("PING\r\n"))
                .isInstanceOf(RespProtocolException.class)
                .hasMessageContaining("'P'");
    }

    @Test
    void rejectsNonNumericLength() {
        assertThatThrownBy(() -> parse("$abc\r\n")).isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> parse(":12a\r\n")).isInstanceOf(RespProtocolException.class);
    }

    @Test
    void rejectsNegativeLengths() {
        assertThatThrownBy(() -> parse("$-5\r\n")).isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> parse("*-1\r\n")).isInstanceOf(RespProtocolException.class);
    }

    @Test
    void rejectsHugeLengthsWithoutAllocatingMemory() {
        // Client xấu khai báo 100 GB: phải bị chặn ngay, không được cố đọc hay cấp phát
        assertThatThrownBy(() -> parse("$100000000000\r\n")).isInstanceOf(RespProtocolException.class);
        assertThatThrownBy(() -> parse("*100000000\r\n")).isInstanceOf(RespProtocolException.class);
    }

    @Test
    void rejectsBulkStringWithWrongLength() {
        // Khai báo 3 byte nhưng gửi 5: sau 3 byte phải là \r\n, ở đây lại là 'l'
        assertThatThrownBy(() -> parse("$3\r\nhello\r\n")).isInstanceOf(RespProtocolException.class);
    }

    @Test
    void rejectsCrWithoutLf() {
        assertThatThrownBy(() -> parse("+OK\rX")).isInstanceOf(RespProtocolException.class);
    }

    @Test
    void rejectsTooLongLine() {
        String longLine = "+" + "a".repeat(RespParser.MAX_LINE_LENGTH + 1) + "\r\n";
        assertThatThrownBy(() -> parse(longLine)).isInstanceOf(RespProtocolException.class);
    }

    // ---------- readCommand: lệnh từ client ----------

    private static List<String> command(RespParser parser) throws IOException {
        List<BulkString> args = parser.readCommand();
        return args == null ? null : args.stream().map(BulkString::asString).toList();
    }

    @Test
    void readsRespCommand() throws IOException {
        assertThat(command(parserOf("*2\r\n$4\r\nECHO\r\n$4\r\na b\n\r\n")))
                .containsExactly("ECHO", "a b\n");
    }

    @Test
    void readsInlineCommandSplitOnWhitespace() throws IOException {
        assertThat(command(parserOf("SET  key\tvalue \r\n"))).containsExactly("SET", "key", "value");
    }

    @Test
    void readsInlineCommandEndingWithLfOnly() throws IOException {
        // nc trên Linux gửi \n chứ không phải \r\n
        assertThat(command(parserOf("PING\n"))).containsExactly("PING");
    }

    @Test
    void readsMixOfInlineAndRespCommands() throws IOException {
        RespParser parser = parserOf("PING\r\n*1\r\n$4\r\nPING\r\nECHO hi\r\n");

        assertThat(command(parser)).containsExactly("PING");
        assertThat(command(parser)).containsExactly("PING");
        assertThat(command(parser)).containsExactly("ECHO", "hi");
        assertThat(command(parser)).isNull();
    }

    @Test
    void skipsEmptyLinesAndEmptyArrays() throws IOException {
        // Người gõ telnet bấm Enter thừa: Redis lờ đi, không trả lỗi
        RespParser parser = parserOf("\r\n   \r\n*0\r\n*-1\r\nPING\r\n");

        assertThat(command(parser)).containsExactly("PING");
        assertThat(command(parser)).isNull();
    }

    @Test
    void readsInlineCommandArrivingOneByteAtATime() throws IOException {
        byte[] bytes = "ECHO chào\r\n".getBytes(StandardCharsets.UTF_8);
        RespParser parser = new RespParser(new OneByteAtATimeInputStream(new ByteArrayInputStream(bytes)));

        assertThat(command(parser)).containsExactly("ECHO", "chào");
    }

    @Test
    void throwsEofWhenStreamEndsInsideCommand() {
        assertThatThrownBy(() -> parserOf("PING").readCommand()).isInstanceOf(EOFException.class);
        assertThatThrownBy(() -> parserOf("*2\r\n$4\r\nPING\r\n").readCommand()).isInstanceOf(EOFException.class);
    }

    @Test
    void rejectsNonBulkElementInCommand() {
        assertThatThrownBy(() -> parserOf("*1\r\n:1\r\n").readCommand())
                .isInstanceOf(RespProtocolException.class)
                .hasMessageContaining("'$'");
    }

    @Test
    void rejectsNullBulkInCommand() {
        assertThatThrownBy(() -> parserOf("*1\r\n$-1\r\n").readCommand())
                .isInstanceOf(RespProtocolException.class);
    }

    @Test
    void rejectsHugeCommandArray() {
        assertThatThrownBy(() -> parserOf("*100000000\r\n").readCommand())
                .isInstanceOf(RespProtocolException.class);
    }

    @Test
    void rejectsTooLongInlineCommand() {
        String longLine = "a".repeat(RespParser.MAX_LINE_LENGTH + 1) + "\r\n";
        assertThatThrownBy(() -> parserOf(longLine).readCommand()).isInstanceOf(RespProtocolException.class);
    }

    /** Giả lập mạng chậm: mỗi lần đọc chỉ trả về tối đa 1 byte, như thể mỗi byte là một gói tin. */
    private static class OneByteAtATimeInputStream extends FilterInputStream {

        OneByteAtATimeInputStream(InputStream in) {
            super(in);
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            return super.read(b, off, Math.min(len, 1));
        }
    }
}
