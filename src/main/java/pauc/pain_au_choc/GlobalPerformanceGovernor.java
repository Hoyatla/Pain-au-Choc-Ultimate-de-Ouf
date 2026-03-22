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
    private static final int SLOW_DECISION_INTERVAL_TICKS = 10;
    private static final int MODE_SWITCH_COOLDOWN_TICKS = 40;
    private static final float CRISIS_CHUNK_BACKLOG_THRESHOLD = 0.97F;
    private static final int BASE_SCAN_RADIUS_CHUNKS = 2;
    private static final int BASE_BLOCK_ENTITY_THRESHOLD = 20;
    private static final double COMBAT_ENEMY_RADIUS_BLOCKS = 36.0D;
    private static final double COMBAT_PROJECTILE_RADIUS_BLOCKS = 28.0D;
    private static final double COMBAT_KEEP_RADIUS_BLOCKS = 48.0D;
    private static final double TRANSIT_ENTER_SPEED_THRESHOLD_SQR = 0.26D * 0.26D;
    private static final double TRANSIT_EXIT_SPEED_THRESHOLD_SQR = 0.14D * 0.14D;
    private static final int TRANSIT_HOLD_TICKS = 24;

    private static GlobalPerformanceMode mode = GlobalPerformanceMode.EXPLORATION;
    private static int globalPressure;
    private static int combatHoldTicks;
    private static int sampledEnemyCount;
    private static int sampledProjectileCount;
    private static int sampledNearbyBlockEntities;
    private static int tickCounter;
    private static int decisionTickCounter;
    private static int modeSwitchCooldownTicks;
    private static int trackedLevelIdentity;
    private static GlobalPerformanceMode pendingMode = GlobalPerformanceMode.EXPLORATION;
    private static int pendingModeTicks;
    private static int modeTransitionCount;
    private static int transitHoldTicks;

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
        decisionTickCounter++;
        if (modeSwitchCooldownTicks > 0) {
            modeSwitchCooldownTicks--;
        }
        if (transitHoldTicks > 0) {
            transitHoldTicks--;
        }

        if (tickCounter % COMBAT_SAMPLE_INTERVAL_TICKS == 0) {
            sampleCombatSignals(level, player);
        } else if (combatHoldTicks > 0) {
            combatHoldTicks--;
        }

        if (tickCounter % BASE_SAMPLE_INTERVAL_TICKS == 0) {
            sampledNearbyBlockEntities = sampleNearbyBlockEntities(level, player);
        }

        globalPressure = computeGlobalPressure();
        GlobalPerformanceMode desiredMode = resolveMode(player);
        updateModeWithHysteresis(desiredMode);
    }

    public static void reset() {
        mode = GlobalPerformanceMode.EXPLORATION;
        globalPressure = 0;
        combatHoldTicks = 0;
        sampledEnemyCount = 0;
        sampledProjectileCount = 0;
        sampledNearbyBlockEntities = 0;
        tickCounter = 0;
        decisionTickCounter = 0;
        modeSwitchCooldownTicks = 0;
        trackedLevelIdentity = 0;
        pendingMode = GlobalPerformanceMode.EXPLORATION;
        pendingModeTicks = 0;
        modeTransitionCount = 0;
        transitHoldTicks = 0;
    }

    public static GlobalPerformanceMode getMode() {
        return mode;
    }

    public static int getGlobalPressure() {
        return globalPressure;
    }

    public static int getModeSwitchCooldownTicks() {
        return modeSwitchCooldownTicks;
    }

    public static int getModeTransitionCount() {
        return modeTransitionCount;
    }

    /**
     * Ordered visual degradation tier.
     * 0 = no extra cuts, 4 = severe cuts.
     */
    public static int getRenderDegradeTier() {
        return switch (mode) {
            case EXPLORATION -> 0;
            case TRANSIT -> 1;
            case BASE, COMBAT -> 2;
            case CRISIS -> 4;
        };
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
        boolean gpuBound = BottleneckController.isGpuBound();
        boolean cpuBound = BottleneckController.isCpuBound();
        int latencyPressure = LatencyController.getPressureLevel();
        int serverPressure = IntegratedServerLoadController.getPressureLevel();
        boolean severePressure = globalPressure >= 3 || latencyPressure >= 2 || gpuBound;
        boolean moderatePressure = globalPressure >= 2 || serverPressure >= 2 || (cpuBound && latencyPressure >= 1);
        return switch (mode) {
            case COMBAT -> {
                if (severePressure) {
                    yield Math.max(0.38D, clampedScale - 0.32D);
                }
                if (moderatePressure) {
                    yield Math.max(0.56D, clampedScale - 0.12D);
                }
                yield Math.max(0.72D, clampedScale);
            }
            case TRANSIT -> {
                if (severePressure) {
                    yield Math.max(0.40D, clampedScale - 0.30D);
                }
                if (moderatePressure) {
                    yield Math.max(0.56D, clampedScale - 0.14D);
                }
                yield Math.max(0.58D, clampedScale - 0.10D);
            }
            case BASE -> {
                if (severePressure) {
                    yield Math.max(0.42D, clampedScale - 0.26D);
                }
                if (moderatePressure) {
                    yield Math.max(0.58D, clampedScale - 0.08D);
                }
                yield Math.max(0.60D, clampedScale - 0.04D);
            }
            case CRISIS -> severePressure
                    ? Math.max(0.35D, clampedScale - 0.38D)
                    : Math.max(0.44D, clampedScale - 0.24D);
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
        if (mode == GlobalPerformanceMode.COMBAT && globalPressure >= 3) {
            return 0.55D;
        }
        if (mode == GlobalPerformanceMode.TRANSIT && globalPressure >= 3) {
            return 0.70D;
        }
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
        if (mode == GlobalPerformanceMode.CRISIS && globalPressure >= 3) {
            return true;
        }
        return mode == GlobalPerformanceMode.COMBAT
                && globalPressure >= 3
                && (BottleneckController.isGpuBound() || LatencyController.getPressureLevel() >= 1);
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
        int latencyPressure = LatencyController.getPressureLevel();
        int serverPressure = IntegratedServerLoadController.getPressureLevel();
        boolean chunkBacklogCritical = ChunkBuildQueueController.getBackPressureRatio() >= CRISIS_CHUNK_BACKLOG_THRESHOLD;
        boolean hardServerCrisis = serverPressure >= 3;
        boolean coupledPressureCrisis = serverPressure >= 2 && latencyPressure >= 2;
        boolean chunkAndServerCrisis = chunkBacklogCritical && serverPressure >= 2;
        boolean crisis = hardServerCrisis || coupledPressureCrisis || chunkAndServerCrisis;
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

    private static void updateModeWithHysteresis(GlobalPerformanceMode desiredMode) {
        if (desiredMode == null) {
            return;
        }

        if (desiredMode == mode) {
            pendingMode = mode;
            pendingModeTicks = 0;
            return;
        }

        if (desiredMode == GlobalPerformanceMode.CRISIS && isImmediateCrisisRequired()) {
            switchMode(GlobalPerformanceMode.CRISIS);
            return;
        }

        if (desiredMode == GlobalPerformanceMode.COMBAT && mode != GlobalPerformanceMode.CRISIS) {
            switchMode(GlobalPerformanceMode.COMBAT);
            return;
        }

        if (desiredMode != pendingMode) {
            pendingMode = desiredMode;
            pendingModeTicks = 1;
        } else {
            pendingModeTicks = Math.min(200, pendingModeTicks + 1);
        }

        if (modeSwitchCooldownTicks > 0) {
            return;
        }

        if (decisionTickCounter % SLOW_DECISION_INTERVAL_TICKS != 0) {
            return;
        }

        if (pendingModeTicks < getRequiredPendingTicks(desiredMode)) {
            return;
        }

        switchMode(desiredMode);
    }

    private static int getRequiredPendingTicks(GlobalPerformanceMode targetMode) {
        return switch (targetMode) {
            case EXPLORATION -> 6;
            case TRANSIT -> 6;
            case BASE -> 8;
            case COMBAT -> 1;
            case CRISIS -> 4;
        };
    }

    private static boolean isImmediateCrisisRequired() {
        return IntegratedServerLoadController.getPressureLevel() >= 3;
    }

    private static void switchMode(GlobalPerformanceMode newMode) {
        if (newMode == mode) {
            return;
        }

        GlobalPerformanceMode previousMode = mode;
        mode = newMode;
        pendingMode = newMode;
        pendingModeTicks = 0;
        modeSwitchCooldownTicks = getModeCooldownTicks(newMode);
        modeTransitionCount++;
        Pain_au_Choc.LOGGER.info(
                "PauC governor mode {} -> {} (pressure={}, latency={}, server={}, cooldown={})",
                previousMode,
                newMode,
                globalPressure,
                LatencyController.getPressureLevel(),
                IntegratedServerLoadController.getPressureLevel(),
                modeSwitchCooldownTicks
        );
    }

    private static int getModeCooldownTicks(GlobalPerformanceMode targetMode) {
        return switch (targetMode) {
            case CRISIS -> MODE_SWITCH_COOLDOWN_TICKS + 32;
            case COMBAT -> MODE_SWITCH_COOLDOWN_TICKS + 20;
            case TRANSIT, BASE -> MODE_SWITCH_COOLDOWN_TICKS + 24;
            default -> MODE_SWITCH_COOLDOWN_TICKS + 8;
        };
    }

    private static boolean isPlayerInTransit(LocalPlayer player) {
        if (player == null) {
            return false;
        }

        if (player.isFallFlying() || player.isPassenger()) {
            transitHoldTicks = TRANSIT_HOLD_TICKS;
            return true;
        }

        Vec3 delta = player.getDeltaMovement();
        double horizontalSpeedSqr = delta.x * delta.x + delta.z * delta.z;
        if (horizontalSpeedSqr >= TRANSIT_ENTER_SPEED_THRESHOLD_SQR) {
            transitHoldTicks = TRANSIT_HOLD_TICKS;
            return true;
        }

        return transitHoldTicks > 0 && horizontalSpeedSqr >= TRANSIT_EXIT_SPEED_THRESHOLD_SQR;
    }

    private static double clampScale(double value) {
        return Math.max(0.35D, Math.min(1.00D, value));
    }

    private static double square(double value) {
        return value * value;
    }
}
