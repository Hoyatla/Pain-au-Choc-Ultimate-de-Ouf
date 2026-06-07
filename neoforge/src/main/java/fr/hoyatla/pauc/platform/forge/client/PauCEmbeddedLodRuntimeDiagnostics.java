package fr.hoyatla.pauc.platform.forge.client;

import com.seibel.distanthorizons.core.generation.queues.WorldGenerationQueue;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalResult;
import com.seibel.distanthorizons.core.generation.tasks.DataSourceRetrievalTask;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

public final class PauCEmbeddedLodRuntimeDiagnostics {
	private static final int DETAIL_BUCKETS = 16;
	private static final AtomicLong submittedTasks = new AtomicLong();
	private static final AtomicLong startedTasks = new AtomicLong();
	private static final AtomicLong completedTasks = new AtomicLong();
	private static final AtomicLong failedTasks = new AtomicLong();
	private static final AtomicLong splitTasks = new AtomicLong();
	private static final AtomicLong coarseFillReroutedTasks = new AtomicLong();
	private static final AtomicLong coarseFillPreservedTasks = new AtomicLong();
	private static final AtomicLong coarseFillDecisionCounter = new AtomicLong();
	private static final AtomicLongArray submittedByDetail = new AtomicLongArray(DETAIL_BUCKETS);
	private static final AtomicLongArray startedByDetail = new AtomicLongArray(DETAIL_BUCKETS);
	private static final AtomicLongArray completedByDetail = new AtomicLongArray(DETAIL_BUCKETS);
	private static volatile QueueSnapshot lastQueueSnapshot = QueueSnapshot.unavailable();
	private static final String COARSE_FILL_DETAIL_PROPERTY = "pauc.lod.coarseFillDetailLevel";
	private static final String COARSE_FILL_REROUTE_PROPERTY = "pauc.lod.coarseFillRerouteDetail";
	private static final String COARSE_FILL_VALIDATE_QUEUE_DETAIL_PROPERTY = "pauc.lod.coarseFillValidateQueueDetailRange";
	private static final String COARSE_FILL_REROUTE_RATIO_PROPERTY = "pauc.lod.coarseFillRerouteRatio";
	private static final String COARSE_FILL_CATCHUP_REROUTE_RATIO_PROPERTY = "pauc.lod.coarseFillCatchupRerouteRatio";
	private static final String COARSE_FILL_BALANCE_WINDOW_PROPERTY = "pauc.lod.coarseFillBalanceWindow";
	private static final String FILL_PRESENTATION_MIN_COMPLETED_TASKS_PROPERTY = "pauc.lod.fillPresentationMinCompletedTasks";
	private static final String FILL_PRESENTATION_BACKLOG_TASKS_PROPERTY = "pauc.lod.fillPresentationBacklogTasks";

	private PauCEmbeddedLodRuntimeDiagnostics() {
	}

	public static void onTaskSubmitted(WorldGenerationQueue queue, long pos, byte detailLevel, CompletableFuture<DataSourceRetrievalResult> future) {
		submittedTasks.incrementAndGet();
		increment(submittedByDetail, detailLevel);
		captureQueue(queue);
		future.whenComplete((result, throwable) -> {
			if (throwable != null) {
				failedTasks.incrementAndGet();
			} else if (result != null && result.state != null && "SUCCESS".equalsIgnoreCase(result.state.name())) {
				completedTasks.incrementAndGet();
				increment(completedByDetail, detailLevel);
			} else if (result != null && result.state != null && result.state.name().toUpperCase(Locale.ROOT).contains("SPLIT")) {
				splitTasks.incrementAndGet();
			} else {
				completedTasks.incrementAndGet();
				increment(completedByDetail, detailLevel);
			}
			captureQueue(queue);
		});
	}

	public static void onTaskStarted(WorldGenerationQueue queue, DataSourceRetrievalTask task) {
		if (task != null) {
			startedTasks.incrementAndGet();
			increment(startedByDetail, task.requestDetailLevel);
		}
		captureQueue(queue);
	}

