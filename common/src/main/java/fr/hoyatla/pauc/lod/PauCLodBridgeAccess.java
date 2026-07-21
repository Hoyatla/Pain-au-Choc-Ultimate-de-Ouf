package fr.hoyatla.pauc.lod;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * DH-free facade over the embedded DH bridge for always-on callers (governors, telemetry, GPU path
 * controller, frontier warmup). PauC must run with NO Distant Horizons installed; in that world the
 * bridge class (whose constant pool references com.seibel types) must never be touched by code that
 * runs every tick — heavy modpacks ship class scanners/transformers that resolve constant pools
 * eagerly, so even guarded bridge methods proved crash-prone (NCDFE) without DH. The bridge registers
 * these hooks at bootstrap ONLY when the external DH mod is present; without DH every accessor returns
 * a safe default and the bridge class is never referenced from hot code.
 */
public final class PauCLodBridgeAccess {
	public interface CoarseFillRefreshHook {
		void refresh(double coverageRatio, int expectedCells, int coveredCells);
	}

	/**
	 * Fills PauC surface-store spans for a chunk from Distant Horizons' already-generated LOD data
	 * (approach 1 of the DH-as-data-source feature). Registered ONLY when DH is present; the filler
	 * body lives in a DH-bridge class that references com.seibel types. {@code ys}/{@code colors} are
	 * span-0 (surface top + tagged ARGB), {@code bottoms}/{@code bottomColors} are span-1 (water floor
	 * / under-canopy ground). Columns DH cannot supply are left as {@link Short#MIN_VALUE} in
	 * {@code ys} so the caller regenerates only the gaps. Returns the number of columns filled.
	 */
	public interface DhChunkFiller {
		int fill(int chunkX, int chunkZ, int step, short[] ys, int[] colors, short[] bottoms, int[] bottomColors);
	}

	private static volatile BooleanSupplier directGpuUploadActiveHook;
	private static volatile Supplier<String> gpuUploadStateHook;
	private static volatile Supplier<String> actuationStateHook;
	private static volatile Consumer<String> presentationStabilityResetHook;
	private static volatile CoarseFillRefreshHook coarseFillRefreshHook;
	private static volatile DhChunkFiller dhChunkFiller;

	/** Registered by the DH bridge at bootstrap when DH is installed. Null (no-op) without DH. */
	public static void registerDhChunkFiller(DhChunkFiller filler) {
		dhChunkFiller = filler;
	}

	/** TRUE when DH data can be read (DH present and the filler registered). */
	public static boolean isDhDataSourceAvailable() {
		return dhChunkFiller != null;
	}

	/** Fills the chunk's span arrays from DH; returns columns filled (0 without DH). */
	public static int fillChunkFromDh(int chunkX, int chunkZ, int step,
			short[] ys, int[] colors, short[] bottoms, int[] bottomColors) {
		DhChunkFiller filler = dhChunkFiller;
		return filler != null ? filler.fill(chunkX, chunkZ, step, ys, colors, bottoms, bottomColors) : 0;
	}

	private PauCLodBridgeAccess() {
	}

	public static void registerHooks(
		BooleanSupplier directGpuUploadActive,
		Supplier<String> gpuUploadState,
		Supplier<String> actuationState,
		Consumer<String> presentationStabilityReset,
		CoarseFillRefreshHook coarseFillRefresh
	) {
		directGpuUploadActiveHook = directGpuUploadActive;
		gpuUploadStateHook = gpuUploadState;
		actuationStateHook = actuationState;
		presentationStabilityResetHook = presentationStabilityReset;
		coarseFillRefreshHook = coarseFillRefresh;
	}

	public static boolean isDirectGpuUploadActive() {
		BooleanSupplier hook = directGpuUploadActiveHook;
		return hook != null && hook.getAsBoolean();
	}

	public static String describeGpuUploadState() {
		Supplier<String> hook = gpuUploadStateHook;
		return hook != null ? hook.get() : "dhGpu[dh-not-installed]";
	}

	public static String describeActuationState() {
		Supplier<String> hook = actuationStateHook;
		return hook != null ? hook.get() : "embeddedDhAct[dh-not-installed]";
	}

	public static void resetPresentationStability(String reason) {
		Consumer<String> hook = presentationStabilityResetHook;
		if (hook != null) {
			hook.accept(reason);
		}
	}

	public static void refreshRenderCacheForCoarseFill(double coverageRatio, int expectedCells, int coveredCells) {
		CoarseFillRefreshHook hook = coarseFillRefreshHook;
		if (hook != null) {
			hook.refresh(coverageRatio, expectedCells, coveredCells);
		}
	}
}
