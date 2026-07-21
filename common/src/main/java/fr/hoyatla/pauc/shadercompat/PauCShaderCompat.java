package fr.hoyatla.pauc.shadercompat;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * PauC's ONLY doorway to a shader mod (today: the vendored Iris fork; after the planned removal: an
 * EXTERNAL Iris/Oculus installation). Everything is resolved by REFLECTION with cached handles and
 * fails soft to "no shaders" — per the eager-classload law, no PauC class may hold a direct reference
 * to a shader-mod type (heavy packs resolve constant pools eagerly and crash when the mod is absent).
 *
 * <p>Key property: the class-name strings below are rewritten by the build relocator exactly like
 * code references, so while Iris is still vendored this façade resolves the RELOCATED classes; once
 * the vendored tree is removed, the same strings resolve the EXTERNAL mod (or nothing, cleanly).
 * Phase 1 of the Iris-removal plan (docs/iris-removal-plan.md).</p>
 */
public final class PauCShaderCompat {
	private static final Logger LOGGER = LogUtils.getLogger();

	private static final MethodHandle SHADER_PACK_IN_USE = resolveShaderPackInUse();
	private static final MethodHandle SHADOW_PASS_ACTIVE = resolveShadowPassActive();
	private static boolean packQueryFailureLogged;
	private static boolean shadowQueryFailureLogged;

	private PauCShaderCompat() {
	}

	/** True when a shader pack is loaded and active (vendored today / external Iris-Oculus later). */
	public static boolean isShaderPackInUse() {
		if (SHADER_PACK_IN_USE == null) {
			return false;
		}
		try {
			return (boolean) SHADER_PACK_IN_USE.invoke();
		} catch (Throwable throwable) {
			if (!packQueryFailureLogged) {
				packQueryFailureLogged = true;
				LOGGER.warn("PauC shader compat: shader-pack query failed; assuming no shaders.", throwable);
			}
			return false;
		}
	}

	/** True while the shader pipeline is rendering its shadow pass (culling must not fight it). */
	public static boolean isShadowPassActive() {
		if (SHADOW_PASS_ACTIVE == null) {
			return false;
		}
		try {
			return (boolean) SHADOW_PASS_ACTIVE.invoke();
		} catch (Throwable throwable) {
			if (!shadowQueryFailureLogged) {
				shadowQueryFailureLogged = true;
				LOGGER.warn("PauC shader compat: shadow-pass query failed; assuming none.", throwable);
			}
			return false;
		}
	}

	/** True when a shader mod (vendored or external) is present at all. */
	public static boolean isShaderModPresent() {
		return SHADER_PACK_IN_USE != null;
	}

	private static MethodHandle resolveShaderPackInUse() {
		try {
			// Official, stable public API — identical in vendored fork, external Iris and Oculus.
			Class<?> apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
			Object api = apiClass.getMethod("getInstance").invoke(null);
			MethodHandle handle = MethodHandles.publicLookup()
				.findVirtual(apiClass, "isShaderPackInUse", MethodType.methodType(boolean.class));
			return handle.bindTo(api);
		} catch (Throwable ignored) {
			LOGGER.info("PauC shader compat: no shader mod detected (shaderless mode).");
			return null;
		}
	}

	private static MethodHandle resolveShadowPassActive() {
		try {
			Class<?> stateClass = Class.forName("net.irisshaders.iris.shadows.ShadowRenderingState");
			return MethodHandles.publicLookup().findStatic(stateClass,
				"areShadowsCurrentlyBeingRendered", MethodType.methodType(boolean.class));
		} catch (Throwable ignored) {
			return null; // internal class absent (older/newer external build) → treat as never in shadow pass
		}
	}

	// ---------------------------------------------------------------------------------------------
	// P3 additions (iris-removal plan): the category-3 pipeline glue, behind the same reflective
	// soft-fail doorway. Every handle resolves the vendored fork today and the external Iris/Oculus
	// (same class/method names) after the removal — or nothing, cleanly.
	// ---------------------------------------------------------------------------------------------

