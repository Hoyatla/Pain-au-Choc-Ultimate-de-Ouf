package fr.hoyatla.pauc.mixin.forge.compat;

import com.seibel.distanthorizons.common.wrappers.misc.IMixinServerPlayer_forge;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.common.util.ITeleporter;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class MixinPauCDhServerPlayer implements IMixinServerPlayer_forge {
	@Unique
	@Nullable
	private volatile ServerLevel pauc$dhDimensionChangeDestination;

	@Override
	@Nullable
	public ServerLevel distantHorizons$getDimensionChangeDestination() {
		return this.pauc$dhDimensionChangeDestination;
	}

	@Inject(method = "changeDimension", at = @At("HEAD"), remap = false)
	private void pauc$setDhDimensionChangeDestination(ServerLevel destination, ITeleporter teleporter, CallbackInfoReturnable<Entity> cir) {
		this.pauc$dhDimensionChangeDestination = destination;
	}

	@Inject(method = "setServerLevel", at = @At("RETURN"))
	private void pauc$clearDhDimensionChangeDestination(ServerLevel level, CallbackInfo ci) {
		this.pauc$dhDimensionChangeDestination = null;
	}
}
