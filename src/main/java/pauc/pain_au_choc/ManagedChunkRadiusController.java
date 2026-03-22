package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;

public final class ManagedChunkRadiusController {
    private static final boolean EXPERIMENTAL_CHUNK_PIPELINE = Boolean.getBoolean("pauc.experimentalChunkPipeline");
    private static final int MIN_FULL_DETAIL_RADIUS_CHUNKS = 2;
    private static final int MIN_STREAMING_RADIUS_CHUNKS = 12;
    private static final int MAX_STREAMING_RADIUS_CHUNKS = 160;
    private static final int MIN_PROXY_RADIUS_CHUNKS = 32;
    private static final int MAX_PROXY_RADIUS_CHUNKS = 256;
    private static final int PROXY_START_BUFFER_CHUNKS = 4;
    private static final double CHUNK_SIZE = 16.0D;
    private static final int RADIUS_STABILIZATION_INTERVAL_TICKS = 20;
    private static final int STREAMING_RADIUS_HYSTERESIS_CHUNKS = 4;
    private static final int PROXY_RADIUS_HYSTERESIS_CHUNKS = 6;
    private static final int PREDICTIVE_BIAS_HYSTERESIS_CHUNKS = 2;
    private static final int STREAMING_GROWTH_STEP_CHUNKS = 6;
    private static final int STREAMING_SHRINK_STEP_CHUNKS = 12;
    private static final int PROXY_GROWTH_STEP_CHUNKS = 10;
    private static final int PROXY_SHRINK_STEP_CHUNKS = 20;
    private static final int BIAS_GROWTH_STEP_CHUNKS = 3;
    private static final int BIAS_SHRINK_STEP_CHUNKS = 6;
    private static final int EMERGENCY_STREAMING_SHRINK_STEP_CHUNKS = 24;
    private static final int EMERGENCY_PROXY_SHRINK_STEP_CHUNKS = 36;
    private static final int EMERGENCY_BIAS_SHRINK_STEP_CHUNKS = 10;
    private static final int PRESSURE_RECOVERY_GROWTH_DELAY_TICKS = 600;
    private static final int PRESSURE_SPIKE_LEVEL = 2;
    private static final float PRESSURE_ATTACK_SMOOTHING = 0.35F;
    private static final float PRESSURE_RELEASE_SMOOTHING = 0.20F;

    private static int stabilizedStreamingRadiusChunks = -1;
    private static int stabilizedProxyRadiusChunks = -1;
    private static int stabilizedPredictiveBiasChunks = -1;
    private static int trackedLevelIdentity;
    private static long lastStabilizationGameTick = Long.MIN_VALUE;
    private static long lastPressureSampleGameTick = Long.MIN_VALUE;
    private static long lastPressureSpikeGameTick = Long.MIN_VALUE;
    private static boolean pressureSignalsInitialized;
    private static float smoothedClientPressureLevel;
    private static float smoothedServerPressureLevel;
    private static int effectiveClientPressureLevel;
    private static int effectiveServerPressureLevel;

    private ManagedChunkRadiusController() {
    }

    public static int getVanillaRenderRadiusChunks() {
        Minecraft minecraft = Minecraft.getInstance();
        int renderDistanceChunks = minecraft.options == null ? 12 : minecraft.options.renderDistance().get();
        return Math.max(MIN_FULL_DETAIL_RADIUS_CHUNKS, renderDistanceChunks);
    }

    public static int getFullDetailRadiusChunks() {
        return getVanillaRenderRadiusChunks();
    }

    public static int getStreamingRadiusChunks() {
        ensureStabilizedRadii();
        if (stabilizedStreamingRadiusChunks < 0) {
            return computeStreamingRadiusRaw();
        }
        return stabilizedStreamingRadiusChunks;
    }

    public static int getProxyStartRadiusChunks() {
        return getFullDetailRadiusChunks() + PROXY_START_BUFFER_CHUNKS;
    }

