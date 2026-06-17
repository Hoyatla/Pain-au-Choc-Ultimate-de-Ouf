package net.irisshaders.iris.mixin;

import fr.hoyatla.pauc.lod.PauCLodDiagnostics;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gui.option.IrisVideoSettings;
import net.minecraft.client.gui.components.DebugScreenOverlay;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.text.CharacterIterator;
import java.text.StringCharacterIterator;
import java.util.List;
import java.util.Objects;

@Mixin(DebugScreenOverlay.class)
public abstract class MixinDebugScreenOverlay {
	@Unique
	private static final String PAUC_RENDERER_NAME = "PauC Renderer";

	@Unique
	private static final List<BufferPoolMXBean> iris$pools = ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class);

	@Unique
	private static final BufferPoolMXBean iris$directPool;

	static {
		BufferPoolMXBean found = null;

		for (BufferPoolMXBean pool : iris$pools) {
			if (pool.getName().equals("direct")) {
				found = pool;
				break;
			}
		}

		iris$directPool = Objects.requireNonNull(found);
	}

	// stackoverflow.com/a/3758880
	@Unique
	private static String iris$humanReadableByteCountBin(long bytes) {
		long absB = bytes == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(bytes);
		if (absB < 1024) {
			return bytes + " B";
		}
		long value = absB;
		CharacterIterator ci = new StringCharacterIterator("KMGTPE");
		for (int i = 40; i >= 0 && absB > 0xfffccccccccccccL >> i; i -= 10) {
			value >>= 10;
			ci.next();
		}
		value *= Long.signum(bytes);
		return String.format("%.3f %ciB", value / 1024.0, ci.current());
	}

	// From Sodium
	@Unique
	private static long iris$getNativeMemoryUsage() {
		return ManagementFactory.getMemoryMXBean().getNonHeapMemoryUsage().getUsed();
	}

	@Inject(method = "getSystemInformation", at = @At("RETURN"))
	private void iris$appendShaderPackText(CallbackInfoReturnable<List<String>> cir) {
		List<String> messages = cir.getReturnValue();

		messages.add("");
		messages.add("[" + Iris.MODNAME + "] Version: " + Iris.getVersionLabel());
		messages.add("");

		if (Iris.getIrisConfig().areShadersEnabled()) {
			messages.add("[" + Iris.MODNAME + "] Shaderpack: " + Iris.getCurrentPackName() + (Iris.isFallback() ? " (fallback)" : ""));
			Iris.getCurrentPack().ifPresent(pack -> {
				messages.add("[" + Iris.MODNAME + "] " + pack.getProfileInfo());
			});
			messages.add("[" + Iris.MODNAME + "] Color space: " + IrisVideoSettings.colorSpace.name());
		} else {
			messages.add("[" + Iris.MODNAME + "] Shaders are disabled");
		}

		messages.add(3, "Direct Buffers: +" + iris$humanReadableByteCountBin(iris$directPool.getMemoryUsed()));

		//if (!Iris.isSodiumInstalled()) {
		//	messages.add(3, "Native Memory: +" + iris$humanReadableByteCountBin(iris$getNativeMemoryUsage()));
		//}

		iris$sanitizeLegacyRendererLabels(messages);
	}

	@Inject(method = "getGameInformation", at = @At("RETURN"))
	private void iris$appendShadowDebugText(CallbackInfoReturnable<List<String>> cir) {
		List<String> messages = cir.getReturnValue();

		//if (!Iris.isSodiumInstalled() && Iris.getCurrentPack().isPresent()) {
		//	messages.add(1, ChatFormatting.YELLOW + "[" + Iris.MODNAME + "] Sodium isn't installed; you will have poor performance.");
		//	messages.add(2, ChatFormatting.YELLOW + "[" + Iris.MODNAME + "] Install Sodium if you want to run benchmarks or get higher FPS!");
		//}

		Iris.getPipelineManager().getPipeline().ifPresent(pipeline -> pipeline.addDebugText(messages));
		if (PauCLodDiagnostics.enabled() || PauCLodDiagnostics.hasHudFpsMetrics()) {
			messages.add(PauCLodDiagnostics.fpsLine());
		}
		if (PauCLodDiagnostics.enabled()) {
			messages.add(PauCLodDiagnostics.compactOverviewLine());
			messages.add(PauCLodDiagnostics.compactModeLine());
		}
		iris$sanitizeLegacyRendererLabels(messages);
	}

	@Unique
	private static void iris$sanitizeLegacyRendererLabels(List<String> messages) {
		for (int i = 0; i < messages.size(); i++) {
			String value = messages.get(i);
			messages.set(i, value
				.replace("SodiumRenderer", "PauCRenderer")
				.replace("sodiumrenderer", "paucrenderer")
				.replace("Sodium Renderer", PAUC_RENDERER_NAME)
				.replace("sodium renderer", "pauc renderer"));
		}
	}
}
