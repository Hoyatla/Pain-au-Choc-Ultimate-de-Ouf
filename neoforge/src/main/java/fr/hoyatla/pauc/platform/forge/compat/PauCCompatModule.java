package fr.hoyatla.pauc.platform.forge.compat;

import javax.annotation.Nullable;

public enum PauCCompatModule {
	SANDWORM_SONIC_BOOM(
		"sandwormSonicBoom",
		"sandworm_mod",
		"Sandworm sonic boom post-process guard",
		new String[] { "pauc.compat.sandwormSonicBoom", "pauc.sandwormSonicBoomCompat" }
	),
	BEYOND_THE_ABYSS_WORLD_SHADER(
		"beyondTheAbyssWorldShader",
		"beyondtheabyss",
		"Beyond The Abyss depth/world shader guard",
		new String[] { "pauc.compat.beyondTheAbyssWorldShader", "pauc.beyondTheAbyssWorldShaderCompat" }
	),
	CLIENT_FRONTIER_WARMUP_PIPELINE(
		"clientFrontierWarmupPipeline",
		null,
		"Client frontier warmup pipeline",
		new String[] { "pauc.compat.clientFrontierWarmupPipeline" }
	),
	CLIENT_RENDER_PREP_PIPELINE(
		"clientRenderPrepPipeline",
		null,
		"Client render preparation pipeline",
		new String[] { "pauc.compat.clientRenderPrepPipeline" }
	),
	CLIENT_LOD_HORIZON(
		"clientLodHorizon",
		null,
		"Client LOD horizon governor",
		new String[] { "pauc.compat.clientLodHorizon" }
	),
	TFMG_GOGGLE_OVERLAY_GUARD(
		"tfmgGoggleOverlayGuard",
		"tfmg",
		"TFMG goggles overlay cast guard",
		new String[] { "pauc.compat.tfmgGoggleOverlayGuard" }
	),
	XAERO_WORLD_MAP_CLEANUP_GUARD(
		"xaeroWorldMapCleanupGuard",
		"xaeroworldmap",
		"Xaero World Map shutdown cleanup watchdog",
		new String[] { "pauc.compat.xaeroWorldMapCleanupGuard" }
	),
	CLIENT_CHUNK_RETENTION_RING(
		"clientChunkRetentionRing",
		null,
		"Client chunk retention ring",
		new String[] { "pauc.compat.clientChunkRetentionRing" }
	),
	CLIENT_RENDER_SHUTDOWN_GUARD(
		"clientRenderShutdownGuard",
		null,
		"Client render pipeline shutdown guard",
		new String[] { "pauc.compat.clientRenderShutdownGuard" }
	),
	SAVE_BARRIER_WATCHDOG(
		"saveBarrierWatchdog",
		null,
		"PauC save and shutdown barrier watchdog",
		new String[] { "pauc.compat.saveBarrierWatchdog" }
	),
	VOICECHAT_SHUTDOWN_GUARD(
		"voicechatShutdownGuard",
		"voicechat",
		"Simple Voice Chat shutdown reconnect guard",
		new String[] { "pauc.compat.voicechatShutdownGuard" }
	);

	private final String id;
	@Nullable
	private final String modId;
	private final String displayName;
	private final String[] propertyKeys;

	PauCCompatModule(String id, @Nullable String modId, String displayName, String[] propertyKeys) {
		this.id = id;
		this.modId = modId;
		this.displayName = displayName;
		this.propertyKeys = propertyKeys;
	}

	public String getId() {
		return id;
	}

	@Nullable
	public String getModId() {
		return modId;
	}

	public String getDisplayName() {
		return displayName;
	}

	public String[] getPropertyKeys() {
		return propertyKeys;
	}
}
