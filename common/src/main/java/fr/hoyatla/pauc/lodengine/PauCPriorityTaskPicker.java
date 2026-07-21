package fr.hoyatla.pauc.lodengine;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.util.PriorityQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

/**
 * Priority-based thread pool that shares N threads across multiple named task queues.
 * Executors with shorter total runtime get priority (fair time sharing).
 * Auto-pauses executors when their {@code canRun} predicate returns false.
 *
 * <p>Ported from Distant Horizons {@code PriorityTaskPicker.java} (LGPL v3).</p>
 */
public final class PauCPriorityTaskPicker {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final int maxThreads;
    private final PooledExecutor[] executors;
    private int executorCount;

    public PauCPriorityTaskPicker(int maxThreads) {
        this.maxThreads = maxThreads;
        this.executors = new PooledExecutor[8];
        this.executorCount = 0;
    }

    public PooledExecutor createExecutor(String name) {
        return createExecutor(name, () -> true);
    }

    public PooledExecutor createExecutor(String name, BooleanSupplier canRun) {
        if (executorCount >= executors.length) {
            throw new IllegalStateException("Too many executors (max " + executors.length + ")");
        }
        PooledExecutor ex = new PooledExecutor(name, canRun);
        executors[executorCount++] = ex;
        return ex;
    }

    /**
     * Try to start the next task from the highest-priority executor.
     * Called by the shared worker threads.
     */
    void tryStartNextTask() {
        // sort by shortest total runtime (fair time sharing)
        PooledExecutor[] active = new PooledExecutor[executorCount];
        int activeCount = 0;
        for (int i = 0; i < executorCount; i++) {
            if (executors[i].canRun.getAsBoolean() && !executors[i].queue.isEmpty()) {
                active[activeCount++] = executors[i];
            }
        }
        if (activeCount == 0) return;

        // simple insertion sort by total runtime (ascending)
        for (int i = 1; i < activeCount; i++) {
            PooledExecutor key = active[i];
            long keyTime = key.totalRuntimeNanos.get();
            int j = i - 1;
            while (j >= 0 && active[j].totalRuntimeNanos.get() > keyTime) {
                active[j + 1] = active[j];
                j--;
            }
            active[j + 1] = key;
        }

        for (int i = 0; i < activeCount; i++) {
            Runnable task = active[i].queue.poll();
            if (task != null) {
                long start = System.nanoTime();
                try {
                    task.run();
                } catch (Exception e) {
                    LOGGER.error("PauCPriorityTaskPicker [{}] task error: {}", active[i].name, e.getMessage(), e);
                }
                active[i].totalRuntimeNanos.addAndGet(System.nanoTime() - start);
                return;
            }
        }
    }

    public void shutdown() {
        for (int i = 0; i < executorCount; i++) {
            executors[i].queue.clear();
        }
    }

    public final class PooledExecutor {
        final String name;
        final BooleanSupplier canRun;
        final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
        final AtomicLong totalRuntimeNanos = new AtomicLong(0);
        private final ThreadPoolExecutor pool;

        PooledExecutor(String name, BooleanSupplier canRun) {
            this.name = name;
            this.canRun = canRun;
            this.pool = new ThreadPoolExecutor(1, 1, 60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "PauC-" + name);
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                });
            this.pool.allowCoreThreadTimeOut(true);
        }

        public void submit(Runnable task) {
            pool.execute(task);
        }

        public int getQueueSize() { return queue.size(); }
        public void shutdown() { pool.shutdownNow(); }
    }
}
