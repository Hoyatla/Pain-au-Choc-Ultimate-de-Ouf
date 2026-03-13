package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;

public final class GlobalPerformanceGovernor {
    private static final int COMBAT_HOLD_TICKS = 60;
    private static final int COMBAT_SAMPLE_INTERVAL_TICKS = 5;
    private static final int BASE_SAMPLE_INTERVAL_TICKS = 20;
    private static final int BASE_SCAN_RADIUS_CHUNKS = 2;
    private static final int BASE_BLOCK_ENTITY_THRESHOLD = 20;
    private static final double COMBAT_ENEMY_RADIUS_BLOCKS = 36.0D;
    private static final double COMBAT_PROJECTILE_RADIUS_BLOCKS = 28.0D;
    private static final double COMBAT_KEEP_RADIUS_BLOCKS = 48.0D;
    private static final double TRANSIT_SPEED_THRESHOLD_SQR = 0.18D * 0.18D;

    private static GlobalPerformanceMode mode = GlobalPerformanceMode.EXPLORATION;
    private static int globalPressure;
    private static int combatHoldTicks;
    private static int sampledEnemyCount;
    private static int sampledProjectileCount;
    private static int sampledNearbyBlockEntities;
    private static int tickCounter;
    private static int trackedLevelIdentity;

    private GlobalPerformanceGovernor() {
    }

    public static void onClientTick() {
        if (!PauCClient.isBudgetActive()) {
            reset();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null) {
            reset();
            return;
        }

        int levelIdentity = System.identityHashCode(level);
        if (trackedLevelIdentity != levelIdentity) {
            reset();
            trackedLevelIdentity = levelIdentity;
        }

        tickCounter++;
        if (tickCounter % COMBAT_SAMPLE_INTERVAL_TICKS == 0) {
            sampleCombatSignals(level, player);
        } else if (combatHoldTicks > 0) {
            combatHoldTicks--;
        }

        if (tickCounter % BASE_SAMPLE_INTERVAL_TICKS == 0) {
            sampledNearbyBlockEntities = sampleNearbyBlockEntities(level, player);
        }

        globalPressure = computeGlobalPressure();
        mode = resolveMode(player);
    }

    public static void reset() {
        mode = GlobalPerformanceMode.EXPLORATION;
        globalPressure = 0;
        combatHoldTicks = 0;
        sampledEnemyCount = 0;
        sampledProjectileCount = 0;
        sampledNearbyBlockEntities = 0;
        tickCounter = 0;
        trackedLevelIdentity = 0;
    }

    public static GlobalPerformanceMode getMode() {
        return mode;
    }

    public static int getGlobalPressure() {
        return globalPressure;
    }

    public static boolean isReadabilityProtected() {
        return combatHoldTicks > 0 || mode == GlobalPerformanceMode.COMBAT;
    }

    public static boolean shouldAllowClientEntityPruning() {
        return !isReadabilityProtected();
    }

    public static boolean shouldProtectCombatEntity(Entity entity, LocalPlayer player) {
        if (!isReadabilityProtected() || entity == null || player == null) {
            return false;
        }

        if (entity == player || entity.isPassengerOfSameVehicle(player)) {
            return true;
        }

        if (!(entity instanceof Enemy) && !(entity instanceof Projectile) && !(entity instanceof Player)) {
            return false;
        }

        return entity.distanceToSqr(player) <= square(COMBAT_KEEP_RADIUS_BLOCKS);
    }

    public static double getEffectiveDynamicResolutionMinScale(double configuredMinScale) {
        double clampedScale = clampScale(configuredMinScale);
        return switch (mode) {
            case COMBAT -> Math.max(clampedScale, 0.78D);
            case TRANSIT -> Math.max(0.56D, clampedScale - 0.10D);
            case BASE -> Math.max(0.60D, clampedScale - 0.04D);
            case CRISIS -> Math.max(0.52D, clampedScale - 0.14D);
            default -> clampedScale;
        };
    }

    public static double getChunkCompileBudgetMultiplier() {
        return switch (mode) {
            case TRANSIT -> 1.25D;
            case COMBAT -> 0.95D;
            case BASE -> 0.85D;
            case CRISIS -> 0.75D;
            default -> 1.00D;
        };
    }

    /**
     * Shadow distance multiplier per governor mode.
     * CRISIS and COMBAT reduce shadow distance to save GPU time,
     * BASE stays normal, TRANSIT and EXPLORATION are full.
     */
    public static double getShadowDistanceMultiplier() {
        return switch (mode) {
            case CRISIS -> 0.50D;
            case COMBAT -> 0.75D;
            case BASE -> 0.90D;
            case TRANSIT -> 1.00D;
            default -> 1.00D; // EXPLORATION
        };
    }

    /**
     * Whether the deferred shader pipeline should skip shadow rendering entirely.
     * Only in extreme crisis with high pressure.
     */
    public static boolean shouldSkipShadowPass() {
        return mode == GlobalPerformanceMode.CRISIS && globalPressure >= 3;
    }

    public static boolean shouldFavorPlayerAffectedChunkPriority() {
        return mode == GlobalPerformanceMode.TRANSIT || mode == GlobalPerformanceMode.COMBAT || mode == GlobalPerformanceMode.CRISIS;
    }

