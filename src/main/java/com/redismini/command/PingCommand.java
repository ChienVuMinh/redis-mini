package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleString;

import java.util.List;

/** {@code PING} trả {@code +PONG}; {@code PING message} trả lại message dạng Bulk String. */
public class PingCommand implements Command {

    @Override
    public String name() {
        return "PING";
    }

    @Override
    public RespValue execute(List<BulkString> args) {
        return switch (args.size()) {
            case 0 -> new SimpleString("PONG");
            case 1 -> args.get(0);
            default -> Command.wrongNumberOfArguments(name());
        };
    }
}
