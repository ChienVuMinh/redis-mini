package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import com.redismini.store.DataStore;

import java.util.List;

/**
 * {@code SET key value [EX seconds | PXAT timestamp-ms]}: ghi đè nếu key đã có, trả {@code +OK}.
 * Tuỳ chọn {@code EX seconds} thiết lập thời gian hết hạn sau số giây chỉ định.
 * Tuỳ chọn {@code PXAT timestamp-ms} đặt thời điểm hết hạn tuyệt đối (dùng cho AOF).
 */
public class SetCommand implements Command {

    private final DataStore store;

    public SetCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "SET";
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
        if (args.size() < 2) {
            return new Outcome(Command.wrongNumberOfArguments(name()), null);
        }
        BulkString keyArg = args.get(0);
        BulkString valueArg = args.get(1);
        if (args.size() == 2) {
            store.set(keyArg.asString(), valueArg.data());
            return new Outcome(new SimpleString("OK"), List.of(BulkString.of("SET"), keyArg, valueArg));
        }
        if (args.size() == 4) {
            String option = args.get(2).asString();
            long rawValue;
            if (!option.equalsIgnoreCase("EX") && !option.equalsIgnoreCase("PXAT")) {
                return new Outcome(new SimpleError("ERR syntax error"), null);
            }
            try {
                rawValue = Command.parseInteger(args.get(3).asString());
            } catch (NumberFormatException e) {
                return new Outcome(new SimpleError("ERR value is not an integer or out of range"), null);
            }
            if (rawValue <= 0) {
                return new Outcome(new SimpleError("ERR invalid expire time in 'set' command"), null);
            }

            long expireAt;
            if (option.equalsIgnoreCase("EX")) {
                try {
                    long millis = Math.multiplyExact(rawValue, 1000L);
                    expireAt = Math.addExact(store.getClock().millis(), millis);
                } catch (ArithmeticException e) {
                    return new Outcome(new SimpleError("ERR invalid expire time in 'set' command"), null);
                }
            } else {
                expireAt = rawValue;
            }

            if (expireAt <= store.getClock().millis()) {
                boolean deleted = store.delete(keyArg.asString());
                List<BulkString> aof = deleted ? List.of(BulkString.of("DEL"), keyArg) : null;
                return new Outcome(new SimpleString("OK"), aof);
            }

            store.set(keyArg.asString(), valueArg.data(), expireAt);
            List<BulkString> aof = List.of(
                    BulkString.of("SET"),
                    keyArg,
                    valueArg,
                    BulkString.of("PXAT"),
                    BulkString.of(Long.toString(expireAt))
            );
            return new Outcome(new SimpleString("OK"), aof);
        }
        return new Outcome(new SimpleError("ERR syntax error"), null);
    }
}
