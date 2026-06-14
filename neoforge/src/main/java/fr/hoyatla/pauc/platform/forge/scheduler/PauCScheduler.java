package fr.hoyatla.pauc.platform.forge.scheduler;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.platform.PauCPlatformServices;
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
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class PauCScheduler {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int AVAILABLE_PROCESSORS = Math.max(1, Runtime.getRuntime().availableProcessors());
	private static final String HEAVY_MOD_COUNT_PROPERTY = "pauc.scheduler.heavyModCount";
	private static final String HUGE_MOD_COUNT_PROPERTY = "pauc.scheduler.hugeModCount";
	private static final String ENFORCE_FRAME_BUDGET_PROPERTY = "pauc.scheduler.enforceFrameBudget";

	private static final Map<PauCTaskLane, LaneExecutor> LANES = new ConcurrentHashMap<>();
	private static final FrameBudget FRAME_BUDGET = new FrameBudget();
	private static volatile boolean bootstrapped;

	private PauCScheduler() {
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	public static synchronized void bootstrap() {
		if (bootstrapped) {
			return;
		}

		int modCount = loadedModCount();
		boolean heavyModpack = isHeavyModpack(modCount);
		boolean hugeModpack = isHugeModpack(modCount);
		int serverPrepareThreads = computeServerPrepareThreads(heavyModpack, hugeModpack);
		int clientPrepareThreads = computeClientPrepareThreads(heavyModpack, hugeModpack);
		int chunkMeshThreads = computeChunkMeshThreads(heavyModpack, hugeModpack);
		int gpuUploadThreads = computeGpuUploadThreads();
		int ioThreads = computeIoThreads(heavyModpack, hugeModpack);
		int modTickThreads = computeModTickThreads(heavyModpack, hugeModpack);
		int serverPrepareQueueCapacity = computeServerPrepareQueueCapacity(heavyModpack, hugeModpack);
		int clientPrepareQueueCapacity = computeClientPrepareQueueCapacity(heavyModpack, hugeModpack);
		int chunkMeshQueueCapacity = computeChunkMeshQueueCapacity(heavyModpack, hugeModpack);
		int gpuUploadQueueCapacity = computeGpuUploadQueueCapacity(heavyModpack, hugeModpack);
		int ioQueueCapacity = computeIoQueueCapacity(heavyModpack, hugeModpack);
		int modTickQueueCapacity = computeModTickQueueCapacity(heavyModpack, hugeModpack);

		bootstrapped = true;
		LANES.put(PauCTaskLane.SERVER_PREPARE, new LaneExecutor(PauCTaskLane.SERVER_PREPARE, serverPrepareThreads, serverPrepareQueueCapacity));
		LANES.put(PauCTaskLane.CLIENT_PREPARE, new LaneExecutor(PauCTaskLane.CLIENT_PREPARE, clientPrepareThreads, clientPrepareQueueCapacity));
		LANES.put(PauCTaskLane.CHUNK_MESH, new LaneExecutor(PauCTaskLane.CHUNK_MESH, chunkMeshThreads, chunkMeshQueueCapacity));
		LANES.put(PauCTaskLane.GPU_UPLOAD, new LaneExecutor(PauCTaskLane.GPU_UPLOAD, gpuUploadThreads, gpuUploadQueueCapacity));
		LANES.put(PauCTaskLane.IO, new LaneExecutor(PauCTaskLane.IO, ioThreads, ioQueueCapacity));
		LANES.put(PauCTaskLane.MOD_TICK, new LaneExecutor(PauCTaskLane.MOD_TICK, modTickThreads, modTickQueueCapacity));
		LOGGER.info(
			"PauC scheduler active: {} (mods={}, heavy={}, huge={}, sizing=server:{} / client:{} / mesh:{} / gpu:{} / io:{} / modTick:{}).",
			describeState(),
			modCount >= 0 ? modCount : "?",
			heavyModpack,
			hugeModpack,
			serverPrepareThreads,
			clientPrepareThreads,
			chunkMeshThreads,
			gpuUploadThreads,
			ioThreads,
			modTickThreads
		);
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

	public static CompletableFuture<Void> submitChunkMesh(PauCTaskPriority priority, String description, Runnable runnable) {
		return submit(PauCTaskLane.CHUNK_MESH, priority, description, runnable);
	}

	public static CompletableFuture<Void> submitGpuUpload(PauCTaskPriority priority, String description, Runnable runnable) {
		return submit(PauCTaskLane.GPU_UPLOAD, priority, description, runnable);
	}

	public static CompletableFuture<Void> submitModTick(PauCTaskPriority priority, String description, Runnable runnable) {
		return submit(PauCTaskLane.MOD_TICK, priority, description, runnable);
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

	public static <T> CompletableFuture<T> submitChunkMesh(PauCTaskPriority priority, String description, Callable<T> callable) {
		return submit(PauCTaskLane.CHUNK_MESH, priority, description, callable);
	}

	public static <T> CompletableFuture<T> submitGpuUpload(PauCTaskPriority priority, String description, Callable<T> callable) {
		return submit(PauCTaskLane.GPU_UPLOAD, priority, description, callable);
	}

	public static <T> CompletableFuture<T> submitModTick(PauCTaskPriority priority, String description, Callable<T> callable) {
		return submit(PauCTaskLane.MOD_TICK, priority, description, callable);
	}

	public static void onClientFrameStart() {
		FRAME_BUDGET.onFrameStart();
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

	private static int loadedModCount() {
		try {
			return Math.max(-1, PauCPlatformServices.getInstance().loadedModCount());
		} catch (RuntimeException | LinkageError ignored) {
			return -1;
		}
	}

	private static boolean isHeavyModpack(int modCount) {
		return modCount >= readInt(HEAVY_MOD_COUNT_PROPERTY, 160, 40, 2_048);
	}

	private static boolean isHugeModpack(int modCount) {
		return modCount >= readInt(HUGE_MOD_COUNT_PROPERTY, 260, 80, 4_096);
	}

	private static int computeServerPrepareThreads(boolean heavyModpack, boolean hugeModpack) {
		int fallback = clamp(2 + (AVAILABLE_PROCESSORS / 8) + (heavyModpack ? 1 : 0) + (hugeModpack ? 1 : 0), 2, 8);
		return readInt("pauc.scheduler.serverPrepareThreads", fallback, 1, 8);
	}

	private static int computeClientPrepareThreads(boolean heavyModpack, boolean hugeModpack) {
		int fallback = clamp(2 + (AVAILABLE_PROCESSORS / 4) + (heavyModpack ? 1 : 0) + (hugeModpack ? 1 : 0), 2, 10);
		return readInt("pauc.scheduler.clientPrepareThreads", fallback, 1, 10);
	}

	private static int computeChunkMeshThreads(boolean heavyModpack, boolean hugeModpack) {
		int fallback = clamp(Math.max(2, (AVAILABLE_PROCESSORS / 2) - 2) + (hugeModpack ? 1 : 0), 2, 12);
		return readInt("pauc.scheduler.chunkMeshThreads", fallback, 1, 12);
	}

	private static int computeGpuUploadThreads() {
		return readInt("pauc.scheduler.gpuUploadThreads", 1, 1, 2);
	}

	private static int computeIoThreads(boolean heavyModpack, boolean hugeModpack) {
		int fallback = clamp((AVAILABLE_PROCESSORS / 4) + (hugeModpack ? 1 : 0), 2, 16);
		return readInt("pauc.scheduler.ioThreads", fallback, 1, 16);
	}

	private static int computeModTickThreads(boolean heavyModpack, boolean hugeModpack) {
		int fallback = clamp(heavyModpack ? 2 + (hugeModpack ? 1 : 0) : 1, 1, 4);
		return readInt("pauc.scheduler.modTickThreads", fallback, 1, 4);
	}

	private static int computeServerPrepareQueueCapacity(boolean heavyModpack, boolean hugeModpack) {
		int fallback = clamp(256 + (heavyModpack ? 128 : 0) + (hugeModpack ? 128 : 0), 64, 1_024);
		return readInt("pauc.scheduler.serverPrepareQueue", fallback, 16, 1_024);
	}

	private static int computeClientPrepareQueueCapacity(boolean heavyModpack, boolean hugeModpack) {
		int fallback = clamp(384 + (heavyModpack ? 192 : 0) + (hugeModpack ? 192 : 0), 128, 1_536);
		return readInt("pauc.scheduler.clientPrepareQueue", fallback, 32, 1_536);
	}

	private static int computeChunkMeshQueueCapacity(boolean heavyModpack, boolean hugeModpack) {
		int fallback = clamp(1_024 + (heavyModpack ? 512 : 0) + (hugeModpack ? 512 : 0), 256, 4_096);
		return readInt("pauc.scheduler.chunkMeshQueue", fallback, 64, 4_096);
	}

	private static int computeGpuUploadQueueCapacity(boolean heavyModpack, boolean hugeModpack) {
		int fallback = clamp(256 + (heavyModpack ? 128 : 0) + (hugeModpack ? 128 : 0), 64, 1_024);
		return readInt("pauc.scheduler.gpuUploadQueue", fallback, 32, 1_024);
	}

	private static int computeIoQueueCapacity(boolean heavyModpack, boolean hugeModpack) {
		int fallback = clamp(1_024 + (heavyModpack ? 512 : 0) + (hugeModpack ? 512 : 0), 256, 4_096);
		return readInt("pauc.scheduler.ioQueue", fallback, 64, 4_096);
	}

	private static int computeModTickQueueCapacity(boolean heavyModpack, boolean hugeModpack) {
		int fallback = clamp(512 + (heavyModpack ? 256 : 0) + (hugeModpack ? 256 : 0), 128, 2_048);
		return readInt("pauc.scheduler.modTickQueue", fallback, 64, 2_048);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
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
			long estimatedNanos = FRAME_BUDGET.estimatedTaskNanos(lane);
			if (FRAME_BUDGET.shouldDelay(lane, priority, estimatedNanos)) {
				LockSupport.parkNanos(FRAME_BUDGET.delayNanos());
			}
			startedAtMillis = System.currentTimeMillis();
			runningTasks.put(sequence, this);

			try {
				long startedNanos = System.nanoTime();
				future.complete(callable.call());
				FRAME_BUDGET.onTaskComplete(lane, System.nanoTime() - startedNanos);
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

	private static final class FrameBudget {
		private static final long DEFAULT_DELAY_NANOS = TimeUnit.MILLISECONDS.toNanos(1L);
		private final AtomicLong frameStartNanos = new AtomicLong();
		private final AtomicLong usedThisFrameNanos = new AtomicLong();

		private void onFrameStart() {
			frameStartNanos.set(System.nanoTime());
			usedThisFrameNanos.set(0L);
		}

		private boolean shouldDelay(PauCTaskLane lane, PauCTaskPriority priority, long estimatedNanos) {
			if (!Boolean.parseBoolean(System.getProperty(ENFORCE_FRAME_BUDGET_PROPERTY, "false"))
				|| priority == PauCTaskPriority.CRITICAL
				|| !isFrameSensitiveLane(lane)) {
				return false;
			}

			long frameStarted = frameStartNanos.get();
			if (frameStarted <= 0L) {
				return false;
			}
			long budgetNanos = TimeUnit.SECONDS.toNanos(1L) / Math.max(30, readInt("pauc.scheduler.frameBudgetFps", 144, 30, 360));
			long allowedNanos = Math.round(budgetNanos * readInt("pauc.scheduler.frameBudgetPercent", 65, 10, 95) / 100.0D);
			return usedThisFrameNanos.get() + estimatedNanos > allowedNanos;
		}

		private long delayNanos() {
			return DEFAULT_DELAY_NANOS;
		}

		private void onTaskComplete(PauCTaskLane lane, long actualNanos) {
			if (isFrameSensitiveLane(lane)) {
				usedThisFrameNanos.addAndGet(Math.max(0L, actualNanos));
			}
		}

		private long estimatedTaskNanos(PauCTaskLane lane) {
			return switch (lane) {
				case GPU_UPLOAD -> TimeUnit.MICROSECONDS.toNanos(300L);
				case CHUNK_MESH -> TimeUnit.MILLISECONDS.toNanos(2L);
				case CLIENT_PREPARE -> TimeUnit.MILLISECONDS.toNanos(1L);
				default -> 0L;
			};
		}

		private boolean isFrameSensitiveLane(PauCTaskLane lane) {
			return lane == PauCTaskLane.CLIENT_PREPARE
				|| lane == PauCTaskLane.CHUNK_MESH
				|| lane == PauCTaskLane.GPU_UPLOAD;
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
