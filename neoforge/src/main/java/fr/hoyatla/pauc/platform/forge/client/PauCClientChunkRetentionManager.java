package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import fr.hoyatla.pauc.platform.forge.compat.PauCClientRenderShutdownGuard;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatModule;
import net.irisshaders.iris.api.v0.IrisApi;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public final class PauCClientChunkRetentionManager {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ALLOW_SHADER_OFF_RETENTION_WITHOUT_BRIDGE_PROPERTY = "pauc.client.allowShaderOffChunkRetentionWithoutBridge";
	private static final int SHADER_OFF_MIN_RETENTION_MARGIN_CHUNKS = 8;
	private static final int FAST_TRAVEL_MIN_RETENTION_MARGIN_CHUNKS = 10;
	private static final int ELYTRA_MIN_RETENTION_MARGIN_CHUNKS = 12;
	private static final int MAX_RETENTION_MARGIN_CHUNKS = 14;
	private static final int EVICTION_BUDGET_PER_TICK = 32;
	private static final double FAST_TRAVEL_SPEED_BLOCKS_PER_TICK = 0.55D;
	private static final ThreadLocal<Boolean> FORCE_DROP = ThreadLocal.withInitial(() -> false);
	private static final Object RETAINED_LOCK = new Object();
	private static final Map<Long, RetainedChunkState> RETAINED_CHUNKS = new LinkedHashMap<>();
	private static volatile boolean shutdownSuspended;
	@Nullable
	private static volatile String lastKnownDimension;

	private PauCClientChunkRetentionManager() {
	}

	public static boolean shouldRetainDrop(ClientChunkCache chunkCache, int chunkX, int chunkZ) {
		if (Boolean.TRUE.equals(FORCE_DROP.get()) || !PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_CHUNK_RETENTION_RING) || !isHardRetentionEnabled()) {
			return false;
		}

		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null || level.getChunkSource() != chunkCache) {
			return false;
		}

		if (!isWithinRetentionRadius(minecraft, chunkX, chunkZ)) {
			return false;
		}

		if (!PauCClientFrontierWarmupManager.shouldRetainChunk(minecraft, level, chunkX, chunkZ)) {
			return false;
		}

		long chunkKey = new ChunkPos(chunkX, chunkZ).toLong();
		synchronized (RETAINED_LOCK) {
			RETAINED_CHUNKS.put(chunkKey, new RetainedChunkState(level.dimension().location().toString(), System.currentTimeMillis()));
		}
		lastKnownDimension = level.dimension().location().toString();
		PauCCompatManager.logActionOnce(
			PauCCompatModule.CLIENT_CHUNK_RETENTION_RING,
			"enabled",
			"PauC enabled the client chunk retention ring with a " + getRetentionMarginChunks() + "-chunk margin beyond the active view distance."
		);
		return true;
	}

	public static void onRealChunkDataReceived(@Nullable net.minecraft.world.level.chunk.LevelChunk chunk) {
		if (chunk == null) {
			return;
		}

		int chunkX = chunk.getPos().x;
		int chunkZ = chunk.getPos().z;
		synchronized (RETAINED_LOCK) {
			RETAINED_CHUNKS.remove(new ChunkPos(chunkX, chunkZ).toLong());
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null) {
			PauCClientFrontierWarmupManager.onChunkDataReady(minecraft.level, chunk);
		}
	}

	public static void onClientTick() {
		Minecraft minecraft = Minecraft.getInstance();
		PauCClientGpuPathController.captureOnClientTick(minecraft);

		if (PauCClientRenderShutdownGuard.isShutdownInProgress()) {
			if (!shutdownSuspended) {
				shutdownSuspended = true;
				PauCClientFrontierWarmupManager.onClientLogoutStarted();
				clearTrackingOnly();
			}
			return;
		}

		if (shutdownSuspended) {
			shutdownSuspended = false;
			PauCClientFrontierWarmupManager.onClientSessionResumed();
		}

		if (!PauCCompatManager.isEnabled(PauCCompatModule.CLIENT_CHUNK_RETENTION_RING)) {
			return;
		}

		String suspensionReason = getHardRetentionSuspensionReason();
		if (suspensionReason != null) {
			PauCCompatManager.logActionOnce(
				PauCCompatModule.CLIENT_CHUNK_RETENTION_RING,
				"suspended-" + suspensionReason,
				"PauC suspended hard client chunk retention because " + suspensionReason + "; soft frontier warmup remains active."
			);
		}

		ClientLevel level = minecraft.level;
		if (level == null || minecraft.player == null) {
			clearTrackingOnly();
			return;
		}

		lastKnownDimension = level.dimension().location().toString();
		if (suspensionReason != null) {
			releaseAll(level.getChunkSource(), "hard-retention-suspended");
			PauCClientFrontierWarmupManager.onClientTick(level, Map.of());
			return;
		}

		PauCClientFrontierWarmupManager.onClientTick(level, snapshotRetainedChunks());
		evictRetainedChunks(minecraft, level);
	}

	public static void onClientLevelUnload(ClientLevel level) {
		PauCClientFrontierWarmupManager.onClientLevelUnload();
		releaseAll(level.getChunkSource(), "client-level-unload");
	}

	public static void onClientLogoutStarted() {
		shutdownSuspended = true;
		PauCClientFrontierWarmupManager.onClientLogoutStarted();
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level != null) {
			releaseAll(level.getChunkSource(), "client-logout");
		} else {
			clearTrackingOnly();
		}
	}

	public static void onClientSessionResumed() {
		shutdownSuspended = false;
		PauCClientFrontierWarmupManager.onClientSessionResumed();
		clearTrackingOnly();
	}

	public static String describeState() {
		String dimension = lastKnownDimension != null ? lastKnownDimension : "-";
		int retainedCount;
		synchronized (RETAINED_LOCK) {
			retainedCount = RETAINED_CHUNKS.size();
		}
		return "clientRetention[retained="
			+ retainedCount
			+ ", margin="
			+ getRetentionMarginChunks()
			+ ", dimension="
			+ dimension
			+ "], "
			+ PauCClientFrontierWarmupManager.describeState()
			+ ", "
			+ PauCClientGpuPathController.describeState();
	}

	private static void evictRetainedChunks(Minecraft minecraft, ClientLevel level) {
		int evictedChunks = 0;
		List<Long> budgetEvictions = PauCClientFrontierWarmupManager.collectRetentionEvictions(level, snapshotRetainedChunks());
		synchronized (RETAINED_LOCK) {
			for (Long chunkKey : budgetEvictions) {
				if (RETAINED_CHUNKS.remove(chunkKey) != null) {
					forceDrop(level.getChunkSource(), new ChunkPos(chunkKey));
					PauCClientFrontierWarmupManager.onRetainedChunkReleased(chunkKey);
					PauCClientFrontierWarmupManager.onChunkDropped(level, chunkKey);
					evictedChunks++;
				}
			}

			Iterator<Map.Entry<Long, RetainedChunkState>> iterator = RETAINED_CHUNKS.entrySet().iterator();

			while (iterator.hasNext() && evictedChunks < EVICTION_BUDGET_PER_TICK) {
				Map.Entry<Long, RetainedChunkState> entry = iterator.next();
				ChunkPos chunkPos = new ChunkPos(entry.getKey());
				RetainedChunkState retainedChunk = entry.getValue();

				if (!retainedChunk.dimensionId().equals(level.dimension().location().toString())) {
					iterator.remove();
					continue;
				}

				if (level.getChunkSource().getChunk(chunkPos.x, chunkPos.z, ChunkStatus.FULL, false) == null) {
					iterator.remove();
					continue;
				}

				if (isWithinLiveRadius(minecraft, chunkPos.x, chunkPos.z)) {
					iterator.remove();
					continue;
				}

				if (isWithinRetentionRadius(minecraft, chunkPos.x, chunkPos.z)) {
					continue;
				}

				iterator.remove();
				forceDrop(level.getChunkSource(), chunkPos);
				PauCClientFrontierWarmupManager.onRetainedChunkReleased(entry.getKey());
				PauCClientFrontierWarmupManager.onChunkDropped(level, entry.getKey());
				evictedChunks++;
			}
		}

		if (evictedChunks > 0) {
			LOGGER.debug("PauC evicted {} retained client chunk(s) outside the warm ring.", evictedChunks);
		}
	}

	private static void releaseAll(ClientChunkCache chunkCache, String reason) {
		int releasedChunks = 0;
		synchronized (RETAINED_LOCK) {
			if (RETAINED_CHUNKS.isEmpty()) {
				clearTrackingOnly();
				return;
			}

			Iterator<Map.Entry<Long, RetainedChunkState>> iterator = RETAINED_CHUNKS.entrySet().iterator();
			while (iterator.hasNext()) {
				Map.Entry<Long, RetainedChunkState> entry = iterator.next();
				iterator.remove();
				forceDrop(chunkCache, new ChunkPos(entry.getKey()));
				PauCClientFrontierWarmupManager.onRetainedChunkReleased(entry.getKey());
				releasedChunks++;
			}
		}

		lastKnownDimension = null;
		LOGGER.info("PauC released {} retained client chunk(s) during {}.", releasedChunks, reason);
	}

	private static void clearTrackingOnly() {
		synchronized (RETAINED_LOCK) {
			RETAINED_CHUNKS.clear();
		}
		lastKnownDimension = null;
	}

	private static void forceDrop(ClientChunkCache chunkCache, ChunkPos chunkPos) {
		FORCE_DROP.set(true);
		try {
			chunkCache.drop(chunkPos.x, chunkPos.z);
		} catch (RuntimeException exception) {
			LOGGER.debug("PauC could not force-drop retained client chunk {} during retention cleanup.", chunkPos, exception);
		} finally {
			FORCE_DROP.remove();
		}
	}

	private static boolean isWithinLiveRadius(Minecraft minecraft, int chunkX, int chunkZ) {
		return chebyshevDistanceToPlayer(minecraft, chunkX, chunkZ) <= minecraft.options.getEffectiveRenderDistance();
	}

	private static boolean isWithinRetentionRadius(Minecraft minecraft, int chunkX, int chunkZ) {
		return chebyshevDistanceToPlayer(minecraft, chunkX, chunkZ) <= minecraft.options.getEffectiveRenderDistance() + getRetentionMarginChunks();
	}

	static int getRetentionRadiusChunks() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft.player != null ? minecraft.options.getEffectiveRenderDistance() + getRetentionMarginChunks() : getRetentionMarginChunks();
	}

	private static int chebyshevDistanceToPlayer(Minecraft minecraft, int chunkX, int chunkZ) {
		ChunkPos playerChunk = minecraft.player.chunkPosition();
		return Math.max(Math.abs(chunkX - playerChunk.x), Math.abs(chunkZ - playerChunk.z));
	}

	public static int getRetentionMarginChunks() {
		String rawValue = System.getProperty("pauc.client.chunkRetainMarginChunks");
		if (rawValue == null) {
			rawValue = System.getProperty("pauc.client.chunkRetainMargin");
		}

		if (rawValue == null) {
			return effectiveRetentionMargin(PauCLodClientSettings.retentionMarginChunks());
		}

		try {
			return effectiveRetentionMargin(Integer.parseInt(rawValue));
		} catch (NumberFormatException ignored) {
			return effectiveRetentionMargin(PauCLodClientSettings.retentionMarginChunks());
		}
	}

	private static int effectiveRetentionMargin(int configuredMarginChunks) {
		int clamped = Math.max(0, Math.min(MAX_RETENTION_MARGIN_CHUNKS, configuredMarginChunks));
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null) {
			if (minecraft.player.isFallFlying()) {
				clamped = Math.max(clamped, ELYTRA_MIN_RETENTION_MARGIN_CHUNKS);
			} else if (minecraft.player.getDeltaMovement().horizontalDistance() >= FAST_TRAVEL_SPEED_BLOCKS_PER_TICK) {
				clamped = Math.max(clamped, FAST_TRAVEL_MIN_RETENTION_MARGIN_CHUNKS);
			}
		}
		if (!PauCLodShaderContext.isShaderPackInUse()) {
			return Math.max(SHADER_OFF_MIN_RETENTION_MARGIN_CHUNKS, clamped);
		}

		return clamped;
	}

	private static Map<Long, RetainedChunkState> snapshotRetainedChunks() {
		synchronized (RETAINED_LOCK) {
			return new LinkedHashMap<>(RETAINED_CHUNKS);
		}
	}

	private static boolean isHardRetentionEnabled() {
		return getHardRetentionSuspensionReason() == null;
	}

	@Nullable
	private static String getHardRetentionSuspensionReason() {
		String explicit = System.getProperty("pauc.client.allowHardChunkRetention");
		if (explicit != null) {
			return Boolean.parseBoolean(explicit) ? null : "it is disabled by pauc.client.allowHardChunkRetention";
		}

		if (PauCClientRenderShutdownGuard.isShutdownInProgress()) {
			return "the render shutdown guard is active";
		}

		if (isDistantHorizonsLoaded() && isShaderPipelineActive()) {
			return "Distant Horizons is rendering LOD terrain under an active shader pipeline";
		}

		if (!PauCorRendererBridge.isAvailable() && !shouldAllowShaderOffRetentionWithoutBridge()) {
			return "the PauCor terrain rebuild bridge is unavailable";
		}

		return null;
	}

	private static boolean shouldAllowShaderOffRetentionWithoutBridge() {
		String rawValue = System.getProperty(ALLOW_SHADER_OFF_RETENTION_WITHOUT_BRIDGE_PROPERTY);
		return rawValue == null || Boolean.parseBoolean(rawValue);
	}

	private static boolean isDistantHorizonsLoaded() {
		try {
			return ModList.get().isLoaded("distanthorizons");
		} catch (RuntimeException exception) {
			LOGGER.debug("PauC could not query the Distant Horizons mod state while gating hard chunk retention.", exception);
			return false;
		}
	}

	private static boolean isShaderPipelineActive() {
		try {
			return IrisApi.getInstance().isShaderPackInUse();
		} catch (RuntimeException | LinkageError exception) {
			LOGGER.debug("PauC could not query the shader pipeline state while gating hard chunk retention.", exception);
			return false;
		}
	}

	record RetainedChunkState(
		String dimensionId,
		long retainedAtMillis
	) {
	}
}
