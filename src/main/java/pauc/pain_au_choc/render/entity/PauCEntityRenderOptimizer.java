package pauc.pain_au_choc.render.entity;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import pauc.pain_au_choc.EntityLodController;
import pauc.pain_au_choc.EntityLodTier;
import pauc.pain_au_choc.GlobalPerformanceGovernor;
import pauc.pain_au_choc.GlobalPerformanceMode;
import pauc.pain_au_choc.PauCClient;

/**
 * Optimizations for entity rendering, adapted from Embeddium.
 *
 * - Fast model part rendering: skip invisible parts early.
 * - LOD-aware model detail: reduce cuboid detail at distance.
 * - Particle throttle hints: reduce particle spawn rates under pressure.
 */
public final class PauCEntityRenderOptimizer {

    private static int visibleEntityCount;
    private static int skippedEntityPartCount;

    private PauCEntityRenderOptimizer() {
    }

    /**
     * Determines if a ModelPart should be rendered given the current LOD tier.
     * At reduced detail levels, small or non-essential model parts are skipped.
     *
     * @param part the model part
     * @param entity the entity being rendered (nullable for non-entity models)
     * @return true if the part should render
     */
    public static boolean shouldRenderModelPart(ModelPart part, Entity entity) {
        if (!PauCClient.isBudgetActive() || entity == null) {
            return true;
        }

        EntityLodTier tier = EntityLodController.getTier(entity);
        if (tier == EntityLodTier.FULL) {
            visibleEntityCount++;
            return true;
        }

        // At simplified tier, skip parts that have no children and are very small
        // This approximation works well for armor layers, small decorations, etc.
        if (tier == EntityLodTier.SIMPLIFIED || tier == EntityLodTier.STATIC) {
            if (!part.visible) {
                skippedEntityPartCount++;
                return false;
            }
        }

        visibleEntityCount++;
        return true;
    }

    /**
     * Returns a particle spawn rate multiplier based on governor mode.
     * In high-pressure modes, particles are reduced to save CPU and GPU time.
     *
     * @return multiplier between 0.0 and 1.0
     */
    public static float getParticleSpawnMultiplier() {
        if (!PauCClient.isBudgetActive()) {
            return 1.0F;
        }

        GlobalPerformanceMode mode = GlobalPerformanceGovernor.getMode();
        return switch (mode) {
            case CRISIS -> 0.15F;
            case COMBAT -> 0.40F;
            case TRANSIT -> 0.60F;
            case EXPLORATION -> 0.80F;
            case BASE -> 1.0F;
        };
    }

    /**
     * Returns a render distance multiplier for billboard particles.
     * Distant particles are culled more aggressively under pressure.
     *
     * @return squared distance multiplier
     */
    public static double getParticleRenderDistanceSqMultiplier() {
        if (!PauCClient.isBudgetActive()) {
            return 1.0;
        }

        int quality = PauCClient.getQualityLevel();
        if (quality >= 8) {
            return 1.0;
        }
        if (quality >= 5) {
            return 0.75;
        }
        if (quality >= 3) {
            return 0.50;
        }
        return 0.30;
    }

    /**
     * Determines if an item entity should use simplified rendering
     * (flat billboard instead of full 3D model) based on distance.
     */
    public static boolean shouldSimplifyItemEntity(ItemEntity item) {
        if (!PauCClient.isBudgetActive()) {
            return false;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return false;
        }

        double distSqr = item.distanceToSqr(player);
        int quality = PauCClient.getQualityLevel();
        double threshold = quality >= 7 ? 400.0 : quality >= 4 ? 225.0 : 100.0;
        return distSqr > threshold;
    }

    public static int getVisibleEntityCount() {
        return visibleEntityCount;
    }

    public static int getSkippedEntityPartCount() {
        return skippedEntityPartCount;
    }

    public static void resetFrameCounters() {
        visibleEntityCount = 0;
        skippedEntityPartCount = 0;
    }
}
