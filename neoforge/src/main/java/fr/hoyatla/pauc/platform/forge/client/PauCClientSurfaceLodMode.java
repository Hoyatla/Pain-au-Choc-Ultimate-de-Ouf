package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.enums.config.EDhApiVerticalQuality;
import com.seibel.distanthorizons.api.enums.worldGeneration.EDhApiDistantGeneratorMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import org.slf4j.Logger;

import java.util.Locale;

public final class PauCClientSurfaceLodMode {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.lod.surfaceOnlyWhenAboveGround";
	private static final String FORCE_SURFACE_GENERATOR_PROPERTY = "pauc.lod.surfaceOnlyForceSurfaceGenerator";
	private static final String ALLOW_QUALITY_REDUCTION_PROPERTY = "pauc.lod.surfaceOnlyCanLowerQuality";
	private static final String VERTICAL_QUALITY_PROPERTY = "pauc.lod.surfaceOnlyVerticalQuality";
	private static final String MIN_Y_PROPERTY = "pauc.lod.surfaceOnlyMinY";
	private static final String SURFACE_TOLERANCE_PROPERTY = "pauc.lod.surfaceOnlyToleranceBlocks";
	private static final String ENTER_TICKS_PROPERTY = "pauc.lod.surfaceOnlyEnterTicks";
	private static final String EXIT_TICKS_PROPERTY = "pauc.lod.surfaceOnlyExitTicks";
	private static final String CAVE_CLEARANCE_PROPERTY = "pauc.lod.surfaceOnlyCaveClearanceBlocks";
	private static final int LOG_THROTTLE_TICKS = 100;
	private static volatile SurfaceState lastState = SurfaceState.unavailable("not-started");
	private static int surfaceTicks;
	private static int nonSurfaceTicks;
	private static int ticksUntilNextLog;

	private PauCClientSurfaceLodMode() {
	}

	public static void onClientTick(Minecraft minecraft) {
		SurfaceSample sample = sample(minecraft);
		SurfaceState previous = lastState;
		boolean active = previous.active();
		if (sample.candidate()) {
			surfaceTicks++;
			nonSurfaceTicks = 0;
			if (!active && surfaceTicks >= readInt(ENTER_TICKS_PROPERTY, 30, 0, 200)) {
				active = true;
			}
		} else {
			nonSurfaceTicks++;
			surfaceTicks = 0;
			if (active && nonSurfaceTicks >= readInt(EXIT_TICKS_PROPERTY, 4, 0, 80)) {
				active = false;
			}
		}

		SurfaceState state = new SurfaceState(
			sample.available(),
			active,
			sample.candidate(),
			sample.reason(),
			sample.cameraY(),
			sample.surfaceY()
		);
		lastState = state;
		if (!state.equals(previous) || ticksUntilNextLog-- <= 0) {
			ticksUntilNextLog = LOG_THROTTLE_TICKS;
			LOGGER.info("PauC surface LOD mode: {}.", state.describe());
		}
	}

	public static void reset() {
		lastState = SurfaceState.unavailable("reset");
		surfaceTicks = 0;
		nonSurfaceTicks = 0;
		ticksUntilNextLog = 0;
	}

	public static boolean isSurfaceOnlyActive() {
		return lastState.active();
	}

	public static String adjustVerticalQuality(String requestedQuality) {
		if (!isSurfaceOnlyActive() || !readBoolean(ALLOW_QUALITY_REDUCTION_PROPERTY, false)) {
			return requestedQuality;
		}
		return System.getProperty(VERTICAL_QUALITY_PROPERTY, EDhApiVerticalQuality.HEIGHT_MAP.name());
	}

	public static EDhApiVerticalQuality adjustVerticalQuality(EDhApiVerticalQuality requestedQuality) {
		if (!isSurfaceOnlyActive() || !readBoolean(ALLOW_QUALITY_REDUCTION_PROPERTY, false)) {
			return requestedQuality;
		}
		return readEnum(VERTICAL_QUALITY_PROPERTY, EDhApiVerticalQuality.class, EDhApiVerticalQuality.HEIGHT_MAP);
	}

