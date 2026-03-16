package pauc.pain_au_choc.mixin;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import pauc.pain_au_choc.render.PauCWorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into ClientLevel to capture chunk load/unload events
 * and forward them to PauCWorldRenderer's section manager.
 *
 * This ensures PAUC's render section graph stays synchronized
 * with the world's chunk state.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {

    /**
     * When a chunk is loaded on the client, register its sections
     * with PauCWorldRenderer for rendering.
     */
    @Inject(
            method = "onChunkLoaded",
            at = @At("RETURN")
    )
    private void pauc$onChunkLoaded(int chunkX, int chunkZ, CallbackInfo ci) {
        PauCWorldRenderer renderer = PauCWorldRenderer.instanceNullable();
        if (renderer != null) {
            renderer.onChunkAdded(chunkX, chunkZ);
        }
    }

    /**
     * When a chunk section is marked dirty (block changes),
     * schedule a rebuild in PAUC's pipeline.
     */
    @Inject(
            method = "setSectionDirtyWithNeighbors",
            at = @At("HEAD")
    )
    private void pauc$onSectionDirtyWithNeighbors(int sectionX, int sectionY, int sectionZ, CallbackInfo ci) {
        PauCWorldRenderer renderer = PauCWorldRenderer.instanceNullable();
        if (renderer != null) {
            // Schedule rebuild for the dirty section and potentially adjacent ones
            renderer.scheduleRebuildForChunk(sectionX, sectionY, sectionZ, false);
        }
    }

    /**
     * When the chunk map is unloaded (disconnect), ensure PAUC cleans up.
     */
    @Inject(
            method = "unload",
            at = @At("HEAD")
    )
    private void pauc$onChunkUnload(LevelChunk chunk, CallbackInfo ci) {
        PauCWorldRenderer renderer = PauCWorldRenderer.instanceNullable();
        if (renderer != null) {
            renderer.onChunkRemoved(chunk.getPos().x, chunk.getPos().z);
        }
    }
}
