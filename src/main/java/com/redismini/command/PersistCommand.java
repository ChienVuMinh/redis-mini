package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.store.DataStore;

import java.util.List;

/**
 * {@code PERSIST key}: xoá thời gian hết hạn của key, biến key thành tồn tại vĩnh viễn.
 * Trả về:
 * <ul>
 *   <li>{@code :1} nếu xoá TTL thành công.</li>
 *   <li>{@code :0} nếu key không tồn tại hoặc không có TTL.</li>
 * </ul>
 */
public class PersistCommand implements Command {

    private final DataStore store;

    public PersistCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "PERSIST";
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
        if (args.size() != 1) {
            return new Outcome(Command.wrongNumberOfArguments(name()), null);
        }
        String key = args.get(0).asString();
        boolean persisted = store.persist(key);
        List<BulkString> aof = persisted ? List.of(BulkString.of("PERSIST"), args.get(0)) : null;
        return new Outcome(new RespInteger(persisted ? 1 : 0), aof);
    }
}