	public static EDhApiDistantGeneratorMode adjustGeneratorMode(EDhApiDistantGeneratorMode requestedMode) {
		if (!isSurfaceOnlyActive() || !readBoolean(FORCE_SURFACE_GENERATOR_PROPERTY, true) || requestedMode == EDhApiDistantGeneratorMode.PRE_EXISTING_ONLY) {
			return requestedMode;
		}
		return EDhApiDistantGeneratorMode.SURFACE;
	}

	public static int surfaceCaveCullingHeight(int fallbackHeight) {
		SurfaceState state = lastState;
		if (!state.active() || state.surfaceY() <= Integer.MIN_VALUE / 2) {
			return fallbackHeight;
		}
		int clearance = readInt(CAVE_CLEARANCE_PROPERTY, 8, 0, 96);
		return Math.max(fallbackHeight, state.surfaceY() - clearance);
	}

	public static String describeState() {
		return lastState.describe();
	}

	private static SurfaceSample sample(Minecraft minecraft) {
		if (!readBoolean(ENABLED_PROPERTY, true)) {
			return SurfaceSample.inactive("disabled");
		}
		if (minecraft == null || minecraft.level == null || minecraft.player == null || minecraft.gameRenderer == null) {
			return SurfaceSample.inactive("no-client-level");
		}
		if (minecraft.gameRenderer.getMainCamera().getFluidInCamera() != FogType.NONE) {
			return SurfaceSample.inactive("camera-in-fluid");
		}

		ClientLevel level = minecraft.level;
		BlockPos cameraPos = BlockPos.containing(minecraft.gameRenderer.getMainCamera().getPosition());
		int cameraY = cameraPos.getY();
		int minY = readInt(MIN_Y_PROPERTY, 48, -64, 320);
		if (cameraY < minY) {
			return SurfaceSample.of(false, "below-surface-y", cameraY, Integer.MIN_VALUE);
		}

		int surfaceY = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cameraPos.getX(), cameraPos.getZ());
		int tolerance = readInt(SURFACE_TOLERANCE_PROPERTY, 6, 0, 32);
		if (cameraY < surfaceY - tolerance) {
			return SurfaceSample.of(false, "below-local-surface", cameraY, surfaceY);
		}

		return SurfaceSample.of(true, "surface-stable", cameraY, surfaceY);
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return clamp(fallback, min, max);
		}
		try {
			return clamp(Integer.parseInt(rawValue.trim()), min, max);
		} catch (NumberFormatException ignored) {
			return clamp(fallback, min, max);
		}
	}

	private static <T extends Enum<T>> T readEnum(String key, Class<T> enumType, T fallback) {
		String rawValue = System.getProperty(key);
		if (rawValue == null || rawValue.isBlank()) {
			return fallback;
		}
		try {
			return Enum.valueOf(enumType, rawValue.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException ignored) {
			return fallback;
		}
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private record SurfaceSample(boolean available, boolean candidate, String reason, int cameraY, int surfaceY) {
		private static SurfaceSample inactive(String reason) {
			return new SurfaceSample(false, false, reason, Integer.MIN_VALUE, Integer.MIN_VALUE);
		}

		private static SurfaceSample of(boolean candidate, String reason, int cameraY, int surfaceY) {
			return new SurfaceSample(true, candidate, reason, cameraY, surfaceY);
		}
	}

	private record SurfaceState(boolean available, boolean active, boolean candidate, String reason, int cameraY, int surfaceY) {
		private static SurfaceState unavailable(String reason) {
			return new SurfaceState(false, false, false, reason, Integer.MIN_VALUE, Integer.MIN_VALUE);
		}

		private String describe() {
			if (!available) {
				return "surfaceLod[unavailable, reason=" + reason + "]";
			}
			return "surfaceLod[active="
				+ active
				+ ", candidate="
				+ candidate
				+ ", reason="
				+ reason
				+ ", cameraY="
				+ cameraY
				+ ", surfaceY="
				+ surfaceY
				+ "]";
		}
	}
}
