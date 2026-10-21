# Kế hoạch dự án Redis mini (Java)

## Tổng quan

Xây một server key-value chạy trong RAM bằng Java, nói chuyện được với `redis-cli` thật, lưu bền dữ liệu bằng AOF, xử lý nhiều client bằng event loop. Thời gian dự kiến: 6 tuần, làm buổi tối.

**Mục tiêu học (không phải làm sản phẩm):**

- Hiểu TCP và cách server biến byte thành lệnh, thứ mà Spring Boot/Tomcat đang che đi.
- Hiểu vì sao Redis đơn luồng mà vẫn nhanh: event loop với Java NIO `Selector`.
- Hiểu đánh đổi tốc độ và độ bền dữ liệu qua AOF và `fsync`.
- Có một repo GitHub có benchmark so với Redis thật để đưa vào CV.

**Phạm vi:**

| Làm | Không làm |
| --- | --- |
| Giao thức RESP, chạy được với `redis-cli` và Jedis | Giao diện web |
| String: `PING`, `ECHO`, `SET`, `GET`, `DEL`, `EXISTS`, `INCR` | Cluster, replication |
| TTL: `EXPIRE`, `TTL`, `SET ... EX` | Bảo mật, phân quyền |
| AOF + khôi phục khi khởi động | Snapshot RDB (để phần mở rộng) |
| Event loop NIO, benchmark | Pub/Sub, Lua script |

**Kết quả cuối:** một file `.jar` chạy bằng `java -jar redis-mini.jar --port 6380`, kèm README, test và bảng benchmark.

---

## Chuẩn bị môi trường

| Thứ cần | Ghi chú |
| --- | --- |
| JDK 21 (Temurin hoặc Oracle) | Cần bản 21 để thử virtual thread |
| IDE (VS Code + Extension Pack for Java, hoặc IntelliJ) | Debug, chạy test |
| Maven hoặc Gradle | Maven đơn giản hơn nếu quen Spring |
| Docker | Chạy Redis thật để có `redis-cli`, `redis-benchmark` và so sánh |
| Git + GitHub | Commit theo từng giai đoạn |

```bash
docker run -d --name redis-real -p 6379:6379 redis
docker exec -it redis-real redis-cli -h host.docker.internal -p 6380
```

**Thư viện:**

- Code chính: chỉ JDK (`java.net`, `java.nio`, `java.util.concurrent`, `FileChannel`).
- Chỉ dùng khi test: JUnit 5, AssertJ, Jedis (lấy bản mới nhất trên Maven Central).
- Tuỳ chọn: Awaitility (test TTL), SLF4J + Logback (log).
- Đo hiệu năng: JFR có sẵn trong JDK, xem bằng JDK Mission Control hoặc VisualVM.

---

## Kiến trúc

Mỗi lệnh đi qua 4 tầng, lệnh ghi được lưu thêm vào AOF. Phản hồi đi ngược lại cùng đường; `AofLoader` chỉ chạy lúc khởi động.

```
 ┌──────────────┐   ┌──────────────┐   ┌──────────────┐   ┌─────────────────┐
 │    Client    │──>│ Network (NIO)│──>│  RespParser  │──>│ CommandRegistry │
 │redis-cli,Jedis│  │Selector,Buffer│  │ byte -> lệnh │   │ SET, GET, ...   │
 └──────────────┘   └──────────────┘   └──────────────┘   └───────┬─────────┘
                                                        đọc/ghi  │    │ lệnh ghi
                                                     ┌───────────┘    v
 ┌──────────────┐   ┌──────────────┐               ┌──────────────┐
 │ExpiryManager │──>│  DataStore   │               │  AofWriter   │
 │xoá key hết hạn│  │Map<String,Entry>│            │append + fsync│
 └──────────────┘   └──────────────┘               └──────┬───────┘
                           ^ khi khởi động                v
                    ┌──────────────┐               ┌──────────────┐
                    │  AofLoader   │<──────────────│appendonly.aof│
                    │chạy lại lệnh cũ│             │ file trên đĩa│
                    └──────────────┘               └──────────────┘
```

**Cấu trúc package gợi ý:**

