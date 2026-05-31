package net.irisshaders.iris.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.compat.PauCCompatManager;
import fr.hoyatla.pauc.platform.forge.compat.PauCCompatModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(
	targets = "com.simibubi.create.content.equipment.goggles.GoggleOverlayRenderer",
	priority = 2000,
	remap = false
)
public abstract class MixinCreateGoggleOverlayRenderer {
	@Inject(
		method = "renderOverlay(Lnet/minecraftforge/client/gui/overlay/ForgeGui;Lnet/minecraft/client/gui/GuiGraphics;FII)V",
		at = @At("HEAD"),
		cancellable = true,
		remap = false
	)
	private static void pauc$guardTfmgOverlayCast(
		ForgeGui gui,
		GuiGraphics graphics,
		float partialTick,
		int width,
		int height,
		CallbackInfo ci
	) {
		if (!PauCCompatManager.isEnabled(PauCCompatModule.TFMG_GOGGLE_OVERLAY_GUARD)) {
			return;
		}

		if (Minecraft.getInstance().hitResult instanceof BlockHitResult) {
			return;
		}

		PauCCompatManager.logActionOnce(
			PauCCompatModule.TFMG_GOGGLE_OVERLAY_GUARD,
			"entity-hitresult-overlay-guard",
			"PauC skipped Create/TFMG goggles overlay on a non-block hit result to prevent a ClassCastException."
		);
		ci.cancel();
	}
}
