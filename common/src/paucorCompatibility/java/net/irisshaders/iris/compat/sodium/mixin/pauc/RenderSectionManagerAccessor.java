package net.irisshaders.iris.compat.sodium.mixin.pauc;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkBuilder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSectionManager.class)
public interface RenderSectionManagerAccessor {
	@Accessor("builder")
	ChunkBuilder pauc$getBuilder();
}