	public static void captureQueue(@Nullable WorldGenerationQueue queue) {
		if (queue == null) {
			return;
		}
		try {
			int waitingTasks = Math.max(0, queue.getWaitingTaskCount());
			int inProgressTasks = Math.max(0, queue.getInProgressTaskCount());
			int queuedChunks = Math.max(0, queue.getQueuedChunkCount());
			int rawRemainingTasks = Math.max(0, queue.getEstimatedRemainingTaskCount());
			int rawRemainingChunks = Math.max(0, queue.getRetrievalEstimatedRemainingChunkCount());
			int pendingTasks = Math.max(rawRemainingTasks, waitingTasks + inProgressTasks);
			int pendingChunks = Math.max(rawRemainingChunks, queuedChunks);
			double rollingAverageMs = queue.getRollingAverageChunkGenTimeInMs() != null
				? queue.getRollingAverageChunkGenTimeInMs().getAverage()
				: -1.0D;
			lastQueueSnapshot = new QueueSnapshot(
				true,
				waitingTasks,
				inProgressTasks,
				queuedChunks,
				rawRemainingTasks,
				rawRemainingChunks,
				pendingTasks,
				pendingChunks,
				queue.lowestDataDetail(),
				queue.highestDataDetail(),
				rollingAverageMs,
				submittedTasks.get(),
				startedTasks.get(),
				completedTasks.get(),
				failedTasks.get(),
				splitTasks.get(),
				coarseFillReroutedTasks.get(),
				coarseFillPreservedTasks.get()
			);
		} catch (RuntimeException | LinkageError ignored) {
			lastQueueSnapshot = QueueSnapshot.unavailable();
		}
	}

	public static byte adjustRequiredDetailForCoarseFill(@Nullable WorldGenerationQueue queue, byte requiredDataDetail) {
		if (!readBoolean(COARSE_FILL_REROUTE_PROPERTY, true)) {
			return requiredDataDetail;
		}
		boolean shaderFallbackFill = PauCLodShaderContext.isShaderPackInUse() && PauCLodShaderContext.isFallbackActive();
		if (!shaderFallbackFill && !PauCClientFrontierWarmupManager.shouldPreferCoarseFill() && !PauCClientChunkPriorityScorer.isMovementCatchupActive()) {
			return requiredDataDetail;
		}

		int originalDetail = Byte.toUnsignedInt(requiredDataDetail);
		int coarseDetail = readInt(COARSE_FILL_DETAIL_PROPERTY, 4, 1, 8);
		if (queue != null) {
			int highestSupportedDetail = Byte.toUnsignedInt(queue.highestDataDetail());
			int lowestSupportedDetail = Byte.toUnsignedInt(queue.lowestDataDetail());
			int minimumSupportedDetail = Math.min(highestSupportedDetail, lowestSupportedDetail);
			int maximumSupportedDetail = Math.max(highestSupportedDetail, lowestSupportedDetail);
			int supportedCoarseDetail = readBoolean(COARSE_FILL_VALIDATE_QUEUE_DETAIL_PROPERTY, false)
				? Math.max(minimumSupportedDetail, Math.min(maximumSupportedDetail, coarseDetail))
				: coarseDetail;
			if (supportedCoarseDetail > maximumSupportedDetail || supportedCoarseDetail < minimumSupportedDetail) {
				coarseFillPreservedTasks.incrementAndGet();
				return requiredDataDetail;
			}
			if (supportedCoarseDetail == originalDetail) {
				return requiredDataDetail;
			}
			coarseDetail = supportedCoarseDetail;
		}
		if (originalDetail >= coarseDetail) {
			return requiredDataDetail;
		}
		if (!shouldRerouteThisFillRequest(shaderFallbackFill)) {
			coarseFillPreservedTasks.incrementAndGet();
			return requiredDataDetail;
		}
		coarseFillReroutedTasks.incrementAndGet();
		return (byte) coarseDetail;
	}

