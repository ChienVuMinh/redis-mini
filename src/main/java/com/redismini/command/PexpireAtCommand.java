package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.store.DataStore;

import java.util.List;

/**
 * {@code PEXPIREAT key milliseconds-timestamp}: đặt thời điểm hết hạn tuyệt đối (Unix epoch ms).
 * Chủ yếu dùng khi AOF lưu và khôi phục TTL chính xác bất kể thời gian server ngừng chạy.
 */
public class PexpireAtCommand implements Command {

    private final DataStore store;

    public PexpireAtCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "PEXPIREAT";
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
        if (args.size() != 2) {
            return new Outcome(Command.wrongNumberOfArguments(name()), null);
        }
        String key = args.get(0).asString();
        long expireAt;
        try {
            expireAt = Command.parseInteger(args.get(1).asString());
        } catch (NumberFormatException e) {
            return new Outcome(new SimpleError("ERR value is not an integer or out of range"), null);
        }

        if (expireAt <= store.getClock().millis()) {
            boolean deleted = store.delete(key);
            List<BulkString> aof = deleted ? List.of(BulkString.of("DEL"), args.get(0)) : null;
            return new Outcome(new RespInteger(deleted ? 1 : 0), aof);
        }

        boolean ok = store.setExpire(key, expireAt);
        List<BulkString> aof = ok
                ? List.of(BulkString.of("PEXPIREAT"), args.get(0), BulkString.of(Long.toString(expireAt)))
                : null;
        return new Outcome(new RespInteger(ok ? 1 : 0), aof);
    }
}