	private static final MethodHandle PIPELINE_SHUTDOWN = resolveStatic(
		"net.irisshaders.iris.Iris", "requestPipelineShutdownForClientLogout", MethodType.methodType(void.class));
	private static final MethodHandle CURRENT_PACK_NAME = resolveStatic(
		"net.irisshaders.iris.Iris", "getCurrentPackName", MethodType.methodType(String.class));
	private static final MethodHandle SHADERPACKS_DIRECTORY = resolveStatic(
		"net.irisshaders.iris.Iris", "getShaderpacksDirectory", MethodType.methodType(java.nio.file.Path.class));
	private static final MethodHandle DESCRIBE_PROGRAM_PATCHES = resolveStatic(
		"net.irisshaders.iris.pipeline.PauCShaderPackProgramPatches", "describeState", MethodType.methodType(String.class));

	private static MethodHandle resolveStatic(String className, String method, MethodType type) {
		try {
			return MethodHandles.publicLookup().findStatic(Class.forName(className), method, type);
		} catch (Throwable ignored) {
			return null; // absent (external build variant or shader mod removed) → soft no-op
		}
	}

	/** Asks the shader mod to tear its pipeline down on client logout. No-op without a shader mod. */
	public static void requestPipelineShutdown() {
		if (PIPELINE_SHUTDOWN == null) {
			return;
		}
		try {
			PIPELINE_SHUTDOWN.invoke();
		} catch (Throwable ignored) {
			// logout teardown is best-effort; the external mod owns its own lifecycle anyway
		}
	}

	/** Name of the currently selected shader pack, or null when none/unknown. */
	public static String currentPackName() {
		if (CURRENT_PACK_NAME == null) {
			return null;
		}
		try {
			return (String) CURRENT_PACK_NAME.invoke();
		} catch (Throwable ignored) {
			return null;
		}
	}

	/** Root path of the selected shader pack (directory or zip), or null when unresolvable. */
	public static java.nio.file.Path currentPackPath() {
		if (SHADERPACKS_DIRECTORY == null) {
			return null;
		}
		try {
			String name = currentPackName();
			if (name == null || name.isBlank()) {
				return null;
			}
			java.nio.file.Path dir = (java.nio.file.Path) SHADERPACKS_DIRECTORY.invoke();
			if (dir == null) {
				return null;
			}
			java.nio.file.Path pack = dir.resolve(name);
			return java.nio.file.Files.exists(pack) ? pack : null;
		} catch (Throwable ignored) {
			return null;
		}
	}

	/** Program-patch diagnostics from the vendored pipeline; empty once it is removed. */
	public static String describeProgramPatches() {
		if (DESCRIBE_PROGRAM_PATCHES == null) {
			return "";
		}
		try {
			String state = (String) DESCRIBE_PROGRAM_PATCHES.invoke();
			return state == null ? "" : state;
		} catch (Throwable ignored) {
			return "";
		}
	}

	/**
	 * TRUE when the active pipeline exposes the accelerated Sodium chunk shadow pass
	 * ({@code Iris.getPipelineManager().getPipelineNullable().supportsSodiumShadowPass()}).
	 * Reflective chain, false on any absence — callers fall back to the conservative path.
	 */
	public static boolean pipelineSupportsSodiumShadowPass() {
		try {
			Class<?> irisClass = Class.forName("net.irisshaders.iris.Iris");
			Object manager = irisClass.getMethod("getPipelineManager").invoke(null);
			if (manager == null) {
				return false;
			}
			Object pipeline = manager.getClass().getMethod("getPipelineNullable").invoke(manager);
			if (pipeline == null) {
				return false;
			}
			return (boolean) pipeline.getClass().getMethod("supportsSodiumShadowPass").invoke(pipeline);
		} catch (Throwable ignored) {
			return false;
		}
	}

	/** Opens the shader mod's pack-selection screen, or null when no shader mod ships one. */
	public static net.minecraft.client.gui.screens.Screen createShaderPackScreen(net.minecraft.client.gui.screens.Screen parent) {
		try {
			Class<?> screenClass = Class.forName("net.irisshaders.iris.gui.screen.ShaderPackScreen");
			return (net.minecraft.client.gui.screens.Screen) screenClass
				.getConstructor(net.minecraft.client.gui.screens.Screen.class)
				.newInstance(parent);
		} catch (Throwable ignored) {
			return null;
		}
	}
}
