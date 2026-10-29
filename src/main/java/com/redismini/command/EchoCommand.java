package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;

import java.util.List;

/** {@code ECHO message}: trả lại đúng message. Khác PING ở chỗ bắt buộc có đúng 1 tham số. */
public class EchoCommand implements Command {

    @Override
    public String name() {
        return "ECHO";
    }

    @Override
    public RespValue execute(List<BulkString> args) {
        if (args.size() != 1) {
            return Command.wrongNumberOfArguments(name());
        }
        return args.get(0);
    }
}
