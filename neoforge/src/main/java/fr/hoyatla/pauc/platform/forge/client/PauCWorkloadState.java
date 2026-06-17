package fr.hoyatla.pauc.platform.forge.client;

import fr.hoyatla.pauc.lod.PauCVillagePerformanceDiagnostics;

/**
 * Shared workload classification used by the FPS governor and the fluidity controller.
 * It keeps "is PauC still the bottleneck?" decisions in one place so FPS dips are not
 * interpreted differently by multiple actuators.
 */
public final class PauCWorkloadState {
	private static final String DRAINED_QUEUE_STABLE_TICKS_PROPERTY = "pauc.lod.drainedQueueStableTicks";
	private static final String DRAINED_QUEUE_RESOLVED_MAX_HEAP_RATIO_PROPERTY = "pauc.lod.drainedQueueResolvedMaxHeapRatio";
	private static final String DRAINED_QUEUE_RESOLVED_MIN_DELIVERY_RATIO_PROPERTY = "pauc.lod.drainedQueueResolvedMinDeliveryRatio";
	private static final String IDLE_QUEUE_RESOLVED_STABLE_TICKS_PROPERTY = "pauc.lod.idleQueueResolvedStableTicks";
	private static volatile Snapshot lastSnapshot = Snapshot.empty();
	private static int queueDrainedStableTicks;

	private PauCWorkloadState() {
	}

	public static Snapshot update(double queuePressure, double heapPressure, double deliveryRatio, boolean villageSeverePressure) {
		boolean queueAvailable = PauCEmbeddedLodRuntimeDiagnostics.queueAvailable();
		int pendingChunks = PauCEmbeddedLodRuntimeDiagnostics.pendingChunks();
		int pendingTasks = PauCEmbeddedLodRuntimeDiagnostics.pendingTasks();
		int backlogTasks = PauCEmbeddedLodRuntimeDiagnostics.backlogTasks();
		double avgChunkMs = PauCEmbeddedLodRuntimeDiagnostics.rollingAverageChunkMs();
		boolean frameWatchdogSpike = readBoolean("pauc.runtime.frameWatchdogSpike", false);
		boolean queueDrained = queueAvailable
			&& pendingChunks <= readInt("pauc.lod.drainedQueuePendingChunks", 0, 0, 256)
			&& backlogTasks <= readInt("pauc.lod.drainedQueueBacklogTasks", 0, 0, 64)
			&& queuePressure <= readDouble("pauc.lod.drainedQueuePressure", 0.03D, 0.0D, 0.20D);
		boolean queueNearlyDrained = queueAvailable
			&& pendingChunks <= readInt("pauc.lod.nearlyDrainedQueuePendingChunks", 96, 0, 512)
			&& backlogTasks <= readInt("pauc.lod.nearlyDrainedQueueBacklogTasks", 8, 0, 96)
			&& queuePressure <= readDouble("pauc.lod.nearlyDrainedQueuePressure", 0.08D, 0.0D, 0.30D);
		boolean queueFullyDrained = queueDrained && pendingTasks <= 0 && backlogTasks <= 0 && pendingChunks <= 0;
		updateQueueDrainState(queueDrained, queueNearlyDrained, queueFullyDrained);

		boolean coverageTelemetry = PauCClientFrontierWarmupManager.hasCoverageTelemetry();
		boolean stableCoverage = PauCClientFrontierWarmupManager.hasStablePresentationCoverage();
		boolean coverageHoldActive = PauCClientFrontierWarmupManager.isPresentationHoldActive();
		boolean idleQueueResolved = isQueueIdleResolved(frameWatchdogSpike, heapPressure, queueDrained, queueFullyDrained);
		boolean queueResolved = idleQueueResolved
			|| (queueDrained
				&& queueDrainedStableTicks >= Math.max(4, readInt(DRAINED_QUEUE_STABLE_TICKS_PROPERTY, 10, 1, 200) / 2)
				&& !frameWatchdogSpike
				&& heapPressure <= readDouble(DRAINED_QUEUE_RESOLVED_MAX_HEAP_RATIO_PROPERTY, 0.86D, 0.20D, 0.95D));
		boolean villagePressure = PauCVillagePerformanceDiagnostics.isVillagePressureActive();
		boolean backlogResolved = queueDrained
			&& !frameWatchdogSpike
			&& heapPressure <= readDouble(DRAINED_QUEUE_RESOLVED_MAX_HEAP_RATIO_PROPERTY, 0.86D, 0.20D, 0.95D)
			&& (idleQueueResolved
				|| queueResolved
				|| queueDrainedStableTicks >= readInt(DRAINED_QUEUE_STABLE_TICKS_PROPERTY, 10, 1, 200)
				|| (queueFullyDrained && stableCoverage && !coverageHoldActive))
			&& (!villagePressure || queueFullyDrained)
			&& (!villageSeverePressure || queueFullyDrained);
		boolean workloadRecovered = !frameWatchdogSpike
			&& heapPressure <= readDouble(DRAINED_QUEUE_RESOLVED_MAX_HEAP_RATIO_PROPERTY, 0.86D, 0.20D, 0.95D)
			&& (idleQueueResolved
				|| queueResolved
				|| (queueDrained && queueFullyDrained && stableCoverage && !coverageHoldActive)
				|| (backlogResolved && (!villagePressure || queueFullyDrained)))
			&& (!villageSeverePressure || queueFullyDrained && !coverageHoldActive);
		boolean paucResolved = idleQueueResolved
			|| backlogResolved
			|| workloadRecovered
			|| (queueDrained && queueFullyDrained && stableCoverage && !coverageHoldActive);
		boolean externalFpsDip = isExternalFpsDip(
			queueDrained,
			queueFullyDrained,
			backlogResolved,
			queueResolved,
			coverageTelemetry,
			stableCoverage,
			coverageHoldActive,
			frameWatchdogSpike,
			deliveryRatio,
			heapPressure,
			idleQueueResolved
		);

		Snapshot snapshot = new Snapshot(
			queueAvailable,
			pendingChunks,
			pendingTasks,
			backlogTasks,
			avgChunkMs,
			frameWatchdogSpike,
			coverageTelemetry,
			stableCoverage,
			coverageHoldActive,
			queueDrained,
			queueNearlyDrained,
			queueFullyDrained,
			queueDrainedStableTicks,
			idleQueueResolved,
			queueResolved,
			backlogResolved,
			workloadRecovered,
			paucResolved,
			externalFpsDip
		);
		lastSnapshot = snapshot;
		return snapshot;
	}