    public static int getProxyRadiusChunks() {
        ensureStabilizedRadii();
        int proxyStartRadius = getProxyStartRadiusChunks();
        if (!isProxyEnabledRaw()) {
            return proxyStartRadius;
        }

        if (stabilizedProxyRadiusChunks < 0) {
            return computeProxyRadiusRaw();
        }
        return Math.max(proxyStartRadius, stabilizedProxyRadiusChunks);
    }

    public static int getManagedRadiusChunks() {
        return Math.max(getStreamingRadiusChunks(), getProxyRadiusChunks());
    }

    public static int getProxyCaptureRadiusChunks() {
        ensureStabilizedRadii();
        int fullDetailRadius = getFullDetailRadiusChunks();
        int mitigationTier = IntegratedServerLoadController.getMitigationTier();
        int expansion = switch (GlobalPerformanceGovernor.getMode()) {
            case TRANSIT -> 16;
            case EXPLORATION -> 10;
            case COMBAT -> 6;
            case BASE -> 8;
            case CRISIS -> 0;
        };
        expansion += Math.max(0, getPredictiveBiasChunks() / 2);
        expansion -= getEffectiveClientPressureLevel() * 2 + getEffectiveServerPressureLevel() * 2;
        expansion -= switch (mitigationTier) {
            case 1 -> 1;
            case 2 -> 3;
            default -> mitigationTier >= 3 ? 6 : 0;
        };
        if (IntegratedServerLoadController.isEmergencyMitigationActive()) {
            expansion -= 3;
        }
        int maxRadius = Math.min(getStreamingRadiusChunks(), fullDetailRadius + 24);
        return clampRadius(fullDetailRadius + Math.max(0, expansion), fullDetailRadius, maxRadius);
    }

    public static int getPredictiveBiasChunks() {
        ensureStabilizedRadii();
        if (!isProxyEnabledRaw()) {
            return 0;
        }

        if (stabilizedPredictiveBiasChunks < 0) {
            return computePredictiveBiasRaw();
        }
        return stabilizedPredictiveBiasChunks;
    }

    public static int getProxyStride(int distanceChunks) {
        if (distanceChunks >= getUltraImpostorStartRadiusChunks()
                || (GlobalPerformanceGovernor.getMode() == GlobalPerformanceMode.CRISIS && distanceChunks >= getUltraImpostorStartRadiusChunks() - 24)) {
            return 8;
        }
        if (distanceChunks >= 192 || (GlobalPerformanceGovernor.getMode() == GlobalPerformanceMode.CRISIS && distanceChunks >= 128)) {
            return 4;
        }
        if (distanceChunks >= 128 || (GlobalPerformanceGovernor.getMode() == GlobalPerformanceMode.TRANSIT && distanceChunks >= 96)) {
            return 2;
        }
        return 1;
    }

    public static int getUltraImpostorStartRadiusChunks() {
        int proxyStart = getProxyStartRadiusChunks();
        int proxyRadius = getProxyRadiusChunks();
        int targetStart = Math.max(proxyStart + 32, proxyRadius - 48);
        return clampRadius(targetStart, proxyStart + 16, proxyRadius);
    }

    public static boolean isProxyEnabled() {
        return isProxyEnabledRaw();
    }

    public static void reset() {
        resetStabilizationState();
    }

    private static boolean isProxyEnabledRaw() {
        if (!EXPERIMENTAL_CHUNK_PIPELINE) {
            return false;
        }
        return PauCClient.isBudgetActive()
                && PauCClient.isAuthoritativeRuntimeEnabled()
                && !AuthoritativeRuntimeController.shouldDisableTerrainProxy();
    }

    public static boolean shouldRenderProxyTerrain() {
        return isProxyEnabled() && getProxyRadiusChunks() > getProxyStartRadiusChunks();
    }

