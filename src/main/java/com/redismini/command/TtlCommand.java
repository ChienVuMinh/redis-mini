package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.store.DataStore;

import java.util.List;

/**
 * {@code TTL key}: trả về số giây còn sống của key.
 * Trả về:
 * <ul>
 *   <li>{@code >= 1}: số giây còn lại trước khi hết hạn</li>
 *   <li>{@code -1}: nếu key tồn tại nhưng không có thời gian hết hạn (persistent)</li>
 *   <li>{@code -2}: nếu key không tồn tại hoặc đã hết hạn</li>
 * </ul>
 */
public class TtlCommand implements Command {

    private final DataStore store;

    public TtlCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "TTL";
    }

    @Override
    public RespValue execute(List<BulkString> args) {
        if (args.size() != 1) {
            return Command.wrongNumberOfArguments(name());
        }
        String key = args.get(0).asString();
        long ttl = store.getTtlSeconds(key);
        return new RespInteger(ttl);
    }
}

