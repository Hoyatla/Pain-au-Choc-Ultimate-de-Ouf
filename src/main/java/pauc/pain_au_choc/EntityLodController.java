package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;

public final class EntityLodController {
    private static final double FULL_DETAIL_DISTANCE_BLOCKS = 20.0D;
    private static final double SIMPLIFIED_DISTANCE_BLOCKS = 40.0D;
    private static final double STATIC_DISTANCE_BLOCKS = 80.0D;
    private static final int STATIC_FRAME_DIVISOR = 2;

    private static int frameCounter;

    private EntityLodController() {
    }

    public static void onClientTick() {
        frameCounter = frameCounter == Integer.MAX_VALUE ? 0 : frameCounter + 1;
    }

    public static void reset() {
        frameCounter = 0;
    }

    public static EntityLodTier getTier(Entity entity) {
        if (!PauCClient.isBudgetActive() || entity == null) {
            return EntityLodTier.FULL;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || isAlwaysFullDetail(entity, player)) {
            return EntityLodTier.FULL;
        }

        if (!(entity instanceof LivingEntity) || !entity.isAlive()) {
            return EntityLodTier.FULL;
        }

        double distanceSqr = entity.distanceToSqr(player);
        double distanceFactor = getQualityDistanceFactor();
        double fullDistanceSqr = DistanceBudgetController.square(FULL_DETAIL_DISTANCE_BLOCKS * distanceFactor);
        if (distanceSqr <= fullDistanceSqr) {
            return EntityLodTier.FULL;
        }

        double simplifiedDistanceSqr = DistanceBudgetController.square(SIMPLIFIED_DISTANCE_BLOCKS * distanceFactor);
        if (distanceSqr <= simplifiedDistanceSqr) {
            return EntityLodTier.SIMPLIFIED;
        }

        double staticDistanceSqr = DistanceBudgetController.square(STATIC_DISTANCE_BLOCKS * distanceFactor);
        if (distanceSqr <= staticDistanceSqr) {
            return GeckoLibCompat.isGeckoEntity(entity) ? EntityLodTier.SIMPLIFIED : EntityLodTier.STATIC;
        }

        if (CompatibilityGuards.shouldDisableEntityLodBillboards()) {
            return EntityLodTier.STATIC;
        }

        return GeckoLibCompat.isGeckoEntity(entity) ? EntityLodTier.SIMPLIFIED : EntityLodTier.BILLBOARD;
    }

    public static boolean shouldRenderEntityThisFrame(Entity entity) {
        if (entity == null) {
            return true;
        }

        EntityLodTier tier = getTier(entity);
        if (tier != EntityLodTier.STATIC) {
            return true;
        }

        int phase = Math.floorMod(entity.getId(), STATIC_FRAME_DIVISOR);
        int framePhase = Math.floorMod(frameCounter, STATIC_FRAME_DIVISOR);
        return framePhase == phase;
    }

    public static boolean shouldRenderAsBillboard(Entity entity) {
        return getTier(entity) == EntityLodTier.BILLBOARD;
    }

    public static float remapPartialTick(Entity entity, float partialTick) {
        EntityLodTier tier = getTier(entity);
        if (tier == EntityLodTier.SIMPLIFIED) {
            return quantizePartialTick(partialTick, 0.25F);
        }

        if (tier == EntityLodTier.STATIC || tier == EntityLodTier.BILLBOARD) {
            return 0.0F;
        }

        return partialTick;
    }

    public static boolean shouldRenderShadows(Entity entity) {
        EntityLodTier tier = getTier(entity);
        return tier == EntityLodTier.FULL || tier == EntityLodTier.SIMPLIFIED;
    }

    public static float getBillboardHalfWidth(Entity entity) {
        float entityWidth = Math.max(0.45F, entity.getBbWidth());
        float entityHeight = Math.max(0.90F, entity.getBbHeight());
        float baseScale = Math.max(entityWidth, entityHeight * 0.45F);
        return Mth.clamp(baseScale * 0.50F, 0.35F, 1.40F);
    }

    public static float getBillboardHeight(Entity entity) {
        float entityHeight = Math.max(0.90F, entity.getBbHeight());
        return Mth.clamp(entityHeight * 0.80F, 0.75F, 2.00F);
    }

    public static int getBillboardColorArgb(Entity entity) {
        if (entity instanceof net.minecraft.world.entity.monster.Enemy) {
            return 0xCCFF5A5A;
        }
        if (entity instanceof net.minecraft.world.entity.animal.Animal) {
            return 0xCC7CE8A3;
        }
        if (entity instanceof net.minecraft.world.entity.npc.AbstractVillager) {
            return 0xCCE6CC8F;
        }
        return 0xCCB8C2D4;
    }

    private static boolean isAlwaysFullDetail(Entity entity, LocalPlayer player) {
        if (entity == player || entity.isPassengerOfSameVehicle(player)) {
            return true;
        }
        if (entity instanceof Player || entity instanceof Projectile) {
            return true;
        }
        return entity instanceof EnderDragon || entity instanceof WitherBoss;
    }

    private static double getQualityDistanceFactor() {
        double normalized = (PauCClient.getQualityLevel() - PauCClient.getMinQualityLevel())
                / (double) (PauCClient.getMaxQualityLevel() - PauCClient.getMinQualityLevel());
        return 0.85D + normalized * 0.30D;
    }

    private static float quantizePartialTick(float partialTick, float step) {
        if (step <= 0.0F) {
            return partialTick;
        }

        float clampedTick = Mth.clamp(partialTick, 0.0F, 1.0F);
        float snapped = Math.round(clampedTick / step) * step;
        return Mth.clamp(snapped, 0.0F, 1.0F);
    }
}

