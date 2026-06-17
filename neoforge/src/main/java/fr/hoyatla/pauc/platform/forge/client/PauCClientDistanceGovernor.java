package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;

public final class PauCClientDistanceGovernor {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int LOG_THROTTLE_TICKS = 300;
	private static volatile DistanceState lastState = DistanceState.unavailable();
	private static int ticksUntilNextLog;

	private PauCClientDistanceGovernor() {
	}

	public static void onClientTick(Minecraft minecraft) {
		if (minecraft == null || minecraft.options == null || minecraft.level == null || minecraft.player == null) {
			lastState = DistanceState.unavailable();
			return;
		}

		int vanillaDistance = minecraft.options.getEffectiveRenderDistance();
		int recommendedDistance = Math.min(
			PauCLodClientSettings.recommendedVanillaDistanceChunks(),
			Math.max(4, PauCLodClientSettings.targetDistanceChunks() - 4)
		);
		boolean autoReduce = PauCLodClientSettings.autoReduceVanillaDistance();
		boolean aboveRecommendation = vanillaDistance > recommendedDistance;
		DistanceState previous = lastState;
		DistanceState state = new DistanceState(true, vanillaDistance, recommendedDistance, autoReduce, aboveRecommendation);
		lastState = state;
		if (!state.equals(previous) || ticksUntilNextLog-- <= 0) {
			ticksUntilNextLog = LOG_THROTTLE_TICKS;
			LOGGER.info("PauC distance governor: {}", state.describe());
		}
	}

	public static void reset() {
		lastState = DistanceState.unavailable();
		ticksUntilNextLog = 0;
	}

	public static String describeState() {
		return lastState.describe();
	}

	private record DistanceState(
		boolean available,
		int vanillaDistanceChunks,
		int recommendedVanillaDistanceChunks,
		boolean autoReduce,
		boolean aboveRecommendation
	) {
		static DistanceState unavailable() {
			return new DistanceState(false, 0, 0, false, false);
		}

		String describe() {
			if (!available) {
				return "distanceGovernor[unavailable]";
			}

			return "distanceGovernor[vanilla="
				+ vanillaDistanceChunks
				+ ", recommended<="
				+ recommendedVanillaDistanceChunks
				+ ", autoReduce="
				+ autoReduce
				+ ", aboveRecommendation="
				+ aboveRecommendation
				+ ", action=advisory-only"
				+ "]";
		}
	}
}
