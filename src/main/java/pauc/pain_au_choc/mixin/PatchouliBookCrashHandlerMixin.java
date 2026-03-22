package pauc.pain_au_choc.mixin;

import net.minecraft.SystemReport;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Compatibility shim for userdev runs with production-obfuscated Patchouli jars.
 * We disable only Patchouli's crash report hook so it cannot call obfuscated
 * client symbols in a deobfuscated dev runtime.
 */
@Pseudo
@Mixin(targets = "vazkii.patchouli.client.handler.BookCrashHandler", remap = false)
public abstract class PatchouliBookCrashHandlerMixin {

    @Inject(method = "appendToCrashReport", at = @At("HEAD"), cancellable = true, remap = false)
    private static void pauc$skipPatchouliCrashContext(SystemReport report, CallbackInfo callbackInfo) {
        callbackInfo.cancel();
    }
}
