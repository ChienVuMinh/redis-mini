package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.store.DataStore;

import java.util.ArrayList;
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
    public boolean isWrite() {
        return true;
    }

    @Override
    public RespValue execute(List<BulkString> args) {
        return executeWithOutcome(args).response();
    }

    @Override
    public Outcome executeWithOutcome(List<BulkString> args) {
        if (args.isEmpty()) {
            return new Outcome(Command.wrongNumberOfArguments(name()), null);
        }
        long deleted = 0;
        List<BulkString> aof = new ArrayList<>();
        aof.add(BulkString.of("DEL"));
        for (BulkString key : args) {
            if (store.delete(key.asString())) {
                deleted++;
                aof.add(key);
            }
        }
        return new Outcome(new RespInteger(deleted), deleted > 0 ? List.copyOf(aof) : null);
    }
}
