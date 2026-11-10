package com.redismini.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Active Eviction: Dọn dẹp key hết hạn định kỳ ở background.
 * <p>
 * Thuật toán giống Redis:
 * Cứ 100ms chạy một chu kỳ:
 * 1. Lấy ngẫu nhiên tối đa 20 key có TTL (SAMPLE_SIZE).
 * 2. Xoá những key đã hết hạn.
 * 3. Nếu số key hết hạn vượt quá 25% (RECHECK_THRESHOLD), lặp lại bước 1.
 * Giới hạn tối đa 16 vòng lặp mỗi chu kỳ để tránh nghẽn thread.
 */
public class ExpiryManager implements AutoCloseable {

    public static final int SAMPLE_SIZE = 20;
    public static final double RECHECK_THRESHOLD = 0.25;
    public static final int MAX_ITERATIONS = 16;
    public static final long DEFAULT_PERIOD_MS = 100;

    private final DataStore store;
    private final ScheduledExecutorService scheduler;
    private volatile boolean running;

    public ExpiryManager(DataStore store) {
        this(store, Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "expiry-manager");
            t.setDaemon(true);
            return t;
        }));
    }

    public ExpiryManager(DataStore store, ScheduledExecutorService scheduler) {
        this.store = store;
        this.scheduler = scheduler;
    }

    /** Bắt đầu chạy task định kỳ dọn dẹp key hết hạn. */
    public synchronized void start() {
        if (!running) {
            running = true;
            scheduler.scheduleWithFixedDelay(
                    this::runEvictionCycleSafely,
                    DEFAULT_PERIOD_MS,
                    DEFAULT_PERIOD_MS,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    private void runEvictionCycleSafely() {
        try {
            runEvictionCycle();
        } catch (Throwable t) {
            // Không để exception bất ngờ làm chết ScheduledExecutor
            System.err.println("Error in ExpiryManager eviction cycle: " + t.getMessage());
        }
    }

    /**
     * Chạy một chu kỳ dọn dẹp chủ động.
     *
     * @return tổng số key đã bị xoá trong chu kỳ này
     */
    public int runEvictionCycle() {
        int totalEvicted = 0;
        int iterations = 0;

        while (iterations < MAX_ITERATIONS) {
            iterations++;
            Set<String> keysWithTtl = store.expiresKeySet();
            if (keysWithTtl.isEmpty()) {
                break;
            }

            List<String> keyList = new ArrayList<>(keysWithTtl);
            if (keyList.isEmpty()) {
                break;
            }
            Collections.shuffle(keyList);
            int sampleSize = Math.min(SAMPLE_SIZE, keyList.size());
            List<String> sample = keyList.subList(0, sampleSize);

            int expiredInSample = 0;
            for (String key : sample) {
                if (store.evictIfExpired(key)) {
                    expiredInSample++;
                    totalEvicted++;
                }
            }

            // Nếu tỷ lệ key hết hạn <= 25%, dừng chu kỳ này
            if ((double) expiredInSample / sampleSize <= RECHECK_THRESHOLD) {
                break;
            }
        }

        return totalEvicted;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public synchronized void close() {
        running = false;
        scheduler.shutdownNow();
    }
}

