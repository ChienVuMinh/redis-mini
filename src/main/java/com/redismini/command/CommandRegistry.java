package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.store.DataStore;

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

    public CommandRegistry(List<Command> commands) {
        Map<String, Command> byName = new HashMap<>();
        for (Command command : commands) {
            String name = command.name().toUpperCase(Locale.ROOT);
            if (byName.put(name, command) != null) {
                throw new IllegalArgumentException("Duplicate command: " + name);
            }
        }
        this.commands = Map.copyOf(byName);
    }

    /**
     * Các lệnh redis-mini hỗ trợ. Thêm lệnh mới vào đây.
     *
     * @param store kho dữ liệu dùng chung: mọi lệnh nhận cùng một object, nên SET của client này
     *              thì GET của client khác thấy được
     */
    public static CommandRegistry createDefault(DataStore store) {
        return new CommandRegistry(List.of(
                new PingCommand(),
                new EchoCommand(),
                new SetCommand(store),
                new GetCommand(store),
                new DelCommand(store),
                new ExistsCommand(store)
        ));
    }

    /**
     * Chạy một lệnh từ client.
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
