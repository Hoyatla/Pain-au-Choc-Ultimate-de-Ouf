package pauc.pain_au_choc;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.wither.WitherBoss;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;

public final class ServerMobCadenceController {
    private static final double CRITICAL_DISTANCE_BLOCKS = 24.0D;
    private static final double COMBAT_DISTANCE_BLOCKS = 40.0D;
    private static final double MID_DISTANCE_BLOCKS = 56.0D;
    private static final double FAR_DISTANCE_BLOCKS = 96.0D;
    private static final double MAX_PLAYER_QUERY_BLOCKS = 192.0D;

    private ServerMobCadenceController() {
    }

    public static boolean shouldRunTargetSelectorTick(Mob mob, boolean runningOnlyPass) {
        int cadence = getSelectorCadence(mob);
        if (runningOnlyPass && cadence > 1) {
            cadence = Math.max(1, cadence - 1);
        }
        return shouldRunThisTick(mob, cadence);
    }

    public static boolean shouldRunGoalSelectorTick(Mob mob, boolean runningOnlyPass) {
        int cadence = getSelectorCadence(mob);
        if (runningOnlyPass && cadence > 1) {
            cadence = Math.max(1, cadence - 1);
        }
        return shouldRunThisTick(mob, cadence);
    }

    public static boolean shouldRunNavigationTick(Mob mob) {
        int cadence = getNavigationCadence(mob);
        return shouldRunThisTick(mob, cadence);
    }

    private static int getSelectorCadence(Mob mob) {
        if (!isBudgetApplicable(mob) || isCriticalMobState(mob)) {
            return 1;
        }

        int pressure = IntegratedServerLoadController.getPressureLevel();
        if (pressure <= 0) {
            return 1;
        }

        double distanceSqr = getNearestPlayerDistanceSqr(mob);
        if (distanceSqr <= MID_DISTANCE_BLOCKS * MID_DISTANCE_BLOCKS) {
            return pressure >= 3 ? 2 : 1;
        }

        if (distanceSqr <= FAR_DISTANCE_BLOCKS * FAR_DISTANCE_BLOCKS) {
            return GlobalPerformanceGovernor.adjustMobCadence(switch (pressure) {
                case 1 -> 2;
                case 2 -> 3;
                default -> 4;
            }, false);
        }

        return GlobalPerformanceGovernor.adjustMobCadence(switch (pressure) {
            case 1 -> 3;
            case 2 -> 4;
            default -> 5;
        }, false);
    }

    private static int getNavigationCadence(Mob mob) {
        int selectorCadence = getSelectorCadence(mob);
        if (selectorCadence <= 1) {
            return 1;
        }

        double distanceSqr = getNearestPlayerDistanceSqr(mob);
        if (distanceSqr > FAR_DISTANCE_BLOCKS * FAR_DISTANCE_BLOCKS) {
            return GlobalPerformanceGovernor.adjustMobCadence(Math.min(6, selectorCadence + 1), true);
        }

        return GlobalPerformanceGovernor.adjustMobCadence(selectorCadence, true);
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
