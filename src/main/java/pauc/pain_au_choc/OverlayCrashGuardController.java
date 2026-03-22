package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.HitResult;
import net.minecraftforge.client.event.RenderGuiOverlayEvent;

import java.util.Objects;

public final class OverlayCrashGuardController {
    private static final ResourceLocation CREATE_GOGGLE_OVERLAY_ID = Objects.requireNonNull(ResourceLocation.tryParse("create:goggle_info"));

    private OverlayCrashGuardController() {
    }

    public static void onRenderGuiOverlayPre(RenderGuiOverlayEvent.Pre event) {
        if (event == null || event.getOverlay() == null || event.getOverlay().id() == null) {
            return;
        }

        if (!CREATE_GOGGLE_OVERLAY_ID.equals(event.getOverlay().id())) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        HitResult hitResult = minecraft == null ? null : minecraft.hitResult;
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            event.setCanceled(true);
        }
    }
}
