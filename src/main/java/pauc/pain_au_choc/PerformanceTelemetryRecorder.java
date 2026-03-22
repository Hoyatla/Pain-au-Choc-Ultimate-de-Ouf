package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraftforge.fml.loading.FMLPaths;
import pauc.pain_au_choc.render.PauCWorldRenderer;
import pauc.pain_au_choc.render.chunk.PauCRenderSectionManager;
import pauc.pain_au_choc.render.chunk.PauCUploadManager;
import pauc.pain_au_choc.render.shader.DeferredWorldRenderingPipeline;
import pauc.pain_au_choc.render.shader.PauCDeferredShaderController;
import pauc.pain_au_choc.render.shader.ShaderPackLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class PerformanceTelemetryRecorder {
    private static final String TELEMETRY_SCHEMA_VERSION = "20260318_shadowv2";
    private static final int SAMPLE_INTERVAL_TICKS = 20;
    private static final int FLUSH_EVERY_SAMPLES = 10;
    private static final Path TELEMETRY_DIR = FMLPaths.GAMEDIR.get().resolve("pauc_telemetry");
    private static final Path TELEMETRY_FILE = TELEMETRY_DIR.resolve("runtime_metrics.csv");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final String CSV_HEADER =
            "timestamp,telemetry_schema_version,session_seconds,world,dimension,player_x,player_y,player_z,"
                    + "fps_raw,fps_smoothed,frame_ms,jitter_ms,target_frame_ms,bottleneck_state,drs_scale,"
                    + "drs_active,drs_reason,"
                    + "governor_mode,governor_pressure,runtime_status,particle_budget,mspt_smoothed,server_pressure,"
                    + "server_mitigation_tier,server_emergency_ticks,"
                    + "sim_distance_applied,sim_distance_base,sim_distance_min,sim_distance_cooldown,sim_distance_high_samples,sim_distance_last_tps,sim_distance_adjustments,"
                    + "mob_selector_cadence,mob_navigation_cadence,mob_target_run_ratio,mob_goal_run_ratio,mob_nav_run_ratio,"
                    + "heap_used_mb,heap_max_mb,quality_level,quality_target,auto_quality_enabled,auto_quality_score,auto_quality_cooldown,auto_quality_adjustments,auto_quality_reason,pauc_enabled,"
                    + "stream_known,stream_active,stream_deferred,stream_radius,full_radius,"
                    + "proxy_cache,proxy_cache_target,proxy_active,proxy_reason,"
                    + "upload_backlog,upload_last_sections,upload_budget_sections,upload_last_mb,upload_budget_mb,"
                    + "chunk_compile_budget_preview,chunk_compile_backpressure,chunk_builder_backpressure,chunk_builder_pending,"
                    + "visible_sections,visible_full,visible_stream,visible_deferred,visible_culled,"
                    + "visible_block_entities,global_block_entities,"
                    + "deferred_active,deferred_mode,deferred_mode_preferred,deferred_mode_effective,deferred_pack,deferred_warnings,shader_upscaler,shader_route";

    private static final StringBuilder pendingRows = new StringBuilder(8192);
    private static boolean fileReady;
    private static boolean writeFailed;
    private static int tickCounter;
    private static int pendingSamples;
    private static long sessionStartMillis;

    private PerformanceTelemetryRecorder() {
    }

    public static void onClientTick() {
        if (writeFailed) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null) {
            return;
        }

        ensureFileReady();
        if (!fileReady) {
            return;
        }

        tickCounter++;
        if (tickCounter % SAMPLE_INTERVAL_TICKS != 0) {
            return;
        }

        appendSample(minecraft);
        if (pendingSamples >= FLUSH_EVERY_SAMPLES) {
            flushNow();
        }
    }

    public static void flushNow() {
        if (pendingRows.length() == 0 || writeFailed) {
            return;
        }

        ensureFileReady();
        if (!fileReady) {
            return;
        }

        try {
            Files.writeString(
                    TELEMETRY_FILE,
                    pendingRows.toString(),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND
            );
            pendingRows.setLength(0);
            pendingSamples = 0;
        } catch (IOException exception) {
            writeFailed = true;
            Pain_au_Choc.LOGGER.warn("Failed to flush PauC telemetry file {}", TELEMETRY_FILE, exception);
        }
    }

    private static void appendSample(Minecraft minecraft) {
        LocalPlayer player = minecraft.player;
        ResourceKey<Level> dimensionKey = minecraft.level.dimension();
        String worldName = resolveWorldName(minecraft);
        String dimensionName = dimensionKey.location().toString();

        double playerX = player == null ? 0.0D : player.getX();
        double playerY = player == null ? 0.0D : player.getY();
        double playerZ = player == null ? 0.0D : player.getZ();

        Runtime runtime = Runtime.getRuntime();
        long usedBytes = runtime.totalMemory() - runtime.freeMemory();
        long maxBytes = runtime.maxMemory();

        long nowMillis = System.currentTimeMillis();
        long sessionMillis = Math.max(0L, nowMillis - sessionStartMillis);
        int streamKnown = StructureStreamingController.getKnownChunkCount();
        int streamActive = StructureStreamingController.getActiveChunkCount();
        int streamDeferred = StructureStreamingController.getDeferredChunkCount();
        int streamRadius = ManagedChunkRadiusController.getStreamingRadiusChunks();
        int fullRadius = ManagedChunkRadiusController.getFullDetailRadiusChunks();
        int simDistanceApplied = AdaptiveSimulationDistanceController.getAppliedSimulationDistance();
        int simDistanceBase = AdaptiveSimulationDistanceController.getBaseSimulationDistance();
        int simDistanceMin = AdaptiveSimulationDistanceController.getLastMinimumSimulationDistance();
        int simDistanceCooldown = AdaptiveSimulationDistanceController.getCooldownTicks();
        int simDistanceHighSamples = AdaptiveSimulationDistanceController.getStableHighTpsSamples();
        double simDistanceLastTps = AdaptiveSimulationDistanceController.getLastSampledTps();
        int simDistanceAdjustments = AdaptiveSimulationDistanceController.getAdjustmentCount();
        int qualityTarget = PauCClient.getAdaptiveQualityTargetLevel();
        boolean autoQualityEnabled = PauCClient.isAdaptiveQualityEnabled();
        int autoQualityScore = AdaptiveQualityController.getLastPressureScore();
        int autoQualityCooldown = AdaptiveQualityController.getCooldownTicks();
        int autoQualityAdjustments = AdaptiveQualityController.getAdjustmentCount();
        String autoQualityReason = AdaptiveQualityController.getLastAdjustmentReason();
        int mobSelectorCadence = ServerMobCadenceController.getLastSelectorCadence();
        int mobNavigationCadence = ServerMobCadenceController.getLastNavigationCadence();
        double mobTargetRunRatio = ServerMobCadenceController.getSmoothedTargetRunRatio();
        double mobGoalRunRatio = ServerMobCadenceController.getSmoothedGoalRunRatio();
        double mobNavigationRunRatio = ServerMobCadenceController.getSmoothedNavigationRunRatio();
        int proxyCache = TerrainProxyController.getCachedChunkCount();
        int proxyCacheTarget = TerrainProxyController.getTargetCacheEntries();
        boolean drsActive = PauCClient.isDynamicResolutionActive();
        String drsReason = PauCClient.getDynamicResolutionRuntimeReason();
        boolean proxyActive = ManagedChunkRadiusController.shouldRenderProxyTerrain();
        String proxyReason = ManagedChunkRadiusController.getProxyRuntimeReason();
        String shaderUpscaler = PauCShaderManager.getActiveShaderLabel();
        String shaderRoute = PauCShaderManager.shouldProcessAtNativeScale() ? "native" : "drs";

        PauCWorldRenderer renderer = PauCWorldRenderer.instanceNullable();
        PauCRenderSectionManager sectionManager = renderer == null ? null : renderer.getSectionManager();
        PauCUploadManager uploadManager = sectionManager == null ? null : sectionManager.getUploadManager();
        int uploadBacklog = uploadManager == null ? 0 : uploadManager.getPendingUploadCount();
        int uploadLastSections = uploadManager == null ? 0 : uploadManager.getLastUploadPassSections();
        int uploadBudgetSections = uploadManager == null ? 0 : uploadManager.getLastUploadSectionBudget();
        double uploadLastMb = uploadManager == null ? 0.0D : bytesToMegabytes(uploadManager.getLastUploadPassBytes());
        double uploadBudgetMb = uploadManager == null ? 0.0D : bytesToMegabytes(uploadManager.getLastUploadByteBudget());
        int chunkCompileBudgetPreview = ChunkBuildQueueController.previewCompileBudget();
        if (chunkCompileBudgetPreview == Integer.MAX_VALUE) {
            chunkCompileBudgetPreview = 0;
        }
        float chunkCompileBackpressure = ChunkBuildQueueController.getBackPressureRatio();
        float chunkBuilderBackpressure = sectionManager == null ? 0.0F : sectionManager.getChunkBuilder().getBackPressureRatio();
        int chunkBuilderPending = sectionManager == null ? 0 : sectionManager.getChunkBuilder().getPendingTaskCount();
        int visibleSections = sectionManager == null ? 0 : sectionManager.getVisibleChunkCount();
        int visibleFull = sectionManager == null ? 0 : sectionManager.getLastFullDetailVisibleSections();
        int visibleStream = sectionManager == null ? 0 : sectionManager.getLastStreamingVisibleSections();
        int visibleDeferred = sectionManager == null ? 0 : sectionManager.getLastDeferredVisibleSections();
        int visibleCulled = sectionManager == null ? 0 : sectionManager.getLastBudgetCulledVisibleSections();
        int visibleBlockEntities = sectionManager == null ? 0 : sectionManager.getLastVisibleCulledBlockEntityCount();
        int globalBlockEntities = sectionManager == null ? 0 : sectionManager.getLastGlobalBlockEntityCount();
        DeferredWorldRenderingPipeline pipeline = DeferredWorldRenderingPipeline.getActivePipeline();
        boolean deferredActive = pipeline != null && pipeline.isInitialized();
        String deferredMode = PauCDeferredShaderController.getCompatibilityLabel();
        String deferredPreferredMode = PauCDeferredShaderController.getCompatibilityMode().getConfigKey();
        String deferredEffectiveMode = PauCDeferredShaderController.getEffectiveCompatibilityMode().getConfigKey();
        String deferredPack = PauCDeferredShaderController.getSelectedPack();
        int deferredWarnings = 0;
        if (deferredActive) {
            ShaderPackLoader.ShaderPack activePack = pipeline.getShaderPack();
            deferredWarnings = activePack == null ? 0 : activePack.warnings.size();
        }

        pendingRows
                .append(csvEscape(TIMESTAMP_FORMATTER.format(Instant.ofEpochMilli(nowMillis)))).append(',')
                .append(csvEscape(TELEMETRY_SCHEMA_VERSION)).append(',')
                .append(formatDouble(sessionMillis / 1000.0D)).append(',')
                .append(csvEscape(worldName)).append(',')
                .append(csvEscape(dimensionName)).append(',')
                .append(formatDouble(playerX)).append(',')
                .append(formatDouble(playerY)).append(',')
                .append(formatDouble(playerZ)).append(',')
                .append(minecraft.getFps()).append(',')
                .append(formatDouble(LatencyController.getSmoothedFps())).append(',')
                .append(formatDouble(LatencyController.getStabilizedFrameMillis())).append(',')
                .append(formatDouble(LatencyController.getFrameTimeJitterMillis())).append(',')
                .append(formatDouble(BottleneckController.getTargetFrameMillis())).append(',')
                .append(BottleneckController.getState().name()).append(',')
                .append(formatDouble(DynamicResolutionController.getCurrentScale())).append(',')
                .append(drsActive).append(',')
                .append(csvEscape(drsReason)).append(',')
                .append(GlobalPerformanceGovernor.getMode().name()).append(',')
                .append(GlobalPerformanceGovernor.getGlobalPressure()).append(',')
                .append(AuthoritativeRuntimeController.getStatusLabel()).append(',')
                .append(ParticleBudgetController.getCurrentBudget()).append(',')
                .append(formatDouble(IntegratedServerLoadController.getSmoothedMspt())).append(',')
                .append(IntegratedServerLoadController.getPressureLevel()).append(',')
                .append(IntegratedServerLoadController.getMitigationTier()).append(',')
                .append(IntegratedServerLoadController.getEmergencyHoldTicks()).append(',')
                .append(simDistanceApplied).append(',')
                .append(simDistanceBase).append(',')
                .append(simDistanceMin).append(',')
                .append(simDistanceCooldown).append(',')
                .append(simDistanceHighSamples).append(',')
                .append(formatDouble(simDistanceLastTps)).append(',')
                .append(simDistanceAdjustments).append(',')
                .append(mobSelectorCadence).append(',')
                .append(mobNavigationCadence).append(',')
                .append(formatDouble(mobTargetRunRatio)).append(',')
                .append(formatDouble(mobGoalRunRatio)).append(',')
                .append(formatDouble(mobNavigationRunRatio)).append(',')
                .append(formatDouble(bytesToMegabytes(usedBytes))).append(',')
                .append(formatDouble(bytesToMegabytes(maxBytes))).append(',')
                .append(PauCClient.getQualityLevel()).append(',')
                .append(qualityTarget).append(',')
                .append(autoQualityEnabled).append(',')
                .append(autoQualityScore).append(',')
                .append(autoQualityCooldown).append(',')
                .append(autoQualityAdjustments).append(',')
                .append(csvEscape(autoQualityReason)).append(',')
                .append(PauCClient.isEnabled()).append(',')
                .append(streamKnown).append(',')
                .append(streamActive).append(',')
                .append(streamDeferred).append(',')
                .append(streamRadius).append(',')
                .append(fullRadius).append(',')
                .append(proxyCache).append(',')
                .append(proxyCacheTarget).append(',')
                .append(proxyActive).append(',')
                .append(csvEscape(proxyReason)).append(',')
                .append(uploadBacklog).append(',')
                .append(uploadLastSections).append(',')
                .append(uploadBudgetSections).append(',')
                .append(formatDouble(uploadLastMb)).append(',')
                .append(formatDouble(uploadBudgetMb)).append(',')
                .append(chunkCompileBudgetPreview).append(',')
                .append(formatDouble(chunkCompileBackpressure)).append(',')
                .append(formatDouble(chunkBuilderBackpressure)).append(',')
                .append(chunkBuilderPending).append(',')
                .append(visibleSections).append(',')
                .append(visibleFull).append(',')
                .append(visibleStream).append(',')
                .append(visibleDeferred).append(',')
                .append(visibleCulled).append(',')
                .append(visibleBlockEntities).append(',')
                .append(globalBlockEntities).append(',')
                .append(deferredActive).append(',')
                .append(csvEscape(deferredMode)).append(',')
                .append(csvEscape(deferredPreferredMode)).append(',')
                .append(csvEscape(deferredEffectiveMode)).append(',')
                .append(csvEscape(deferredPack)).append(',')
                .append(deferredWarnings).append(',')
                .append(csvEscape(shaderUpscaler)).append(',')
                .append(csvEscape(shaderRoute))
                .append(System.lineSeparator());
        pendingSamples++;
    }

    private static void ensureFileReady() {
        if (fileReady || writeFailed) {
            return;
        }

        try {
            Files.createDirectories(TELEMETRY_DIR);
            if (!Files.exists(TELEMETRY_FILE)) {
                Files.writeString(
                        TELEMETRY_FILE,
                        CSV_HEADER + System.lineSeparator(),
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING
                );
            } else if (!hasExpectedHeader()) {
                migrateTelemetrySchema();
            }
            sessionStartMillis = System.currentTimeMillis();
            fileReady = true;
        } catch (IOException exception) {
            writeFailed = true;
            Pain_au_Choc.LOGGER.warn("Failed to initialize PauC telemetry file {}", TELEMETRY_FILE, exception);
        }
    }

    private static boolean hasExpectedHeader() {
        try {
            try (var reader = Files.newBufferedReader(TELEMETRY_FILE, StandardCharsets.UTF_8)) {
                String firstLine = reader.readLine();
                return CSV_HEADER.equals(firstLine);
            }
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void migrateTelemetrySchema() throws IOException {
        Path backupPath = TELEMETRY_DIR.resolve("runtime_metrics_legacy_" + Instant.now().toEpochMilli() + ".csv");
        try {
            Files.move(TELEMETRY_FILE, backupPath, StandardCopyOption.REPLACE_EXISTING);
            Pain_au_Choc.LOGGER.info("PauC telemetry schema updated; legacy file moved to {}", backupPath);
        } catch (IOException moveException) {
            Pain_au_Choc.LOGGER.warn(
                    "PauC telemetry schema update could not move legacy file; overwriting telemetry in place {}",
                    TELEMETRY_FILE,
                    moveException
            );
        }

        Files.writeString(
                TELEMETRY_FILE,
                CSV_HEADER + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    private static String resolveWorldName(Minecraft minecraft) {
        try {
            if (minecraft.getSingleplayerServer() != null) {
                return minecraft.getSingleplayerServer().getWorldData().getLevelName();
            }
            if (minecraft.level != null) {
                return minecraft.level.dimension().location().toString();
            }
        } catch (Exception ignored) {
        }
        return "unknown";
    }

    private static double bytesToMegabytes(long bytes) {
        return bytes / (1024.0D * 1024.0D);
    }

    private static String formatDouble(double value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        String escaped = value.replace("\"", "\"\"");
        if (escaped.contains(",") || escaped.contains("\"") || escaped.contains("\n")) {
            return "\"" + escaped + "\"";
        }
        return escaped;
    }
}
