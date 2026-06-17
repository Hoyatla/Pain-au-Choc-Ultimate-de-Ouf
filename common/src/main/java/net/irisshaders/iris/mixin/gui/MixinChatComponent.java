package net.irisshaders.iris.mixin.gui;

import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ChatComponent.class)
public final class MixinChatComponent {
	@Unique
	private static boolean pauc$shouldSuppressSystemMessage(Component component) {
		if (component == null) {
			return false;
		}

		String plainText = component.getString();
		return plainText.contains("Garbage collector detected")
			|| plainText.contains("This can cause FPS stuttering")
			|| plainText.contains("Shenandoah (Java 8 through 17)")
			|| plainText.contains("ZGC (Java 21+)")
			|| plainText.contains("PauC UltimateLOD: slow world gen.")
			|| plainText.contains("C2ME missing, low CPU usage and slow world gen speeds expected.")
			|| plainText.contains("PL is set to use MC's internal server for world gen")
			|| plainText.contains("this mode is less efficient unless a mod like C2ME is present.")
			|| plainText.contains("Saved screenshot as ");
	}

	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void pauc$filterGcWarning(Component component, CallbackInfo ci) {
		if (pauc$shouldSuppressSystemMessage(component)) {
			ci.cancel();
		}
	}

	@Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V", at = @At("HEAD"), cancellable = true, require = 0)
	private void pauc$filterGcWarning(Component component, MessageSignature signature, GuiMessageTag tag, CallbackInfo ci) {
		if (pauc$shouldSuppressSystemMessage(component)) {
			ci.cancel();
		}
	}
}
