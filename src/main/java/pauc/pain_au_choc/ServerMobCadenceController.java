package pauc.pain_au_choc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

import java.util.Locale;

public final class ServerMobCadenceController {
    private static final double CRITICAL_DISTANCE_BLOCKS = 24.0D;
    private static final double COMBAT_DISTANCE_BLOCKS = 40.0D;
    private static final double MID_DISTANCE_BLOCKS = 56.0D;
    private static final double FAR_DISTANCE_BLOCKS = 96.0D;
    private static final double MAX_PLAYER_QUERY_BLOCKS = 192.0D;
    private static final double STATS_SMOOTHING = 0.20D;

    private static int lastSelectorCadence = 1;
    private static int lastNavigationCadence = 1;
    private static int lastMitigationTier;
    private static int statsTick;
    private static int targetChecksThisTick;
    private static int targetRunsThisTick;
    private static int goalChecksThisTick;
    private static int goalRunsThisTick;
    private static int navigationChecksThisTick;
    private static int navigationRunsThisTick;
    private static double smoothedTargetRunRatio = 1.0D;
    private static double smoothedGoalRunRatio = 1.0D;
    private static double smoothedNavigationRunRatio = 1.0D;

    private ServerMobCadenceController() {
    }

    public static boolean shouldRunTargetSelectorTick(Mob mob, boolean runningOnlyPass) {
        int cadence = getSelectorCadence(mob);
        if (runningOnlyPass && cadence > 1) {
            cadence = Math.max(1, cadence - 1);
        }
        boolean shouldRun = shouldRunThisTick(mob, cadence);
        if (isBudgetApplicable(mob)) {
            recordSelectorDecision(mob, cadence, shouldRun, true);
        }
        return shouldRun;
    }

    public static boolean shouldRunGoalSelectorTick(Mob mob, boolean runningOnlyPass) {
        int cadence = getSelectorCadence(mob);
        if (runningOnlyPass && cadence > 1) {
            cadence = Math.max(1, cadence - 1);
        }
        boolean shouldRun = shouldRunThisTick(mob, cadence);
        if (isBudgetApplicable(mob)) {
            recordSelectorDecision(mob, cadence, shouldRun, false);
        }
        return shouldRun;
    }

    public static boolean shouldRunNavigationTick(Mob mob) {
        int cadence = getNavigationCadence(mob);
        boolean shouldRun = shouldRunThisTick(mob, cadence);
        if (isBudgetApplicable(mob)) {
            recordNavigationDecision(mob, cadence, shouldRun);
        }
        return shouldRun;
    }

    public static void reset() {
        lastSelectorCadence = 1;
        lastNavigationCadence = 1;
        lastMitigationTier = 0;
        statsTick = 0;
        targetChecksThisTick = 0;
        targetRunsThisTick = 0;
        goalChecksThisTick = 0;
        goalRunsThisTick = 0;
        navigationChecksThisTick = 0;
        navigationRunsThisTick = 0;
        smoothedTargetRunRatio = 1.0D;
        smoothedGoalRunRatio = 1.0D;
        smoothedNavigationRunRatio = 1.0D;
    }

    public static int getLastSelectorCadence() {
        return lastSelectorCadence;
    }

    public static int getLastNavigationCadence() {
        return lastNavigationCadence;
    }

    public static int getLastMitigationTier() {
        return lastMitigationTier;
    }

    public static double getSmoothedTargetRunRatio() {
        return smoothedTargetRunRatio;
    }

    public static double getSmoothedGoalRunRatio() {
        return smoothedGoalRunRatio;
    }

    public static double getSmoothedNavigationRunRatio() {
        return smoothedNavigationRunRatio;
    }

    public static String getStatusLine() {
        return "mobCadence sel="
                + lastSelectorCadence
                + " nav="
                + lastNavigationCadence
                + " tier="
                + lastMitigationTier
                + " run="
                + String.format(
                        Locale.ROOT,
                        "%.2f/%.2f/%.2f",
                        smoothedTargetRunRatio,
                        smoothedGoalRunRatio,
                        smoothedNavigationRunRatio
                );
    }

    private static int getSelectorCadence(Mob mob) {
        if (!isBudgetApplicable(mob) || isCriticalMobState(mob)) {
            return 1;
        }

        int pressure = IntegratedServerLoadController.getPressureLevel();
        int mitigationTier = IntegratedServerLoadController.getMitigationTier();
        lastMitigationTier = mitigationTier;
        pressure = Math.max(pressure, mitigationTier);
        if (pressure <= 0) {
            return 1;
        }

        double distanceSqr = getNearestPlayerDistanceSqr(mob);
        if (distanceSqr <= MID_DISTANCE_BLOCKS * MID_DISTANCE_BLOCKS) {
            return pressure >= 3 ? 2 : 1;
        }

        if (distanceSqr <= FAR_DISTANCE_BLOCKS * FAR_DISTANCE_BLOCKS) {
            int cadence = GlobalPerformanceGovernor.adjustMobCadence(switch (pressure) {
                case 1 -> 2;
                case 2 -> 3;
                default -> 4;
            }, false);
            if (mitigationTier >= 3) {
                cadence = GlobalPerformanceGovernor.adjustMobCadence(cadence + 1, false);
            }
            return cadence;
        }

        int cadence = GlobalPerformanceGovernor.adjustMobCadence(switch (pressure) {
            case 1 -> 3;
            case 2 -> 4;
            default -> 5;
        }, false);
        if (mitigationTier >= 2) {
            cadence = GlobalPerformanceGovernor.adjustMobCadence(cadence + 1, false);
        }
        return cadence;
    }

