package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.PrioritizeChunkUpdates;

public final class ChunkBuildQueueController {
    private static final int MIN_BUILD_BUDGET = 2;
    private static final int MAX_BUILD_BUDGET = 24;

    private static PrioritizeChunkUpdates baselinePriority;
    private static boolean managingPriority;
    private static int compileBudget;
    private static int scheduledCount;
    private static int deferredCount;
    private static int backPressureTicks;

    private ChunkBuildQueueController() {
    }

    public static void onClientTick() {
        if (!PauCClient.isBudgetActive()) {
            reset();
            return;
        }

        syncPriorityMode();
        if (backPressureTicks > 0 && deferredCount == 0) {
            backPressureTicks--;
        }
    }

    public static int beginCompilePass() {
        if (!PauCClient.isBudgetActive()) {
            compileBudget = Integer.MAX_VALUE;
            scheduledCount = 0;
            deferredCount = 0;
            return compileBudget;
        }

        compileBudget = computeBudgetValue();
        scheduledCount = 0;
        deferredCount = 0;
        return compileBudget;
    }

    /**
     * Compute a budget preview without mutating compile-pass counters/state.
     * This is used by PAUC-side builders that need a budget hint but do not
     * participate in vanilla's consumeBuildSlot() accounting.
     */
    public static int previewCompileBudget() {
        if (!PauCClient.isBudgetActive()) {
            return Integer.MAX_VALUE;
        }
        return computeBudgetValue();
    }

    public static boolean consumeBuildSlot() {
        if (compileBudget == Integer.MAX_VALUE) {
            scheduledCount++;
            return true;
        }

        if (compileBudget > 0) {
            compileBudget--;
            scheduledCount++;
            return true;
        }

        deferredCount++;
        return false;
    }

    public static void endCompilePass() {
        if (deferredCount > 0) {
            backPressureTicks = Math.min(72, backPressureTicks + Math.max(1, deferredCount / 2));
        } else if (scheduledCount > 0) {
            backPressureTicks = Math.max(0, backPressureTicks - 1);
        }
    }

    public static float getBackPressureRatio() {
        return Math.max(0.0F, Math.min(1.0F, backPressureTicks / 48.0F));
    }

    public static void reset() {
        restorePriorityMode();
        compileBudget = 0;
        scheduledCount = 0;
        deferredCount = 0;
        backPressureTicks = 0;
    }

    private static void syncPriorityMode() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options == null) {
            return;
        }

        if (!managingPriority) {
            baselinePriority = minecraft.options.prioritizeChunkUpdates().get();
            managingPriority = true;
        }

        PrioritizeChunkUpdates targetPriority;
        if (AuthoritativeRuntimeController.shouldForcePlayerAffectedChunkPriority()) {
            targetPriority = PrioritizeChunkUpdates.PLAYER_AFFECTED;
        } else if (GlobalPerformanceGovernor.shouldFavorPlayerAffectedChunkPriority()) {
            targetPriority = PrioritizeChunkUpdates.PLAYER_AFFECTED;
        } else if (GlobalPerformanceGovernor.shouldFavorNearbyChunkPriority()) {
            targetPriority = PrioritizeChunkUpdates.NEARBY;
        } else if (BottleneckController.isCpuBound() || LatencyController.getPressureLevel() >= 2) {
            targetPriority = PrioritizeChunkUpdates.PLAYER_AFFECTED;
        } else if (LatencyController.getPressureLevel() >= 1) {
            targetPriority = PrioritizeChunkUpdates.NEARBY;
        } else {
            targetPriority = baselinePriority == null ? PrioritizeChunkUpdates.NONE : baselinePriority;
        }

        if (minecraft.options.prioritizeChunkUpdates().get() != targetPriority) {
            minecraft.options.prioritizeChunkUpdates().set(targetPriority);
        }
    }

    private static void restorePriorityMode() {
        Minecraft minecraft = Minecraft.getInstance();
        if (!managingPriority || minecraft.options == null) {
            managingPriority = false;
            baselinePriority = null;
            return;
        }

        if (baselinePriority != null && minecraft.options.prioritizeChunkUpdates().get() != baselinePriority) {
            minecraft.options.prioritizeChunkUpdates().set(baselinePriority);
        }

        managingPriority = false;
        baselinePriority = null;
    }

    private static int computeBudgetValue() {
        int cpuLevel = PauCClient.getCpuInvolvementLevel();
        int baseBudget = switch (cpuLevel) {
            case 1 -> 6;
            case 3 -> 14;
            default -> 10;
        };

        double qualityRatio = ChunkBudgetController.getChunkRebuildBudgetRatio();
        double cpuMultiplier = 0.80D + cpuLevel * 0.20D;
        double pressurePenalty = 1.0D - Math.min(0.55D, LatencyController.getPressureLevel() * 0.16D);
        double serverPressurePenalty = 1.0D - Math.min(0.45D, IntegratedServerLoadController.getPressureLevel() * 0.12D);
        int mitigationTier = IntegratedServerLoadController.getMitigationTier();
        double mitigationTierPenalty = switch (mitigationTier) {
            case 1 -> 0.94D;
            case 2 -> 0.82D;
            default -> mitigationTier >= 3 ? 0.68D : 1.00D;
        };
        double emergencyPenalty = IntegratedServerLoadController.isEmergencyMitigationActive() ? 0.75D : 1.00D;
        double bottleneckPenalty = BottleneckController.isCpuBound() ? 0.75D : 1.00D;
        double bottleneckBoost = BottleneckController.isGpuBound() ? 1.08D : 1.00D;
        double backlogPenalty = 1.0D - Math.min(0.35D, backPressureTicks / 36.0D);
        double authorityPenalty = AuthoritativeRuntimeController.getChunkBudgetPenaltyMultiplier();
        double modeMultiplier = GlobalPerformanceGovernor.getChunkCompileBudgetMultiplier();
        int computedBudget = (int) Math.round(
                baseBudget
                        * qualityRatio
                        * cpuMultiplier
                        * pressurePenalty
                        * serverPressurePenalty
                        * mitigationTierPenalty
                        * emergencyPenalty
                        * bottleneckPenalty
                        * bottleneckBoost
                        * backlogPenalty
                        * authorityPenalty
                        * modeMultiplier
        );
        return Math.max(MIN_BUILD_BUDGET, Math.min(MAX_BUILD_BUDGET, computedBudget));
    }
}

