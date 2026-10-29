package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.store.DataStore;

import java.util.List;

/**
 * {@code EXISTS key [key ...]}: đếm số key tồn tại. Key lặp lại được đếm nhiều lần,
 * như Redis thật: {@code EXISTS a a} trả 2 nếu có a.
 */
public class ExistsCommand implements Command {

    private final DataStore store;

    public ExistsCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "EXISTS";
    }

    @Override
    public RespValue execute(List<BulkString> args) {
        if (args.isEmpty()) {
            return Command.wrongNumberOfArguments(name());
        }
        long count = 0;
        for (BulkString key : args) {
            if (store.exists(key.asString())) {
                count++;
            }
        }
        return new RespInteger(count);
    }
}
