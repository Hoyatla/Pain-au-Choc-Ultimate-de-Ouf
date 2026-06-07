package net.irisshaders.iris.mixin.forge.compat;

import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiGenericObjectShaderProgram;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.common.render.openGl.generic.GlGenericObjectRenderer;
import com.seibel.distanthorizons.core.render.renderer.RenderableBoxGroup;
import com.seibel.distanthorizons.core.util.math.Vec3d;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IProfilerWrapper;
import fr.hoyatla.pauc.lod.PauCLodGenericObjectCulling;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GlGenericObjectRenderer.class, remap = false)
public abstract class MixinPauCDhGenericObjectRenderer {
	@Inject(method = "renderBoxGroupInstanced", at = @At("HEAD"), cancellable = true, require = 0)
	private void pauc$cullNearGenericLodInstanced(
		IDhApiGenericObjectShaderProgram shaderProgram,
		DhApiRenderParam renderParam,
		RenderableBoxGroup boxGroup,
		Vec3d cameraPos,
		IProfilerWrapper profiler,
		CallbackInfo ci
	) {
		if (pauc$shouldCullNearGenericLod(boxGroup)) {
			ci.cancel();
		}
	}

	@Inject(method = "renderBoxGroupDirect", at = @At("HEAD"), cancellable = true, require = 0)
	private void pauc$cullNearGenericLodDirect(
		IDhApiGenericObjectShaderProgram shaderProgram,
		DhApiRenderParam renderParam,
		RenderableBoxGroup boxGroup,
		Vec3d cameraPos,
		IProfilerWrapper profiler,
		CallbackInfo ci
	) {
		if (pauc$shouldCullNearGenericLod(boxGroup)) {
			ci.cancel();
		}
	}

	@Unique
	private static boolean pauc$shouldCullNearGenericLod(RenderableBoxGroup boxGroup) {
		return PauCLodGenericObjectCulling.shouldCullNearGenericLod(boxGroup);
	}
}