    public static boolean shouldFavorNearbyChunkPriority() {
        return mode == GlobalPerformanceMode.BASE;
    }

    public static int adjustMobCadence(int cadence, boolean navigation) {
        if (cadence <= 1) {
            return 1;
        }

        int modeAdjustedCadence = switch (mode) {
            case COMBAT -> Math.min(cadence, 2);
            case BASE, CRISIS -> Math.min(navigation ? 7 : 6, cadence + 1);
            default -> cadence;
        };
        return AuthoritativeRuntimeController.adjustMobCadence(modeAdjustedCadence, navigation);
    }

    private static void sampleCombatSignals(ClientLevel level, LocalPlayer player) {
        int nearbyEnemies = 0;
        int nearbyProjectiles = 0;
        double enemyRadiusSqr = square(COMBAT_ENEMY_RADIUS_BLOCKS);
        double projectileRadiusSqr = square(COMBAT_PROJECTILE_RADIUS_BLOCKS);

        for (Entity entity : level.entitiesForRendering()) {
            if (entity == null || entity.isRemoved() || entity == player) {
                continue;
            }

            double distanceSqr = entity.distanceToSqr(player);
            if (entity instanceof Enemy && distanceSqr <= enemyRadiusSqr) {
                nearbyEnemies++;
            } else if (entity instanceof Projectile && distanceSqr <= projectileRadiusSqr) {
                nearbyProjectiles++;
            }

            if (nearbyEnemies >= 6 && nearbyProjectiles >= 3) {
                break;
            }
        }

        sampledEnemyCount = nearbyEnemies;
        sampledProjectileCount = nearbyProjectiles;
        boolean inCombatNow = player.hurtTime > 0 || nearbyEnemies > 0 || nearbyProjectiles >= 2;
        if (inCombatNow) {
            combatHoldTicks = COMBAT_HOLD_TICKS;
        } else if (combatHoldTicks > 0) {
            combatHoldTicks--;
        }
    }

    private static int sampleNearbyBlockEntities(ClientLevel level, LocalPlayer player) {
        int playerChunkX = Mth.floor(player.getX()) >> 4;
        int playerChunkZ = Mth.floor(player.getZ()) >> 4;
        int count = 0;

        for (int offsetX = -BASE_SCAN_RADIUS_CHUNKS; offsetX <= BASE_SCAN_RADIUS_CHUNKS; offsetX++) {
            for (int offsetZ = -BASE_SCAN_RADIUS_CHUNKS; offsetZ <= BASE_SCAN_RADIUS_CHUNKS; offsetZ++) {
                int chunkX = playerChunkX + offsetX;
                int chunkZ = playerChunkZ + offsetZ;
                if (!level.hasChunk(chunkX, chunkZ)) {
                    continue;
                }

                LevelChunk chunk = level.getChunk(chunkX, chunkZ);
                count += chunk.getBlockEntities().size();
                if (count >= BASE_BLOCK_ENTITY_THRESHOLD) {
                    return count;
                }
            }
        }

        return count;
    }

    private static int computeGlobalPressure() {
        int latencyPressure = LatencyController.getPressureLevel();
        int serverPressure = IntegratedServerLoadController.getPressureLevel();
        int bottleneckPressure = BottleneckController.isGpuBound() || BottleneckController.isCpuBound() ? 1 : 0;
        int chunkPressure = ChunkBuildQueueController.getBackPressureRatio() >= 0.85F ? 2 : 0;
        int authorityPressure = AuthoritativeRuntimeController.getRuntimePressureBias();
        return Math.max(
                Math.max(latencyPressure, serverPressure),
                Math.max(Math.max(bottleneckPressure, chunkPressure), authorityPressure)
        );
    }

    private static GlobalPerformanceMode resolveMode(LocalPlayer player) {
        boolean crisis = globalPressure >= 3 || (LatencyController.getPressureLevel() >= 2 && IntegratedServerLoadController.getPressureLevel() >= 2);
        if (crisis) {
            return GlobalPerformanceMode.CRISIS;
        }

        if (isReadabilityProtected()) {
            return GlobalPerformanceMode.COMBAT;
        }

        if (isPlayerInTransit(player)) {
            return GlobalPerformanceMode.TRANSIT;
        }

        if (sampledNearbyBlockEntities >= BASE_BLOCK_ENTITY_THRESHOLD) {
            return GlobalPerformanceMode.BASE;
        }

        return GlobalPerformanceMode.EXPLORATION;
    }

    private static boolean isPlayerInTransit(LocalPlayer player) {
        if (player == null) {
            return false;
        }

        if (player.isFallFlying() || player.isPassenger()) {
            return true;
        }

        Vec3 delta = player.getDeltaMovement();
        double horizontalSpeedSqr = delta.x * delta.x + delta.z * delta.z;
        return horizontalSpeedSqr >= TRANSIT_SPEED_THRESHOLD_SQR;
    }

    private static double clampScale(double value) {
        return Math.max(0.50D, Math.min(1.00D, value));
    }

    private static double square(double value) {
        return value * value;
    }
}
