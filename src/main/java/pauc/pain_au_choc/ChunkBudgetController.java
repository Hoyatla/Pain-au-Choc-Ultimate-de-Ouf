package pauc.pain_au_choc;

public final class ChunkBudgetController {
    private ChunkBudgetController() {
    }

    public static boolean shouldSoftUnloadSqr(double distanceSqr, boolean insideVisibilityCone) {
        if (!PauCClient.isBudgetActive()) {
            return false;
        }

        QualityBudgetProfile profile = PauCClient.getActiveProfile();
        if (profile.isVanillaEquivalent() || insideVisibilityCone) {
            return false;
        }

        return distanceSqr >= getSoftUnloadDistanceSqr();
    }

    public static double getSoftUnloadDistanceBlocks() {
        QualityBudgetProfile profile = PauCClient.getActiveProfile();
        return DistanceBudgetController.getViewDistanceBlocks() * profile.softUnloadRatio();
    }

    public static double getSoftUnloadDistanceSqr() {
        return DistanceBudgetController.square(getSoftUnloadDistanceBlocks());
    }

    public static double getChunkRebuildBudgetRatio() {
        return PauCClient.getActiveProfile().chunkRebuildBudgetRatio();
    }
}

