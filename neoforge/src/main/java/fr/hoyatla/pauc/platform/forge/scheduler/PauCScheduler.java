package fr.hoyatla.pauc.platform.forge.scheduler;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class PauCScheduler {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int SERVER_PREPARE_THREADS = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 4));
	private static final int CLIENT_PREPARE_THREADS = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 6));
	private static final int IO_THREADS = 1;
	private static final int SERVER_PREPARE_QUEUE_CAPACITY = 64;
	private static final int CLIENT_PREPARE_QUEUE_CAPACITY = 64;
	private static final int IO_QUEUE_CAPACITY = 128;

	private static final Map<PauCTaskLane, LaneExecutor> LANES = new ConcurrentHashMap<>();
	private static volatile boolean bootstrapped;

	private PauCScheduler() {
	}

	public static synchronized void bootstrap() {
		if (bootstrapped) {
			return;
		}

		bootstrapped = true;
		LANES.put(PauCTaskLane.SERVER_PREPARE, new LaneExecutor(PauCTaskLane.SERVER_PREPARE, SERVER_PREPARE_THREADS, SERVER_PREPARE_QUEUE_CAPACITY));
		LANES.put(PauCTaskLane.CLIENT_PREPARE, new LaneExecutor(PauCTaskLane.CLIENT_PREPARE, CLIENT_PREPARE_THREADS, CLIENT_PREPARE_QUEUE_CAPACITY));
		LANES.put(PauCTaskLane.IO, new LaneExecutor(PauCTaskLane.IO, IO_THREADS, IO_QUEUE_CAPACITY));
		LOGGER.info("PauC scheduler active: {}.", describeState());
	}

	public static synchronized void shutdown() {
		for (LaneExecutor lane : LANES.values()) {
			lane.shutdown();
		}

		LANES.clear();
		bootstrapped = false;
	}

	public static CompletableFuture<Void> submitServerPrepare(PauCTaskPriority priority, String description, Runnable runnable) {
		return submit(PauCTaskLane.SERVER_PREPARE, priority, description, runnable);
	}

	public static CompletableFuture<Void> submitClientPrepare(PauCTaskPriority priority, String description, Runnable runnable) {
		return submit(PauCTaskLane.CLIENT_PREPARE, priority, description, runnable);
	}

	public static CompletableFuture<Void> submitIo(PauCTaskPriority priority, String description, Runnable runnable) {
		return submit(PauCTaskLane.IO, priority, description, runnable);
	}

	public static <T> CompletableFuture<T> submitServerPrepare(PauCTaskPriority priority, String description, Callable<T> callable) {
		return submit(PauCTaskLane.SERVER_PREPARE, priority, description, callable);
	}

	public static <T> CompletableFuture<T> submitClientPrepare(PauCTaskPriority priority, String description, Callable<T> callable) {
		return submit(PauCTaskLane.CLIENT_PREPARE, priority, description, callable);
	}

	public static <T> CompletableFuture<T> submitIo(PauCTaskPriority priority, String description, Callable<T> callable) {
		return submit(PauCTaskLane.IO, priority, description, callable);
	}

	public static String describeState() {
		if (LANES.isEmpty()) {
			return "disabled";
		}

		List<String> parts = new ArrayList<>();
		for (PauCTaskLane lane : PauCTaskLane.values()) {
			LaneExecutor executor = LANES.get(lane);
			if (executor != null) {
				parts.add(executor.describeState());
			}
		}
		return String.join(", ", parts);
	}

	public static boolean isIdle() {
		for (LaneExecutor lane : LANES.values()) {
			if (!lane.isIdle()) {
				return false;
			}
		}

		return true;
	}

	private static CompletableFuture<Void> submit(PauCTaskLane lane, PauCTaskPriority priority, String description, Runnable runnable) {
		return submit(lane, priority, description, () -> {
			runnable.run();
			return null;
		});
	}

	private static <T> CompletableFuture<T> submit(PauCTaskLane lane, PauCTaskPriority priority, String description, Callable<T> callable) {
		bootstrap();
		LaneExecutor executor = LANES.get(lane);
		if (executor == null) {
			return CompletableFuture.failedFuture(new IllegalStateException("PauC scheduler lane unavailable: " + lane.id()));
		}

		return executor.submit(priority, description, callable);
	}

	private static final class LaneExecutor {
		private final PauCTaskLane lane;
		private final int queueCapacity;
		private final Semaphore queuePermits;
		private final PriorityBlockingQueue<Runnable> queue = new PriorityBlockingQueue<>();
		private final ThreadPoolExecutor executor;
		private final AtomicLong sequence = new AtomicLong();
		private final Map<Long, ScheduledTask<?>> runningTasks = new ConcurrentHashMap<>();

		private LaneExecutor(PauCTaskLane lane, int threads, int queueCapacity) {
			this.lane = lane;
			this.queueCapacity = queueCapacity;
			this.queuePermits = new Semaphore(queueCapacity);
			this.executor = new ThreadPoolExecutor(
				threads,
				threads,
				30L,
				TimeUnit.SECONDS,
				queue,
				new NamedThreadFactory(lane.threadPrefix())
			);
			this.executor.allowCoreThreadTimeOut(false);
		}

		private <T> CompletableFuture<T> submit(PauCTaskPriority priority, String description, Callable<T> callable) {
			if (!queuePermits.tryAcquire()) {
				RejectedExecutionException exception = new RejectedExecutionException("PauC " + lane.id() + " queue is full");
				LOGGER.warn("PauC rejected {} task '{}' because the queue is full ({} queued / {} max).", lane.id(), description, queue.size(), queueCapacity);
				return CompletableFuture.failedFuture(exception);
			}

			CompletableFuture<T> future = new CompletableFuture<>();
			ScheduledTask<T> task = new ScheduledTask<>(
				lane,
				priority,
				description,
				sequence.incrementAndGet(),
				callable,
				future,
				queuePermits,
				runningTasks
			);

			try {
				executor.execute(task);
				return future;
			} catch (RuntimeException exception) {
				queuePermits.release();
				future.completeExceptionally(exception);
				LOGGER.warn("PauC could not enqueue {} task '{}'.", lane.id(), description, exception);
				return future;
			}
		}

		private boolean isIdle() {
			return queue.isEmpty() && runningTasks.isEmpty();
		}

		private String describeState() {
			List<ScheduledTask<?>> queuedTasks = queue.stream()
				.filter(ScheduledTask.class::isInstance)
				.map(ScheduledTask.class::cast)
				.sorted(Comparator.naturalOrder())
				.limit(3)
				.toList();
			List<ScheduledTask<?>> activeTasks = runningTasks.values().stream()
				.sorted(Comparator.comparingLong(ScheduledTask::startedAtMillis))
				.limit(2)
				.toList();

			String queued = queuedTasks.isEmpty()
				? "-"
				: String.join(" | ", queuedTasks.stream().map(ScheduledTask::describe).toList());
			String active = activeTasks.isEmpty()
				? "-"
				: String.join(" | ", activeTasks.stream().map(ScheduledTask::describe).toList());
			return lane.id()
				+ "[queued="
				+ queue.size()
				+ "/"
				+ queueCapacity
				+ ", running="
				+ runningTasks.size()
				+ ", next="
				+ queued
				+ ", active="
				+ active
				+ "]";
		}

		private void shutdown() {
			executor.shutdownNow();
			queue.clear();
			runningTasks.clear();
			queuePermits.drainPermits();
			queuePermits.release(queueCapacity);
		}
	}

	private static final class ScheduledTask<T> implements Runnable, Comparable<ScheduledTask<?>> {
		private final PauCTaskLane lane;
		private final PauCTaskPriority priority;
		private final String description;
		private final long sequence;
		private final Callable<T> callable;
		private final CompletableFuture<T> future;
		private final Semaphore queuePermits;
		private final Map<Long, ScheduledTask<?>> runningTasks;
		private final long submittedAtMillis = System.currentTimeMillis();
		private volatile long startedAtMillis = -1L;

		private ScheduledTask(
			PauCTaskLane lane,
			PauCTaskPriority priority,
			String description,
			long sequence,
			Callable<T> callable,
			CompletableFuture<T> future,
			Semaphore queuePermits,
			Map<Long, ScheduledTask<?>> runningTasks
		) {
			this.lane = lane;
			this.priority = priority;
			this.description = description;
			this.sequence = sequence;
			this.callable = callable;
			this.future = future;
			this.queuePermits = queuePermits;
			this.runningTasks = runningTasks;
		}

		@Override
		public int compareTo(ScheduledTask<?> other) {
			int priorityOrder = Integer.compare(priority.order(), other.priority.order());
			if (priorityOrder != 0) {
				return priorityOrder;
			}

			return Long.compare(sequence, other.sequence);
		}

		@Override
		public void run() {
			startedAtMillis = System.currentTimeMillis();
			runningTasks.put(sequence, this);

			try {
				future.complete(callable.call());
			} catch (Throwable throwable) {
				future.completeExceptionally(throwable);
				LOGGER.warn("PauC task {} failed in lane {}.", description, lane.id(), throwable);
			} finally {
				runningTasks.remove(sequence);
				queuePermits.release();
			}
		}

		private long startedAtMillis() {
			return startedAtMillis;
		}

		private String describe() {
			long ageMillis = Math.max(0L, System.currentTimeMillis() - submittedAtMillis);
			long runningMillis = startedAtMillis > 0L ? Math.max(0L, System.currentTimeMillis() - startedAtMillis) : -1L;
			String lifecycle = runningMillis >= 0L ? runningMillis + "ms running" : ageMillis + "ms queued";
			return priority.name().toLowerCase() + ":" + description + " (" + lifecycle + ")";
		}
	}

	private static final class NamedThreadFactory implements ThreadFactory {
		private final String prefix;
		private final AtomicInteger counter = new AtomicInteger();

		private NamedThreadFactory(String prefix) {
			this.prefix = prefix;
		}

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
			thread.setDaemon(true);
			return thread;
		}
	}
}