```
src/main/java/com/redismini/
├── Main.java                 // đọc tham số --port, khởi động server
├── server/
│   ├── BlockingServer.java   // giai đoạn 1-5: thread-per-client
│   └── NioServer.java        // giai đoạn 6: event loop + Selector
├── protocol/
│   ├── RespParser.java
│   └── RespWriter.java
├── command/
│   ├── Command.java          // interface execute(args) -> RespValue
│   ├── CommandRegistry.java  // map tên lệnh -> Command
│   └── impl/                 // SetCommand, GetCommand, ExpireCommand...
├── store/
│   ├── DataStore.java
│   ├── Entry.java            // value + expireAt
│   └── ExpiryManager.java
└── persistence/
    ├── AofWriter.java
    └── AofLoader.java
```

---

## Các giai đoạn thực hiện

Bảy giai đoạn, mỗi giai đoạn kết thúc bằng một thứ chạy được. Không sang giai đoạn sau khi giai đoạn trước chưa có test.

### Giai đoạn 1: TCP server cơ bản

- **Làm:** `ServerSocket` lắng nghe cổng 6380, mỗi client một thread (dùng `ExecutorService`). Đọc từng dòng text, trả lời `+PONG` khi nhận `PING`.
- **Kết quả:** `telnet localhost 6380`, gõ `PING`, nhận `PONG`.
- **Học được:** vòng đời socket, `InputStream`/`OutputStream`, client ngắt kết nối giữa chừng.

### Giai đoạn 2: Giao thức RESP

- **Làm:** `RespParser` đọc 5 kiểu: Simple String `+`, Error `-`, Integer `:`, Bulk String `$`, Array `*`. `RespWriter` ghi ngược lại. Xử lý trường hợp một lệnh đến bị cắt thành nhiều gói.
- **Kết quả:** `redis-cli -p 6380 PING` chạy được.
- **Học được:** thiết kế giao thức, vì sao cần độ dài phía trước dữ liệu, parse an toàn.

### Giai đoạn 3: Lưu trữ và lệnh cơ bản

- **Làm:** `DataStore` bọc `ConcurrentHashMap<String, Entry>`. Interface `Command` + `CommandRegistry` (pattern Command) cho `ECHO`, `SET`, `GET`, `DEL`, `EXISTS`, `INCR`. Trả lỗi đúng định dạng Redis khi sai cú pháp hoặc sai kiểu.
- **Kết quả:** Jedis trong một chương trình Java kết nối và gọi `set`/`get` được.
- **Học được:** tổ chức code mở rộng được, thread-safety khi nhiều client cùng ghi.

### Giai đoạn 4: Hết hạn key (TTL)

- **Làm:** lưu thời điểm hết hạn trong `Entry`. Lệnh `EXPIRE`, `TTL`, `PERSIST`, `SET key val EX 10`. Xoá theo 2 cách: *lazy* (kiểm tra khi đọc) và *active* (cứ 100ms lấy ngẫu nhiên 20 key có TTL, xoá key hết hạn, lặp lại nếu hơn 25% đã hết hạn).
- **Kết quả:** `SET a 1 EX 2`, sau 2 giây `GET a` trả `(nil)`, RAM không phình mãi.
- **Học được:** vì sao key hết hạn vẫn chiếm RAM một lúc ở Redis thật, thuật toán xác suất.

### Giai đoạn 5: Persistence bằng AOF

- **Làm:** mỗi lệnh ghi thành công thì append dạng RESP vào `appendonly.aof`. Khởi động đọc file, chạy lại từng lệnh. Ba chế độ `fsync`: `always`, `everysec`, `no` (dùng `FileChannel.force()`). Bỏ qua lệnh cuối bị ghi dở khi crash. Thêm `BGREWRITEAOF`: ghi lại file chỉ chứa trạng thái hiện tại.
- **Kết quả:** `SET` dữ liệu, kill server bằng `kill -9`, chạy lại, dữ liệu còn.
- **Học được:** độ bền dữ liệu, đánh đổi tốc độ và an toàn, xử lý file hỏng.

### Giai đoạn 6: Event loop với Java NIO

