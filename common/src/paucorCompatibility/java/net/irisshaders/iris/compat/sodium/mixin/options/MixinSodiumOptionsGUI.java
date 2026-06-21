package net.irisshaders.iris.compat.sodium.mixin.options;

import net.caffeinemc.mods.sodium.client.gui.SodiumOptionsGUI;
import org.spongepowered.asm.mixin.Mixin;

/**
 * PauC uses its own video settings path. Keep the Sodium/Embeddium GUI free of the
 * legacy Iris shader pack page to avoid duplicate entry points and duplicated state.
 */
@Mixin(SodiumOptionsGUI.class)
public class MixinSodiumOptionsGUI {
}