    private static int getNavigationCadence(Mob mob) {
        int selectorCadence = getSelectorCadence(mob);
        if (selectorCadence <= 1) {
            return 1;
        }

        double distanceSqr = getNearestPlayerDistanceSqr(mob);
        int mitigationTier = IntegratedServerLoadController.getMitigationTier();
        lastMitigationTier = mitigationTier;
        if (distanceSqr > FAR_DISTANCE_BLOCKS * FAR_DISTANCE_BLOCKS) {
            int cadence = GlobalPerformanceGovernor.adjustMobCadence(Math.min(6, selectorCadence + 1), true);
            if (mitigationTier >= 2) {
                cadence = GlobalPerformanceGovernor.adjustMobCadence(Math.min(8, cadence + 1), true);
            }
            return cadence;
        }

        int cadence = GlobalPerformanceGovernor.adjustMobCadence(selectorCadence, true);
        if (mitigationTier >= 3 && distanceSqr > MID_DISTANCE_BLOCKS * MID_DISTANCE_BLOCKS) {
            cadence = GlobalPerformanceGovernor.adjustMobCadence(Math.min(7, cadence + 1), true);
        }
        return cadence;
    }

    private static boolean shouldRunThisTick(Mob mob, int cadence) {
        if (cadence <= 1) {
            return true;
        }

        int baseTick = IntegratedServerLoadController.getServerTick();
        if (baseTick <= 0) {
            baseTick = (int) (mob.level().getGameTime() & Integer.MAX_VALUE);
        }

        return Math.floorMod(baseTick + mob.getId(), cadence) == 0;
    }

    private static boolean isBudgetApplicable(Mob mob) {
        if (mob == null || !PauCClient.isBudgetActive()) {
            return false;
        }

        if (!(mob.level() instanceof ServerLevel serverLevel)) {
            return false;
        }

        return IntegratedServerLoadController.isActiveFor(serverLevel);
    }

    private static void recordSelectorDecision(Mob mob, int cadence, boolean shouldRun, boolean targetSelector) {
        rollStatsIfNeeded(mob);
        lastSelectorCadence = Math.max(1, cadence);
        if (targetSelector) {
            targetChecksThisTick++;
            if (shouldRun) {
                targetRunsThisTick++;
            }
            return;
        }

        goalChecksThisTick++;
        if (shouldRun) {
            goalRunsThisTick++;
        }
    }

    private static void recordNavigationDecision(Mob mob, int cadence, boolean shouldRun) {
        rollStatsIfNeeded(mob);
        lastNavigationCadence = Math.max(1, cadence);
        navigationChecksThisTick++;
        if (shouldRun) {
            navigationRunsThisTick++;
        }
    }

    private static void rollStatsIfNeeded(Mob mob) {
        int currentTick = resolveBaseTick(mob);
        if (currentTick <= 0) {
            return;
        }

        if (statsTick <= 0) {
            statsTick = currentTick;
            return;
        }

        if (currentTick == statsTick) {
            return;
        }

        smoothedTargetRunRatio = smoothRatio(smoothedTargetRunRatio, targetRunsThisTick, targetChecksThisTick);
        smoothedGoalRunRatio = smoothRatio(smoothedGoalRunRatio, goalRunsThisTick, goalChecksThisTick);
        smoothedNavigationRunRatio = smoothRatio(smoothedNavigationRunRatio, navigationRunsThisTick, navigationChecksThisTick);
        targetChecksThisTick = 0;
        targetRunsThisTick = 0;
        goalChecksThisTick = 0;
        goalRunsThisTick = 0;
        navigationChecksThisTick = 0;
        navigationRunsThisTick = 0;
        statsTick = currentTick;
    }

    private static int resolveBaseTick(Mob mob) {
        int baseTick = IntegratedServerLoadController.getServerTick();
        if (baseTick > 0) {
            return baseTick;
        }
        if (mob == null || mob.level() == null) {
            return 0;
        }
        return (int) (mob.level().getGameTime() & Integer.MAX_VALUE);
    }

    private static double smoothRatio(double previous, int runs, int checks) {
        if (checks <= 0) {
            return previous;
        }
        double ratio = Math.max(0.0D, Math.min(1.0D, runs / (double) checks));
        return previous + ((ratio - previous) * STATS_SMOOTHING);
    }

    private static boolean isCriticalMobState(Mob mob) {
        if (mob.getTarget() != null) {
            return true;
        }

        if (mob.isPassenger() || mob.isVehicle() || mob.isLeashed()) {
            return true;
        }

        if (mob instanceof EnderDragon || mob instanceof WitherBoss) {
            return true;
        }

        double distanceSqr = getNearestPlayerDistanceSqr(mob);
        if (distanceSqr <= CRITICAL_DISTANCE_BLOCKS * CRITICAL_DISTANCE_BLOCKS) {
            return true;
        }

        return mob instanceof Enemy && distanceSqr <= COMBAT_DISTANCE_BLOCKS * COMBAT_DISTANCE_BLOCKS;
    }

    private static double getNearestPlayerDistanceSqr(Mob mob) {
        if (!(mob.level() instanceof ServerLevel serverLevel)) {
            return 0.0D;
        }

        Player nearestPlayer = serverLevel.getNearestPlayer(mob, MAX_PLAYER_QUERY_BLOCKS);
        if (nearestPlayer == null) {
            return Double.MAX_VALUE;
        }

        return mob.distanceToSqr(nearestPlayer);
    }
}
