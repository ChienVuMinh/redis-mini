package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import com.redismini.store.DataStore;

import java.util.List;

/**
 * {@code SET key value [EX seconds]}: ghi đè nếu key đã có, trả {@code +OK}.
 * Tuỳ chọn {@code EX seconds} thiết lập thời gian hết hạn sau số giây chỉ định.
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
    public RespValue execute(List<BulkString> args) {
        if (args.size() < 2) {
            return Command.wrongNumberOfArguments(name());
        }
        if (args.size() == 2) {
            store.set(args.get(0).asString(), args.get(1).data());
            return new SimpleString("OK");
        }
        if (args.size() == 4) {
            String option = args.get(2).asString();
            if (!option.equalsIgnoreCase("EX")) {
                return new SimpleError("ERR syntax error");
            }
            long seconds;
            try {
                seconds = Command.parseInteger(args.get(3).asString());
            } catch (NumberFormatException e) {
                return new SimpleError("ERR value is not an integer or out of range");
            }
            if (seconds <= 0) {
                return new SimpleError("ERR invalid expire time in 'set' command");
            }
            long expireAt;
            try {
                long millis = Math.multiplyExact(seconds, 1000L);
                expireAt = Math.addExact(store.getClock().millis(), millis);
            } catch (ArithmeticException e) {
                return new SimpleError("ERR invalid expire time in 'set' command");
            }
            store.set(args.get(0).asString(), args.get(1).data(), expireAt);
            return new SimpleString("OK");
        }
        return new SimpleError("ERR syntax error");
    }
}
