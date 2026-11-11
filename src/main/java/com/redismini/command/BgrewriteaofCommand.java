package com.redismini.command;

import com.redismini.persistence.AofWriter;
import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.protocol.RespValue.SimpleString;
import com.redismini.store.DataStore;

import java.util.List;

/**
 * {@code BGREWRITEAOF}: viết lại file AOF trên nền để chỉ giữ trạng thái hiện tại,
 * loại bỏ các lệnh trùng lặp hoặc key đã bị xoá/hết hạn.
 */
public class BgrewriteaofCommand implements Command {

    private final DataStore store;
    private final AofWriter aofWriter;

    public BgrewriteaofCommand(DataStore store, AofWriter aofWriter) {
        this.store = store;
        this.aofWriter = aofWriter;
    }

    @Override
    public String name() {
        return "BGREWRITEAOF";
    }

    @Override
    public RespValue execute(List<BulkString> args) {
        if (!args.isEmpty()) {
            return Command.wrongNumberOfArguments(name());
        }
        if (aofWriter == null) {
            return new SimpleError("ERR AOF is not enabled");
        }
        if (!aofWriter.startBackgroundRewrite(store)) {
            return new SimpleError("ERR Background append only file rewriting already in progress");
        }
        return new SimpleString("Background append only file rewriting started");
    }
}

