package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.slf4j.Logger;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class PauCorRendererBridge {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String[] PAUCOR_RENDERER_CLASSES = {
		"net.caffeinemc.mods.paucor.client.render.PauCorWorldRenderer",
		"fr.hoyatla.paucor.client.render.PauCorWorldRenderer",
		"fr.hoyatla.paucor.client.renderer.PauCorWorldRenderer",
		"net.paucor.client.render.PauCorWorldRenderer"
	};
	private static final String[] RENDERER_INSTANCE_METHODS = {
		"instanceNullable",
		"getInstance",
		"instance",
		"getRenderer",
		"get"
	};
	private static final String[] RENDER_SECTION_MANAGER_METHODS = {
		"pauc$getRenderSectionManager",
		"getRenderSectionManager",
		"getSectionManager",
		"renderSectionManager"
	};
	private static final String[] RENDER_SECTION_MANAGER_FIELDS = {
		"renderSectionManager",
		"sectionManager"
	};
	private static final String[] CHUNK_BUILDER_METHODS = {
		"pauc$getBuilder",
		"getBuilder",
		"builder"
	};
	private static final String[] CHUNK_BUILDER_FIELDS = {
		"builder"
	};
	private static final String[] MESH_STATS_METHODS = {
		"pauc$getMeshStats",
		"getPauCorMeshStats",
		"getMeshStats",
		"meshStats"
	};
	private static final Set<String> LOGGED_FAILURES = ConcurrentHashMap.newKeySet();
	private static final ConcurrentMap<String, Method> METHOD_CACHE = new ConcurrentHashMap<>();
	private static final Class<?> PAUCOR_RENDERER_CLASS = resolveRendererClass();
	private static volatile boolean bridgeDisabled;
	private static volatile String lastUnavailableReason = PAUCOR_RENDERER_CLASS == null ? "renderer-api-missing" : "renderer-not-ready";
	private static volatile RendererStats lastStats = RendererStats.unavailable(lastUnavailableReason);

	private PauCorRendererBridge() {
	}

	public static boolean isAvailable() {
		return PAUCOR_RENDERER_CLASS != null && !bridgeDisabled;
	}

	public static boolean isMeshAccelerationActive(PauCClientGpuPathController.GpuSnapshot gpuSnapshot) {
		return gpuSnapshot.renderPath() == PauCClientGpuPathController.RenderPath.NVIDIA_MESH_OPENGL
			&& gpuSnapshot.multiDrawIndirect()
			&& lastStats.meshActive();
	}

	public static RendererStats getStats(@Nullable ClientLevel level) {
		if (!isAvailable()) {
			lastStats = RendererStats.unavailable(lastUnavailableReason);
			return lastStats;
		}

		try {
			Object renderer = getRenderer(level);
			if (renderer == null) {
				lastStats = RendererStats.unavailable("renderer-not-ready");
				return lastStats;
			}

			boolean rendererUsable = hasSectionReadyMethod(renderer) && hasScheduleRebuildMethod(renderer);
			Object manager = getRenderSectionManager(renderer);
			Object builder = manager != null ? getChunkBuilder(manager) : null;
			PauCClientGpuPathController.GpuSnapshot gpuSnapshot = PauCClientGpuPathController.getLastSnapshot();
			MeshRuntimeStats meshStats = queryMeshStats(renderer, manager);
			boolean meshReady = gpuSnapshot.renderPath() == PauCClientGpuPathController.RenderPath.NVIDIA_MESH_OPENGL
				&& gpuSnapshot.multiDrawIndirect()
				&& gpuSnapshot.nvMeshShader();
			boolean meshActive = meshReady && meshStats.active();

			lastStats = new RendererStats(
				rendererUsable,
				true,
				builder != null,
				builder != null ? invokeInt(builder, 0, "getScheduledJobCount", "scheduledJobCount", "scheduledJobs") : 0,
				builder != null ? invokeInt(builder, 0, "getScheduledEffort", "scheduledEffort") : 0,
				builder != null ? invokeInt(builder, 0, "getBusyThreadCount", "busyThreadCount", "busyThreads") : 0,
				builder != null ? invokeInt(builder, 0, "getTotalThreadCount", "totalThreadCount", "totalThreads") : 0,
				invokeInt(renderer, 0, "getVisibleChunkCount", "visibleChunkCount", "getVisibleSectionCount"),
				meshReady,
				meshActive,
				gpuSnapshot.multiDrawIndirect(),
				gpuSnapshot.bindlessIndirect(),
				meshStats.residentSections(),
				meshStats.drawCalls(),
				meshStats.multiDrawBatches(),
				meshStats.meshDispatches(),
				meshStats.vramBytes(),
				rendererUsable ? "ok" : "renderer-api-incomplete"
			);
			return lastStats;
		} catch (RuntimeException | LinkageError exception) {
			markBridgeUnavailable("stats", "PauC disabled the PauCor renderer bridge because PauCor internals are unavailable at runtime.", exception);
			lastStats = RendererStats.unavailable(lastUnavailableReason);
			return lastStats;
		}
	}

	public static boolean isSectionReady(ClientLevel level, int chunkX, int sectionY, int chunkZ) {
		Object renderer = getRenderer(level);
		if (renderer == null) {
			return false;
		}

		return invokeBoolean(
			renderer,
			false,
			new Class<?>[] {int.class, int.class, int.class},
			new Object[] {chunkX, sectionY, chunkZ},
			"isSectionReady",
			"isSectionBuilt",
			"isSectionResident",
			"hasSectionMesh"
		);
	}

	public static int applyWarmPlan(ClientLevel level, PauCClientFrontierWarmupManager.PreparedWarmPlan plan, int sectionBudget) {
		if (!isAvailable()) {
			return 0;
		}

		try {
			Object renderer = getRenderer(level);
			if (renderer == null || sectionBudget <= 0) {
				return 0;
			}

			int scheduledSections = 0;
			for (int sectionY : plan.sectionYs()) {
				if (scheduledSections >= sectionBudget) {
					break;
				}

				if (isSectionReady(level, plan.chunkPos().x, sectionY, plan.chunkPos().z)) {
					continue;
				}

				if (scheduleRebuild(renderer, plan.chunkPos().x, sectionY, plan.chunkPos().z, true)) {
					scheduledSections++;
				}
			}

			if (scheduledSections > 0) {
				scheduleTerrainUpdate(renderer);
			}
			return scheduledSections;
		} catch (RuntimeException | LinkageError exception) {
			markBridgeUnavailable("apply", "PauC disabled PauCor warmup submissions because rebuild scheduling is unavailable at runtime.", exception);
			return 0;
		}
	}

	public static int forceResubmitNeighborhood(
		ClientLevel level,
		int centerChunkX,
		int centerChunkZ,
		int minSectionY,
		int maxSectionY,
		int sectionBudget
	) {
		if (!isAvailable()) {
			return 0;
		}

		try {
			Object renderer = getRenderer(level);
			if (renderer == null || sectionBudget <= 0) {
				return 0;
			}

			int scheduled = 0;
			for (int radius = 0; radius <= 1 && scheduled < sectionBudget; radius++) {
				for (int chunkX = centerChunkX - radius; chunkX <= centerChunkX + radius && scheduled < sectionBudget; chunkX++) {
					for (int chunkZ = centerChunkZ - radius; chunkZ <= centerChunkZ + radius && scheduled < sectionBudget; chunkZ++) {
						for (int sectionY = minSectionY; sectionY <= maxSectionY && scheduled < sectionBudget; sectionY++) {
							if (isSectionReady(level, chunkX, sectionY, chunkZ)) {
								continue;
							}
							if (scheduleRebuild(renderer, chunkX, sectionY, chunkZ, true)) {
								scheduled++;
							}
						}
					}
				}
			}

			if (scheduled > 0) {
				scheduleTerrainUpdate(renderer);
			}
			return scheduled;
		} catch (RuntimeException | LinkageError exception) {
			markBridgeUnavailable("force-resubmit", "PauC disabled PauCor emergency resubmits because rebuild scheduling is unavailable at runtime.", exception);
			return 0;
		}
	}

	public static String describeState() {
		return lastStats.describe();
	}

	@Nullable
	private static Object getRenderer(@Nullable ClientLevel level) {
		if (!isAvailable()) {
			return null;
		}

		try {
			Object renderer = invokeStaticFirst(PAUCOR_RENDERER_CLASS, RENDERER_INSTANCE_METHODS);
			if (renderer == null || (level != null && Minecraft.getInstance().level != level)) {
				return null;
			}
			return renderer;
		} catch (RuntimeException | LinkageError exception) {
			markBridgeUnavailable("renderer", "PauC disabled the PauCor renderer bridge because the renderer instance API failed.", exception);
			return null;
		}
	}

	@Nullable
	private static Object getRenderSectionManager(Object renderer) {
		Object manager = invokeNoArgFirst(renderer, RENDER_SECTION_MANAGER_METHODS);
		return manager != null ? manager : readFieldFirst(renderer, RENDER_SECTION_MANAGER_FIELDS);
	}

	@Nullable
	private static Object getChunkBuilder(Object manager) {
		Object builder = invokeNoArgFirst(manager, CHUNK_BUILDER_METHODS);
		return builder != null ? builder : readFieldFirst(manager, CHUNK_BUILDER_FIELDS);
	}

	private static boolean hasSectionReadyMethod(Object renderer) {
		return hasMethod(renderer, new Class<?>[] {int.class, int.class, int.class}, "isSectionReady", "isSectionBuilt", "isSectionResident", "hasSectionMesh");
	}

	private static boolean hasScheduleRebuildMethod(Object renderer) {
		return hasMethod(renderer, new Class<?>[] {int.class, int.class, int.class, boolean.class}, "scheduleRebuildForChunk", "scheduleSectionRebuild", "scheduleRebuild")
			|| hasMethod(renderer, new Class<?>[] {int.class, int.class, int.class}, "scheduleRebuildForChunk", "scheduleSectionRebuild", "scheduleRebuild");
	}

	private static boolean scheduleRebuild(Object renderer, int chunkX, int sectionY, int chunkZ, boolean important) {
		if (!hasScheduleRebuildMethod(renderer)) {
			return false;
		}

		Object result = invoke(
			renderer,
			new Class<?>[] {int.class, int.class, int.class, boolean.class},
			new Object[] {chunkX, sectionY, chunkZ, important},
			"scheduleRebuildForChunk",
			"scheduleSectionRebuild",
			"scheduleRebuild"
		);
		if (result == null) {
			result = invoke(
				renderer,
				new Class<?>[] {int.class, int.class, int.class},
				new Object[] {chunkX, sectionY, chunkZ},
				"scheduleRebuildForChunk",
				"scheduleSectionRebuild",
				"scheduleRebuild"
			);
		}

		return result == null || !(result instanceof Boolean booleanResult) || booleanResult;
	}

	private static void scheduleTerrainUpdate(Object renderer) {
		invoke(renderer, new Class<?>[0], new Object[0], "scheduleTerrainUpdate", "markTerrainDirty", "requestTerrainUpdate");
	}

	private static MeshRuntimeStats queryMeshStats(Object renderer, @Nullable Object manager) {
		Object meshStats = invokeNoArgFirst(renderer, MESH_STATS_METHODS);
		if (meshStats == null && manager != null) {
			meshStats = invokeNoArgFirst(manager, MESH_STATS_METHODS);
		}

		Object source = meshStats != null ? meshStats : renderer;
		boolean active = invokeBoolean(source, false, "isMeshRendererActive", "meshRendererActive", "isMeshRuntimeActive", "meshActive");
		int residentSections = invokeInt(source, 0, "getResidentMeshSectionCount", "residentMeshSectionCount", "residentSections", "meshResidentSections");
		int drawCalls = invokeInt(source, -1, "getDrawCallCount", "drawCallCount", "drawCalls");
		int multiDrawBatches = invokeInt(source, -1, "getMultiDrawBatchCount", "multiDrawBatchCount", "multiDrawBatches");
		int meshDispatches = invokeInt(source, -1, "getMeshDispatchCount", "meshDispatchCount", "meshDispatches");
		long vramBytes = invokeLong(source, -1L, "getEstimatedVramBytes", "estimatedVramBytes", "getMeshVramBytes", "meshVramBytes");
		return new MeshRuntimeStats(active, residentSections, drawCalls, multiDrawBatches, meshDispatches, vramBytes);
	}

	private static Class<?> resolveRendererClass() {
		for (String className : PAUCOR_RENDERER_CLASSES) {
			try {
				return Class.forName(className, false, PauCorRendererBridge.class.getClassLoader());
			} catch (ClassNotFoundException | LinkageError ignored) {
				// Try the next PauCor API candidate.
			}
		}

		logBridgeFailureOnce("bootstrap-missing", "PauC detected no PauCor renderer API, so PauCor renderer bridge features stay disabled.", null);
		return null;
	}

	@Nullable
	private static Object invokeStaticFirst(@Nullable Class<?> owner, String... methodNames) {
		if (owner == null) {
			return null;
		}

		for (String methodName : methodNames) {
			Method method = findMethod(owner, methodName);
			if (method == null || !Modifier.isStatic(method.getModifiers())) {
				continue;
			}
			try {
				return method.invoke(null);
			} catch (ReflectiveOperationException ignored) {
				// Try the next method candidate.
			}
		}
		return null;
	}

	@Nullable
	private static Object invokeNoArgFirst(Object target, String... methodNames) {
		return invoke(target, new Class<?>[0], new Object[0], methodNames);
	}

	private static int invokeInt(Object target, int fallback, String... methodNames) {
		Object value = invokeNoArgFirst(target, methodNames);
		return value instanceof Number number ? number.intValue() : fallback;
	}

	private static long invokeLong(Object target, long fallback, String... methodNames) {
		Object value = invokeNoArgFirst(target, methodNames);
		return value instanceof Number number ? number.longValue() : fallback;
	}

	private static boolean invokeBoolean(Object target, boolean fallback, String... methodNames) {
		Object value = invokeNoArgFirst(target, methodNames);
		return value instanceof Boolean booleanValue ? booleanValue : fallback;
	}

	private static boolean invokeBoolean(
		Object target,
		boolean fallback,
		Class<?>[] parameterTypes,
		Object[] arguments,
		String... methodNames
	) {
		Object value = invoke(target, parameterTypes, arguments, methodNames);
		return value instanceof Boolean booleanValue ? booleanValue : fallback;
	}

	@Nullable
	private static Object invoke(Object target, Class<?>[] parameterTypes, Object[] arguments, String... methodNames) {
		if (target == null) {
			return null;
		}

		for (String methodName : methodNames) {
			Method method = findMethod(target.getClass(), methodName, parameterTypes);
			if (method == null || Modifier.isStatic(method.getModifiers())) {
				continue;
			}
			try {
				return method.invoke(target, arguments);
			} catch (ReflectiveOperationException ignored) {
				// Try the next method candidate.
			}
		}
		return null;
	}

	private static boolean hasMethod(Object target, Class<?>[] parameterTypes, String... methodNames) {
		if (target == null) {
			return false;
		}

		for (String methodName : methodNames) {
			if (findMethod(target.getClass(), methodName, parameterTypes) != null) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	private static Method findMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
		String cacheKey = owner.getName() + "#" + name + Arrays.toString(parameterTypes);
		if (METHOD_CACHE.containsKey(cacheKey)) {
			return METHOD_CACHE.get(cacheKey);
		}

		Class<?> current = owner;
		while (current != null) {
			try {
				Method method = current.getDeclaredMethod(name, parameterTypes);
				method.setAccessible(true);
				METHOD_CACHE.put(cacheKey, method);
				return method;
			} catch (NoSuchMethodException ignored) {
				current = current.getSuperclass();
			}
		}
		return null;
	}

	@Nullable
	private static Object readFieldFirst(Object target, String... fieldNames) {
		if (target == null) {
			return null;
		}

		for (String fieldName : fieldNames) {
			Field field = findField(target.getClass(), fieldName);
			if (field == null) {
				continue;
			}
			try {
				return field.get(target);
			} catch (IllegalAccessException ignored) {
				// Try the next field candidate.
			}
		}
		return null;
	}

	@Nullable
	private static Field findField(Class<?> owner, String name) {
		Class<?> current = owner;
		while (current != null) {
			try {
				Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
				current = current.getSuperclass();
			}
		}
		return null;
	}

	private static void markBridgeUnavailable(String key, String message, Throwable throwable) {
		bridgeDisabled = true;
		lastUnavailableReason = key;
		logBridgeFailureOnce(key, message, throwable);
	}

	private static void logBridgeFailureOnce(String key, String message, @Nullable Throwable throwable) {
		if (LOGGED_FAILURES.add(key)) {
			if (throwable == null) {
				LOGGER.info(message);
			} else {
				LOGGER.warn(message, throwable);
			}
		} else if (throwable != null) {
			LOGGER.debug(message, throwable);
		}
	}

	private record MeshRuntimeStats(
		boolean active,
		int residentSections,
		int drawCalls,
		int multiDrawBatches,
		int meshDispatches,
		long vramBytes
	) {
	}

	public record RendererStats(
		boolean available,
		boolean rendererAvailable,
		boolean builderAvailable,
		int scheduledJobs,
		int scheduledEffort,
		int busyThreads,
		int totalThreads,
		int visibleChunkCount,
		boolean meshReady,
		boolean meshActive,
		boolean multiDrawIndirect,
		boolean bindlessIndirect,
		int residentMeshSections,
		int drawCalls,
		int multiDrawBatches,
		int meshDispatches,
		long meshVramBytes,
		String reason
	) {
		public static RendererStats unavailable(String reason) {
			return new RendererStats(false, false, false, 0, 0, 0, 0, 0, false, false, false, false, 0, -1, -1, -1, -1L, reason);
		}

		public String describe() {
			if (!available) {
				return "paucor[available=false, reason=" + reason + "]";
			}

			return "paucor[available=true"
				+ ", renderer="
				+ rendererAvailable
				+ ", builder="
				+ builderAvailable
				+ ", jobs="
				+ scheduledJobs
				+ ", effort="
				+ scheduledEffort
				+ ", busy="
				+ busyThreads
				+ "/"
				+ totalThreads
				+ ", visible="
				+ visibleChunkCount
				+ ", meshReady="
				+ meshReady
				+ ", meshActive="
				+ meshActive
				+ ", mdi="
				+ multiDrawIndirect
				+ ", bindless="
				+ bindlessIndirect
				+ ", resident="
				+ residentMeshSections
				+ ", draws="
				+ (drawCalls >= 0 ? drawCalls : "-")
				+ ", mdiBatches="
				+ (multiDrawBatches >= 0 ? multiDrawBatches : "-")
				+ ", meshDispatches="
				+ (meshDispatches >= 0 ? meshDispatches : "-")
				+ ", meshVram="
				+ (meshVramBytes >= 0L ? (meshVramBytes / (1024L * 1024L)) + "MiB" : "-")
				+ "]";
		}
	}
}
