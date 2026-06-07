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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class PauCCudaWorker {
	private static final String ENABLED_PROPERTY = "pauc.client.cuda.workerSelfTest";
	private static final String CUDA_AVAILABLE_PROPERTY = "pauc.client.cuda.available";
	private static final String TERRAIN_ENABLED_PROPERTY = "pauc.client.cuda.terrainSeamAveraging";
	private static final String TERRAIN_INTERVAL_MS_PROPERTY = "pauc.client.cuda.terrainSeamMinIntervalMs";
	private static final String TERRAIN_VALIDATION_EPSILON_PROPERTY = "pauc.client.cuda.terrainValidationEpsilon";
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
	private static final AtomicLong TERRAIN_CPU_ROUTES = new AtomicLong();
	private static final AtomicLong TERRAIN_CUDA_ROUTES = new AtomicLong();
	private static final AtomicLong TERRAIN_VANILLA_CPU_ROUTES = new AtomicLong();
	private static final AtomicLong TERRAIN_VANILLA_CUDA_ROUTES = new AtomicLong();
	private static final AtomicLong TERRAIN_SHADER_CPU_ROUTES = new AtomicLong();
	private static final AtomicLong TERRAIN_SHADER_CUDA_ROUTES = new AtomicLong();
	private static final AtomicLong TERRAIN_COST_SAMPLES = new AtomicLong();
	private static final AtomicLong TERRAIN_CUDA_COST_MICROS = new AtomicLong();
	private static final AtomicLong TERRAIN_CPU_COST_MICROS = new AtomicLong();
	private static volatile WorkerState lastState = WorkerState.unavailable("not-run");
	private static volatile CudaRuntime runtime;
	private static volatile boolean selfTestAttempted;
	private static final Set<String> TERRAIN_AUTO_DISABLED_PROFILES = ConcurrentHashMap.newKeySet();
	private static volatile long lastTerrainLaunchNs;
	private static volatile String lastTerrainStatus = "not-run";
	private static volatile String lastTerrainProfile = "unknown";
	private static volatile int lastTerrainBatchSize;

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
			lastTerrainStatus = "terrain-cpu:small-batch:" + profile.id() + "/" + sums.length + "<" + profile.minUsefulBatch();
			return PauCLodCudaBridge.Result.unavailable(lastTerrainStatus, cpuFallback);
		}
		if (isTerrainAutoDisabledForBatch(sums.length, profile)) {
			CUDA_AUTO_DISABLED_CALLS.incrementAndGet();
			recordTerrainCpuRoute(profile);
			lastTerrainStatus = terrainAutoDisabledStatus(profile);
			return PauCLodCudaBridge.Result.unavailable(lastTerrainStatus, cpuFallback);
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
			+ ", lastTerrain="
			+ lastTerrainStatus
			+ "]";
	}

	public static int preferredTerrainBatchSize() {
		return currentTerrainProfile().minUsefulBatch();
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
		TERRAIN_CPU_ROUTES.set(0L);
		TERRAIN_CUDA_ROUTES.set(0L);
		TERRAIN_VANILLA_CPU_ROUTES.set(0L);
		TERRAIN_VANILLA_CUDA_ROUTES.set(0L);
		TERRAIN_SHADER_CPU_ROUTES.set(0L);
		TERRAIN_SHADER_CUDA_ROUTES.set(0L);
		TERRAIN_COST_SAMPLES.set(0L);
		TERRAIN_CUDA_COST_MICROS.set(0L);
		TERRAIN_CPU_COST_MICROS.set(0L);
		TERRAIN_AUTO_DISABLED_PROFILES.clear();
		lastTerrainLaunchNs = 0L;
		lastTerrainStatus = "not-run";
		lastTerrainProfile = "unknown";
		lastTerrainBatchSize = 0;
	}

	private static PauCLodCudaBridge.Result runTerrainSeamAverage(int[] sums, int[] counts, int samplesPerFeature, float[] cpuFallback, long cpuMicros, TerrainProfile profile) {
		CudaRuntime cudaRuntime = runtime();
		int count = sums.length;
		int featureCount = cpuFallback.length;
		long intBytes = (long) count * Integer.BYTES;
		long floatBytes = (long) featureCount * Float.BYTES;
		Memory hostSums = new Memory(intBytes);
		Memory hostCounts = new Memory(intBytes);
		Memory hostOut = new Memory(floatBytes);
		for (int index = 0; index < count; index++) {
			long offset = (long) index * Integer.BYTES;
			hostSums.setInt(offset, sums[index]);
			hostCounts.setInt(offset, counts[index]);
		}

		long started = System.nanoTime();
		long transferNs = 0L;
		CudaDriver cuda = cudaRuntime.cuda();
		synchronized (RUNTIME_LOCK) {
			check(cuda, cuda.cuCtxSetCurrent(cudaRuntime.context()), "cuCtxSetCurrent");
			TerrainBuffers buffers = cudaRuntime.terrainBuffers();
			buffers.ensureCapacity(cuda, count);

			long transferStarted = System.nanoTime();
			check(cuda, cuda.cuMemcpyHtoD_v2(buffers.deviceSums(), hostSums, intBytes), "cuMemcpyHtoD(sums)");
			check(cuda, cuda.cuMemcpyHtoD_v2(buffers.deviceCounts(), hostCounts, intBytes), "cuMemcpyHtoD(counts)");
			transferNs += System.nanoTime() - transferStarted;

			FeatureKernelArgs params = featureKernelParams(buffers.deviceSums(), buffers.deviceCounts(), buffers.deviceOut(), samplesPerFeature, featureCount);
			int blockSize = 32;
			int gridSize = Math.max(1, (featureCount + blockSize - 1) / blockSize);
			check(cuda, cuda.cuLaunchKernel(cudaRuntime.seamFeatureAverageFunction(), gridSize, 1, 1, blockSize, 1, 1, 0, Pointer.NULL, params.params(), Pointer.NULL), "cuLaunchKernel(seam-feature)");
			check(cuda, cuda.cuCtxSynchronize(), "cuCtxSynchronize(seam)");

			transferStarted = System.nanoTime();
			check(cuda, cuda.cuMemcpyDtoH_v2(hostOut, buffers.deviceOut(), floatBytes), "cuMemcpyDtoH(seam)");
			transferNs += System.nanoTime() - transferStarted;
		}

		long elapsedMicros = Math.max(1L, (System.nanoTime() - started) / 1_000L);
		long transferMicros = Math.max(0L, transferNs / 1_000L);
		recordCudaJob(true, elapsedMicros, transferMicros, profile);
		recordTerrainCost(count, elapsedMicros, cpuMicros, profile);

		if (featureCount > 1) {
			CUDA_TERRAIN_FEATURE_BATCHES.incrementAndGet();
		}
		float[] cudaHeights = new float[featureCount];
		float maxError = 0.0F;
		for (int index = 0; index < featureCount; index++) {
			float actual = hostOut.getFloat((long) index * Float.BYTES);
			cudaHeights[index] = actual;
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

				CudaRuntime created = new CudaRuntime(cuda, context, module, vectorFunctionRef.getValue(), seamFunctionRef.getValue(), seamFeatureFunctionRef.getValue(), new TerrainBuffers());
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
		return profile.routeSmallBatchToCpu() && count < profile.minUsefulBatch();
	}

	private static void recordTerrainCost(int count, long cudaMicros, long cpuMicros, TerrainProfile profile) {
		TERRAIN_COST_SAMPLES.incrementAndGet();
		TERRAIN_CUDA_COST_MICROS.addAndGet(Math.max(1L, cudaMicros));
		TERRAIN_CPU_COST_MICROS.addAndGet(Math.max(1L, cpuMicros));
		if (count > profile.minUsefulBatch()) {
			return;
		}

		long samples = TERRAIN_COST_SAMPLES.get();
		int requiredSamples = readInt(TERRAIN_PROFIT_SAMPLES_PROPERTY, 8, 2, 256);
		if (samples < requiredSamples) {
			return;
		}

		long averageCuda = average(TERRAIN_CUDA_COST_MICROS.get(), samples);
		long averageCpu = Math.max(1L, average(TERRAIN_CPU_COST_MICROS.get(), samples));
		double maxRatio = readFloat(TERRAIN_PROFIT_MAX_RATIO_PROPERTY, 2.5F, 1.0F, 32.0F);
		long minUsefulGpuMicros = readLong(TERRAIN_PROFIT_MIN_GPU_MICROS_PROPERTY, 250L, 10L, 20_000L);
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
		if (!shaderActive) {
			int defaultMinBatch = readInt(TERRAIN_MIN_USEFUL_BATCH_PROPERTY, 192, 8, 4096);
			long defaultInterval = readLong(TERRAIN_INTERVAL_MS_PROPERTY, 160L, 0L, 5_000L);
			return new TerrainProfile(
				"shader-off",
				false,
				readInt(VANILLA_TERRAIN_MIN_USEFUL_BATCH_PROPERTY, defaultMinBatch, 8, 4096),
				readLong(VANILLA_TERRAIN_INTERVAL_MS_PROPERTY, defaultInterval, 0L, 5_000L),
				readBoolean(VANILLA_TERRAIN_CPU_SMALL_BATCH_PROPERTY, readBoolean(TERRAIN_CPU_SMALL_BATCH_PROPERTY, true))
			);
		}

		PauCLodShaderRuntime.Pressure pressure = PauCLodShaderRuntime.pressure();
		int baseMinBatch = switch (pressure) {
			case RELIEF -> 72;
			case BALANCED -> 48;
			case HEADROOM -> 36;
			default -> 48;
		};
		long baseIntervalMillis = switch (pressure) {
			case RELIEF -> 110L;
			case BALANCED -> 75L;
			case HEADROOM -> 45L;
			default -> 75L;
		};
		String pressureId = pressure == PauCLodShaderRuntime.Pressure.OFF
			? "startup"
			: pressure.name().toLowerCase(Locale.ROOT);
		String shaderMode = switch (PauCLodShaderContext.effectiveDhMode()) {
			case EXPLICIT_NATIVE -> "native-explicit";
			case SYNTHETIC_NATIVE -> "native-synthetic";
			case FALLBACK -> "fallback";
			default -> "generic";
		};
		return new TerrainProfile(
			"shader-" + pressureId + "-" + shaderMode,
			true,
			readInt(SHADER_TERRAIN_MIN_USEFUL_BATCH_PROPERTY, baseMinBatch, 8, 4096),
			readLong(SHADER_TERRAIN_INTERVAL_MS_PROPERTY, baseIntervalMillis, 0L, 5_000L),
			readBoolean(SHADER_TERRAIN_CPU_SMALL_BATCH_PROPERTY, true)
		);
	}

	private static long average(long total, long count) {
		return count <= 0L ? 0L : Math.max(0L, Math.round(total / (double) count));
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

		int cuMemcpyHtoD_v2(long devicePointer, Pointer hostPointer, long bytes);

		int cuMemcpyDtoH_v2(Pointer hostPointer, long devicePointer, long bytes);

		int cuLaunchKernel(Pointer function, int gridDimX, int gridDimY, int gridDimZ, int blockDimX, int blockDimY, int blockDimZ, int sharedMemBytes, Pointer stream, Pointer kernelParams, Pointer extra);

		int cuCtxSynchronize();

		int cuGetErrorName(int error, PointerByReference name);

		int cuGetErrorString(int error, PointerByReference description);
	}

	private record TerrainProfile(String id, boolean shaderActive, int minUsefulBatch, long minIntervalMillis, boolean routeSmallBatchToCpu) {
	}

	private static final class TerrainBuffers {
		private int capacity;
		private long deviceSums;
		private long deviceCounts;
		private long deviceOut;

		private void ensureCapacity(CudaDriver cuda, int count) {
			if (count <= capacity && deviceSums != 0L && deviceCounts != 0L && deviceOut != 0L) {
				return;
			}

			release(cuda);
			long intBytes = (long) count * Integer.BYTES;
			long floatBytes = (long) count * Float.BYTES;
			long newSums = 0L;
			long newCounts = 0L;
			long newOut = 0L;
			try {
				LongByReference sumsRef = new LongByReference();
				LongByReference countsRef = new LongByReference();
				LongByReference outRef = new LongByReference();
				check(cuda, cuda.cuMemAlloc_v2(sumsRef, intBytes), "cuMemAlloc(sums-buffer)");
				check(cuda, cuda.cuMemAlloc_v2(countsRef, intBytes), "cuMemAlloc(counts-buffer)");
				check(cuda, cuda.cuMemAlloc_v2(outRef, floatBytes), "cuMemAlloc(out-buffer)");
				newSums = sumsRef.getValue();
				newCounts = countsRef.getValue();
				newOut = outRef.getValue();
				deviceSums = newSums;
				deviceCounts = newCounts;
				deviceOut = newOut;
				capacity = count;
				CUDA_BUFFER_ALLOCS.incrementAndGet();
			} catch (RuntimeException | Error error) {
				tryFree(cuda, newSums);
				tryFree(cuda, newCounts);
				tryFree(cuda, newOut);
				capacity = 0;
				deviceSums = 0L;
				deviceCounts = 0L;
				deviceOut = 0L;
				throw error;
			}
		}

		private void release(CudaDriver cuda) {
			tryFree(cuda, deviceSums);
			tryFree(cuda, deviceCounts);
			tryFree(cuda, deviceOut);
			capacity = 0;
			deviceSums = 0L;
			deviceCounts = 0L;
			deviceOut = 0L;
		}

		private long deviceSums() {
			return deviceSums;
		}

		private long deviceCounts() {
			return deviceCounts;
		}

		private long deviceOut() {
			return deviceOut;
		}
	}

	private record CudaRuntime(CudaDriver cuda, Pointer context, Pointer module, Pointer vectorAddFunction, Pointer seamAverageFunction, Pointer seamFeatureAverageFunction, TerrainBuffers terrainBuffers) {
	}

	private record KernelArgs(Memory params, Memory argA, Memory argB, Memory argOut, Memory argCount) {
	}

	private record FeatureKernelArgs(Memory params, Memory argSums, Memory argCounts, Memory argOut, Memory argSamplesPerFeature, Memory argFeatureCount) {
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
