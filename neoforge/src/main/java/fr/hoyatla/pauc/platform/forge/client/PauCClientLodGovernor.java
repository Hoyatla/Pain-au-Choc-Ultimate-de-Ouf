package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodHorizonState;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodRange;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodVideoSettings;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;

public final class PauCClientLodGovernor {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final int LOG_THROTTLE_TICKS = 100;
	private static volatile PauCLodRange currentRange = PauCLodRange.disabled(2, PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS);
	private static volatile LodFrame lastFrame = LodFrame.unavailable();
	private static int ticksUntilNextLog;
	private static int startupRefreshTicks;

	private PauCClientLodGovernor() {
	}

	public static void onClientTick() {
		// PauC must run standalone: without the external Distant Horizons mod the LOD subsystem stays off
		// and the bridge (whose code paths resolve com.seibel classes) must never execute.
		if (!fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime.isInitialized()) {
			currentRange = PauCLodRange.disabled(readVanillaDistance(Minecraft.getInstance()), readTargetDistance());
			PauCLodHorizonState.update(currentRange);
			lastFrame = LodFrame.unavailable();
			return;
		}
		if (!PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_LOD_HORIZON)) {
			currentRange = PauCLodRange.disabled(readVanillaDistance(Minecraft.getInstance()), readTargetDistance());
			PauCLodHorizonState.update(currentRange);
			PauCEmbeddedDhBridge.applyLodRange(currentRange);
			lastFrame = LodFrame.unavailable();
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		LocalPlayer player = minecraft.player;
		if (level == null || player == null) {
			PauCLodHorizonState.reset();
			lastFrame = LodFrame.unavailable();
			return;
		}

		int vanillaDistance = readVanillaDistance(minecraft);
		PauCLodRange range = PauCLodRange.fromVanillaDistance(vanillaDistance, readTargetDistance(), readEnabled());
		ChunkPos playerChunk = player.chunkPosition();
		LodFrame frame = new LodFrame(
			true,
			level.dimension().location().toString(),
			playerChunk.x,
			playerChunk.z,
			range
		);

		PauCLodRange previousRange = currentRange;
		currentRange = range;
		PauCLodHorizonState.update(range);
		if (startupRefreshTicks > 0) {
			startupRefreshTicks--;
		}
		PauCEmbeddedDhBridge.applyLodRange(range);
		lastFrame = frame;
		if (!previousRange.equals(range) || ticksUntilNextLog-- <= 0) {
			ticksUntilNextLog = LOG_THROTTLE_TICKS;
			LOGGER.info("PauC LOD governor: {}", frame.describe());
		}
	}

	public static void reset() {
		PauCLodClientSettings.reloadForClientSession();
		PauCLodVideoSettings.syncFromClientSettings();
		currentRange = PauCLodRange.disabled(2, readTargetDistance());
		PauCLodHorizonState.reset();
		if (fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime.isInitialized()) {
			// Heavy packs eagerly resolve the bridge's constant pool at first invoke; without the external
			// DH mod the bridge class must never be touched (NCDFE on its com.seibel enum references).
			PauCEmbeddedDhBridge.reset();
		}
		lastFrame = LodFrame.unavailable();
		ticksUntilNextLog = 0;
		startupRefreshTicks = 40;
	}

	public static PauCLodRange currentRange() {
		return currentRange;
	}

	public static boolean shouldRenderLodChunk(int chunkX, int chunkZ) {
		LodFrame frame = lastFrame;
		if (!frame.available()) {
			return false;
		}

		int deltaChunkX = chunkX - frame.playerChunkX();
		int deltaChunkZ = chunkZ - frame.playerChunkZ();
		return frame.range().containsRoundHorizonOffset(deltaChunkX, deltaChunkZ);
	}

	public static String describeState() {
		LodFrame frame = lastFrame;
		String bridgeState = fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime.isInitialized()
			? PauCEmbeddedDhBridge.describeState()
			: "embeddedDh[dh-not-installed]";
		return frame.available()
			? frame.describe() + ", " + bridgeState + ", " + PauCLodShaderContext.describe()
			: "lodGovernor[unavailable], " + bridgeState + ", " + PauCLodShaderContext.describe();
	}

	public static String telemetryStateKey() {
		PauCLodRange range = currentRange;
		return "enabled="
			+ range.enabled()
			+ ",vanilla="
			+ range.vanillaRenderDistanceChunks()
			+ ",lod="
			+ (range.enabled() ? range.lodStartChunk() + "-" + range.lodEndChunk() : "inactive")
			+ ",target="
			+ range.configuredExtraDistanceChunks()
			+ ",roundHorizon="
			+ range.roundHorizonEndChunk();
	}

	private static int readVanillaDistance(Minecraft minecraft) {
		if (minecraft == null || minecraft.options == null) {
			return 2;
		}
		return minecraft.options.getEffectiveRenderDistance();
	}

	private static int readTargetDistance() {
		return PauCLodClientSettings.targetDistanceChunks();
	}

	private static boolean readEnabled() {
		return PauCLodClientSettings.isLodsEnabled();
	}

	public record LodFrame(
		boolean available,
		String dimensionId,
		int playerChunkX,
		int playerChunkZ,
		PauCLodRange range
	) {
		public static LodFrame unavailable() {
			return new LodFrame(false, "-", 0, 0, PauCLodRange.disabled(2, PauCLodRange.DEFAULT_TARGET_DISTANCE_CHUNKS));
		}

		public String describe() {
			return "lodGovernor[dimension="
				+ dimensionId
				+ ", playerChunk="
				+ playerChunkX
				+ ","
				+ playerChunkZ
				+ ", "
				+ range.describe()
				+ "]";
		}
	}
}
