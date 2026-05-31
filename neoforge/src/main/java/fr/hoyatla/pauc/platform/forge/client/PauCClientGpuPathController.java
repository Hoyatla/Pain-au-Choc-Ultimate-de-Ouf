package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GLCapabilities;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class PauCClientGpuPathController {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final long CAPTURE_INTERVAL_MS = 3_000L;
	private static volatile long lastCaptureAtMillis = -1L;
	private static volatile GpuSnapshot lastSnapshot = GpuSnapshot.unavailable("not-captured");
	private static volatile boolean startupLogged;

	private PauCClientGpuPathController() {
	}

	public static void captureOnClientTick(Minecraft minecraft) {
		if (minecraft == null || minecraft.getWindow() == null || minecraft.getWindow().getWindow() == 0L) {
			return;
		}

		if (!RenderSystem.isOnRenderThreadOrInit()) {
			return;
		}

		long now = System.currentTimeMillis();
		if (lastCaptureAtMillis > 0L && now - lastCaptureAtMillis < CAPTURE_INTERVAL_MS && lastSnapshot.available()) {
			return;
		}

		lastCaptureAtMillis = now;
		lastSnapshot = probe();
		if (!startupLogged) {
			startupLogged = true;
			LOGGER.info("PauC GPU path controller: {}", lastSnapshot.describe());
		}
	}

	public static String describeState() {
		return lastSnapshot.describe();
	}

	public static GpuSnapshot getLastSnapshot() {
		return lastSnapshot;
	}

	private static GpuSnapshot probe() {
		try {
			GLCapabilities caps = GL.getCapabilities();
			String vendor = fallback(GL11C.glGetString(GL11C.GL_VENDOR), "unknown");
			String renderer = fallback(GL11C.glGetString(GL11C.GL_RENDERER), "unknown");
			String version = fallback(GL11C.glGetString(GL11C.GL_VERSION), "unknown");

			boolean nvidiaVendor = vendor.toUpperCase(Locale.ROOT).contains("NVIDIA");
			boolean turingOrNewer = nvidiaVendor && isLikelyTuringOrNewer(renderer);
			boolean multiDrawIndirect = caps.OpenGL43 || caps.GL_ARB_multi_draw_indirect;
			boolean nvMeshShader = caps.GL_NV_mesh_shader;
			boolean extMeshShader = hasBooleanCapability(caps, "GL_EXT_mesh_shader");
			boolean meshShaderAvailable = nvMeshShader || extMeshShader;
			boolean bindlessIndirect = caps.GL_NV_bindless_multi_draw_indirect;

			boolean nvidiaOnly = readBoolean("pauc.client.gpu.nvidiaOnly", false);
			boolean requireTuring = readBoolean("pauc.client.gpu.requireTuring", true);
			boolean requireMeshShader = readBoolean("pauc.client.gpu.requireNvMeshShader", true);
			boolean dlssSkeletonEnabled = readBoolean("pauc.client.dlss.skeletonEnabled", false);
			String dlssInterop = normalizeInterop(System.getProperty("pauc.client.dlss.interop", "vulkan"));

			boolean nvidiaMeshReady = nvidiaVendor
				&& (!requireTuring || turingOrNewer)
				&& multiDrawIndirect
				&& (!requireMeshShader || nvMeshShader);

			List<String> blockers = new ArrayList<>();
			if (nvidiaOnly && !nvidiaVendor) {
				blockers.add("vendor-not-nvidia");
			}
			if (requireTuring && nvidiaVendor && !turingOrNewer) {
				blockers.add("turing-required");
			}
			if (requireMeshShader && !meshShaderAvailable) {
				blockers.add("mesh-shader-required");
			}
			if (!multiDrawIndirect) {
				blockers.add("multi-draw-indirect-missing");
			}

			RenderPath renderPath;
			if (nvidiaOnly) {
				renderPath = nvidiaMeshReady
					? RenderPath.NVIDIA_MESH_OPENGL
					: (multiDrawIndirect ? RenderPath.INDIRECT_OPENGL : RenderPath.LEGACY_OPENGL);
			} else {
				renderPath = nvidiaMeshReady
					? RenderPath.NVIDIA_MESH_OPENGL
					: (multiDrawIndirect ? RenderPath.INDIRECT_OPENGL : RenderPath.LEGACY_OPENGL);
			}

			DlssSkeletonPath dlssPath = DlssSkeletonPath.DISABLED;
			if (dlssSkeletonEnabled) {
				dlssPath = switch (dlssInterop) {
					case "dx12" -> DlssSkeletonPath.DX12_WIP;
					case "vulkan" -> DlssSkeletonPath.VULKAN_WIP;
					default -> DlssSkeletonPath.OPENGL_UNSUPPORTED;
				};
			}

			return new GpuSnapshot(
				true,
				vendor,
				renderer,
				version,
				nvidiaVendor,
				turingOrNewer,
				multiDrawIndirect,
				nvMeshShader,
				extMeshShader,
				bindlessIndirect,
				nvidiaOnly,
				requireTuring,
				requireMeshShader,
				renderPath,
				dlssPath,
				String.join("|", blockers)
			);
		} catch (Throwable throwable) {
			return GpuSnapshot.unavailable("probe-error:" + throwable.getClass().getSimpleName());
		}
	}

	private static boolean isLikelyTuringOrNewer(String renderer) {
		String upper = renderer.toUpperCase(Locale.ROOT);
		if (upper.contains("GTX")) {
			int gtxSeries = parseSeriesAfterToken(upper, "GTX ");
			return gtxSeries >= 16;
		}

		if (upper.contains("RTX")) {
			if (upper.contains("TITAN RTX") || upper.contains("RTX A") || upper.contains("QUADRO RTX")) {
				return true;
			}
			int rtxSeries = parseSeriesAfterToken(upper, "RTX ");
			return rtxSeries >= 20;
		}

		return true;
	}

	private static int parseSeriesAfterToken(String text, String token) {
		int tokenIndex = text.indexOf(token);
		if (tokenIndex < 0) {
			return -1;
		}

		int start = tokenIndex + token.length();
		while (start < text.length() && text.charAt(start) == ' ') {
			start++;
		}

		int end = start;
		while (end < text.length() && Character.isDigit(text.charAt(end))) {
			end++;
		}

		if (end <= start) {
			return -1;
		}

		try {
			String digits = text.substring(start, end);
			if (digits.length() >= 2) {
				return Integer.parseInt(digits.substring(0, 2));
			}
			return Integer.parseInt(digits);
		} catch (NumberFormatException ignored) {
			return -1;
		}
	}

	private static String normalizeInterop(String rawInterop) {
		return rawInterop == null ? "opengl" : rawInterop.trim().toLowerCase(Locale.ROOT);
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String value = System.getProperty(key);
		return value == null ? fallback : Boolean.parseBoolean(value);
	}

	private static boolean hasBooleanCapability(GLCapabilities caps, String fieldName) {
		try {
			Field field = GLCapabilities.class.getField(fieldName);
			return field.getBoolean(caps);
		} catch (ReflectiveOperationException ignored) {
			return false;
		}
	}

	private static String fallback(String value, String fallback) {
		return value == null || value.isBlank() ? fallback : value;
	}

	public enum RenderPath {
		LEGACY_OPENGL("legacy"),
		INDIRECT_OPENGL("indirect"),
		NVIDIA_MESH_OPENGL("nvidia-mesh");

		private final String id;

		RenderPath(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}
	}

	public enum DlssSkeletonPath {
		DISABLED("disabled"),
		DX12_WIP("dx12-wip"),
		VULKAN_WIP("vulkan-wip"),
		OPENGL_UNSUPPORTED("opengl-unsupported");

		private final String id;

		DlssSkeletonPath(String id) {
			this.id = id;
		}

		public String id() {
			return id;
		}
	}

	public record GpuSnapshot(
		boolean available,
		String vendor,
		String renderer,
		String glVersion,
		boolean nvidiaVendor,
		boolean turingOrNewer,
		boolean multiDrawIndirect,
		boolean nvMeshShader,
		boolean extMeshShader,
		boolean bindlessIndirect,
		boolean nvidiaOnly,
		boolean requireTuring,
		boolean requireMeshShader,
		RenderPath renderPath,
		DlssSkeletonPath dlssPath,
		String blockers
	) {
		public static GpuSnapshot unavailable(String reason) {
			return new GpuSnapshot(
				false,
				"unknown",
				"unknown",
				"unknown",
				false,
				false,
				false,
				false,
				false,
				false,
				false,
				true,
				true,
				RenderPath.LEGACY_OPENGL,
				DlssSkeletonPath.DISABLED,
				reason
			);
		}

		public String describe() {
			return "gpu[available="
				+ available
				+ ", vendor="
				+ vendor
				+ ", renderer="
				+ renderer
				+ ", gl="
				+ glVersion
				+ ", path="
				+ renderPath.id()
				+ ", dlss="
				+ dlssPath.id()
				+ ", nvidiaOnly="
				+ nvidiaOnly
				+ ", turingReq="
				+ requireTuring
				+ ", meshReq="
				+ requireMeshShader
				+ ", nvidia="
				+ nvidiaVendor
				+ ", turing="
				+ turingOrNewer
				+ ", mdi="
				+ multiDrawIndirect
				+ ", meshNV="
				+ nvMeshShader
				+ ", meshEXT="
				+ extMeshShader
				+ ", bindless="
				+ bindlessIndirect
				+ ", rendererBridge="
				+ PauCorRendererBridge.describeState()
				+ (blockers.isBlank() ? "" : ", blockers=" + blockers)
				+ "]";
		}
	}
}