	public static String describeState() {
		QueueSnapshot snapshot = lastQueueSnapshot;
		return "plQueue["
			+ snapshot.describe()
			+ ", submitted="
			+ snapshot.submittedTasks
			+ ", started="
			+ snapshot.startedTasks
			+ ", completed="
			+ snapshot.completedTasks
			+ ", split="
			+ snapshot.splitTasks
			+ ", failed="
			+ snapshot.failedTasks
			+ ", coarseReroutes="
			+ snapshot.coarseFillReroutedTasks
			+ ", coarsePreserves="
			+ snapshot.coarseFillPreservedTasks
			+ ", submittedByDetail="
			+ describeBuckets(submittedByDetail)
			+ ", startedByDetail="
			+ describeBuckets(startedByDetail)
			+ ", completedByDetail="
			+ describeBuckets(completedByDetail)
			+ "]";
	}

	public static boolean shouldKeepFillPresentation() {
		QueueSnapshot snapshot = lastQueueSnapshot;
		if (!snapshot.available) {
			return true;
		}
		if (PauCClientFrontierWarmupManager.shouldHoldPresentationForCoverage()) {
			return true;
		}

		long completed = completedTasks.get();
		int backlog = snapshot.waitingTasks + snapshot.inProgressTasks;
		int minCompletedTasks = readInt(FILL_PRESENTATION_MIN_COMPLETED_TASKS_PROPERTY, 96, 8, 4096);
		if (completed < minCompletedTasks && backlog > 0) {
			return true;
		}
		if (snapshot.pendingChunks > 0 || snapshot.pendingTasks > 0) {
			return true;
		}

		int backlogLimit = readInt(FILL_PRESENTATION_BACKLOG_TASKS_PROPERTY, 256, 16, 8192);
		return backlog > backlogLimit;
	}

	public static String describeFillPresentationState() {
		QueueSnapshot snapshot = lastQueueSnapshot;
		if (!snapshot.available) {
			return "fillPresentation[hold=true, reason=queue-unavailable]";
		}

		return "fillPresentation[hold="
			+ shouldKeepFillPresentation()
			+ ", completed="
			+ snapshot.completedTasks
			+ ", pendingTasks="
			+ snapshot.pendingTasks
			+ ", pendingChunks="
			+ snapshot.pendingChunks
			+ ", backlog="
			+ (snapshot.waitingTasks + snapshot.inProgressTasks)
			+ ", coverageHold="
			+ PauCClientFrontierWarmupManager.shouldHoldPresentationForCoverage()
			+ "]";
	}

	public static double backlogPressure() {
		QueueSnapshot snapshot = lastQueueSnapshot;
		if (!snapshot.available) {
			return 0.0D;
		}

		double taskPressure = clamp01((snapshot.pendingTasks - 48) / 384.0D);
		double chunkPressure = clamp01((snapshot.pendingChunks - 192) / 2048.0D);
		double latencyPressure = snapshot.rollingAverageMs > 0.0D
			? clamp01((snapshot.rollingAverageMs - 220.0D) / 480.0D)
			: 0.0D;
		double backlogPressure = clamp01((snapshot.waitingTasks + snapshot.inProgressTasks - 24) / 192.0D);
		return clamp01((taskPressure * 0.34D) + (chunkPressure * 0.28D) + (latencyPressure * 0.20D) + (backlogPressure * 0.18D));
	}

	public static void resetSession() {
		submittedTasks.set(0L);
		startedTasks.set(0L);
		completedTasks.set(0L);
		failedTasks.set(0L);
		splitTasks.set(0L);
		coarseFillReroutedTasks.set(0L);
		coarseFillPreservedTasks.set(0L);
		coarseFillDecisionCounter.set(0L);
		for (int i = 0; i < DETAIL_BUCKETS; i++) {
			submittedByDetail.set(i, 0L);
			startedByDetail.set(i, 0L);
			completedByDetail.set(i, 0L);
		}
		lastQueueSnapshot = QueueSnapshot.unavailable();
	}

