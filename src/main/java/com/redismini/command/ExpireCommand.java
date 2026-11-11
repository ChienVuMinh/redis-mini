package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.store.DataStore;

import java.util.List;

/**
 * {@code EXPIRE key seconds}: đặt thời gian sống (TTL) cho key theo giây.
 * Trả về {@code :1} nếu đặt TTL thành công (hoặc xoá key nếu seconds <= 0).
 * Trả về {@code :0} nếu key không tồn tại hoặc đã hết hạn.
 */
public class ExpireCommand implements Command {

    private final DataStore store;

    public ExpireCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "EXPIRE";
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
        long seconds;
        try {
            seconds = Command.parseInteger(args.get(1).asString());
        } catch (NumberFormatException e) {
            return new Outcome(new SimpleError("ERR value is not an integer or out of range"), null);
        }

        // Giống Redis: nếu seconds <= 0 thì xoá key ngay lập tức
        if (seconds <= 0) {
            boolean deleted = store.delete(key);
            List<BulkString> aof = deleted ? List.of(BulkString.of("DEL"), args.get(0)) : null;
            return new Outcome(new RespInteger(deleted ? 1 : 0), aof);
        }

        long expireAt;
        try {
            long millis = Math.multiplyExact(seconds, 1000L);
            expireAt = Math.addExact(store.getClock().millis(), millis);
        } catch (ArithmeticException e) {
            expireAt = Long.MAX_VALUE;
        }

        boolean ok = store.setExpire(key, expireAt);
        List<BulkString> aof = ok
                ? List.of(BulkString.of("PEXPIREAT"), args.get(0), BulkString.of(Long.toString(expireAt)))
                : null;
        return new Outcome(new RespInteger(ok ? 1 : 0), aof);
    }
}