	public static Snapshot lastSnapshot() {
		return lastSnapshot;
	}

	public static void reset() {
		queueDrainedStableTicks = 0;
		lastSnapshot = Snapshot.empty();
	}

	private static void updateQueueDrainState(boolean queueDrained, boolean queueNearlyDrained, boolean queueFullyDrained) {
		if (queueFullyDrained) {
			queueDrainedStableTicks = Math.min(600, queueDrainedStableTicks + 2);
			return;
		}
		if (queueDrained) {
			queueDrainedStableTicks = Math.min(600, queueDrainedStableTicks + 1);
			return;
		}
		if (queueNearlyDrained) {
			queueDrainedStableTicks = Math.max(0, queueDrainedStableTicks - 1);
			return;
		}
		queueDrainedStableTicks = Math.max(0, queueDrainedStableTicks - 2);
	}

	private static boolean isQueueIdleResolved(boolean frameWatchdogSpike, double heapPressure, boolean queueDrained, boolean queueFullyDrained) {
		return queueDrained
			&& queueFullyDrained
			&& queueDrainedStableTicks >= readInt(IDLE_QUEUE_RESOLVED_STABLE_TICKS_PROPERTY, 12, 1, 240)
			&& !frameWatchdogSpike
			&& heapPressure <= readDouble(DRAINED_QUEUE_RESOLVED_MAX_HEAP_RATIO_PROPERTY, 0.86D, 0.20D, 0.95D);
	}

	private static boolean isExternalFpsDip(
		boolean queueDrained,
		boolean queueFullyDrained,
		boolean backlogResolved,
		boolean queueResolved,
		boolean coverageTelemetry,
		boolean stableCoverage,
		boolean coverageHoldActive,
		boolean frameWatchdogSpike,
		double deliveryRatio,
		double heapPressure,
		boolean idleQueueResolved
	) {
		if (frameWatchdogSpike) {
			return false;
		}
		if (heapPressure > readDouble("pauc.lod.externalReliefMaxHeapRatio", 0.84D, 0.20D, 0.95D)) {
			return false;
		}
		if (idleQueueResolved || backlogResolved || queueResolved) {
			return deliveryRatio >= readDouble(DRAINED_QUEUE_RESOLVED_MIN_DELIVERY_RATIO_PROPERTY, 0.46D, 0.10D, 1.10D);
		}
		if (coverageTelemetry && queueDrained && queueFullyDrained && stableCoverage && !coverageHoldActive) {
			return deliveryRatio >= readDouble(DRAINED_QUEUE_RESOLVED_MIN_DELIVERY_RATIO_PROPERTY, 0.46D, 0.10D, 1.10D);
		}
		return queueDrained
			&& deliveryRatio >= readDouble("pauc.lod.externalReliefMinDeliveryRatio", 0.74D, 0.40D, 1.10D);
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue.trim());
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return fallback;
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	private static double readDouble(String key, double fallback, double min, double max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return fallback;
		}
		try {
			return Math.max(min, Math.min(max, Double.parseDouble(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return fallback;
		}
	}

	public record Snapshot(
		boolean queueAvailable,
		int pendingChunks,
		int pendingTasks,
		int backlogTasks,
		double averageChunkMs,
		boolean frameWatchdogSpike,
		boolean coverageTelemetry,
		boolean stableCoverage,
		boolean coverageHoldActive,
		boolean queueDrained,
		boolean queueNearlyDrained,
		boolean queueFullyDrained,
		int queueDrainedStableTicks,
		boolean idleQueueResolved,
		boolean queueResolved,
		boolean backlogResolved,
		boolean workloadRecovered,
		boolean paucResolved,
		boolean externalFpsDip
	) {
		private static Snapshot empty() {
			return new Snapshot(false, 0, 0, 0, -1.0D, false, false, false, false, false, false, false, 0, false, false, false, false, false, false);
		}
	}
}
