package fr.hoyatla.pauc.platform.forge.client;

import com.sun.jna.Library;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.ptr.LongByReference;
import com.sun.jna.ptr.PointerByReference;
import fr.hoyatla.pauc.lod.PauCLodCudaBridge;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderRuntime;
import fr.hoyatla.pauc.lod.PauCTerrainGeneratorDetector;
import fr.hoyatla.pauc.lod.PauCVillagePerformanceDiagnostics;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class PauCCudaWorker {
	private static final String ENABLED_PROPERTY = "pauc.client.cuda.workerSelfTest";
	private static final String CUDA_AVAILABLE_PROPERTY = "pauc.client.cuda.available";
	private static final String TERRAIN_ENABLED_PROPERTY = "pauc.client.cuda.terrainSeamAveraging";
	private static final String TERRAIN_INTERVAL_MS_PROPERTY = "pauc.client.cuda.terrainSeamMinIntervalMs";
	private static final String TERRAIN_VALIDATION_EPSILON_PROPERTY = "pauc.client.cuda.terrainValidationEpsilon";
	private static final String TERRAIN_ASYNC_ENABLED_PROPERTY = "pauc.client.cuda.terrainAsyncQueue";
	private static final String TERRAIN_MIN_USEFUL_BATCH_PROPERTY = "pauc.client.cuda.terrainMinUsefulBatch";
	private static final String TERRAIN_PROFIT_SAMPLES_PROPERTY = "pauc.client.cuda.terrainProfitSamples";
	private static final String TERRAIN_PROFIT_MAX_RATIO_PROPERTY = "pauc.client.cuda.terrainProfitMaxRatio";
	private static final String TERRAIN_PROFIT_MIN_GPU_MICROS_PROPERTY = "pauc.client.cuda.terrainProfitMinGpuMicros";
	private static final String TERRAIN_CPU_SMALL_BATCH_PROPERTY = "pauc.client.cuda.terrainCpuSmallBatch";
	private static final String VANILLA_TERRAIN_MIN_USEFUL_BATCH_PROPERTY = "pauc.client.cuda.vanillaTerrainMinUsefulBatch";
	private static final String VANILLA_TERRAIN_INTERVAL_MS_PROPERTY = "pauc.client.cuda.vanillaTerrainSeamMinIntervalMs";
	private static final String VANILLA_TERRAIN_CPU_SMALL_BATCH_PROPERTY = "pauc.client.cuda.vanillaTerrainCpuSmallBatch";
	private static final String SHADER_TERRAIN_MIN_USEFUL_BATCH_PROPERTY = "pauc.client.cuda.shaderTerrainMinUsefulBatch";
	private static final String SHADER_TERRAIN_INTERVAL_MS_PROPERTY = "pauc.client.cuda.shaderTerrainSeamMinIntervalMs";
	private static final String SHADER_TERRAIN_CPU_SMALL_BATCH_PROPERTY = "pauc.client.cuda.shaderTerrainCpuSmallBatch";
	private static final String WORLD_CACHE_COALESCER_ENABLED_PROPERTY = "pauc.lod.cuda.worldCacheCoalescer";
	private static final String WORLD_CACHE_COALESCER_MIN_FEATURES_PROPERTY = "pauc.lod.cuda.worldCacheCoalescerMinFeatures";
	private static final String WORLD_CACHE_COALESCER_HOT_RESTORE_SCALE_PROPERTY = "pauc.lod.cuda.worldCacheCoalescerHotRestoreScale";
	private static final String BULK_RESTORE_ENABLED_PROPERTY = "pauc.lod.cuda.bulkRestore";
	private static final String BULK_RESTORE_VALIDATED_PROPERTY = "pauc.lod.cuda.bulkRestoreValidated";
	private static final String VANILLA_MESHER_ENABLED_PROPERTY = "pauc.lod.cuda.vanillaMesher";
	private static final String VANILLA_MESHER_VALIDATED_PROPERTY = "pauc.lod.cuda.vanillaMesherValidated";
	private static final String WORLDGEN_SUPPORT_ENABLED_PROPERTY = "pauc.lod.cuda.worldgenSupport";
	private static final String WORLDGEN_SUPPORT_VALIDATED_PROPERTY = "pauc.lod.cuda.worldgenSupportValidated";
	private static final String HORDE_FLOW_ENABLED_PROPERTY = "pauc.lod.cuda.hordeFlow";
	private static final String HORDE_FLOW_VALIDATED_PROPERTY = "pauc.lod.cuda.hordeFlowValidated";
	private static final int CUDA_SUCCESS = 0;
	private static final Object RUNTIME_LOCK = new Object();
	private static final AtomicLong CUDA_JOBS = new AtomicLong();
	private static final AtomicLong CUDA_TERRAIN_JOBS = new AtomicLong();
	private static final AtomicLong CUDA_TERRAIN_FEATURE_BATCHES = new AtomicLong();
	private static final AtomicLong CUDA_MICROS = new AtomicLong();
	private static final AtomicLong CUDA_TRANSFER_MICROS = new AtomicLong();
	private static final AtomicLong CUDA_FALLBACKS = new AtomicLong();
	private static final AtomicLong CUDA_THROTTLES = new AtomicLong();
	private static final AtomicLong CUDA_MISMATCHES = new AtomicLong();
	private static final AtomicLong CUDA_BUFFER_ALLOCS = new AtomicLong();
	private static final AtomicLong CUDA_AUTO_DISABLED_CALLS = new AtomicLong();
	private static final AtomicLong CUDA_TERRAIN_ASYNC_SUBMITTED = new AtomicLong();
	private static final AtomicLong CUDA_TERRAIN_ASYNC_HITS = new AtomicLong();
	private static final AtomicLong CUDA_TERRAIN_ASYNC_DROPS = new AtomicLong();
	private static final AtomicLong TERRAIN_CPU_ROUTES = new AtomicLong();
	private static final AtomicLong TERRAIN_CUDA_ROUTES = new AtomicLong();
	private static final AtomicLong TERRAIN_VANILLA_CPU_ROUTES = new AtomicLong();
	private static final AtomicLong TERRAIN_VANILLA_CUDA_ROUTES = new AtomicLong();
	private static final AtomicLong TERRAIN_SHADER_CPU_ROUTES = new AtomicLong();
	private static final AtomicLong TERRAIN_SHADER_CUDA_ROUTES = new AtomicLong();
	private static final AtomicLong TERRAIN_COST_SAMPLES = new AtomicLong();
	private static final AtomicLong TERRAIN_CUDA_COST_MICROS = new AtomicLong();
	private static final AtomicLong TERRAIN_CPU_COST_MICROS = new AtomicLong();
	private static final AtomicLong WORLD_CACHE_COALESCED_BATCHES = new AtomicLong();
	private static final AtomicBoolean TERRAIN_ASYNC_IN_FLIGHT = new AtomicBoolean();
	private static final ExecutorService TERRAIN_ASYNC_EXECUTOR = Executors.newSingleThreadExecutor(new DaemonThreadFactory("PauC-CUDA-Terrain"));
	private static final TerrainAsyncScratch TERRAIN_ASYNC_SCRATCH = new TerrainAsyncScratch();
	private static volatile WorkerState lastState = WorkerState.unavailable("not-run");
	private static volatile CudaRuntime runtime;
	private static volatile boolean selfTestAttempted;
	private static final Set<String> TERRAIN_AUTO_DISABLED_PROFILES = ConcurrentHashMap.newKeySet();
	// Heavy modpacks route thousands of small batches per session through the refusal paths below;
	// caching the diagnostic status strings avoids rebuilding them (concat + average math) per call.
	private static final ConcurrentHashMap<String, String> TERRAIN_ROUTE_STATUS_CACHE = new ConcurrentHashMap<>();
	private static volatile long lastTerrainLaunchNs;
	private static volatile String lastTerrainStatus = "not-run";
	private static volatile String lastTerrainProfile = "unknown";
	private static volatile int lastTerrainBatchSize;
	private static volatile String lastAccelerationPlan = "cudaPlan[not-run]";
	private static volatile TerrainAsyncResult lastTerrainAsyncResult;

	private static final String CUDA_KERNELS_PTX = """
		.version 6.0
		.target sm_30
		.address_size 64

		.visible .entry pauc_vec_add(
			.param .u64 pauc_vec_add_param_0,
			.param .u64 pauc_vec_add_param_1,
			.param .u64 pauc_vec_add_param_2,
			.param .u32 pauc_vec_add_param_3
		)
		{
			.reg .pred %p<2>;
			.reg .f32 %f<4>;
			.reg .b32 %r<6>;
			.reg .b64 %rd<10>;

			ld.param.u64 %rd1, [pauc_vec_add_param_0];
			ld.param.u64 %rd2, [pauc_vec_add_param_1];
			ld.param.u64 %rd3, [pauc_vec_add_param_2];
			ld.param.u32 %r1, [pauc_vec_add_param_3];

			mov.u32 %r2, %ctaid.x;
			mov.u32 %r3, %ntid.x;
			mov.u32 %r4, %tid.x;
			mad.lo.s32 %r5, %r2, %r3, %r4;
			setp.ge.u32 %p1, %r5, %r1;
			@%p1 bra DONE_VEC;

			mul.wide.u32 %rd4, %r5, 4;
			add.s64 %rd5, %rd1, %rd4;
			add.s64 %rd6, %rd2, %rd4;
			add.s64 %rd7, %rd3, %rd4;
			ld.global.f32 %f1, [%rd5];
			ld.global.f32 %f2, [%rd6];
			add.f32 %f3, %f1, %f2;
			st.global.f32 [%rd7], %f3;

		DONE_VEC:
			ret;
		}

		.visible .entry pauc_seam_average(
			.param .u64 pauc_seam_average_param_0,
			.param .u64 pauc_seam_average_param_1,
			.param .u64 pauc_seam_average_param_2,
			.param .u32 pauc_seam_average_param_3
		)
		{
			.reg .pred %p<3>;
			.reg .f32 %f<4>;
			.reg .b32 %r<9>;
			.reg .b64 %rd<10>;

			ld.param.u64 %rd1, [pauc_seam_average_param_0];
			ld.param.u64 %rd2, [pauc_seam_average_param_1];
			ld.param.u64 %rd3, [pauc_seam_average_param_2];
			ld.param.u32 %r1, [pauc_seam_average_param_3];

			mov.u32 %r2, %ctaid.x;
			mov.u32 %r3, %ntid.x;
			mov.u32 %r4, %tid.x;
			mad.lo.s32 %r5, %r2, %r3, %r4;
			setp.ge.u32 %p1, %r5, %r1;
			@%p1 bra DONE_AVG;

			mul.wide.u32 %rd4, %r5, 4;
			add.s64 %rd5, %rd1, %rd4;
			add.s64 %rd6, %rd2, %rd4;
			add.s64 %rd7, %rd3, %rd4;
			ld.global.s32 %r6, [%rd5];
			ld.global.s32 %r7, [%rd6];
			setp.le.s32 %p2, %r7, 0;
			@%p2 bra ZERO_AVG;

			cvt.rn.f32.s32 %f1, %r6;
			cvt.rn.f32.s32 %f2, %r7;
			div.rn.f32 %f3, %f1, %f2;
			st.global.f32 [%rd7], %f3;
			bra DONE_AVG;

		ZERO_AVG:
			mov.u32 %r8, 0;
			cvt.rn.f32.s32 %f3, %r8;
			st.global.f32 [%rd7], %f3;

		DONE_AVG:
			ret;
		}

		.visible .entry pauc_seam_feature_average(
			.param .u64 pauc_seam_feature_average_param_0,
			.param .u64 pauc_seam_feature_average_param_1,
			.param .u64 pauc_seam_feature_average_param_2,
			.param .u32 pauc_seam_feature_average_param_3,
			.param .u32 pauc_seam_feature_average_param_4
		)
		{
			.reg .pred %p<6>;
			.reg .f32 %f<8>;
			.reg .b32 %r<14>;
			.reg .b64 %rd<10>;

			ld.param.u64 %rd1, [pauc_seam_feature_average_param_0];
			ld.param.u64 %rd2, [pauc_seam_feature_average_param_1];
			ld.param.u64 %rd3, [pauc_seam_feature_average_param_2];
			ld.param.u32 %r1, [pauc_seam_feature_average_param_3];
			ld.param.u32 %r2, [pauc_seam_feature_average_param_4];

			mov.u32 %r3, %ctaid.x;
			mov.u32 %r4, %ntid.x;
			mov.u32 %r5, %tid.x;
			mad.lo.s32 %r6, %r3, %r4, %r5;
			setp.ge.u32 %p1, %r6, %r2;
			@%p1 bra DONE_FEATURE;

			mov.u32 %r7, 0;
			mov.u32 %r8, 0;
			mov.f32 %f1, 0f00000000;

		FEATURE_LOOP:
			setp.ge.u32 %p2, %r7, %r1;
			@%p2 bra FEATURE_STORE;
			mad.lo.s32 %r9, %r6, %r1, %r7;
			mul.wide.u32 %rd4, %r9, 4;
			add.s64 %rd5, %rd1, %rd4;
			add.s64 %rd6, %rd2, %rd4;
			ld.global.s32 %r10, [%rd5];
			ld.global.s32 %r11, [%rd6];
			setp.le.s32 %p3, %r11, 0;
			@%p3 bra FEATURE_NEXT;
			cvt.rn.f32.s32 %f2, %r10;
			cvt.rn.f32.s32 %f3, %r11;
			div.rn.f32 %f4, %f2, %f3;
			add.f32 %f1, %f1, %f4;
			add.s32 %r8, %r8, 1;

		FEATURE_NEXT:
			add.s32 %r7, %r7, 1;
			bra FEATURE_LOOP;

		FEATURE_STORE:
			mul.wide.u32 %rd7, %r6, 4;
			add.s64 %rd8, %rd3, %rd7;
			setp.le.s32 %p4, %r8, 0;
			@%p4 bra FEATURE_ZERO;
			cvt.rn.f32.s32 %f5, %r8;
			div.rn.f32 %f6, %f1, %f5;
			st.global.f32 [%rd8], %f6;
			bra DONE_FEATURE;

		FEATURE_ZERO:
			mov.u32 %r12, 0;
			cvt.rn.f32.s32 %f7, %r12;
			st.global.f32 [%rd8], %f7;

		DONE_FEATURE:
			ret;
		}
		""";

	private PauCCudaWorker() {
	}

	public static WorkerState ensureSelfTest(boolean cudaRequested, boolean nvidiaVendor, boolean driverAvailable) {
		if (!cudaRequested) {
			return WorkerState.unavailable("disabled");
		}
		if (!nvidiaVendor) {
			lastState = WorkerState.unavailable("gpu-not-nvidia");
			return lastState;
		}
		if (!driverAvailable) {
			lastState = WorkerState.unavailable("driver-not-ready");
			return lastState;
		}
		if (!readBoolean(ENABLED_PROPERTY, true)) {
			lastState = WorkerState.unavailable("self-test-disabled");
			return lastState;
		}
		if (selfTestAttempted) {
			return lastState;
		}

		synchronized (PauCCudaWorker.class) {
			if (selfTestAttempted) {
				return lastState;
			}
			selfTestAttempted = true;
			lastState = runVectorAddSelfTest();
			return lastState;
		}
	}

	public static PauCLodCudaBridge.Result averageSeamHeights(int[] sums, int[] counts, float[] cpuFallback, long cpuMicros) {
		return averageSeamHeights(sums, counts, 1, cpuFallback, cpuMicros);
	}

	public static PauCLodCudaBridge.Result averageSeamHeights(int[] sums, int[] counts, int samplesPerFeature, float[] cpuFallback, long cpuMicros) {
		TerrainProfile profile = currentTerrainProfile();
		lastTerrainProfile = profile.id();
		String invalidInput = validateTerrainInput(sums, counts, samplesPerFeature, cpuFallback);
		if (invalidInput != null) {
			return terrainUnavailable(invalidInput, cpuFallback, false);
		}
		lastTerrainBatchSize = sums.length;
		if (!readBoolean(TERRAIN_ENABLED_PROPERTY, true)) {
			recordTerrainCpuRoute(profile);
			return terrainUnavailable("terrain-disabled", cpuFallback, false);
		}
		if (!readBoolean(CUDA_AVAILABLE_PROPERTY, lastState.available())) {
			recordTerrainCpuRoute(profile);
			return terrainUnavailable("runtime-disabled", cpuFallback, false);
		}
		if (!lastState.available()) {
			recordTerrainCpuRoute(profile);
			return terrainUnavailable("worker-not-ready:" + lastState.status(), cpuFallback, false);
		}
		if (shouldRouteSmallBatchToCpu(sums.length, profile)) {
			recordTerrainCpuRoute(profile);
			String status = TERRAIN_ROUTE_STATUS_CACHE.get(profile.id());
			if (status == null || !status.startsWith("terrain-cpu:")) {
				int threshold = smallBatchCpuThreshold(profile);
				status = "terrain-cpu:small-batch:" + profile.id() + "/<" + threshold + ":min=" + profile.minUsefulBatch();
				TERRAIN_ROUTE_STATUS_CACHE.put(profile.id(), status);
			}
			lastTerrainStatus = status;
			return PauCLodCudaBridge.Result.unavailable(lastTerrainStatus, cpuFallback);
		}
		if (isTerrainAutoDisabledForBatch(sums.length, profile)) {
			CUDA_AUTO_DISABLED_CALLS.incrementAndGet();
			recordTerrainCpuRoute(profile);
			String status = TERRAIN_ROUTE_STATUS_CACHE.get(profile.id());
			if (status == null || !status.startsWith("terrain-auto-disabled:")) {
				status = terrainAutoDisabledStatus(profile);
				TERRAIN_ROUTE_STATUS_CACHE.put(profile.id(), status);
			}
			lastTerrainStatus = status;
			return PauCLodCudaBridge.Result.unavailable(lastTerrainStatus, cpuFallback);
		}

		if (readBoolean(TERRAIN_ASYNC_ENABLED_PROPERTY, true)) {
			long signature = terrainSignature(sums, counts, samplesPerFeature);
			PauCLodCudaBridge.Result cachedResult = cachedTerrainAsyncResult(signature, cpuFallback.length, profile);
			if (cachedResult != null) {
				return cachedResult;
			}

			long minIntervalNs = Math.max(0L, profile.minIntervalMillis()) * 1_000_000L;
			long now = System.nanoTime();
			if (minIntervalNs > 0L && lastTerrainLaunchNs > 0L && now - lastTerrainLaunchNs < minIntervalNs) {
				CUDA_THROTTLES.incrementAndGet();
				recordTerrainCpuRoute(profile);
				lastTerrainStatus = "terrain-throttled:" + profile.id();
				return PauCLodCudaBridge.Result.unavailable(lastTerrainStatus, cpuFallback);
			}
			return queueTerrainSeamAverage(signature, sums, counts, samplesPerFeature, cpuFallback, Math.max(1L, cpuMicros), profile, now);
		}

		long minIntervalNs = Math.max(0L, profile.minIntervalMillis()) * 1_000_000L;
		long now = System.nanoTime();
		if (minIntervalNs > 0L && lastTerrainLaunchNs > 0L && now - lastTerrainLaunchNs < minIntervalNs) {
			CUDA_THROTTLES.incrementAndGet();
			recordTerrainCpuRoute(profile);
			lastTerrainStatus = "terrain-throttled:" + profile.id();
			return PauCLodCudaBridge.Result.unavailable(lastTerrainStatus, cpuFallback);
		}
		lastTerrainLaunchNs = now;

		try {
			return runTerrainSeamAverage(sums, counts, samplesPerFeature, cpuFallback, Math.max(1L, cpuMicros), profile);
		} catch (Throwable throwable) {
			return terrainUnavailable("terrain-error:" + throwable.getClass().getSimpleName(), cpuFallback, true);
		}
	}

	public static String describeState() {
		return lastState.describe();
	}

	public static String describeMetrics() {
		TerrainProfile runtimeProfile = currentTerrainProfile();
		return "cudaMetrics[jobs="
			+ CUDA_JOBS.get()
			+ ", terrainJobs="
			+ CUDA_TERRAIN_JOBS.get()
			+ ", featureBatches="
			+ CUDA_TERRAIN_FEATURE_BATCHES.get()
			+ ", micros="
			+ CUDA_MICROS.get()
			+ ", transferMicros="
			+ CUDA_TRANSFER_MICROS.get()
			+ ", fallbacks="
			+ CUDA_FALLBACKS.get()
			+ ", throttles="
			+ CUDA_THROTTLES.get()
			+ ", mismatches="
			+ CUDA_MISMATCHES.get()
			+ ", bufferAllocs="
			+ CUDA_BUFFER_ALLOCS.get()
			+ ", autoDisabled="
			+ CUDA_AUTO_DISABLED_CALLS.get()
			+ ", asyncSubmitted="
			+ CUDA_TERRAIN_ASYNC_SUBMITTED.get()
			+ ", asyncHits="
			+ CUDA_TERRAIN_ASYNC_HITS.get()
			+ ", asyncDrops="
			+ CUDA_TERRAIN_ASYNC_DROPS.get()
			+ ", asyncInFlight="
			+ TERRAIN_ASYNC_IN_FLIGHT.get()
			+ ", runtimeProfile="
			+ runtimeProfile.id()
			+ ", runtimeShader="
			+ runtimeProfile.shaderActive()
			+ ", lastWorkProfile="
			+ lastTerrainProfile
			+ ", lastBatch="
			+ lastTerrainBatchSize
			+ ", cpuRoutes="
			+ TERRAIN_CPU_ROUTES.get()
			+ ", cudaRoutes="
			+ TERRAIN_CUDA_ROUTES.get()
			+ ", vanillaCpuRoutes="
			+ TERRAIN_VANILLA_CPU_ROUTES.get()
			+ ", vanillaCudaRoutes="
			+ TERRAIN_VANILLA_CUDA_ROUTES.get()
			+ ", shaderCpuRoutes="
			+ TERRAIN_SHADER_CPU_ROUTES.get()
			+ ", shaderCudaRoutes="
			+ TERRAIN_SHADER_CUDA_ROUTES.get()
			+ ", avgCudaMicros="
			+ average(TERRAIN_CUDA_COST_MICROS.get(), TERRAIN_COST_SAMPLES.get())
			+ ", avgCpuMicros="
			+ average(TERRAIN_CPU_COST_MICROS.get(), TERRAIN_COST_SAMPLES.get())
			+ ", coalescedWorldBatches="
			+ WORLD_CACHE_COALESCED_BATCHES.get()
			+ ", "
			+ describeAccelerationPlan()
			+ ", lastTerrain="
			+ lastTerrainStatus
			+ "]";
	}

	public static int preferredTerrainBatchSize() {
		return currentTerrainProfile().minUsefulBatch();
	}

	public static int coalescedWorldCacheBatchFeatures(int requestedFeatures, int requestRadius, boolean hotRestore) {
		int sanitized = Math.max(1, requestedFeatures);
		if (!readBoolean(WORLD_CACHE_COALESCER_ENABLED_PROPERTY, true)) {
			lastAccelerationPlan = buildAccelerationPlan("coalescer-disabled", sanitized, requestRadius, hotRestore);
			return sanitized;
		}

		int samplesPerFeature = 3;
		int profileMinimum = Math.max(1, (preferredTerrainBatchSize() + samplesPerFeature - 1) / samplesPerFeature);
		int configuredMinimum = readInt(WORLD_CACHE_COALESCER_MIN_FEATURES_PROPERTY, profileMinimum, 1, 2048);
		int target = Math.max(sanitized, Math.max(profileMinimum, configuredMinimum));
		if (hotRestore) {
			float scale = readFloat(WORLD_CACHE_COALESCER_HOT_RESTORE_SCALE_PROPERTY, 1.25F, 1.0F, 3.0F);
			target = Math.max(target, (int) Math.ceil(target * scale));
		}
		if (requestRadius >= 192) {
			target = Math.max(target, 96);
		} else if (requestRadius >= 128) {
			target = Math.max(target, 64);
		}
		target = Math.min(2048, target);
		if (target != sanitized) {
			WORLD_CACHE_COALESCED_BATCHES.incrementAndGet();
		}
		lastAccelerationPlan = buildAccelerationPlan("world-cache", target, requestRadius, hotRestore);
		return target;
	}

	public static boolean isBulkRestoreGpuReady() {
		return validatedCudaPath(BULK_RESTORE_ENABLED_PROPERTY, BULK_RESTORE_VALIDATED_PROPERTY);
	}

	public static boolean isVanillaMesherGpuReady() {
		return validatedCudaPath(VANILLA_MESHER_ENABLED_PROPERTY, VANILLA_MESHER_VALIDATED_PROPERTY);
	}

	public static boolean isWorldgenSupportGpuReady() {
		return validatedCudaPath(WORLDGEN_SUPPORT_ENABLED_PROPERTY, WORLDGEN_SUPPORT_VALIDATED_PROPERTY);
	}

	public static boolean isHordeFlowGpuReady() {
		return validatedCudaPath(HORDE_FLOW_ENABLED_PROPERTY, HORDE_FLOW_VALIDATED_PROPERTY);
	}

	public static String describeAccelerationPlan() {
		lastAccelerationPlan = buildAccelerationPlan("describe", lastTerrainBatchSize, preferredTerrainBatchSize(), false);
		return lastAccelerationPlan;
	}

	public static void resetMetrics() {
		CUDA_JOBS.set(0L);
		CUDA_TERRAIN_JOBS.set(0L);
		CUDA_TERRAIN_FEATURE_BATCHES.set(0L);
		CUDA_MICROS.set(0L);
		CUDA_TRANSFER_MICROS.set(0L);
		CUDA_FALLBACKS.set(0L);
		CUDA_THROTTLES.set(0L);
		CUDA_MISMATCHES.set(0L);
		CUDA_BUFFER_ALLOCS.set(0L);
		CUDA_AUTO_DISABLED_CALLS.set(0L);
		CUDA_TERRAIN_ASYNC_SUBMITTED.set(0L);
		CUDA_TERRAIN_ASYNC_HITS.set(0L);
		CUDA_TERRAIN_ASYNC_DROPS.set(0L);
		TERRAIN_CPU_ROUTES.set(0L);
		TERRAIN_CUDA_ROUTES.set(0L);
		TERRAIN_VANILLA_CPU_ROUTES.set(0L);
		TERRAIN_VANILLA_CUDA_ROUTES.set(0L);
		TERRAIN_SHADER_CPU_ROUTES.set(0L);
		TERRAIN_SHADER_CUDA_ROUTES.set(0L);
		TERRAIN_COST_SAMPLES.set(0L);
		TERRAIN_CUDA_COST_MICROS.set(0L);
		TERRAIN_CPU_COST_MICROS.set(0L);
		WORLD_CACHE_COALESCED_BATCHES.set(0L);
		TERRAIN_AUTO_DISABLED_PROFILES.clear();
		TERRAIN_ROUTE_STATUS_CACHE.clear();
		lastTerrainLaunchNs = 0L;
		lastTerrainStatus = "not-run";
		lastTerrainProfile = "unknown";
		lastTerrainBatchSize = 0;
		lastAccelerationPlan = "cudaPlan[not-run]";
		lastTerrainAsyncResult = null;
	}

	private static PauCLodCudaBridge.Result cachedTerrainAsyncResult(long signature, int featureCount, TerrainProfile profile) {
		TerrainAsyncResult cached = lastTerrainAsyncResult;
		if (cached == null || cached.signature() != signature || cached.heights().length != featureCount) {
			return null;
		}
		long ttlMs = readLong("pauc.client.cuda.terrainAsyncCacheTtlMs", 350L, 20L, 5_000L);
		if (System.currentTimeMillis() - cached.completedAtMillis() > ttlMs) {
			return null;
		}
		CUDA_TERRAIN_ASYNC_HITS.incrementAndGet();
		lastTerrainStatus = "terrain-async-hit:" + profile.id() + "/" + cached.sampleCount() + "->" + featureCount + "/" + cached.elapsedMicros() + "us";
		return PauCLodCudaBridge.Result.available(lastTerrainStatus, Arrays.copyOf(cached.heights(), cached.heights().length));
	}

	private static PauCLodCudaBridge.Result queueTerrainSeamAverage(
		long signature,
		int[] sums,
		int[] counts,
		int samplesPerFeature,
		float[] cpuFallback,
		long cpuMicros,
		TerrainProfile profile,
		long nowNs
	) {
		if (!TERRAIN_ASYNC_IN_FLIGHT.compareAndSet(false, true)) {
			CUDA_TERRAIN_ASYNC_DROPS.incrementAndGet();
			recordTerrainCpuRoute(profile);
			lastTerrainStatus = "terrain-async-busy:" + profile.id();
			return PauCLodCudaBridge.Result.unavailable(lastTerrainStatus, cpuFallback);
		}

		TerrainAsyncPayload payload = TERRAIN_ASYNC_SCRATCH.capture(sums, counts, cpuFallback);
		lastTerrainLaunchNs = nowNs;
		CUDA_TERRAIN_ASYNC_SUBMITTED.incrementAndGet();
		try {
			TERRAIN_ASYNC_EXECUTOR.execute(() -> {
				try {
					PauCLodCudaBridge.Result result = runTerrainSeamAverage(payload.sums(), payload.counts(), samplesPerFeature, payload.cpuFallback(), cpuMicros, profile);
					if (result.available() && result.heights() != null && result.heights().length == payload.cpuFallback().length) {
						lastTerrainAsyncResult = new TerrainAsyncResult(
							signature,
							result.heights(),
							payload.sums().length,
							Math.max(1L, average(TERRAIN_CUDA_COST_MICROS.get(), TERRAIN_COST_SAMPLES.get())),
							System.currentTimeMillis()
						);
					}
				} catch (Throwable throwable) {
					terrainUnavailable("terrain-async-error:" + throwable.getClass().getSimpleName(), payload.cpuFallback(), true);
				} finally {
					TERRAIN_ASYNC_IN_FLIGHT.set(false);
				}
			});
		} catch (RuntimeException exception) {
			TERRAIN_ASYNC_IN_FLIGHT.set(false);
			return terrainUnavailable("terrain-async-submit-error:" + exception.getClass().getSimpleName(), cpuFallback, true);
		}

		recordTerrainCpuRoute(profile);
		lastTerrainStatus = "terrain-async-queued:" + profile.id() + "/" + sums.length + "->" + cpuFallback.length;
		return PauCLodCudaBridge.Result.unavailable(lastTerrainStatus, cpuFallback);
	}

	private static long terrainSignature(int[] sums, int[] counts, int samplesPerFeature) {
		long hash = 0x9E3779B97F4A7C15L;
		hash = (hash * 31L) + samplesPerFeature;
		hash = (hash * 31L) + sums.length;
		hash = (hash * 31L) + counts.length;
		hash = (hash * 31L) + Arrays.hashCode(sums);
		hash = (hash * 31L) + Arrays.hashCode(counts);
		return hash;
	}

	private static PauCLodCudaBridge.Result runTerrainSeamAverage(int[] sums, int[] counts, int samplesPerFeature, float[] cpuFallback, long cpuMicros, TerrainProfile profile) {
		CudaRuntime cudaRuntime = runtime();
		int count = sums.length;
		int featureCount = cpuFallback.length;
		long intBytes = (long) count * Integer.BYTES;
		long floatBytes = (long) featureCount * Float.BYTES;

		long started = System.nanoTime();
		long transferNs = 0L;
		CudaDriver cuda = cudaRuntime.cuda();
		float[] cudaHeights = new float[featureCount];
		check(cuda, cuda.cuCtxSetCurrent(cudaRuntime.context()), "cuCtxSetCurrent");
		TerrainBuffers buffers = cudaRuntime.terrainBuffers();
		buffers.ensureCapacity(cuda, count);
		long deviceSums = buffers.deviceInput();
		long deviceCounts = buffers.deviceInput() + intBytes;
		Pointer pinnedInput = buffers.pinnedInput();
		Pointer pinnedOut = buffers.pinnedOut();
		// One pinned, contiguous host buffer laid out as [sums | counts]; bulk-written, single DMA upload.
		pinnedInput.write(0L, sums, 0, count);
		pinnedInput.write(intBytes, counts, 0, count);

		// Queued on the dedicated CUDA terrain worker: the render thread never waits here. The stream sync remains
		// inside the worker task only, after H2D, kernel and D2H have been enqueued on the stream.
		Pointer stream = cudaRuntime.stream();
		long transferStarted = System.nanoTime();
		check(cuda, cuda.cuMemcpyHtoDAsync_v2(deviceSums, pinnedInput, 2L * intBytes, stream), "cuMemcpyHtoDAsync(input)");
		transferNs += System.nanoTime() - transferStarted;

		FeatureKernelArgs params = featureKernelParams(deviceSums, deviceCounts, buffers.deviceOut(), samplesPerFeature, featureCount);
		int blockSize = 32;
		int gridSize = Math.max(1, (featureCount + blockSize - 1) / blockSize);
		check(cuda, cuda.cuLaunchKernel(cudaRuntime.seamFeatureAverageFunction(), gridSize, 1, 1, blockSize, 1, 1, 0, stream, params.params(), Pointer.NULL), "cuLaunchKernel(seam-feature)");

		transferStarted = System.nanoTime();
		check(cuda, cuda.cuMemcpyDtoHAsync_v2(pinnedOut, buffers.deviceOut(), floatBytes, stream), "cuMemcpyDtoHAsync(seam)");
		check(cuda, cuda.cuStreamSynchronize(stream), "cuStreamSynchronize(seam)");
		transferNs += System.nanoTime() - transferStarted;
		// Bulk native->heap read of the result.
		pinnedOut.read(0L, cudaHeights, 0, featureCount);

		long elapsedMicros = Math.max(1L, (System.nanoTime() - started) / 1_000L);
		long transferMicros = Math.max(0L, transferNs / 1_000L);
		recordCudaJob(true, elapsedMicros, transferMicros, profile);
		recordTerrainCost(count, elapsedMicros, cpuMicros, profile);

		if (featureCount > 1) {
			CUDA_TERRAIN_FEATURE_BATCHES.incrementAndGet();
		}
		float maxError = 0.0F;
		for (int index = 0; index < featureCount; index++) {
			float actual = cudaHeights[index];
			float expected = cpuFallback[index];
			if (!Float.isFinite(actual)) {
				maxError = Float.POSITIVE_INFINITY;
			} else {
				maxError = Math.max(maxError, Math.abs(expected - actual));
			}
		}

		float epsilon = readFloat(TERRAIN_VALIDATION_EPSILON_PROPERTY, 0.001F, 0.0F, 1.0F);
		if (maxError > epsilon) {
			CUDA_MISMATCHES.incrementAndGet();
			return terrainUnavailable("terrain-mismatch:" + maxError, cpuFallback, true);
		}

		lastTerrainStatus = "terrain-seam-feature-passed:" + profile.id() + "/" + count + "->" + featureCount + "/" + elapsedMicros + "us";
		return PauCLodCudaBridge.Result.available(lastTerrainStatus, cudaHeights);
	}

	private static WorkerState runVectorAddSelfTest() {
		long started = System.nanoTime();
		CudaDriver cuda = null;
		Pointer context = null;
		Pointer module = null;
		long deviceA = 0L;
		long deviceB = 0L;
		long deviceOut = 0L;

		try {
			cuda = loadCudaDriver();
			check(cuda, cuda.cuInit(0), "cuInit");

			IntByReference deviceRef = new IntByReference();
			check(cuda, cuda.cuDeviceGet(deviceRef, 0), "cuDeviceGet");

			PointerByReference contextRef = new PointerByReference();
			check(cuda, cuda.cuCtxCreate_v2(contextRef, 0, deviceRef.getValue()), "cuCtxCreate");
			context = contextRef.getValue();

			PointerByReference moduleRef = new PointerByReference();
			Memory ptx = nulTerminated(CUDA_KERNELS_PTX);
			check(cuda, cuda.cuModuleLoadData(moduleRef, ptx), "cuModuleLoadData");
			module = moduleRef.getValue();

			PointerByReference functionRef = new PointerByReference();
			check(cuda, cuda.cuModuleGetFunction(functionRef, module, "pauc_vec_add"), "cuModuleGetFunction");

			int count = 1024;
			long bytes = count * Float.BYTES;
			Memory hostA = new Memory(bytes);
			Memory hostB = new Memory(bytes);
			Memory hostOut = new Memory(bytes);
			for (int index = 0; index < count; index++) {
				hostA.setFloat((long) index * Float.BYTES, index * 0.25F);
				hostB.setFloat((long) index * Float.BYTES, 2.0F + index * 0.5F);
			}

			LongByReference deviceRefA = new LongByReference();
			LongByReference deviceRefB = new LongByReference();
			LongByReference deviceRefOut = new LongByReference();
			check(cuda, cuda.cuMemAlloc_v2(deviceRefA, bytes), "cuMemAlloc(a)");
			check(cuda, cuda.cuMemAlloc_v2(deviceRefB, bytes), "cuMemAlloc(b)");
			check(cuda, cuda.cuMemAlloc_v2(deviceRefOut, bytes), "cuMemAlloc(out)");
			deviceA = deviceRefA.getValue();
			deviceB = deviceRefB.getValue();
			deviceOut = deviceRefOut.getValue();

			check(cuda, cuda.cuMemcpyHtoD_v2(deviceA, hostA, bytes), "cuMemcpyHtoD(a)");
			check(cuda, cuda.cuMemcpyHtoD_v2(deviceB, hostB, bytes), "cuMemcpyHtoD(b)");

			KernelArgs params = kernelParams(deviceA, deviceB, deviceOut, count);
			check(cuda, cuda.cuLaunchKernel(functionRef.getValue(), 4, 1, 1, 256, 1, 1, 0, Pointer.NULL, params.params(), Pointer.NULL), "cuLaunchKernel");
			check(cuda, cuda.cuCtxSynchronize(), "cuCtxSynchronize");
			check(cuda, cuda.cuMemcpyDtoH_v2(hostOut, deviceOut, bytes), "cuMemcpyDtoH(out)");

			float maxError = 0.0F;
			for (int index = 0; index < count; index++) {
				float expected = hostA.getFloat((long) index * Float.BYTES) + hostB.getFloat((long) index * Float.BYTES);
				float actual = hostOut.getFloat((long) index * Float.BYTES);
				maxError = Math.max(maxError, Math.abs(expected - actual));
			}
			if (maxError > 0.0001F) {
				return WorkerState.unavailable("self-test-mismatch:" + maxError);
			}

			long elapsedMicros = Math.max(1L, (System.nanoTime() - started) / 1_000L);
			recordCudaJob(false, elapsedMicros, 0L, null);
			return new WorkerState(true, "self-test-passed", count, elapsedMicros);
		} catch (Throwable throwable) {
			return WorkerState.unavailable("self-test-error:" + throwable.getClass().getSimpleName());
		} finally {
			tryFree(cuda, deviceA);
			tryFree(cuda, deviceB);
			tryFree(cuda, deviceOut);
			tryUnload(cuda, module);
			tryDestroy(cuda, context);
		}
	}

	private static CudaRuntime runtime() {
		CudaRuntime existing = runtime;
		if (existing != null) {
			return existing;
		}

		synchronized (RUNTIME_LOCK) {
			existing = runtime;
			if (existing != null) {
				return existing;
			}

			CudaDriver cuda = null;
			Pointer context = null;
			Pointer module = null;
			try {
				cuda = loadCudaDriver();
				check(cuda, cuda.cuInit(0), "cuInit");

				IntByReference deviceRef = new IntByReference();
				check(cuda, cuda.cuDeviceGet(deviceRef, 0), "cuDeviceGet");

				PointerByReference contextRef = new PointerByReference();
				check(cuda, cuda.cuCtxCreate_v2(contextRef, 0, deviceRef.getValue()), "cuCtxCreate(runtime)");
				context = contextRef.getValue();

				PointerByReference moduleRef = new PointerByReference();
				Memory ptx = nulTerminated(CUDA_KERNELS_PTX);
				check(cuda, cuda.cuModuleLoadData(moduleRef, ptx), "cuModuleLoadData(runtime)");
				module = moduleRef.getValue();

				PointerByReference vectorFunctionRef = new PointerByReference();
				check(cuda, cuda.cuModuleGetFunction(vectorFunctionRef, module, "pauc_vec_add"), "cuModuleGetFunction(vec)");

				PointerByReference seamFunctionRef = new PointerByReference();
				check(cuda, cuda.cuModuleGetFunction(seamFunctionRef, module, "pauc_seam_average"), "cuModuleGetFunction(seam)");

				PointerByReference seamFeatureFunctionRef = new PointerByReference();
				check(cuda, cuda.cuModuleGetFunction(seamFeatureFunctionRef, module, "pauc_seam_feature_average"), "cuModuleGetFunction(seam-feature)");

				PointerByReference streamRef = new PointerByReference();
				check(cuda, cuda.cuStreamCreate(streamRef, 0), "cuStreamCreate(runtime)");

				CudaRuntime created = new CudaRuntime(cuda, context, module, vectorFunctionRef.getValue(), seamFunctionRef.getValue(), seamFeatureFunctionRef.getValue(), streamRef.getValue(), new TerrainBuffers());
				runtime = created;
				return created;
			} catch (Throwable throwable) {
				tryUnload(cuda, module);
				tryDestroy(cuda, context);
				throw throwable;
			}
		}
	}

	private static KernelArgs kernelParams(long deviceA, long deviceB, long deviceOut, int count) {
		Memory argA = new Memory(Long.BYTES);
		Memory argB = new Memory(Long.BYTES);
		Memory argOut = new Memory(Long.BYTES);
		Memory argCount = new Memory(Integer.BYTES);
		argA.setLong(0L, deviceA);
		argB.setLong(0L, deviceB);
		argOut.setLong(0L, deviceOut);
		argCount.setInt(0L, count);

		Memory params = new Memory((long) Native.POINTER_SIZE * 4L);
		params.setPointer(0L, argA);
		params.setPointer((long) Native.POINTER_SIZE, argB);
		params.setPointer((long) Native.POINTER_SIZE * 2L, argOut);
		params.setPointer((long) Native.POINTER_SIZE * 3L, argCount);
		return new KernelArgs(params, argA, argB, argOut, argCount);
	}

	private static FeatureKernelArgs featureKernelParams(long deviceSums, long deviceCounts, long deviceOut, int samplesPerFeature, int featureCount) {
		Memory argSums = new Memory(Long.BYTES);
		Memory argCounts = new Memory(Long.BYTES);
		Memory argOut = new Memory(Long.BYTES);
		Memory argSamplesPerFeature = new Memory(Integer.BYTES);
		Memory argFeatureCount = new Memory(Integer.BYTES);
		argSums.setLong(0L, deviceSums);
		argCounts.setLong(0L, deviceCounts);
		argOut.setLong(0L, deviceOut);
		argSamplesPerFeature.setInt(0L, samplesPerFeature);
		argFeatureCount.setInt(0L, featureCount);

		Memory params = new Memory((long) Native.POINTER_SIZE * 5L);
		params.setPointer(0L, argSums);
		params.setPointer((long) Native.POINTER_SIZE, argCounts);
		params.setPointer((long) Native.POINTER_SIZE * 2L, argOut);
		params.setPointer((long) Native.POINTER_SIZE * 3L, argSamplesPerFeature);
		params.setPointer((long) Native.POINTER_SIZE * 4L, argFeatureCount);
		return new FeatureKernelArgs(params, argSums, argCounts, argOut, argSamplesPerFeature, argFeatureCount);
	}

	private static String validateTerrainInput(int[] sums, int[] counts, int samplesPerFeature, float[] cpuFallback) {
		if (sums == null || counts == null || cpuFallback == null) {
			return "terrain-invalid-null";
		}
		if (samplesPerFeature <= 0) {
			return "terrain-invalid-feature-size";
		}
		if (sums.length <= 0 || counts.length != sums.length || sums.length % samplesPerFeature != 0 || cpuFallback.length != sums.length / samplesPerFeature) {
			return "terrain-invalid-size";
		}
		for (int count : counts) {
			if (count <= 0) {
				return "terrain-invalid-count";
			}
		}
		return null;
	}

	private static PauCLodCudaBridge.Result terrainUnavailable(String status, float[] cpuFallback, boolean countFallback) {
		if (countFallback) {
			CUDA_FALLBACKS.incrementAndGet();
		}
		lastTerrainStatus = status;
		return PauCLodCudaBridge.Result.unavailable(status, cpuFallback);
	}

	private static void recordCudaJob(boolean terrain, long elapsedMicros, long transferMicros, TerrainProfile profile) {
		CUDA_JOBS.incrementAndGet();
		CUDA_MICROS.addAndGet(Math.max(0L, elapsedMicros));
		CUDA_TRANSFER_MICROS.addAndGet(Math.max(0L, transferMicros));
		if (terrain) {
			CUDA_TERRAIN_JOBS.incrementAndGet();
			TERRAIN_CUDA_ROUTES.incrementAndGet();
			if (profile != null && profile.shaderActive()) {
				TERRAIN_SHADER_CUDA_ROUTES.incrementAndGet();
			} else {
				TERRAIN_VANILLA_CUDA_ROUTES.incrementAndGet();
			}
		}
	}

	private static void recordTerrainCpuRoute(TerrainProfile profile) {
		TERRAIN_CPU_ROUTES.incrementAndGet();
		if (profile.shaderActive()) {
			TERRAIN_SHADER_CPU_ROUTES.incrementAndGet();
		} else {
			TERRAIN_VANILLA_CPU_ROUTES.incrementAndGet();
		}
	}

	private static boolean shouldRouteSmallBatchToCpu(int count, TerrainProfile profile) {
		return profile.routeSmallBatchToCpu() && count < smallBatchCpuThreshold(profile);
	}

	private static void recordTerrainCost(int count, long cudaMicros, long cpuMicros, TerrainProfile profile) {
		TERRAIN_COST_SAMPLES.incrementAndGet();
		TERRAIN_CUDA_COST_MICROS.addAndGet(Math.max(1L, cudaMicros));
		TERRAIN_CPU_COST_MICROS.addAndGet(Math.max(1L, cpuMicros));
		if (count > profile.minUsefulBatch()) {
			return;
		}

		long samples = TERRAIN_COST_SAMPLES.get();
		int requiredSamples = readInt(TERRAIN_PROFIT_SAMPLES_PROPERTY, 12, 2, 256);
		if (samples < requiredSamples) {
			return;
		}

		long averageCuda = average(TERRAIN_CUDA_COST_MICROS.get(), samples);
		long averageCpu = Math.max(1L, average(TERRAIN_CPU_COST_MICROS.get(), samples));
		double maxRatio = readFloat(TERRAIN_PROFIT_MAX_RATIO_PROPERTY, 3.25F, 1.0F, 32.0F);
		long minUsefulGpuMicros = readLong(TERRAIN_PROFIT_MIN_GPU_MICROS_PROPERTY, 320L, 10L, 20_000L);
		if (averageCuda > Math.max(minUsefulGpuMicros, Math.round(averageCpu * maxRatio))) {
			TERRAIN_AUTO_DISABLED_PROFILES.add(profile.id());
			lastTerrainStatus = terrainAutoDisabledStatus(profile);
		}
	}

	private static boolean isTerrainAutoDisabledForBatch(int count, TerrainProfile profile) {
		return TERRAIN_AUTO_DISABLED_PROFILES.contains(profile.id()) && count <= profile.minUsefulBatch();
	}

	private static String terrainAutoDisabledStatus(TerrainProfile profile) {
		long samples = Math.max(1L, TERRAIN_COST_SAMPLES.get());
		return "terrain-auto-disabled:small-batch:" + profile.id() + "/avgCuda="
			+ average(TERRAIN_CUDA_COST_MICROS.get(), samples)
			+ "us/avgCpu="
			+ average(TERRAIN_CPU_COST_MICROS.get(), samples)
			+ "us";
	}

	private static TerrainProfile currentTerrainProfile() {
		boolean shaderActive = PauCLodShaderContext.isShaderPackInUse();
		boolean hotRestore = PauCClientFrontierWarmupManager.isHotRestoreActive();
		boolean queueResolved = PauCClientFpsGovernor.isBacklogResolved();
		boolean fastCatchup = PauCClientChunkPriorityScorer.isMovementCatchupActive();
		boolean hordePressure = PauCVillagePerformanceDiagnostics.isHordePressureActive();
		PauCTerrainGeneratorDetector.GeneratorKind terrain = PauCTerrainGeneratorDetector.currentClientKind();
		PauCTerrainGeneratorDetector.ModpackClass modpackClass = PauCTerrainGeneratorDetector.currentModpackClass();
		int terrainBatchDiscount = terrain.complexVerticalRelief() ? 24 : terrain.wideBiomeTransitions() ? 16 : 0;
		long terrainIntervalDiscount = terrain.complexVerticalRelief() ? 24L : terrain.wideBiomeTransitions() ? 16L : 0L;
		int modpackBatchDiscount = switch (modpackClass) {
			case EXTREME -> 24;
			case HEAVY -> 16;
			case MEDIUM -> 8;
			case LIGHT -> 0;
		};
		long modpackIntervalDiscount = switch (modpackClass) {
			case EXTREME -> 24L;
			case HEAVY -> 16L;
			case MEDIUM -> 8L;
			case LIGHT -> 0L;
		};
		int hordeBatchDiscount = hordePressure ? 12 : 0;
		long hordeIntervalDiscount = hordePressure ? 12L : 0L;
		boolean recoveryBias = hotRestore
			|| queueResolved
			|| fastCatchup
			|| PauCClientFluidityState.lastSnapshot().band() == PauCClientFluidityState.Band.RECOVERY;
		if (!shaderActive) {
			int defaultMinBatch = readInt(TERRAIN_MIN_USEFUL_BATCH_PROPERTY, 160, 8, 4096);
			long defaultInterval = readLong(TERRAIN_INTERVAL_MS_PROPERTY, 160L, 0L, 5_000L);
			defaultMinBatch = Math.max(24, defaultMinBatch - terrainBatchDiscount - modpackBatchDiscount - hordeBatchDiscount);
			defaultInterval = Math.max(8L, defaultInterval - terrainIntervalDiscount - modpackIntervalDiscount - hordeIntervalDiscount);
			if (recoveryBias) {
				defaultMinBatch = Math.max(64, defaultMinBatch / 2);
				defaultInterval = Math.max(20L, defaultInterval / 2L);
			}
			if (queueResolved || fastCatchup) {
				defaultMinBatch = Math.max(24, Math.min(defaultMinBatch, readInt("pauc.cuda.vanillaTerrainResolvedMinUsefulBatch", 48, 8, 4096)));
				defaultInterval = Math.max(8L, Math.min(defaultInterval, readLong("pauc.cuda.vanillaTerrainResolvedIntervalMs", 20L, 0L, 5_000L)));
			}
			return new TerrainProfile(
				(queueResolved ? "shader-off-resolved" : recoveryBias ? "shader-off-recovery" : "shader-off")
					+ (hordePressure ? "-horde" : "")
					+ "-" + terrain.id() + "-" + modpackClass.id(),
				false,
				readInt(VANILLA_TERRAIN_MIN_USEFUL_BATCH_PROPERTY, defaultMinBatch, 8, 4096),
				readLong(VANILLA_TERRAIN_INTERVAL_MS_PROPERTY, defaultInterval, 0L, 5_000L),
				readBoolean(VANILLA_TERRAIN_CPU_SMALL_BATCH_PROPERTY, readBoolean(TERRAIN_CPU_SMALL_BATCH_PROPERTY, !(recoveryBias || queueResolved)))
			);
		}

		PauCLodShaderRuntime.Pressure pressure = PauCLodShaderRuntime.pressure();
		int baseMinBatch = switch (pressure) {
			case RELIEF -> 60;
			case BALANCED -> 40;
			case HEADROOM -> 28;
			default -> 40;
		};
		long baseIntervalMillis = switch (pressure) {
			case RELIEF -> 110L;
			case BALANCED -> 75L;
			case HEADROOM -> 45L;
			default -> 75L;
		};
		if (recoveryBias) {
			baseMinBatch = Math.max(24, baseMinBatch - 16);
			baseIntervalMillis = Math.max(15L, baseIntervalMillis / 2L);
		}
		baseMinBatch = Math.max(12, baseMinBatch - (terrainBatchDiscount / 2) - (modpackBatchDiscount / 2) - (hordeBatchDiscount / 2));
		baseIntervalMillis = Math.max(8L, baseIntervalMillis - (terrainIntervalDiscount / 2L) - (modpackIntervalDiscount / 2L) - (hordeIntervalDiscount / 2L));
		if (queueResolved || fastCatchup) {
			baseMinBatch = Math.max(12, Math.min(baseMinBatch, readInt("pauc.cuda.shaderTerrainResolvedMinUsefulBatch", 20, 8, 4096)));
			baseIntervalMillis = Math.max(8L, Math.min(baseIntervalMillis, readLong("pauc.cuda.shaderTerrainResolvedIntervalMs", 16L, 0L, 5_000L)));
		}
		String pressureId = pressure == PauCLodShaderRuntime.Pressure.OFF
			? "startup"
			: pressure.name().toLowerCase(Locale.ROOT);
		if (queueResolved) {
			pressureId += "-resolved";
		}
		if (recoveryBias) {
			pressureId += "-recovery";
		}
		if (hordePressure) {
			pressureId += "-horde";
		}
		String shaderMode = switch (PauCLodShaderContext.effectiveDhMode()) {
			case EXPLICIT_NATIVE -> "native-explicit";
			case SYNTHETIC_NATIVE -> "native-synthetic";
			case FALLBACK -> "fallback";
			default -> "generic";
		};
		return new TerrainProfile(
			"shader-" + pressureId + "-" + shaderMode + "-" + terrain.id() + "-" + modpackClass.id(),
			true,
			readInt(SHADER_TERRAIN_MIN_USEFUL_BATCH_PROPERTY, baseMinBatch, 8, 4096),
			readLong(SHADER_TERRAIN_INTERVAL_MS_PROPERTY, baseIntervalMillis, 0L, 5_000L),
			readBoolean(SHADER_TERRAIN_CPU_SMALL_BATCH_PROPERTY, !recoveryBias)
		);
	}

	private static long average(long total, long count) {
		return count <= 0L ? 0L : Math.max(0L, Math.round(total / (double) count));
	}

	private static int smallBatchCpuThreshold(TerrainProfile profile) {
		int minUsefulBatch = profile.minUsefulBatch();
		int floor = profile.shaderActive()
			? readInt("pauc.cuda.shaderTerrainSmallBatchFloor", 16, 8, 512)
			: readInt("pauc.cuda.vanillaTerrainSmallBatchFloor", 24, 8, 512);
		double scale = profile.shaderActive() ? 0.72D : 0.80D;
		boolean queueResolved = PauCClientFpsGovernor.isBacklogResolved();
		boolean catchup = PauCClientChunkPriorityScorer.isMovementCatchupActive();
		boolean hordePressure = PauCVillagePerformanceDiagnostics.isHordePressureActive();
		boolean aggressiveVanillaPrefill = !profile.shaderActive()
			&& PauCClientChunkPriorityScorer.isFpsFirstVanillaMode()
			&& PauCClientFrontierWarmupManager.isDirectHorizonFillActive()
			&& (PauCClientFrontierWarmupManager.isActiveTravelFill() || catchup);
		boolean fillCritical = PauCClientFrontierWarmupManager.shouldHoldPresentationForCoverage()
			|| catchup
			|| PauCClientFrontierWarmupManager.isHotRestoreActive()
			|| PauCClientFluidityState.lastSnapshot().band() == PauCClientFluidityState.Band.RECOVERY;
		if (fillCritical) {
			scale = Math.min(scale, profile.shaderActive()
				? readFloat("pauc.cuda.shaderTerrainFillCriticalThresholdScale", 0.60F, 0.20F, 1.0F)
				: readFloat("pauc.cuda.vanillaTerrainFillCriticalThresholdScale", 0.55F, 0.20F, 1.0F));
		}
		if (queueResolved) {
			scale = Math.min(scale, profile.shaderActive()
				? readFloat("pauc.cuda.shaderTerrainResolvedThresholdScale", 0.42F, 0.10F, 1.0F)
				: readFloat("pauc.cuda.vanillaTerrainResolvedThresholdScale", 0.34F, 0.10F, 1.0F));
		}
		if (hordePressure) {
			scale = Math.min(scale, profile.shaderActive()
				? readFloat("pauc.cuda.shaderTerrainHordeThresholdScale", 0.50F, 0.10F, 1.0F)
				: readFloat("pauc.cuda.vanillaTerrainHordeThresholdScale", 0.44F, 0.10F, 1.0F));
			floor = Math.min(floor, profile.shaderActive()
				? readInt("pauc.cuda.shaderTerrainHordeFloor", 12, 8, 512)
				: readInt("pauc.cuda.vanillaTerrainHordeFloor", 16, 8, 512));
		}
		if (aggressiveVanillaPrefill) {
			scale = Math.min(scale, readFloat("pauc.cuda.vanillaTerrainPrefillThresholdScale", 0.20F, 0.10F, 1.0F));
			floor = Math.min(floor, readInt("pauc.cuda.vanillaTerrainPrefillFloor", 10, 8, 512));
		}
		long cpuRoutes = terrainCpuRoutes(profile);
		long cudaRoutes = terrainCudaRoutes(profile);
		long routeSamples = cpuRoutes + cudaRoutes;
		if (routeSamples >= readInt("pauc.cuda.terrainAdaptiveRouteSamples", 96, 8, 8192) && cpuRoutes > cudaRoutes * 2L) {
			scale = Math.min(scale, profile.shaderActive()
				? readFloat("pauc.cuda.shaderTerrainCpuDominanceThresholdScale", 0.74F, 0.20F, 1.0F)
				: readFloat("pauc.cuda.vanillaTerrainCpuDominanceThresholdScale", 0.66F, 0.20F, 1.0F));
		}
		if (routeSamples >= readInt("pauc.cuda.terrainAdaptiveRouteSamples", 96, 8, 8192) && cudaRoutes > Math.max(24L, cpuRoutes)) {
			scale = Math.min(scale, profile.shaderActive()
				? readFloat("pauc.cuda.shaderTerrainCudaHealthyThresholdScale", 0.34F, 0.10F, 1.0F)
				: readFloat("pauc.cuda.vanillaTerrainCudaHealthyThresholdScale", 0.28F, 0.10F, 1.0F));
		}
		if (cudaRoutes >= readInt("pauc.cuda.terrainAdaptiveHealthySamples", 48, 4, 4096)
			&& CUDA_THROTTLES.get() <= Math.max(4L, cudaRoutes / 4L)) {
			scale = Math.min(scale, profile.shaderActive()
				? readFloat("pauc.cuda.shaderTerrainHealthyThresholdScale", 0.84F, 0.20F, 1.0F)
				: readFloat("pauc.cuda.vanillaTerrainHealthyThresholdScale", 0.78F, 0.20F, 1.0F));
		}
		if (queueResolved && !fillCritical) {
			floor = Math.min(floor, profile.shaderActive()
				? readInt("pauc.cuda.shaderTerrainResolvedFloor", 12, 8, 512)
				: readInt("pauc.cuda.vanillaTerrainResolvedFloor", 12, 8, 512));
		}
		int threshold = (int) Math.floor(minUsefulBatch * scale);
		return Math.max(floor, Math.min(Math.max(floor, minUsefulBatch - 1), threshold));
	}

	private static long terrainCpuRoutes(TerrainProfile profile) {
		return profile.shaderActive() ? TERRAIN_SHADER_CPU_ROUTES.get() : TERRAIN_VANILLA_CPU_ROUTES.get();
	}

	private static long terrainCudaRoutes(TerrainProfile profile) {
		return profile.shaderActive() ? TERRAIN_SHADER_CUDA_ROUTES.get() : TERRAIN_VANILLA_CUDA_ROUTES.get();
	}

	private static boolean validatedCudaPath(String enabledProperty, String validatedProperty) {
		return readBoolean(enabledProperty, false)
			&& readBoolean(validatedProperty, false)
			&& lastState.available()
			&& readBoolean(CUDA_AVAILABLE_PROPERTY, lastState.available());
	}

	private static String buildAccelerationPlan(String reason, int batchFeatures, int requestRadius, boolean hotRestore) {
		boolean cudaRuntimeReady = lastState.available() && readBoolean(CUDA_AVAILABLE_PROPERTY, lastState.available());
		boolean bulkRestore = isBulkRestoreGpuReady();
		boolean vanillaMesher = isVanillaMesherGpuReady();
		boolean worldgenSupport = isWorldgenSupportGpuReady();
		boolean hordeFlow = isHordeFlowGpuReady();
		String interop = cudaRuntimeReady ? "driver-api-copy" : "cpu-fallback";
		return "cudaPlan[reason="
			+ reason
			+ ", runtime="
			+ (cudaRuntimeReady ? "ready" : "fallback")
			+ ", residentBuffers="
			+ (runtime != null ? "ready" : "lazy")
			+ ", graphs=off"
			+ ", interop="
			+ interop
			+ ", coalescedFeatures="
			+ batchFeatures
			+ ", requestRadius="
			+ requestRadius
			+ ", hotRestore="
			+ hotRestore
			+ ", bulkRestore="
			+ pathState(BULK_RESTORE_ENABLED_PROPERTY, BULK_RESTORE_VALIDATED_PROPERTY, bulkRestore)
			+ ", vanillaMesher="
			+ pathState(VANILLA_MESHER_ENABLED_PROPERTY, VANILLA_MESHER_VALIDATED_PROPERTY, vanillaMesher)
			+ ", worldgen="
			+ pathState(WORLDGEN_SUPPORT_ENABLED_PROPERTY, WORLDGEN_SUPPORT_VALIDATED_PROPERTY, worldgenSupport)
			+ ", hordeFlow="
			+ pathState(HORDE_FLOW_ENABLED_PROPERTY, HORDE_FLOW_VALIDATED_PROPERTY, hordeFlow)
			+ "]";
	}

	private static String pathState(String enabledProperty, String validatedProperty, boolean ready) {
		if (ready) {
			return "ready";
		}
		if (!readBoolean(enabledProperty, false)) {
			return "disabled";
		}
		if (!readBoolean(validatedProperty, false)) {
			return "unvalidated";
		}
		return lastState.available() ? "blocked" : "cuda-unavailable";
	}

	private static Memory nulTerminated(String text) {
		byte[] bytes = (text + "\0").getBytes(StandardCharsets.UTF_8);
		Memory memory = new Memory(bytes.length);
		memory.write(0L, bytes, 0, bytes.length);
		return memory;
	}

	private static void trySetCurrent(CudaDriver cuda, Pointer context) {
		if (cuda != null && context != null) {
			try {
				cuda.cuCtxSetCurrent(context);
			} catch (Throwable ignored) {
			}
		}
	}

	private static void tryFree(CudaDriver cuda, long devicePointer) {
		if (cuda != null && devicePointer != 0L) {
			try {
				cuda.cuMemFree_v2(devicePointer);
			} catch (Throwable ignored) {
			}
		}
	}

	private static void tryFreeHost(CudaDriver cuda, Pointer hostPointer) {
		if (cuda != null && hostPointer != null) {
			try {
				cuda.cuMemFreeHost(hostPointer);
			} catch (Throwable ignored) {
			}
		}
	}

	private static void tryUnload(CudaDriver cuda, Pointer module) {
		if (cuda != null && module != null) {
			try {
				cuda.cuModuleUnload(module);
			} catch (Throwable ignored) {
			}
		}
	}

	private static void tryDestroy(CudaDriver cuda, Pointer context) {
		if (cuda != null && context != null) {
			try {
				cuda.cuCtxDestroy_v2(context);
			} catch (Throwable ignored) {
			}
		}
	}

	private static void check(CudaDriver cuda, int result, String operation) {
		if (result == CUDA_SUCCESS) {
			return;
		}
		throw new CudaException(operation + " failed: " + cudaError(cuda, result));
	}

	private static String cudaError(CudaDriver cuda, int result) {
		if (cuda == null) {
			return Integer.toString(result);
		}

		PointerByReference nameRef = new PointerByReference();
		PointerByReference descriptionRef = new PointerByReference();
		String name = cuda.cuGetErrorName(result, nameRef) == CUDA_SUCCESS && nameRef.getValue() != null
			? nameRef.getValue().getString(0L)
			: Integer.toString(result);
		String description = cuda.cuGetErrorString(result, descriptionRef) == CUDA_SUCCESS && descriptionRef.getValue() != null
			? descriptionRef.getValue().getString(0L)
			: "no-description";
		return name + "/" + description;
	}

	private static String cudaDriverLibraryName() {
		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		return osName.contains("win") ? "nvcuda" : "cuda";
	}

	private static CudaDriver loadCudaDriver() {
		try {
			return Native.load(cudaDriverLibraryName(), CudaDriver.class);
		} catch (Throwable firstError) {
			Path driverPath = findCudaDriverPath();
			if (driverPath == null) {
				throw firstError;
			}

			try {
				return Native.load(driverPath.toString(), CudaDriver.class);
			} catch (Throwable secondError) {
				firstError.addSuppressed(secondError);
				throw firstError;
			}
		}
	}

	private static Path findCudaDriverPath() {
		String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		if (osName.contains("win")) {
			String windir = System.getenv("WINDIR");
			if (windir == null || windir.isBlank()) {
				windir = "C:\\Windows";
			}

			Path system32 = Paths.get(windir, "System32", "nvcuda.dll");
			if (Files.isRegularFile(system32)) {
				return system32.toAbsolutePath();
			}

			Path sysWow64 = Paths.get(windir, "SysWOW64", "nvcuda.dll");
			if (Files.isRegularFile(sysWow64)) {
				return sysWow64.toAbsolutePath();
			}
		}

		Path linuxDriver = Paths.get("/usr/lib/x86_64-linux-gnu/libcuda.so.1");
		return Files.isRegularFile(linuxDriver) ? linuxDriver.toAbsolutePath() : null;
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String value = System.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value);
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String value = System.getProperty(key);
		if (value == null) {
			return clamp(fallback, min, max);
		}
		try {
			return clamp(Integer.parseInt(value.trim()), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
		}
	}

	private static long readLong(String key, long fallback, long min, long max) {
		String value = System.getProperty(key);
		if (value == null) {
			return clamp(fallback, min, max);
		}
		try {
			return clamp(Long.parseLong(value.trim()), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
		}
	}

	private static float readFloat(String key, float fallback, float min, float max) {
		String value = System.getProperty(key);
		if (value == null) {
			return clamp(fallback, min, max);
		}
		try {
			return clamp(Float.parseFloat(value.trim()), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
		}
	}

	private static long clamp(long value, long min, long max) {
		return Math.max(min, Math.min(max, value));
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static float clamp(float value, float min, float max) {
		return Math.max(min, Math.min(max, value));
	}

	private interface CudaDriver extends Library {
		int cuInit(int flags);

		int cuDeviceGet(IntByReference device, int ordinal);

		int cuCtxCreate_v2(PointerByReference context, int flags, int device);

		int cuCtxSetCurrent(Pointer context);

		int cuCtxDestroy_v2(Pointer context);

		int cuModuleLoadData(PointerByReference module, Pointer image);

		int cuModuleUnload(Pointer module);

		int cuModuleGetFunction(PointerByReference function, Pointer module, String name);

		int cuMemAlloc_v2(LongByReference devicePointer, long bytes);

		int cuMemFree_v2(long devicePointer);

		int cuMemHostAlloc(PointerByReference hostPointer, long bytes, int flags);

		int cuMemFreeHost(Pointer hostPointer);

		int cuMemcpyHtoD_v2(long devicePointer, Pointer hostPointer, long bytes);

		int cuMemcpyDtoH_v2(Pointer hostPointer, long devicePointer, long bytes);

		int cuMemcpyHtoDAsync_v2(long devicePointer, Pointer hostPointer, long bytes, Pointer stream);

		int cuMemcpyDtoHAsync_v2(Pointer hostPointer, long devicePointer, long bytes, Pointer stream);

		int cuStreamCreate(PointerByReference stream, int flags);

		int cuStreamSynchronize(Pointer stream);

		int cuStreamDestroy_v2(Pointer stream);

		int cuLaunchKernel(Pointer function, int gridDimX, int gridDimY, int gridDimZ, int blockDimX, int blockDimY, int blockDimZ, int sharedMemBytes, Pointer stream, Pointer kernelParams, Pointer extra);

		int cuCtxSynchronize();

		int cuGetErrorName(int error, PointerByReference name);

		int cuGetErrorString(int error, PointerByReference description);
	}

	private record TerrainProfile(String id, boolean shaderActive, int minUsefulBatch, long minIntervalMillis, boolean routeSmallBatchToCpu) {
	}

	private record TerrainAsyncResult(long signature, float[] heights, int sampleCount, long elapsedMicros, long completedAtMillis) {
	}

	private record TerrainAsyncPayload(int[] sums, int[] counts, float[] cpuFallback) {
	}

	private static final class TerrainAsyncScratch {
		private int[] sums = new int[0];
		private int[] counts = new int[0];
		private float[] cpuFallback = new float[0];

		private synchronized TerrainAsyncPayload capture(int[] sourceSums, int[] sourceCounts, float[] sourceFallback) {
			if (sums.length != sourceSums.length) {
				sums = new int[sourceSums.length];
			}
			if (counts.length != sourceCounts.length) {
				counts = new int[sourceCounts.length];
			}
			if (cpuFallback.length != sourceFallback.length) {
				cpuFallback = new float[sourceFallback.length];
			}

			System.arraycopy(sourceSums, 0, sums, 0, sourceSums.length);
			System.arraycopy(sourceCounts, 0, counts, 0, sourceCounts.length);
			System.arraycopy(sourceFallback, 0, cpuFallback, 0, sourceFallback.length);
			return new TerrainAsyncPayload(sums, counts, cpuFallback);
		}
	}

	private static final class TerrainBuffers {
		private int capacity;
		// Single contiguous device input buffer laid out as [sums(count) | counts(count)] so the whole input
		// uploads in ONE cuMemcpyHtoD instead of two. The kernel reads sums at the base and counts at base+count.
		private long deviceInput;
		private long deviceOut;
		// Pinned (page-locked) host staging: cuMemHostAlloc memory gives much faster H2D/D2H DMA transfers than
		// pageable memory, and is reused across jobs (grown with capacity) to avoid per-call allocation.
		private Pointer pinnedInput;
		private Pointer pinnedOut;

		private void ensureCapacity(CudaDriver cuda, int count) {
			if (count <= capacity && deviceInput != 0L && deviceOut != 0L
				&& pinnedInput != null && pinnedOut != null) {
				return;
			}

			release(cuda);
			long inputBytes = 2L * count * Integer.BYTES;
			long floatBytes = (long) count * Float.BYTES;
			long newInput = 0L;
			long newOut = 0L;
			Pointer newPinnedInput = null;
			Pointer newPinnedOut = null;
			try {
				LongByReference inputRef = new LongByReference();
				LongByReference outRef = new LongByReference();
				check(cuda, cuda.cuMemAlloc_v2(inputRef, inputBytes), "cuMemAlloc(input-buffer)");
				check(cuda, cuda.cuMemAlloc_v2(outRef, floatBytes), "cuMemAlloc(out-buffer)");
				newInput = inputRef.getValue();
				newOut = outRef.getValue();

				PointerByReference pinnedInputRef = new PointerByReference();
				PointerByReference pinnedOutRef = new PointerByReference();
				check(cuda, cuda.cuMemHostAlloc(pinnedInputRef, inputBytes, 0), "cuMemHostAlloc(input-staging)");
				check(cuda, cuda.cuMemHostAlloc(pinnedOutRef, floatBytes, 0), "cuMemHostAlloc(out-staging)");
				newPinnedInput = pinnedInputRef.getValue();
				newPinnedOut = pinnedOutRef.getValue();

				deviceInput = newInput;
				deviceOut = newOut;
				pinnedInput = newPinnedInput;
				pinnedOut = newPinnedOut;
				capacity = count;
				CUDA_BUFFER_ALLOCS.incrementAndGet();
			} catch (RuntimeException | Error error) {
				tryFree(cuda, newInput);
				tryFree(cuda, newOut);
				tryFreeHost(cuda, newPinnedInput);
				tryFreeHost(cuda, newPinnedOut);
				capacity = 0;
				deviceInput = 0L;
				deviceOut = 0L;
				pinnedInput = null;
				pinnedOut = null;
				throw error;
			}
		}

		private void release(CudaDriver cuda) {
			tryFree(cuda, deviceInput);
			tryFree(cuda, deviceOut);
			tryFreeHost(cuda, pinnedInput);
			tryFreeHost(cuda, pinnedOut);
			capacity = 0;
			deviceInput = 0L;
			deviceOut = 0L;
			pinnedInput = null;
			pinnedOut = null;
		}

		private long deviceInput() {
			return deviceInput;
		}

		private long deviceOut() {
			return deviceOut;
		}

		private Pointer pinnedInput() {
			return pinnedInput;
		}

		private Pointer pinnedOut() {
			return pinnedOut;
		}
	}

	private record CudaRuntime(CudaDriver cuda, Pointer context, Pointer module, Pointer vectorAddFunction, Pointer seamAverageFunction, Pointer seamFeatureAverageFunction, Pointer stream, TerrainBuffers terrainBuffers) {
	}

	private record KernelArgs(Memory params, Memory argA, Memory argB, Memory argOut, Memory argCount) {
	}

	private record FeatureKernelArgs(Memory params, Memory argSums, Memory argCounts, Memory argOut, Memory argSamplesPerFeature, Memory argFeatureCount) {
	}

	private static final class DaemonThreadFactory implements ThreadFactory {
		private final String name;

		private DaemonThreadFactory(String name) {
			this.name = name;
		}

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, name);
			thread.setDaemon(true);
			return thread;
		}
	}

	private static final class CudaException extends RuntimeException {
		private CudaException(String message) {
			super(message);
		}
	}

	public record WorkerState(boolean available, String status, int sampleCount, long elapsedMicros) {
		public static WorkerState unavailable(String status) {
			return new WorkerState(false, status, 0, 0L);
		}

		public String describe() {
			return "cudaWorker[available="
				+ available
				+ ", status="
				+ status
				+ ", samples="
				+ sampleCount
				+ ", micros="
				+ elapsedMicros
				+ "]";
		}
	}
}
