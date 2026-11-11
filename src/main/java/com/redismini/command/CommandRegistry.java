package com.redismini.command;

import com.redismini.persistence.AofWriter;
import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.store.DataStore;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Bảng tên lệnh -> Command. Một registry dùng chung cho mọi client.
 * <p>
 * Danh sách lệnh chốt lúc tạo và không đổi về sau (Map.copyOf cho ra map bất biến),
 * nên nhiều thread cùng đọc mà không cần ConcurrentHashMap hay khoá.
 */
public class CommandRegistry {

    private final Map<String, Command> commands;
    private final AofWriter aofWriter;

    public CommandRegistry(List<Command> commands) {
        this(commands, null);
    }

    public CommandRegistry(List<Command> commands, AofWriter aofWriter) {
        Map<String, Command> byName = new HashMap<>();
        for (Command command : commands) {
            String name = command.name().toUpperCase(Locale.ROOT);
            if (byName.put(name, command) != null) {
                throw new IllegalArgumentException("Duplicate command: " + name);
            }
        }
        this.commands = Map.copyOf(byName);
        this.aofWriter = aofWriter;
    }

    /**
     * Các lệnh redis-mini hỗ trợ. Thêm lệnh mới vào đây.
     *
     * @param store kho dữ liệu dùng chung: mọi lệnh nhận cùng một object, nên SET của client này
     *              thì GET của client khác thấy được
     */
    public static CommandRegistry createDefault(DataStore store) {
        return createDefault(store, null);
    }

    /**
     * Các lệnh redis-mini hỗ trợ, có tích hợp ghi AOF cho các lệnh ghi thành công.
     */
    public static CommandRegistry createDefault(DataStore store, AofWriter aofWriter) {
        return new CommandRegistry(List.of(
                new PingCommand(),
                new EchoCommand(),
                new SetCommand(store),
                new GetCommand(store),
                new DelCommand(store),
                new ExistsCommand(store),
                new IncrCommand(store),
                new ExpireCommand(store),
                new PexpireAtCommand(store),
                new TtlCommand(store),
                new PersistCommand(store),
                new BgrewriteaofCommand(store, aofWriter)
        ), aofWriter);
    }

    /**
     * Chạy một lệnh từ client (ghi vào AOF nếu lệnh có thay đổi dữ liệu và AOF đang bật).
     *
     * @param command tên lệnh và các tham số, như RespParser.readCommand() trả về (luôn có ít nhất 1 phần tử)
     */
    public RespValue execute(List<BulkString> command) {
        String name = command.get(0).asString();
        List<BulkString> args = command.subList(1, command.size());
        // Locale.ROOT: chuyển hoa theo quy tắc chung, không theo ngôn ngữ của máy
        Command handler = commands.get(name.toUpperCase(Locale.ROOT));
        if (handler == null) {
            return unknownCommand(name, args);
        }

        if (aofWriter != null && handler.isWrite()) {
            synchronized (aofWriter) {
                Command.Outcome outcome = handler.executeWithOutcome(args);
                if (outcome.aofCommand() != null) {
                    try {
                        aofWriter.append(outcome.aofCommand());
                    } catch (IOException e) {
                        System.err.println("Failed to append command to AOF: " + e.getMessage());
                    }
                }
                return outcome.response();
            }
        }

        return handler.execute(args);
    }

    /**
     * Chạy lại một lệnh từ file AOF lúc khởi động (không ghi ngược lại vào AOF).
     */
    public RespValue replay(List<BulkString> command) {
        String name = command.get(0).asString();
        List<BulkString> args = command.subList(1, command.size());
        Command handler = commands.get(name.toUpperCase(Locale.ROOT));
        if (handler == null) {
            return unknownCommand(name, args);
        }
        return handler.execute(args);
    }

    private static SimpleError unknownCommand(String name, List<BulkString> args) {
        StringBuilder argsText = new StringBuilder();
        for (BulkString arg : args) {
            argsText.append('\'').append(arg.asString()).append("' ");
        }
        // Tên lệnh do client gửi có thể chứa \r\n, mà Simple Error thì không được chứa
        String message = "ERR unknown command '" + name + "', with args beginning with: " + argsText;
        return new SimpleError(message.replace('\r', ' ').replace('\n', ' '));
    }
}
