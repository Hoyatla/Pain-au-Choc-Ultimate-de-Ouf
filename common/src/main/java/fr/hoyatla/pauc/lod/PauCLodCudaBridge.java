package fr.hoyatla.pauc.lod;

import java.util.concurrent.atomic.AtomicReference;

public final class PauCLodCudaBridge {
	private static final AtomicReference<SeamHeightAverager> SEAM_HEIGHT_AVERAGER = new AtomicReference<>(DisabledAverager.INSTANCE);

	private PauCLodCudaBridge() {
	}

	public static void registerSeamHeightAverager(SeamHeightAverager averager) {
		SEAM_HEIGHT_AVERAGER.set(averager == null ? DisabledAverager.INSTANCE : averager);
	}

	public static Result averageSeamHeights(int[] sums, int[] counts, float[] cpuFallback) {
		return averageSeamHeights(sums, counts, cpuFallback, 0L);
	}

	public static Result averageSeamHeights(int[] sums, int[] counts, float[] cpuFallback, long cpuMicros) {
		return averageSeamHeights(sums, counts, 1, cpuFallback, cpuMicros);
	}

	public static Result averageSeamHeights(int[] sums, int[] counts, int samplesPerFeature, float[] cpuFallback, long cpuMicros) {
		return SEAM_HEIGHT_AVERAGER.get().averageSeamHeights(sums, counts, samplesPerFeature, cpuFallback, cpuMicros);
	}

	public interface SeamHeightAverager {
		Result averageSeamHeights(int[] sums, int[] counts, int samplesPerFeature, float[] cpuFallback, long cpuMicros);
	}

	private enum DisabledAverager implements SeamHeightAverager {
		INSTANCE;

		@Override
		public Result averageSeamHeights(int[] sums, int[] counts, int samplesPerFeature, float[] cpuFallback, long cpuMicros) {
			return Result.unavailable("not-registered", cpuFallback);
		}
	}

	public record Result(boolean available, String status, float[] heights) {
		public static Result available(String status, float[] heights) {
			return new Result(true, status, heights);
		}

		public static Result unavailable(String status, float[] cpuFallback) {
			return new Result(false, status, cpuFallback);
		}
	}
}
