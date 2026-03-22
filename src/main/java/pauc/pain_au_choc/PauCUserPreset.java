package pauc.pain_au_choc;

import pauc.pain_au_choc.render.shader.DeferredCompatibilityMode;

public enum PauCUserPreset {
    SAFE("safe", "Safe", 4, true, 0.72D, true, 1, true, true, false, 0.20D, DeferredCompatibilityMode.FAST, true),
    BALANCED("balanced", "Balanced", 7, true, 0.70D, true, 2, true, true, true, 0.40D, DeferredCompatibilityMode.BALANCED, false),
    COMPETITIVE_240("competitive240", "Competitive 240", 6, true, 0.60D, true, 3, true, true, false, 0.28D, DeferredCompatibilityMode.FAST, false),
    CINEMATIC("cinematic", "Cinematic", 10, true, 0.82D, true, 2, true, true, true, 0.60D, DeferredCompatibilityMode.STRICT, false);

    private final String configKey;
    private final String displayLabel;
    private final int qualityLevel;
    private final boolean dynamicResolutionEnabled;
    private final double dynamicResolutionMinScale;
    private final boolean adaptiveSimulationDistanceEnabled;
    private final int cpuInvolvementLevel;
    private final boolean frameTimeStabilizerEnabled;
    private final boolean gpuBottleneckEnabled;
    private final boolean advancedSharpeningEnabled;
    private final double advancedSharpeningStrength;
    private final DeferredCompatibilityMode deferredMode;
    private final boolean disableDeferredPipeline;

    PauCUserPreset(
            String configKey,
            String displayLabel,
            int qualityLevel,
            boolean dynamicResolutionEnabled,
            double dynamicResolutionMinScale,
            boolean adaptiveSimulationDistanceEnabled,
            int cpuInvolvementLevel,
            boolean frameTimeStabilizerEnabled,
            boolean gpuBottleneckEnabled,
            boolean advancedSharpeningEnabled,
            double advancedSharpeningStrength,
            DeferredCompatibilityMode deferredMode,
            boolean disableDeferredPipeline
    ) {
        this.configKey = configKey;
        this.displayLabel = displayLabel;
        this.qualityLevel = qualityLevel;
        this.dynamicResolutionEnabled = dynamicResolutionEnabled;
        this.dynamicResolutionMinScale = dynamicResolutionMinScale;
        this.adaptiveSimulationDistanceEnabled = adaptiveSimulationDistanceEnabled;
        this.cpuInvolvementLevel = cpuInvolvementLevel;
        this.frameTimeStabilizerEnabled = frameTimeStabilizerEnabled;
        this.gpuBottleneckEnabled = gpuBottleneckEnabled;
        this.advancedSharpeningEnabled = advancedSharpeningEnabled;
        this.advancedSharpeningStrength = advancedSharpeningStrength;
        this.deferredMode = deferredMode;
        this.disableDeferredPipeline = disableDeferredPipeline;
    }

    public String getConfigKey() {
        return configKey;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }

    public int getQualityLevel() {
        return qualityLevel;
    }

    public boolean isDynamicResolutionEnabled() {
        return dynamicResolutionEnabled;
    }

    public double getDynamicResolutionMinScale() {
        return dynamicResolutionMinScale;
    }

    public boolean isAdaptiveSimulationDistanceEnabled() {
        return adaptiveSimulationDistanceEnabled;
    }

    public int getCpuInvolvementLevel() {
        return cpuInvolvementLevel;
    }

    public boolean isFrameTimeStabilizerEnabled() {
        return frameTimeStabilizerEnabled;
    }

    public boolean isGpuBottleneckEnabled() {
        return gpuBottleneckEnabled;
    }

    public boolean isAdvancedSharpeningEnabled() {
        return advancedSharpeningEnabled;
    }

    public double getAdvancedSharpeningStrength() {
        return advancedSharpeningStrength;
    }

    public DeferredCompatibilityMode getDeferredMode() {
        return deferredMode;
    }

    public boolean isDisableDeferredPipeline() {
        return disableDeferredPipeline;
    }

    public PauCUserPreset next() {
        PauCUserPreset[] values = values();
        return values[(this.ordinal() + 1) % values.length];
    }

    public static PauCUserPreset fromConfigKey(String key, PauCUserPreset fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }
        for (PauCUserPreset preset : values()) {
            if (preset.configKey.equalsIgnoreCase(key)) {
                return preset;
            }
        }
        return fallback;
    }
}