	private static void increment(AtomicLongArray buckets, byte detailLevel) {
		int bucket = Byte.toUnsignedInt(detailLevel);
		if (bucket >= DETAIL_BUCKETS) {
			bucket = DETAIL_BUCKETS - 1;
		}
		buckets.incrementAndGet(bucket);
	}

	private static String describeBuckets(AtomicLongArray buckets) {
		StringBuilder builder = new StringBuilder();
		for (int i = 0; i < DETAIL_BUCKETS; i++) {
			long value = buckets.get(i);
			if (value <= 0L) {
				continue;
			}
			if (builder.length() > 0) {
				builder.append('/');
			}
			builder.append(i).append(':').append(value);
		}
		return builder.length() > 0 ? builder.toString() : "-";
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

	private static boolean shouldRerouteThisFillRequest(boolean shaderFallbackFill) {
		int window = readInt(COARSE_FILL_BALANCE_WINDOW_PROPERTY, 16, 4, 64);
		boolean catchupFill = shaderFallbackFill || PauCClientChunkPriorityScorer.isMovementCatchupActive();
		double defaultRatio = catchupFill ? 0.92D : 0.84D;
		double ratio = readDouble(
			catchupFill ? COARSE_FILL_CATCHUP_REROUTE_RATIO_PROPERTY : COARSE_FILL_REROUTE_RATIO_PROPERTY,
			defaultRatio,
			0.10D,
			0.95D
		);
		int coarseSlots = Math.max(1, Math.min(window - 1, (int) Math.round(window * ratio)));
		long slot = Math.floorMod(coarseFillDecisionCounter.getAndIncrement(), window);
		return slot < coarseSlots;
	}

	private static double readDouble(String key, double fallback, double min, double max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Double.parseDouble(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue.trim());
	}

	private static double clamp01(double value) {
		return Math.max(0.0D, Math.min(1.0D, value));
	}

	private record QueueSnapshot(
		boolean available,
		int waitingTasks,
		int inProgressTasks,
		int queuedChunks,
		int rawRemainingTasks,
		int rawRemainingChunks,
		int pendingTasks,
		int pendingChunks,
		byte lowestDetail,
		byte highestDetail,
		double rollingAverageMs,
		long submittedTasks,
		long startedTasks,
		long completedTasks,
		long failedTasks,
		long splitTasks,
		long coarseFillReroutedTasks,
		long coarseFillPreservedTasks
	) {
		private static QueueSnapshot unavailable() {
			return new QueueSnapshot(false, 0, 0, 0, 0, 0, 0, 0, (byte) -1, (byte) -1, -1.0D, 0L, 0L, 0L, 0L, 0L, 0L, 0L);
		}

		private String describe() {
			if (!available) {
				return "available=false";
			}
			return "available=true"
				+ ", waiting="
				+ waitingTasks
				+ ", inProgress="
				+ inProgressTasks
				+ ", queuedChunks="
				+ queuedChunks
				+ ", remainingTasks="
				+ pendingTasks
				+ (pendingTasks != rawRemainingTasks ? "(raw=" + rawRemainingTasks + ")" : "")
				+ ", remainingChunks="
				+ pendingChunks
				+ (pendingChunks != rawRemainingChunks ? "(raw=" + rawRemainingChunks + ")" : "")
				+ ", detail="
				+ Byte.toUnsignedInt(lowestDetail)
				+ "-"
				+ Byte.toUnsignedInt(highestDetail)
				+ ", avgChunkMs="
				+ (rollingAverageMs >= 0.0D ? String.format(Locale.ROOT, "%.1f", rollingAverageMs) : "-")
				;
		}
	}
}