    public static String getProxyRuntimeReason() {
        if (!PauCClient.isEnabled()) {
            return "PauC off";
        }
        if (!PauCClient.isBudgetActive()) {
            return "runtime off";
        }
        if (!PauCClient.isAuthoritativeRuntimeEnabled()) {
            return "authority off";
        }
        if (!EXPERIMENTAL_CHUNK_PIPELINE) {
            return "vanilla chunk fallback";
        }
        if (AuthoritativeRuntimeController.shouldDisableTerrainProxy()) {
            return AuthoritativeRuntimeController.getTerrainProxyBlockReason();
        }
        if (getProxyRadiusChunks() <= getProxyStartRadiusChunks()) {
            return "radius collapsed by pressure";
        }
        return "ready";
    }

    public static double getFullDetailDistanceBlocks() {
        return getFullDetailRadiusChunks() * CHUNK_SIZE;
    }

    public static double getProxyStartDistanceBlocks() {
        return getProxyStartRadiusChunks() * CHUNK_SIZE;
    }

    public static double getProxyDistanceBlocks() {
        return getProxyRadiusChunks() * CHUNK_SIZE;
    }

    public static String getRadiusSummary() {
        return "full=" + getFullDetailRadiusChunks()
                + "c stream=" + getStreamingRadiusChunks()
                + "c proxy=" + getProxyStartRadiusChunks()
                + "-" + getProxyRadiusChunks()
                + "c ultra>=" + getUltraImpostorStartRadiusChunks() + "c";
    }

    private static void ensureStabilizedRadii() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        int levelIdentity = level == null ? 0 : System.identityHashCode(level);
        if (trackedLevelIdentity != levelIdentity) {
            resetStabilizationState();
            trackedLevelIdentity = levelIdentity;
        }

        updateEffectivePressureSignals(level);
        int rawStreamingRadius = computeStreamingRadiusRaw();
        boolean proxyEnabled = isProxyEnabledRaw();
        int rawProxyRadius = proxyEnabled ? computeProxyRadiusRaw() : getProxyStartRadiusChunks();
        int rawPredictiveBias = proxyEnabled ? computePredictiveBiasRaw() : 0;

        if (stabilizedStreamingRadiusChunks < 0 || stabilizedProxyRadiusChunks < 0 || stabilizedPredictiveBiasChunks < 0) {
            stabilizedStreamingRadiusChunks = rawStreamingRadius;
            stabilizedProxyRadiusChunks = rawProxyRadius;
            stabilizedPredictiveBiasChunks = rawPredictiveBias;
            lastStabilizationGameTick = resolveCurrentGameTick(level);
            return;
        }

        boolean emergency = IntegratedServerLoadController.isEmergencyMitigationActive()
                || IntegratedServerLoadController.getMitigationTier() >= 3
                || GlobalPerformanceGovernor.getMode() == GlobalPerformanceMode.CRISIS;
        int governorPressure = clampRadius(GlobalPerformanceGovernor.getGlobalPressure(), 0, 3);
        int maxObservedPressure = Math.max(
                governorPressure,
                Math.max(getEffectiveClientPressureLevel(), getEffectiveServerPressureLevel())
        );
        long currentGameTick = resolveCurrentGameTick(level);
        if (currentGameTick != Long.MIN_VALUE && (emergency || maxObservedPressure >= PRESSURE_SPIKE_LEVEL)) {
            lastPressureSpikeGameTick = currentGameTick;
        }
        boolean shouldStep = emergency
                || currentGameTick == Long.MIN_VALUE
                || lastStabilizationGameTick == Long.MIN_VALUE
                || currentGameTick - lastStabilizationGameTick >= RADIUS_STABILIZATION_INTERVAL_TICKS;
        if (!shouldStep) {
            return;
        }
        boolean allowGrowth = emergency
                || currentGameTick == Long.MIN_VALUE
                || lastPressureSpikeGameTick == Long.MIN_VALUE
                || currentGameTick - lastPressureSpikeGameTick >= PRESSURE_RECOVERY_GROWTH_DELAY_TICKS;

