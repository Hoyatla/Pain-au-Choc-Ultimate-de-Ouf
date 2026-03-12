package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;

public final class ManagedChunkRadiusController {
    private static final int MIN_FULL_DETAIL_RADIUS_CHUNKS = 2;
    private static final int MIN_STREAMING_RADIUS_CHUNKS = 24;
    private static final int MAX_STREAMING_RADIUS_CHUNKS = 160;
    private static final int MIN_PROXY_RADIUS_CHUNKS = 48;
    private static final int MAX_PROXY_RADIUS_CHUNKS = 256;
    private static final int PROXY_START_BUFFER_CHUNKS = 4;
    private static final double CHUNK_SIZE = 16.0D;

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
        int fullDetailRadius = getFullDetailRadiusChunks();
        int qualityLevel = PauCClient.getQualityLevel();
        int cpuLevel = PauCClient.getCpuInvolvementLevel();
        int baseRadius = fullDetailRadius + 16 + qualityLevel * 4 + cpuLevel * 8;
        int modeBonus = switch (GlobalPerformanceGovernor.getMode()) {
            case TRANSIT -> 24;
            case COMBAT -> 8;
            case BASE -> -8;
            case CRISIS -> -20;
            default -> 12;
        };
        int pressurePenalty = LatencyController.getPressureLevel() * 10 + IntegratedServerLoadController.getPressureLevel() * 8;
        if (AuthoritativeRuntimeController.getStatus() == AuthoritativeRuntimeStatus.DEGRADED) {
            pressurePenalty += 12;
        }

        int resolvedRadius = baseRadius + modeBonus - pressurePenalty;
        return clampRadius(resolvedRadius, Math.max(fullDetailRadius + 8, MIN_STREAMING_RADIUS_CHUNKS), MAX_STREAMING_RADIUS_CHUNKS);
    }

    public static int getProxyStartRadiusChunks() {
        return getFullDetailRadiusChunks() + PROXY_START_BUFFER_CHUNKS;
    }

    public static int getProxyRadiusChunks() {
        int proxyStartRadius = getProxyStartRadiusChunks();
        if (!isProxyEnabled()) {
            return proxyStartRadius;
        }

        int qualityLevel = PauCClient.getQualityLevel();
        int cpuLevel = PauCClient.getCpuInvolvementLevel();
        int baseRadius = 64 + qualityLevel * 12 + cpuLevel * 16;
        int modeBonus = switch (GlobalPerformanceGovernor.getMode()) {
            case TRANSIT -> 48;
            case COMBAT -> 12;
            case BASE -> -12;
            case CRISIS -> -40;
            default -> 24;
        };
        int pressurePenalty = LatencyController.getPressureLevel() * 18 + IntegratedServerLoadController.getPressureLevel() * 14;
        if (AuthoritativeRuntimeController.getStatus() == AuthoritativeRuntimeStatus.DEGRADED) {
            pressurePenalty += 18;
        }

        int resolvedRadius = baseRadius + modeBonus - pressurePenalty;
        return clampRadius(resolvedRadius, Math.max(proxyStartRadius + 8, MIN_PROXY_RADIUS_CHUNKS), MAX_PROXY_RADIUS_CHUNKS);
    }

    public static int getManagedRadiusChunks() {
        return Math.max(getStreamingRadiusChunks(), getProxyRadiusChunks());
    }

    public static int getProxyCaptureRadiusChunks() {
        int fullDetailRadius = getFullDetailRadiusChunks();
        int expansion = switch (GlobalPerformanceGovernor.getMode()) {
            case TRANSIT -> 16;
            case EXPLORATION -> 10;
            case COMBAT -> 6;
            case BASE -> 8;
            case CRISIS -> 0;
        };
        expansion += Math.max(0, getPredictiveBiasChunks() / 2);
        expansion -= LatencyController.getPressureLevel() * 2 + IntegratedServerLoadController.getPressureLevel() * 2;
        int maxRadius = Math.min(getStreamingRadiusChunks(), fullDetailRadius + 24);
        return clampRadius(fullDetailRadius + Math.max(0, expansion), fullDetailRadius, maxRadius);
    }

    public static int getPredictiveBiasChunks() {
        if (!isProxyEnabled()) {
            return 0;
        }

        int baseBias = switch (GlobalPerformanceGovernor.getMode()) {
            case TRANSIT -> 18;
            case EXPLORATION -> 8;
            case COMBAT -> 4;
            case BASE -> 4;
            case CRISIS -> 0;
        };
        baseBias += PauCClient.getCpuInvolvementLevel() * 2;
        if (PauCClient.getQualityLevel() >= 8) {
            baseBias += 2;
        }
        baseBias -= LatencyController.getPressureLevel() * 2 + IntegratedServerLoadController.getPressureLevel() * 2;
        if (AuthoritativeRuntimeController.getStatus() == AuthoritativeRuntimeStatus.DEGRADED) {
            baseBias -= 4;
        }
        return clampRadius(baseBias, 0, 24);
    }

    public static int getProxyStride(int distanceChunks) {
        if (distanceChunks >= 192 || (GlobalPerformanceGovernor.getMode() == GlobalPerformanceMode.CRISIS && distanceChunks >= 128)) {
            return 4;
        }
        if (distanceChunks >= 128 || (GlobalPerformanceGovernor.getMode() == GlobalPerformanceMode.TRANSIT && distanceChunks >= 96)) {
            return 2;
        }
        return 1;
    }

    public static boolean isProxyEnabled() {
        return PauCClient.isBudgetActive()
                && PauCClient.isAuthoritativeRuntimeEnabled()
                && !AuthoritativeRuntimeController.shouldDisableTerrainProxy();
    }

    public static boolean shouldRenderProxyTerrain() {
        return isProxyEnabled() && getProxyRadiusChunks() > getProxyStartRadiusChunks();
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
                + "-" + getProxyRadiusChunks() + "c";
    }

    private static int clampRadius(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