- **Làm:** thay mô hình thread-per-client bằng một thread duy nhất: `ServerSocketChannel` non-blocking + `Selector`, mỗi kết nối có buffer đọc/ghi riêng (`ByteBuffer`). Lệnh chạy tuần tự trên thread này nên có thể đổi `ConcurrentHashMap` về `HashMap`.
- **Kết quả:** chịu được 1.000 kết nối đồng thời với một thread.
- **Học được:** nền tảng của Netty, Spring WebFlux; hiểu vì sao một lệnh chậm (`KEYS *`) làm treo cả Redis.

### Giai đoạn 7: Benchmark và hoàn thiện

- **Làm:** chạy `redis-benchmark -p 6380 -t set,get -n 100000 -c 50` cho 3 bản: thread-per-client, NIO, Redis thật. Thử thêm virtual thread (Java 21) cho bản thread-per-client. Viết README có bảng kết quả và sơ đồ kiến trúc.
- **Kết quả:** repo hoàn chỉnh trên GitHub.
- **Học được:** đo hiệu năng, đọc profiler (JFR / VisualVM), giải thích kết quả bằng số liệu.

---

## Lịch trình theo tuần

Khoảng 6 tuần, mỗi tuần 6–8 giờ. Đánh dấu `[x]` khi xong.

- [ ] **Tuần 1:** Giai đoạn 1 + 2 (TCP server, RESP). Mốc: `redis-cli PING` chạy được.
- [ ] **Tuần 2:** Giai đoạn 3 (lưu trữ, lệnh cơ bản, pattern Command). Mốc: Jedis `set`/`get` được.
- [ ] **Tuần 3:** Giai đoạn 4 (TTL lazy + active). Mốc: key tự biến mất đúng hạn.
- [ ] **Tuần 4:** Giai đoạn 5 (AOF, fsync, khôi phục). Mốc: `kill -9` không mất dữ liệu.
- [ ] **Tuần 5:** Giai đoạn 6 (event loop NIO). Mốc: 1.000 kết nối, 1 thread.
- [ ] **Tuần 6:** Giai đoạn 7 (benchmark, README, `BGREWRITEAOF` nếu còn dư thời gian). Mốc: repo công khai trên GitHub.

Tuần 5 là phần khó nhất. Nếu chậm, giữ bản thread-per-client chạy ổn và làm NIO thành nhánh riêng.

---

## Cách test và tiêu chí hoàn thành

**Cách test theo tầng:**

- **Unit:** parser RESP (lệnh đủ, lệnh bị cắt đôi, dữ liệu sai), từng `Command`, logic TTL (giả lập đồng hồ bằng `Clock` để test không phải chờ).
- **Integration:** khởi động server trong test, dùng Jedis gửi lệnh thật, kiểm tra kết quả.
- **Đối chiếu:** chạy cùng một chuỗi lệnh trên Redis thật và Redis mini, kết quả phải giống nhau.

**Tiêu chí hoàn thành:**

- [ ] Mọi lệnh trong phạm vi chạy đúng qua `redis-cli` và Jedis.
- [ ] Restart sau `kill -9` không mất dữ liệu đã ghi (chế độ `always`).
- [ ] Một thread NIO phục vụ 1.000 kết nối đồng thời.
- [ ] Có bảng benchmark so sánh 3 bản trong README.
- [ ] Độ phủ test phần core từ 70% trở lên.

---

## Mở rộng và tài liệu tham khảo

**Làm thêm khi đã xong:** kiểu List (`LPUSH`, `LRANGE`) và Hash (`HSET`, `HGET`); Sorted Set bằng skip list tự viết; snapshot RDB; giới hạn RAM với chính sách xoá LRU; Pub/Sub; replication master–replica.

**Tài liệu:**

- Đặc tả giao thức RESP trên redis.io (mục Protocol spec).
- Tài liệu persistence của Redis trên redis.io (mục Persistence) để đối chiếu AOF và fsync.
- Thử thách *Build your own Redis* trên CodeCrafters.
- Sách *Java Network Programming* (Elliotte Rusty Harold), phần NIO.
- Mã nguồn Redis trên GitHub: `ae.c` (event loop) và `expire.c` (xoá key hết hạn).
