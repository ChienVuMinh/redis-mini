package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.store.DataStore;

import java.util.List;

/** {@code GET key}: trả value dạng Bulk String, hoặc Null Bulk String {@code $-1} (nil) nếu không có key. */
public class GetCommand implements Command {

    private final DataStore store;

    public GetCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "GET";
    }

    @Override
    public RespValue execute(List<BulkString> args) {
        if (args.size() != 1) {
            return Command.wrongNumberOfArguments(name());
        }
        byte[] value = store.get(args.get(0).asString());
        return value == null ? BulkString.NULL : new BulkString(value);
    }
}
