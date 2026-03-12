package pauc.pain_au_choc;

public final class GpuHeadroomController {
    private GpuHeadroomController() {
    }

    public static float getTargetGpuLoad() {
        return QualityBudgetProfile.forLevel(PauCClient.getQualityLevel()).targetGpuLoad();
    }

    public static float getPeakGpuLoad() {
        return QualityBudgetProfile.forLevel(PauCClient.getQualityLevel()).peakGpuLoad();
    }

    public static float getHeadroomFraction() {
        return Math.max(0.05F, getPeakGpuLoad() - getTargetGpuLoad());
    }
}

