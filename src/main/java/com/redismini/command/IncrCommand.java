package com.redismini.command;

import com.redismini.protocol.RespValue;
import com.redismini.protocol.RespValue.BulkString;
import com.redismini.protocol.RespValue.RespInteger;
import com.redismini.protocol.RespValue.SimpleError;
import com.redismini.store.DataStore;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * {@code INCR key}: cộng 1 vào số nguyên lưu ở key, trả giá trị mới. Key chưa có thì coi là 0.
 * Redis không có kiểu số riêng: value vẫn là chuỗi byte "41", INCR đọc ra số, cộng, ghi lại "42".
 */
public class IncrCommand implements Command {

    private final DataStore store;

    public IncrCommand(DataStore store) {
        this.store = store;
    }

    @Override
    public String name() {
        return "INCR";
    }

    @Override
    public RespValue execute(List<BulkString> args) {
        if (args.size() != 1) {
            return Command.wrongNumberOfArguments(name());
        }
        try {
            // Đọc, cộng, ghi trong một lần update: hai client INCR cùng lúc không làm mất lần tăng nào
            byte[] result = store.update(args.get(0).asString(), old -> {
                long current = old == null ? 0 : parseInteger(old);
                // addExact ném ArithmeticException thay vì âm thầm tràn thành số âm
                return Long.toString(Math.addExact(current, 1)).getBytes(StandardCharsets.US_ASCII);
            });
            return new RespInteger(parseInteger(result));
        } catch (NumberFormatException e) {
            return new SimpleError("ERR value is not an integer or out of range");
        } catch (ArithmeticException e) {
            return new SimpleError("ERR increment or decrement would overflow");
        }
    }

    /** @throws NumberFormatException nếu không phải số nguyên hợp lệ hoặc vượt khoảng của long */
    private static long parseInteger(byte[] value) {
        return Command.parseInteger(new String(value, StandardCharsets.US_ASCII));
    }
}
