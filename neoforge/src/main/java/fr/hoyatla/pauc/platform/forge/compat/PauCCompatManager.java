package fr.hoyatla.pauc.platform.forge.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class PauCCompatManager {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final Map<PauCCompatModule, Boolean> ENABLED_CACHE = new EnumMap<>(PauCCompatModule.class);
	private static final Set<String> LOGGED_ACTIONS = ConcurrentHashMap.newKeySet();
	private static final Set<UUID> VOICECHAT_DISCONNECTING_PLAYERS = ConcurrentHashMap.newKeySet();
	private static final long DEFAULT_XAERO_WORLD_MAP_CLEANUP_TIMEOUT_MS = 3_000L;
	private static volatile boolean bootstrapped;
	private static volatile boolean clientWorldSessionActive;
	private static volatile UUID lastKnownClientPlayerId;
	private static volatile boolean serverStopping;

	private PauCCompatManager() {
	}

	public static synchronized void bootstrap() {
		if (bootstrapped) {
			return;
		}

		bootstrapped = true;
		logActiveModules();
	}

	public static synchronized boolean isEnabled(PauCCompatModule module) {
		Boolean enabled = ENABLED_CACHE.get(module);
		if (enabled != null) {
			return enabled;
		}

		boolean resolved = isModuleLoaded(module) && readProperty(module);
		ENABLED_CACHE.put(module, resolved);
		return resolved;
	}

	public static void logActionOnce(PauCCompatModule module, String actionKey, String message) {
		if (isEnabled(module) && LOGGED_ACTIONS.add(module.name() + ":" + actionKey)) {
			LOGGER.info(message);
		}
	}

	public static void onServerStarting() {
		serverStopping = false;
		VOICECHAT_DISCONNECTING_PLAYERS.clear();
	}

	public static void onServerStopping() {
		serverStopping = true;
	}

	public static void onServerStopped() {
		serverStopping = false;
		VOICECHAT_DISCONNECTING_PLAYERS.clear();
	}

	public static boolean isServerStopping() {
		return serverStopping;
	}

	public static void onPlayerLoggedIn(ServerPlayer player) {
		VOICECHAT_DISCONNECTING_PLAYERS.remove(player.getUUID());
	}

	public static void onPlayerLoggedOut(ServerPlayer player) {
		VOICECHAT_DISCONNECTING_PLAYERS.add(player.getUUID());
	}

	public static void onClientPlayerLoggedIn(LocalPlayer player) {
		UUID playerId = player.getUUID();
		clientWorldSessionActive = true;
		lastKnownClientPlayerId = playerId;
		VOICECHAT_DISCONNECTING_PLAYERS.remove(playerId);
	}

	public static void onClientPlayerLoggedOut(LocalPlayer player) {
		UUID playerId = player != null ? player.getUUID() : lastKnownClientPlayerId;
		clientWorldSessionActive = false;
		if (playerId == null) {
			return;
		}

		lastKnownClientPlayerId = playerId;
		VOICECHAT_DISCONNECTING_PLAYERS.add(playerId);
		logActionOnce(
			PauCCompatModule.VOICECHAT_SHUTDOWN_GUARD,
			"client-logout-" + playerId,
			"PauC marked client logout early for Simple Voice Chat shutdown guard on player " + playerId + "."
		);
	}

	public static boolean shouldProcessClientLogout(LocalPlayer player) {
		return player != null || clientWorldSessionActive;
	}

	public static boolean shouldBlockVoicechatInitialization(ServerPlayer player) {
		return isEnabled(PauCCompatModule.VOICECHAT_SHUTDOWN_GUARD)
			&& (serverStopping || VOICECHAT_DISCONNECTING_PLAYERS.contains(player.getUUID()));
	}

	public static void logBlockedVoicechatInitialization(ServerPlayer player) {
		String reason = serverStopping ? "server is stopping" : "player logout is already in progress";
		logActionOnce(
			PauCCompatModule.VOICECHAT_SHUTDOWN_GUARD,
			"voicechat-init-" + player.getUUID(),
			"PauC blocked a late Simple Voice Chat reconnect for "
				+ player.getGameProfile().getName()
				+ " because "
				+ reason
				+ "."
		);
	}

	public static long getXaeroWorldMapCleanupTimeoutMs() {
		String rawValue = System.getProperty("pauc.compat.xaeroWorldMapCleanupTimeoutMs");
		if (rawValue == null) {
			return DEFAULT_XAERO_WORLD_MAP_CLEANUP_TIMEOUT_MS;
		}

		try {
			return Math.max(250L, Long.parseLong(rawValue));
		} catch (NumberFormatException ignored) {
			return DEFAULT_XAERO_WORLD_MAP_CLEANUP_TIMEOUT_MS;
		}
	}

	private static boolean isModuleLoaded(PauCCompatModule module) {
		String modId = module.getModId();
		return modId == null || ModList.get().isLoaded(modId);
	}

	private static boolean readProperty(PauCCompatModule module) {
		for (String propertyKey : module.getPropertyKeys()) {
			String rawValue = System.getProperty(propertyKey);
			if (rawValue != null) {
				return Boolean.parseBoolean(rawValue);
			}
		}

		return true;
	}

	private static void logActiveModules() {
		List<String> activeModules = new ArrayList<>();
		for (PauCCompatModule module : PauCCompatModule.values()) {
			if (isEnabled(module)) {
				activeModules.add(module.getId() + "=" + module.getDisplayName());
			}
		}

		if (activeModules.isEmpty()) {
			LOGGER.info("PauC started without optional compat modules enabled.");
			return;
		}

		LOGGER.info("PauC optional compat modules active: {}", String.join(", ", activeModules));
	}
}
