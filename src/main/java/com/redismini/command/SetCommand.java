package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import com.redismini.store.DataStore;

import java.util.List;

/**
 * {@code SET key value}: ghi đè nếu key đã có, trả {@code +OK}.
 * Các tuỳ chọn như {@code EX 10} để Giai đoạn 4; hiện có thêm tham số là lỗi cú pháp, như Redis thật.
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
        if (args.size() > 2) {
            return new SimpleError("ERR syntax error");
        }
        store.set(args.get(0).asString(), args.get(1).data());
        return new SimpleString("OK");
    }
}
