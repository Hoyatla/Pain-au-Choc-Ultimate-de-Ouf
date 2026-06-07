package net.irisshaders.iris.mixin.forge.compat;

import com.seibel.distanthorizons.common.commonMixins.MixinChunkMapCommon_forge;
import fr.hoyatla.pauc.platform.forge.compat.PauCClientRenderShutdownGuard;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ChunkMap.class)
public abstract class MixinPauCDhChunkMap {
	@Shadow
	@Final
	ServerLevel level;

	@Inject(method = "save", at = @At("RETURN"))
	private void pauc$feedSavedChunkToEmbeddedDh(ChunkAccess chunk, CallbackInfoReturnable<Boolean> cir) {
		if (PauCCompatManager.isServerStopping() || PauCClientRenderShutdownGuard.isShutdownInProgress()) {
			return;
		}

		MixinChunkMapCommon_forge.onChunkSave(this.level, chunk, cir);
	}
}
