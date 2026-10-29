package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.store.DataStore;

import java.util.List;

/** {@code DEL key [key ...]}: xoá các key, trả số key thật sự bị xoá (key không tồn tại không được đếm). */
public class DelCommand implements Command {

    private final DataStore store;

    public DelCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "DEL";
    }

    @Override
    public RespValue execute(List<BulkString> args) {
        if (args.isEmpty()) {
            return Command.wrongNumberOfArguments(name());
        }
        long deleted = 0;
        for (BulkString key : args) {
            if (store.delete(key.asString())) {
                deleted++;
            }
        }
        return new RespInteger(deleted);
    }
}
