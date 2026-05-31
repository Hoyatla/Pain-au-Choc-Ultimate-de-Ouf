package fr.hoyatla.pauc.platform.forge.compat;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.platform.forge.client.PauCClientChunkRetentionManager;
import fr.hoyatla.pauc.platform.forge.runtime.PauCServerRuntimeDashboard;
import fr.hoyatla.pauc.platform.forge.scheduler.PauCScheduler;
import fr.hoyatla.pauc.platform.forge.worldgen.FarChunkPlacementBroker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class PauCShutdownBarrier {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final long DEFAULT_INITIAL_DELAY_MS = 10_000L;
	private static final long DEFAULT_REPEAT_DELAY_MS = 10_000L;

	private static volatile WatchSession activeSession;

	private PauCShutdownBarrier() {
	}

	public static void onClientLogoutStarted() {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.SAVE_BARRIER_WATCHDOG)) {
			return;
		}

		startOrReuse("client-logout");
	}

	public static void onClientSessionResumed() {
		stop("client-session-resumed");
	}

	public static void onServerStopping(MinecraftServer server) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.SAVE_BARRIER_WATCHDOG)) {
			return;
		}

		startOrReuse("server-stopping").attachServer(server);
	}

	public static void onServerStopped(MinecraftServer server) {
		stop("server-stopped");
	}

	public static void onSaveEverythingStart(MinecraftServer server, boolean suppressLog, boolean flush, boolean force) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.SAVE_BARRIER_WATCHDOG)) {
			return;
		}

		if (!suppressLog && server != null && !server.isDedicatedServer()) {
			PauCClientRenderShutdownGuard.onPreShutdownSaveWindowStarted();
		}

		WatchSession session = startOrReuse("save-everything");
		session.attachServer(server);
		session.setPhase("saveEverything(suppressLog=" + suppressLog + ", flush=" + flush + ", force=" + force + ")");
		session.markServerSaveStart();
	}

	public static void onSaveEverythingEnd(MinecraftServer server, boolean saved, long durationMillis) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.SAVE_BARRIER_WATCHDOG)) {
			return;
		}

		WatchSession session = activeSession;
		if (session == null) {
			return;
		}

		session.attachServer(server);
		session.markServerSaveEnd(durationMillis);
		LOGGER.info("PauC save barrier observed saveEverything result={} in {} ms.", saved, durationMillis);

		// Keep the watchdog alive only while an actual shutdown is active.
		// Routine integrated-server save windows should be disarmed quickly.
		boolean keepArmedForShutdown = PauCClientRenderShutdownGuard.isShutdownInProgress()
			|| (server != null && server.isDedicatedServer());
		if (!keepArmedForShutdown) {
			stop("save-everything-completed");
		}
	}

	public static void onLevelSaveStart(ServerLevel level, boolean flush, boolean skipSave) {
		WatchSession session = activeSession;
		if (session == null || !PauCCompatManager.isEnabled(PauCCompatModule.SAVE_BARRIER_WATCHDOG)) {
			return;
		}

		session.attachServer(level.getServer());
		session.markLevelSaveStart(level, flush, skipSave);
	}

	public static void onLevelSaveEnd(ServerLevel level, long durationMillis) {
		WatchSession session = activeSession;
		if (session == null || !PauCCompatManager.isEnabled(PauCCompatModule.SAVE_BARRIER_WATCHDOG)) {
			return;
		}

		session.attachServer(level.getServer());
		session.markLevelSaveEnd(level, durationMillis);
	}

	private static synchronized WatchSession startOrReuse(String reason) {
		WatchSession session = activeSession;
		if (session != null && session.isRunning()) {
			session.setPhase(reason);
			return session;
		}

		WatchSession newSession = new WatchSession(reason);
		activeSession = newSession;
		newSession.start();
		return newSession;
	}

	private static synchronized void stop(String reason) {
		WatchSession session = activeSession;
		if (session == null) {
			return;
		}

		activeSession = null;
		session.stop(reason);
	}

	private static final class WatchSession {
		private final long startedAtMillis = System.currentTimeMillis();
		private final long initialDelayMillis = Long.getLong("pauc.shutdown.watchdogInitialDelayMs", DEFAULT_INITIAL_DELAY_MS);
		private final long repeatDelayMillis = Long.getLong("pauc.shutdown.watchdogRepeatDelayMs", DEFAULT_REPEAT_DELAY_MS);
		private final AtomicBoolean running = new AtomicBoolean(true);
		private final AtomicReference<String> phase = new AtomicReference<>();
		private final Map<String, LevelSaveState> levelSaveStates = new ConcurrentHashMap<>();
		private final Thread watchdogThread;
		private volatile MinecraftServer server;
		private volatile long serverSaveStartedAtMillis = -1L;
		private volatile long lastServerSaveDurationMillis = -1L;

		private WatchSession(String reason) {
			this.phase.set(reason);
			this.watchdogThread = new Thread(this::run, "PauC-Shutdown-Watchdog");
			this.watchdogThread.setDaemon(true);
		}

		private void start() {
			LOGGER.info("PauC save barrier watchdog armed for phase '{}'.", phase.get());
			watchdogThread.start();
		}

		private void stop(String reason) {
			if (!running.getAndSet(false)) {
				return;
			}

			watchdogThread.interrupt();
			LOGGER.info("PauC save barrier watchdog disarmed after {} ms ({}).", System.currentTimeMillis() - startedAtMillis, reason);
		}

		private boolean isRunning() {
			return running.get();
		}

		private void setPhase(String newPhase) {
			phase.set(newPhase);
		}

		private void attachServer(MinecraftServer server) {
			this.server = server;
		}

		private void markServerSaveStart() {
			serverSaveStartedAtMillis = System.currentTimeMillis();
		}

		private void markServerSaveEnd(long durationMillis) {
			lastServerSaveDurationMillis = durationMillis;
			serverSaveStartedAtMillis = -1L;
		}

		private void markLevelSaveStart(ServerLevel level, boolean flush, boolean skipSave) {
			levelSaveStates.computeIfAbsent(level.dimension().location().toString(), ignored -> new LevelSaveState())
				.start(flush, skipSave);
		}

		private void markLevelSaveEnd(ServerLevel level, long durationMillis) {
			levelSaveStates.computeIfAbsent(level.dimension().location().toString(), ignored -> new LevelSaveState())
				.finish(durationMillis);
		}

		private void run() {
			try {
				Thread.sleep(initialDelayMillis);
				while (running.get()) {
					logSnapshot();
					Thread.sleep(repeatDelayMillis);
				}
			} catch (InterruptedException ignored) {
			}
		}

		private void logSnapshot() {
			if (!running.get()) {
				return;
			}

			long elapsedMillis = System.currentTimeMillis() - startedAtMillis;
			StringBuilder message = new StringBuilder()
				.append("PauC save barrier still active after ")
				.append(elapsedMillis)
				.append(" ms during phase '")
				.append(phase.get())
				.append("'. Scheduler=")
				.append(PauCScheduler.describeState())
				.append(", ")
				.append(PauCClientChunkRetentionManager.describeState())
				.append(", ")
				.append(PauCClientRenderShutdownGuard.describeState());

			if (serverSaveStartedAtMillis > 0L) {
				message.append(", saveEverythingRunningFor=").append(System.currentTimeMillis() - serverSaveStartedAtMillis).append("ms");
			} else if (lastServerSaveDurationMillis >= 0L) {
				message.append(", lastSaveEverything=").append(lastServerSaveDurationMillis).append("ms");
			}

			MinecraftServer currentServer = server;
			if (currentServer != null) {
				List<String> levelStates = new ArrayList<>();
				for (ServerLevel level : currentServer.getAllLevels()) {
					LevelSaveState saveState = levelSaveStates.get(level.dimension().location().toString());
					String saveDetails = saveState != null ? saveState.describe() : "save[idle]";
					levelStates.add(level.dimension().location() + "{" + saveDetails + ", " + FarChunkPlacementBroker.describeState(level) + "}");
				}
				if (!levelStates.isEmpty()) {
					message.append(", levels=").append(String.join(" | ", levelStates));
				}
				message.append(", runtime=").append(PauCServerRuntimeDashboard.describe(currentServer));
			}

			message.append(", threads=").append(describeThreads());
			LOGGER.warn(message.toString());
		}

		private String describeThreads() {
			return Thread.getAllStackTraces().entrySet().stream()
				.filter(entry -> !entry.getKey().isDaemon())
				.sorted(Comparator.comparing((Map.Entry<Thread, StackTraceElement[]> entry) -> entry.getKey().getState().name())
					.thenComparing(entry -> entry.getKey().getName()))
				.limit(8)
				.map(entry -> {
					Thread thread = entry.getKey();
					StackTraceElement[] stack = entry.getValue();
					String topFrame = stack.length > 0 ? stack[0].toString() : "no-stack";
					return thread.getName() + "[" + thread.getState() + " @" + topFrame + "]";
				})
				.reduce((left, right) -> left + " | " + right)
				.orElse("-");
		}
	}

	private static final class LevelSaveState {
		private volatile long startedAtMillis = -1L;
		private volatile long lastDurationMillis = -1L;
		private volatile boolean flush;
		private volatile boolean skipSave;

		private void start(boolean flush, boolean skipSave) {
			this.startedAtMillis = System.currentTimeMillis();
			this.flush = flush;
			this.skipSave = skipSave;
		}

		private void finish(long durationMillis) {
			this.lastDurationMillis = durationMillis;
			this.startedAtMillis = -1L;
		}

		private String describe() {
			if (startedAtMillis > 0L) {
				return "save[running=" + (System.currentTimeMillis() - startedAtMillis) + "ms, flush=" + flush + ", skip=" + skipSave + "]";
			}

			if (lastDurationMillis >= 0L) {
				return "save[last=" + lastDurationMillis + "ms]";
			}

			return "save[idle]";
		}
	}
}
