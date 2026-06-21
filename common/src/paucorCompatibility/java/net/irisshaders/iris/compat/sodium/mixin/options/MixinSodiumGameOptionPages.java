package net.irisshaders.iris.compat.sodium.mixin.options;

import net.caffeinemc.mods.sodium.client.gui.SodiumGameOptionPages;
import org.spongepowered.asm.mixin.Mixin;

/**
 * PauC owns the active settings surface. Avoid injecting parallel Iris-specific
 * options into Sodium/Embeddium pages until those controls are reintroduced through
 * a single PauC-managed path.
 */
@Mixin(SodiumGameOptionPages.class)
public class MixinSodiumGameOptionPages {
}