        int streamingShrinkStep = emergency ? EMERGENCY_STREAMING_SHRINK_STEP_CHUNKS : STREAMING_SHRINK_STEP_CHUNKS;
        int proxyShrinkStep = emergency ? EMERGENCY_PROXY_SHRINK_STEP_CHUNKS : PROXY_SHRINK_STEP_CHUNKS;
        int biasShrinkStep = emergency ? EMERGENCY_BIAS_SHRINK_STEP_CHUNKS : BIAS_SHRINK_STEP_CHUNKS;

        stabilizedStreamingRadiusChunks = stepTowardTarget(
                stabilizedStreamingRadiusChunks,
                rawStreamingRadius,
                STREAMING_RADIUS_HYSTERESIS_CHUNKS,
                STREAMING_GROWTH_STEP_CHUNKS,
                streamingShrinkStep,
                allowGrowth
        );
        stabilizedProxyRadiusChunks = stepTowardTarget(
                stabilizedProxyRadiusChunks,
                rawProxyRadius,
                PROXY_RADIUS_HYSTERESIS_CHUNKS,
                PROXY_GROWTH_STEP_CHUNKS,
                proxyShrinkStep,
                allowGrowth
        );
        stabilizedPredictiveBiasChunks = stepTowardTarget(
                stabilizedPredictiveBiasChunks,
                rawPredictiveBias,
                PREDICTIVE_BIAS_HYSTERESIS_CHUNKS,
                BIAS_GROWTH_STEP_CHUNKS,
                biasShrinkStep,
                allowGrowth
        );

        int fullDetailRadius = getFullDetailRadiusChunks();
        int proxyStartRadius = fullDetailRadius + PROXY_START_BUFFER_CHUNKS;
        stabilizedStreamingRadiusChunks = clampRadius(
                stabilizedStreamingRadiusChunks,
                Math.max(fullDetailRadius + 6, MIN_STREAMING_RADIUS_CHUNKS),
                MAX_STREAMING_RADIUS_CHUNKS
        );
        stabilizedProxyRadiusChunks = clampRadius(
                stabilizedProxyRadiusChunks,
                Math.max(proxyStartRadius + 6, MIN_PROXY_RADIUS_CHUNKS),
                MAX_PROXY_RADIUS_CHUNKS
        );
        stabilizedPredictiveBiasChunks = clampRadius(stabilizedPredictiveBiasChunks, 0, 24);

        if (!proxyEnabled) {
            stabilizedProxyRadiusChunks = proxyStartRadius;
            stabilizedPredictiveBiasChunks = 0;
        }

