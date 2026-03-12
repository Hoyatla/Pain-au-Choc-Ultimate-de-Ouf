package pauc.pain_au_choc;

import pauc.pain_au_choc.mixin.GameRendererAccessor;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.Projectile;

public final class DistanceBudgetController {
    private static final double CHUNK_SIZE = 16.0D;

    private DistanceBudgetController() {
    }

    public static double getViewDistanceBlocks() {
        return ManagedChunkRadiusController.getFullDetailDistanceBlocks();
    }

    public static double getCollisionCutoffDistanceBlocks() {
        return 15.0D * CHUNK_SIZE;
    }

    public static double getCollisionCutoffDistanceSqr() {
        return square(getCollisionCutoffDistanceBlocks());
    }

    public static double getFullDetailDistanceBlocks() {
        return getViewDistanceBlocks() * PauCClient.getActiveProfile().fullDetailRatio();
    }

    public static double getFullDetailDistanceSqr() {
        return square(getFullDetailDistanceBlocks());
    }

    public static double getReducedDetailDistanceBlocks() {
        return getViewDistanceBlocks() * PauCClient.getActiveProfile().reducedDetailRatio();
    }

    public static double getReducedDetailDistanceSqr() {
        return square(getReducedDetailDistanceBlocks());
    }

    public static double getAggressiveDetailDistanceBlocks() {
        return getViewDistanceBlocks() * PauCClient.getActiveProfile().aggressiveDetailRatio();
    }

    public static double getAggressiveDetailDistanceSqr() {
        return square(getAggressiveDetailDistanceBlocks());
    }

    public static double getEntityDistanceLimit(Entity entity) {
        QualityBudgetProfile profile = PauCClient.getActiveProfile();
        double baseDistance = getViewDistanceBlocks() * profile.entityDistanceRatio();

        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            return Math.max(CHUNK_SIZE, baseDistance * 0.45D);
        }

        if (entity instanceof AmbientCreature) {
            return Math.max(CHUNK_SIZE * 1.5D, baseDistance * 0.60D);
        }

        if (entity instanceof Projectile) {
            return Math.max(CHUNK_SIZE * 2.0D, baseDistance * 0.75D);
        }

        return Math.max(CHUNK_SIZE * 2.0D, baseDistance);
    }

    public static double getBlockEntityDistanceLimit() {
        QualityBudgetProfile profile = PauCClient.getActiveProfile();
        return Math.max(CHUNK_SIZE, getViewDistanceBlocks() * profile.blockEntityDistanceRatio());
    }

    public static double getEntityShadowDistanceLimit() {
        QualityBudgetProfile profile = PauCClient.getActiveProfile();
        if (!profile.entityShadows() || profile.shadowDistanceRatio() <= 0.0D) {
            return 0.0D;
        }
        return Math.max(CHUNK_SIZE, getViewDistanceBlocks() * profile.shadowDistanceRatio());
    }

    public static double getEntityShadowDistanceLimitSqr() {
        return square(getEntityShadowDistanceLimit());
    }

    public static double getVisibilityConeMarginDegrees(double bonusDegrees) {
        QualityBudgetProfile profile = PauCClient.getActiveProfile();
        return profile.coneMarginDegrees() + bonusDegrees;
    }

    public static double getCurrentCameraFovDegrees() {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer == null ? null : minecraft.gameRenderer.getMainCamera();
        if (camera == null || minecraft.options == null) {
            return 70.0D;
        }

        try {
            return ((GameRendererAccessor) minecraft.gameRenderer).pauc$invokeGetFov(camera, minecraft.getFrameTime(), true);
        } catch (RuntimeException ignored) {
            return minecraft.options.fov().get();
        }
    }

    public static double square(double value) {
        return value * value;
    }
}

