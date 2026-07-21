package fr.hoyatla.pauc.compat.dh;

import fr.hoyatla.pauc.lod.PauCEmbeddedDhRuntime;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Gates PauC's Distant-Horizons-bound mixins so PauC boots and plays with NO DH installed.
 *
 * <p>Migrated out of the vendored iris tree (P4 iris-removal, Coupe 1c): the DH presence check now
 * goes through {@link PauCEmbeddedDhRuntime#isDistantHorizonsPresent()} (a framework-independent
 * reflective probe) instead of the vendored {@code IrisPlatformHelpers.isModLoaded}. The veto is by
 * mixin NAME (not package), so it keeps working after the DH mixins move to {@code fr.hoyatla.*}.</p>
 */
public class PauCDhMixinConfigPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return "";
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (PauCEmbeddedDhRuntime.isDistantHorizonsPresent()
			|| PauCEmbeddedDhRuntime.shouldExposeToShaderBridge()) {
			return true;
		}
		// Without DH, veto every DH-bound mixin — either living in a compat.dh package or named PauCDh*
		// (those reference com.seibel classes in their bodies even when they target vanilla classes, and
		// would CNFE during transform). Non-DH mixins sharing a gated config still apply.
		return !(mixinClassName.contains(".compat.dh.") || mixinClassName.contains("PauCDh"));
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return List.of();
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
