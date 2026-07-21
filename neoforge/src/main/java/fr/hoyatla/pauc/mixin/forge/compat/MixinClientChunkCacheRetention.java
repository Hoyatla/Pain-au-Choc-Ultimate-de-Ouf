package fr.hoyatla.pauc.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.client.PauCClientChunkRetentionManager;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.chunk.LevelChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.function.Consumer;

@Mixin(ClientChunkCache.class)
public class MixinClientChunkCacheRetention {
	@Inject(method = "drop", at = @At("HEAD"), cancellable = true)
	private void pauc$retainWarmChunks(int chunkX, int chunkZ, CallbackInfo ci) {
		if (PauCClientChunkRetentionManager.shouldRetainDrop((ClientChunkCache) (Object) this, chunkX, chunkZ)) {
			ci.cancel();
		}
	}

	@Inject(method = "replaceWithPacketData", at = @At("RETURN"))
	private void pauc$markChunkLive(
		int chunkX,
		int chunkZ,
		FriendlyByteBuf buffer,
		CompoundTag blockEntitiesTag,
		Consumer<ClientboundLevelChunkPacketData.BlockEntityTagOutput> consumer,
		CallbackInfoReturnable<LevelChunk> cir
	) {
		PauCClientChunkRetentionManager.onRealChunkDataReceived(cir.getReturnValue());
	}
}