        lastStabilizationGameTick = currentGameTick;
    }

    private static int computeStreamingRadiusRaw() {
        int fullDetailRadius = getFullDetailRadiusChunks();
        if (AuthoritativeRuntimeController.shouldDeferNonCriticalMutations()) {
            return clampRadius(
                    fullDetailRadius + 4,
                    Math.max(fullDetailRadius + 4, MIN_STREAMING_RADIUS_CHUNKS),
                    MAX_STREAMING_RADIUS_CHUNKS
            );
        }

        int qualityLevel = resolveTerrainQualityLevel();
        int cpuLevel = PauCClient.getCpuInvolvementLevel();
        int baseRadius = fullDetailRadius + 16 + qualityLevel * 4 + cpuLevel * 8;
        int modeBonus = switch (GlobalPerformanceGovernor.getMode()) {
            case TRANSIT -> 24;
            case COMBAT -> 8;
            case BASE -> -8;
            case CRISIS -> -20;
            default -> 12;
        };
        int mitigationTier = IntegratedServerLoadController.getMitigationTier();
        int pressurePenalty = getEffectiveClientPressureLevel() * 10 + getEffectiveServerPressureLevel() * 8;
        pressurePenalty += switch (mitigationTier) {
            case 1 -> 4;
            case 2 -> 12;
            default -> mitigationTier >= 3 ? 24 : 0;
        };
        if (IntegratedServerLoadController.isEmergencyMitigationActive()) {
            pressurePenalty += 12;
        }
        if (AuthoritativeRuntimeController.getStatus() == AuthoritativeRuntimeStatus.DEGRADED) {
            pressurePenalty += 12;
        }

        int resolvedRadius = baseRadius + modeBonus - pressurePenalty;
        return clampRadius(resolvedRadius, Math.max(fullDetailRadius + 6, MIN_STREAMING_RADIUS_CHUNKS), MAX_STREAMING_RADIUS_CHUNKS);
    }

    private static int computeProxyRadiusRaw() {
        int proxyStartRadius = getProxyStartRadiusChunks();
        if (!isProxyEnabledRaw()) {
            return proxyStartRadius;
        }
        if (AuthoritativeRuntimeController.shouldDeferNonCriticalMutations()) {
            return clampRadius(
                    proxyStartRadius + 6,
                    Math.max(proxyStartRadius + 6, MIN_PROXY_RADIUS_CHUNKS),
                    MAX_PROXY_RADIUS_CHUNKS
            );
        }

        int qualityLevel = resolveTerrainQualityLevel();
        int cpuLevel = PauCClient.getCpuInvolvementLevel();
        int baseRadius = 64 + qualityLevel * 12 + cpuLevel * 16;
        int modeBonus = switch (GlobalPerformanceGovernor.getMode()) {
            case TRANSIT -> 48;
            case COMBAT -> 12;
            case BASE -> -12;
            case CRISIS -> -40;
            default -> 24;
        };
        int mitigationTier = IntegratedServerLoadController.getMitigationTier();
        int pressurePenalty = getEffectiveClientPressureLevel() * 18 + getEffectiveServerPressureLevel() * 14;
        pressurePenalty += switch (mitigationTier) {
            case 1 -> 8;
            case 2 -> 20;
            default -> mitigationTier >= 3 ? 40 : 0;
        };
        if (IntegratedServerLoadController.isEmergencyMitigationActive()) {
            pressurePenalty += 20;
        }
        if (AuthoritativeRuntimeController.getStatus() == AuthoritativeRuntimeStatus.DEGRADED) {
            pressurePenalty += 18;
        }

        int resolvedRadius = baseRadius + modeBonus - pressurePenalty;
        return clampRadius(resolvedRadius, Math.max(proxyStartRadius + 6, MIN_PROXY_RADIUS_CHUNKS), MAX_PROXY_RADIUS_CHUNKS);
    }

    private static int computePredictiveBiasRaw() {
        if (!isProxyEnabledRaw()) {
            return 0;
        }
        if (AuthoritativeRuntimeController.shouldDeferNonCriticalMutations()) {
            return 0;
        }

        int qualityLevel = resolveTerrainQualityLevel();
        int mitigationTier = IntegratedServerLoadController.getMitigationTier();
        int baseBias = switch (GlobalPerformanceGovernor.getMode()) {
            case TRANSIT -> 18;
            case EXPLORATION -> 8;
            case COMBAT -> 4;
            case BASE -> 4;
            case CRISIS -> 0;
        };
        baseBias += PauCClient.getCpuInvolvementLevel() * 2;
        if (qualityLevel >= 8) {
            baseBias += 2;
        }
        baseBias -= getEffectiveClientPressureLevel() * 2 + getEffectiveServerPressureLevel() * 2;
        baseBias -= switch (mitigationTier) {
            case 1 -> 1;
            case 2 -> 4;
            default -> mitigationTier >= 3 ? 8 : 0;
        };
        if (IntegratedServerLoadController.isEmergencyMitigationActive()) {
            baseBias -= 4;
        }
        if (AuthoritativeRuntimeController.getStatus() == AuthoritativeRuntimeStatus.DEGRADED) {
            baseBias -= 4;
        }
        return clampRadius(baseBias, 0, 24);
    }

    private static long resolveCurrentGameTick(ClientLevel level) {
        if (level == null) {
            return Long.MIN_VALUE;
        }
        return level.getGameTime();
    }

    private static void updateEffectivePressureSignals(ClientLevel level) {
        long currentGameTick = resolveCurrentGameTick(level);
        if (currentGameTick != Long.MIN_VALUE && currentGameTick == lastPressureSampleGameTick) {
            return;
        }

        int rawClientPressure = clampRadius(LatencyController.getPressureLevel(), 0, 3);
        int rawServerPressure = clampRadius(IntegratedServerLoadController.getPressureLevel(), 0, 3);
        if (!pressureSignalsInitialized) {
            smoothedClientPressureLevel = rawClientPressure;
            smoothedServerPressureLevel = rawServerPressure;
            pressureSignalsInitialized = true;
        } else {
            smoothedClientPressureLevel = blendPressure(smoothedClientPressureLevel, rawClientPressure);
            smoothedServerPressureLevel = blendPressure(smoothedServerPressureLevel, rawServerPressure);
        }

        effectiveClientPressureLevel = clampRadius(Math.round(smoothedClientPressureLevel), 0, 3);
        effectiveServerPressureLevel = clampRadius(Math.round(smoothedServerPressureLevel), 0, 3);
        lastPressureSampleGameTick = currentGameTick;
    }

    private static float blendPressure(float current, int target) {
        float alpha = target > current ? PRESSURE_ATTACK_SMOOTHING : PRESSURE_RELEASE_SMOOTHING;
        return current + (target - current) * alpha;
    }

    private static int getEffectiveClientPressureLevel() {
        if (!pressureSignalsInitialized) {
            return clampRadius(LatencyController.getPressureLevel(), 0, 3);
        }
        return effectiveClientPressureLevel;
    }

    private static int getEffectiveServerPressureLevel() {
        if (!pressureSignalsInitialized) {
            return clampRadius(IntegratedServerLoadController.getPressureLevel(), 0, 3);
        }
        return effectiveServerPressureLevel;
    }

    private static int stepTowardTarget(
            int current,
            int target,
            int hysteresis,
            int growthStep,
            int shrinkStep,
            boolean allowGrowth
    ) {
        int delta = target - current;
        if (Math.abs(delta) <= hysteresis) {
            return current;
        }

        if (delta > 0) {
            if (!allowGrowth) {
                return current;
            }
            return current + Math.min(delta, Math.max(1, growthStep));
        }
        return current - Math.min(Math.abs(delta), Math.max(1, shrinkStep));
    }

    private static void resetStabilizationState() {
        stabilizedStreamingRadiusChunks = -1;
        stabilizedProxyRadiusChunks = -1;
        stabilizedPredictiveBiasChunks = -1;
        trackedLevelIdentity = 0;
        lastStabilizationGameTick = Long.MIN_VALUE;
        lastPressureSampleGameTick = Long.MIN_VALUE;
        lastPressureSpikeGameTick = Long.MIN_VALUE;
        pressureSignalsInitialized = false;
        smoothedClientPressureLevel = 0.0F;
        smoothedServerPressureLevel = 0.0F;
        effectiveClientPressureLevel = 0;
        effectiveServerPressureLevel = 0;
    }

    private static int resolveTerrainQualityLevel() {
        int level = PauCClient.isAdaptiveQualityActive()
                ? PauCClient.getAdaptiveQualityTargetLevel()
                : PauCClient.getQualityLevel();
        return Math.max(PauCClient.getMinQualityLevel(), Math.min(PauCClient.getMaxQualityLevel(), level));
    }

    private static int clampRadius(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
