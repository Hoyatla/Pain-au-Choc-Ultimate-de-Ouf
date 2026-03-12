package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;

import java.util.ArrayList;
import java.util.List;

public final class ClientWorldCleanupController {
    private static final int CLEANUP_INTERVAL_TICKS = 40;
    private static final int MAX_REMOVALS_PER_PASS = 32;
    private static final int WORK_PHASE = 2;
    private static final List<Entity> REMOVAL_BUFFER = new ArrayList<>();
    private static int cleanupTicker;

    private ClientWorldCleanupController() {
    }

    public static void tick() {
        if (!PauCClient.isBudgetActive()) {
            cleanupTicker = 0;
            REMOVAL_BUFFER.clear();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            cleanupTicker = 0;
            REMOVAL_BUFFER.clear();
            return;
        }

        cleanupTicker++;
        int adaptiveInterval = LatencyController.getAdaptiveInterval(CLEANUP_INTERVAL_TICKS);
        if (cleanupTicker < adaptiveInterval) {
            return;
        }

        if (!LatencyController.shouldRunBackgroundWork(WORK_PHASE)) {
            return;
        }
        cleanupTicker = 0;

        REMOVAL_BUFFER.clear();
        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (RenderBudgetManager.shouldPruneClientEntity(entity)) {
                REMOVAL_BUFFER.add(entity);
                if (REMOVAL_BUFFER.size() >= MAX_REMOVALS_PER_PASS) {
                    break;
                }
            }
        }

        for (Entity entity : REMOVAL_BUFFER) {
            if (!entity.isRemoved()) {
                entity.discard();
            }
        }

        if (!REMOVAL_BUFFER.isEmpty() && Pain_au_Choc.LOGGER.isDebugEnabled()) {
            Pain_au_Choc.LOGGER.debug("PauC pruned {} client entities for tick headroom", REMOVAL_BUFFER.size());
        }

        REMOVAL_BUFFER.clear();
    }
}


