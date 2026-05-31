package fr.hoyatla.pauc.platform.forge.worldgen;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

public final class PauCWorldgenEventBridge {
	@SubscribeEvent
	public void onChunkLoad(ChunkEvent.Load event) {
		if (!(event.getLevel() instanceof ServerLevel level) || !(event.getChunk() instanceof LevelChunk chunk)) {
			return;
		}

		FarChunkPlacementBroker.flushChunk(level, chunk.getPos());
	}

	@SubscribeEvent
	public void onLevelTick(TickEvent.LevelTickEvent event) {
		if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) {
			return;
		}

		FarChunkPlacementBroker.tick(level);
	}

	@SubscribeEvent
	public void onLevelUnload(LevelEvent.Unload event) {
		if (!(event.getLevel() instanceof ServerLevel level)) {
			return;
		}

		FarChunkPlacementBroker.shutdownLevel(level);
	}
}
