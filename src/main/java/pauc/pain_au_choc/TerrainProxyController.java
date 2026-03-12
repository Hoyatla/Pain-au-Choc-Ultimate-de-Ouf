package pauc.pain_au_choc;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class TerrainProxyController {
    private static final int CAPTURE_INTERVAL_TICKS = 2;
    private static final int BASE_CAPTURE_BATCH = 64;
    private static final int WORK_PHASE = 4;
    private static final int CELL_GRID = 4;
    private static final int CELL_COUNT = CELL_GRID * CELL_GRID;
    private static final int CELL_SIZE_BLOCKS = 16 / CELL_GRID;
    private static final int RESET_SHRINK_THRESHOLD = 4096;
    private static final int MAX_CACHE_ENTRIES = 24576;
    private static final int STALE_SWEEP_GRACE = 12;
    private static final int CACHE_DISTANCE_SLACK = 24;
    private static final double CHUNK_SIZE = 16.0D;

    private static HashMap<Long, TerrainProxyChunk> proxyChunks = new HashMap<>();
    private static ArrayList<TerrainProxyChunk> renderBuffer = new ArrayList<>();

    private static int trackedLevelIdentity;
    private static int centerChunkX = Integer.MIN_VALUE;
    private static int centerChunkZ = Integer.MIN_VALUE;
    private static int captureRadiusChunks = -1;
    private static int captureCursor;
    private static int captureTicker;
    private static int captureGeneration;
    private static int predictiveBiasChunks;
    private static int lastRenderedProxyChunks;
    private static int lastRenderedCells;

    private TerrainProxyController() {
    }

    public static void tick() {
        if (!ManagedChunkRadiusController.isProxyEnabled()) {
            lastRenderedProxyChunks = 0;
            lastRenderedCells = 0;
            if (!PauCClient.isBudgetActive()) {
                reset();
            }
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
        if (levelIdentity != trackedLevelIdentity) {
            reset();
            trackedLevelIdentity = levelIdentity;
        }

        ChunkAnchor captureAnchor = resolveCaptureAnchor(player);
        int targetCaptureRadius = ManagedChunkRadiusController.getProxyCaptureRadiusChunks();
        if (captureAnchor.chunkX != centerChunkX || captureAnchor.chunkZ != centerChunkZ || targetCaptureRadius != captureRadiusChunks) {
            beginCapture(captureAnchor.chunkX, captureAnchor.chunkZ, targetCaptureRadius);
        }

        captureTicker++;
        int adaptiveInterval = LatencyController.getAdaptiveInterval(CAPTURE_INTERVAL_TICKS);
        if (AuthoritativeRuntimeController.getStatus() == AuthoritativeRuntimeStatus.DEGRADED) {
            adaptiveInterval++;
        }
        if (captureTicker < adaptiveInterval || !LatencyController.shouldRunBackgroundWork(WORK_PHASE)) {
            return;
        }

        captureTicker = 0;
        processCaptureBatch(level, player);
    }

    public static void render(PoseStack poseStack, Matrix4f projectionMatrix, Camera camera, float partialTick) {
        if (!ManagedChunkRadiusController.shouldRenderProxyTerrain()) {
            lastRenderedProxyChunks = 0;
            lastRenderedCells = 0;
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft.level;
        LocalPlayer player = minecraft.player;
        if (level == null || player == null || camera == null || proxyChunks.isEmpty()) {
            lastRenderedProxyChunks = 0;
            lastRenderedCells = 0;
            return;
        }

        prepareRenderBuffer(camera, player);
        if (renderBuffer.isEmpty()) {
            lastRenderedProxyChunks = 0;
            lastRenderedCells = 0;
            return;
        }

        Vec3 cameraPosition = camera.getPosition();
        double cameraX = cameraPosition.x;
        double cameraY = cameraPosition.y;
        double cameraZ = cameraPosition.z;
        Matrix4f poseMatrix = poseStack.last().pose();

        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(true);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator tesselator = RenderSystem.renderThreadTesselator();
        BufferBuilder bufferBuilder = tesselator.getBuilder();
        bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);

        int renderedCells = 0;
        int levelMinBuildHeight = level.getMinBuildHeight();
        for (TerrainProxyChunk proxyChunk : renderBuffer) {
            int distanceChunks = resolveChunkDistance(player, proxyChunk.chunkX(), proxyChunk.chunkZ());
            int stride = ManagedChunkRadiusController.getProxyStride(distanceChunks);
            float alpha = resolveChunkAlpha(distanceChunks);
            renderedCells += appendChunkProxy(bufferBuilder, poseMatrix, proxyChunk, cameraX, cameraY, cameraZ, levelMinBuildHeight, stride, alpha);
        }

        BufferUploader.drawWithShader(bufferBuilder.end());
        RenderSystem.enableCull();
        RenderSystem.disableBlend();
        lastRenderedProxyChunks = renderBuffer.size();
        lastRenderedCells = renderedCells;
    }

    public static void reset() {
        trackedLevelIdentity = 0;
        centerChunkX = Integer.MIN_VALUE;
        centerChunkZ = Integer.MIN_VALUE;
        captureRadiusChunks = -1;
        captureCursor = 0;
        captureTicker = 0;
        captureGeneration = 0;
        predictiveBiasChunks = 0;
        lastRenderedProxyChunks = 0;
        lastRenderedCells = 0;
        if (proxyChunks.size() > RESET_SHRINK_THRESHOLD) {
            proxyChunks = new HashMap<>(1024);
        } else {
            proxyChunks.clear();
        }
        if (renderBuffer.size() > RESET_SHRINK_THRESHOLD) {
            renderBuffer = new ArrayList<>(256);
        } else {
            renderBuffer.clear();
        }
    }

    public static int getCachedChunkCount() {
        return proxyChunks.size();
    }

    public static String getStatusLine() {
        if (!ManagedChunkRadiusController.isProxyEnabled()) {
            return "Terrain proxy: off (" + AuthoritativeRuntimeController.getTerrainProxyBlockReason() + ")";
        }

        return "Terrain proxy: cache=" + proxyChunks.size()
                + " render=" + lastRenderedProxyChunks
                + " cells=" + lastRenderedCells
                + " bias=" + predictiveBiasChunks;
    }

    private static void beginCapture(int newCenterChunkX, int newCenterChunkZ, int newCaptureRadiusChunks) {
        centerChunkX = newCenterChunkX;
        centerChunkZ = newCenterChunkZ;
        captureRadiusChunks = newCaptureRadiusChunks;
        captureCursor = 0;
        captureTicker = CAPTURE_INTERVAL_TICKS;
    }

    private static void processCaptureBatch(ClientLevel level, LocalPlayer player) {
        if (captureCursor == 0) {
            captureGeneration++;
        }

        int sideLength = captureRadiusChunks * 2 + 1;
        int totalCells = sideLength * sideLength;
        int batchSize = BASE_CAPTURE_BATCH + PauCClient.getCpuInvolvementLevel() * 12;
        if (GlobalPerformanceGovernor.getMode() == GlobalPerformanceMode.TRANSIT) {
            batchSize += 16;
        }
        if (LatencyController.getPressureLevel() >= 2 || IntegratedServerLoadController.getPressureLevel() >= 2) {
            batchSize = Math.max(24, batchSize - 24);
        }

        for (int processed = 0; processed < batchSize && captureCursor < totalCells; processed++) {
            int linearIndex = captureCursor++;
            int offsetX = linearIndex % sideLength - captureRadiusChunks;
            int offsetZ = linearIndex / sideLength - captureRadiusChunks;
            sampleLoadedChunk(level, centerChunkX + offsetX, centerChunkZ + offsetZ);
        }

        if (captureCursor >= totalCells) {
            captureCursor = 0;
            trimCache(player);
        } else if (proxyChunks.size() > MAX_CACHE_ENTRIES) {
            trimCache(player);
        }
    }

    private static void sampleLoadedChunk(ClientLevel level, int chunkX, int chunkZ) {
        if (!level.hasChunk(chunkX, chunkZ)) {
            return;
        }

        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        TerrainProxyChunk proxyChunk = proxyChunks.computeIfAbsent(chunkKey, ignored -> new TerrainProxyChunk(chunkKey, chunkX, chunkZ));

        BlockPos.MutableBlockPos samplePos = new BlockPos.MutableBlockPos();
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = Integer.MIN_VALUE;
        int heightSum = 0;

        for (int cellZ = 0; cellZ < CELL_GRID; cellZ++) {
            for (int cellX = 0; cellX < CELL_GRID; cellX++) {
                int index = cellZ * CELL_GRID + cellX;
                int localX = cellX * CELL_SIZE_BLOCKS + CELL_SIZE_BLOCKS / 2;
                int localZ = cellZ * CELL_SIZE_BLOCKS + CELL_SIZE_BLOCKS / 2;
                int surfaceY = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, localX, localZ);
                int topY = Mth.clamp(surfaceY - 1, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
                samplePos.set((chunkX << 4) + localX, topY, (chunkZ << 4) + localZ);
                BlockState blockState = chunk.getBlockState(samplePos);
                int colorArgb = resolveCellColor(level, samplePos, blockState, topY);
                proxyChunk.setCell(index, topY, colorArgb);
                minHeight = Math.min(minHeight, topY);
                maxHeight = Math.max(maxHeight, topY);
                heightSum += topY;
            }
        }

        proxyChunk.finishUpdate(minHeight, maxHeight, heightSum / CELL_COUNT, captureGeneration);
    }

    private static int resolveCellColor(ClientLevel level, BlockPos blockPos, BlockState blockState, int topY) {
        if (!blockState.getFluidState().isEmpty()) {
            return shadeColor(0xFF4D80C8, resolveHeightShade(level, topY) * 0.92D);
        }

        MapColor mapColor = blockState.getMapColor(level, blockPos);
        if (mapColor != null && mapColor.col != 0) {
            return shadeColor(0xFF000000 | mapColor.col, resolveHeightShade(level, topY));
        }

        return shadeColor(0xFF6E8B5E, resolveHeightShade(level, topY));
    }

    private static double resolveHeightShade(ClientLevel level, int topY) {
        int verticalSpan = Math.max(1, level.getMaxBuildHeight() - level.getMinBuildHeight());
        double normalized = (topY - level.getMinBuildHeight()) / (double) verticalSpan;
        return 0.72D + normalized * 0.32D;
    }

    private static void trimCache(LocalPlayer player) {
        ChunkAnchor captureAnchor = resolveCaptureAnchor(player);
        int managedRadius = ManagedChunkRadiusController.getManagedRadiusChunks();
        int fullDetailRadius = ManagedChunkRadiusController.getFullDetailRadiusChunks();
        boolean trimForCapacity = proxyChunks.size() > MAX_CACHE_ENTRIES;

        Iterator<Map.Entry<Long, TerrainProxyChunk>> iterator = proxyChunks.entrySet().iterator();
        while (iterator.hasNext()) {
            TerrainProxyChunk proxyChunk = iterator.next().getValue();
            int distanceChunks = Math.max(Math.abs(proxyChunk.chunkX() - captureAnchor.chunkX), Math.abs(proxyChunk.chunkZ() - captureAnchor.chunkZ));
            boolean outsideManagedRadius = distanceChunks > managedRadius + CACHE_DISTANCE_SLACK;
            boolean stale = captureGeneration - proxyChunk.lastSeenGeneration() > STALE_SWEEP_GRACE && distanceChunks > fullDetailRadius;
            boolean trimByCapacity = trimForCapacity
                    && captureGeneration - proxyChunk.lastSeenGeneration() > 2
                    && distanceChunks > fullDetailRadius + 8;
            if (outsideManagedRadius || stale || trimByCapacity) {
                iterator.remove();
            }
        }
    }

    private static void prepareRenderBuffer(Camera camera, LocalPlayer player) {
        int expectedCapacity = Math.min(proxyChunks.size(), 1024);
        if (renderBuffer.size() > expectedCapacity * 2 + 128) {
            renderBuffer = new ArrayList<>(Math.max(256, expectedCapacity));
        } else {
            renderBuffer.clear();
            renderBuffer.ensureCapacity(Math.max(64, expectedCapacity));
        }

        int playerChunkX = Mth.floor(player.getX()) >> 4;
        int playerChunkZ = Mth.floor(player.getZ()) >> 4;
        int fullDetailRadius = ManagedChunkRadiusController.getFullDetailRadiusChunks();
        int proxyRadius = ManagedChunkRadiusController.getProxyRadiusChunks();
        double fullDetailDistanceSqr = square(ManagedChunkRadiusController.getProxyStartDistanceBlocks());
        double proxyDistanceSqr = square(ManagedChunkRadiusController.getProxyDistanceBlocks() + CHUNK_SIZE);
        Vec3 cameraPosition = camera.getPosition();

        for (TerrainProxyChunk proxyChunk : proxyChunks.values()) {
            int distanceChunks = Math.max(Math.abs(proxyChunk.chunkX() - playerChunkX), Math.abs(proxyChunk.chunkZ() - playerChunkZ));
            if (distanceChunks <= fullDetailRadius || distanceChunks > proxyRadius) {
                continue;
            }

            int stride = ManagedChunkRadiusController.getProxyStride(distanceChunks);
            if (Math.floorMod(proxyChunk.chunkX(), stride) != 0 || Math.floorMod(proxyChunk.chunkZ(), stride) != 0) {
                continue;
            }

            double dx = proxyChunk.centerX() - cameraPosition.x;
            double dy = proxyChunk.averageHeight() - cameraPosition.y;
            double dz = proxyChunk.centerZ() - cameraPosition.z;
            double distanceSqr = dx * dx + dy * dy + dz * dz;
            if (distanceSqr <= fullDetailDistanceSqr || distanceSqr >= proxyDistanceSqr) {
                continue;
            }

            double bonusDegrees = distanceChunks >= 128 ? 18.0D : 10.0D;
            if (GlobalPerformanceGovernor.getMode() == GlobalPerformanceMode.TRANSIT && isAheadOfTravelVector(player, proxyChunk.centerX(), proxyChunk.centerZ())) {
                bonusDegrees += 8.0D;
            }
            if (!isInsideVisibilityCone(camera, proxyChunk.centerX(), proxyChunk.averageHeight(), proxyChunk.centerZ(), bonusDegrees)) {
                continue;
            }

            renderBuffer.add(proxyChunk);
        }
    }

    private static boolean isInsideVisibilityCone(Camera camera, double targetX, double targetY, double targetZ, double bonusDegrees) {
        Vec3 cameraPosition = camera.getPosition();
        double toTargetX = targetX - cameraPosition.x;
        double toTargetY = targetY - cameraPosition.y;
        double toTargetZ = targetZ - cameraPosition.z;
        double toTargetLengthSqr = toTargetX * toTargetX + toTargetY * toTargetY + toTargetZ * toTargetZ;
        if (toTargetLengthSqr < 1.0E-4D) {
            return true;
        }

        Vector3f lookVector = camera.getLookVector();
        double forwardX = lookVector.x();
        double forwardY = lookVector.y();
        double forwardZ = lookVector.z();
        double forwardLengthSqr = forwardX * forwardX + forwardY * forwardY + forwardZ * forwardZ;
        if (forwardLengthSqr < 1.0E-4D) {
            return true;
        }

        double inverseForwardLength = 1.0D / Math.sqrt(forwardLengthSqr);
        double inverseTargetLength = 1.0D / Math.sqrt(toTargetLengthSqr);
        double dot = (forwardX * toTargetX + forwardY * toTargetY + forwardZ * toTargetZ) * inverseForwardLength * inverseTargetLength;
        double halfFovRadians = Math.toRadians(DistanceBudgetController.getCurrentCameraFovDegrees() * 0.5D + DistanceBudgetController.getVisibilityConeMarginDegrees(bonusDegrees));
        return dot >= Math.cos(halfFovRadians);
    }

    private static int resolveChunkDistance(LocalPlayer player, int chunkX, int chunkZ) {
        int playerChunkX = Mth.floor(player.getX()) >> 4;
        int playerChunkZ = Mth.floor(player.getZ()) >> 4;
        return Math.max(Math.abs(chunkX - playerChunkX), Math.abs(chunkZ - playerChunkZ));
    }

    private static ChunkAnchor resolveCaptureAnchor(LocalPlayer player) {
        int playerChunkX = Mth.floor(player.getX()) >> 4;
        int playerChunkZ = Mth.floor(player.getZ()) >> 4;
        int biasChunks = ManagedChunkRadiusController.getPredictiveBiasChunks();
        predictiveBiasChunks = biasChunks;
        if (biasChunks <= 0 || player == null) {
            return new ChunkAnchor(playerChunkX, playerChunkZ);
        }

        Vec3 forward = resolvePreferredForwardVector(player);
        double horizontalLengthSqr = forward.x * forward.x + forward.z * forward.z;
        if (horizontalLengthSqr < 1.0E-4D) {
            return new ChunkAnchor(playerChunkX, playerChunkZ);
        }

        double inverseLength = 1.0D / Math.sqrt(horizontalLengthSqr);
        int anchorChunkX = playerChunkX + (int) Math.round(forward.x * inverseLength * biasChunks);
        int anchorChunkZ = playerChunkZ + (int) Math.round(forward.z * inverseLength * biasChunks);
        return new ChunkAnchor(anchorChunkX, anchorChunkZ);
    }

    private static Vec3 resolvePreferredForwardVector(LocalPlayer player) {
        Vec3 delta = player.getDeltaMovement();
        double horizontalSpeedSqr = delta.x * delta.x + delta.z * delta.z;
        if (horizontalSpeedSqr >= 1.0E-4D) {
            return new Vec3(delta.x, 0.0D, delta.z);
        }

        Vec3 lookAngle = player.getLookAngle();
        return new Vec3(lookAngle.x, 0.0D, lookAngle.z);
    }

    private static boolean isAheadOfTravelVector(LocalPlayer player, double targetX, double targetZ) {
        Vec3 forward = resolvePreferredForwardVector(player);
        double horizontalLengthSqr = forward.x * forward.x + forward.z * forward.z;
        if (horizontalLengthSqr < 1.0E-4D) {
            return false;
        }

        double playerX = player.getX();
        double playerZ = player.getZ();
        double toTargetX = targetX - playerX;
        double toTargetZ = targetZ - playerZ;
        double targetLengthSqr = toTargetX * toTargetX + toTargetZ * toTargetZ;
        if (targetLengthSqr < 1.0E-4D) {
            return true;
        }

        double inverseForwardLength = 1.0D / Math.sqrt(horizontalLengthSqr);
        double inverseTargetLength = 1.0D / Math.sqrt(targetLengthSqr);
        double dot = (forward.x * toTargetX + forward.z * toTargetZ) * inverseForwardLength * inverseTargetLength;
        return dot >= 0.45D;
    }

    private static float resolveChunkAlpha(int distanceChunks) {
        int proxyStart = ManagedChunkRadiusController.getProxyStartRadiusChunks();
        int proxyRadius = ManagedChunkRadiusController.getProxyRadiusChunks();
        if (proxyRadius <= proxyStart) {
            return 0.55F;
        }

        double progress = (distanceChunks - proxyStart) / (double) Math.max(1, proxyRadius - proxyStart);
        double alpha = 0.84D - progress * 0.34D;
        if (GlobalPerformanceGovernor.getMode() == GlobalPerformanceMode.CRISIS) {
            alpha *= 0.82D;
        }
        return (float) Mth.clamp(alpha, 0.38D, 0.88D);
    }

    private static int resolveRenderCellStep(int distanceChunks) {
        if (distanceChunks >= 192) {
            return 4;
        }
        if (distanceChunks >= 128) {
            return 2;
        }
        return 1;
    }

    private static int resolveChunkDistanceFromWorld(int chunkX, int chunkZ, double cameraX, double cameraZ) {
        int cameraChunkX = Mth.floor(cameraX) >> 4;
        int cameraChunkZ = Mth.floor(cameraZ) >> 4;
        return Math.max(Math.abs(chunkX - cameraChunkX), Math.abs(chunkZ - cameraChunkZ));
    }

    private static int appendChunkProxy(
            BufferBuilder bufferBuilder,
            Matrix4f poseMatrix,
            TerrainProxyChunk proxyChunk,
            double cameraX,
            double cameraY,
            double cameraZ,
            int levelMinBuildHeight,
            int stride,
            float alpha
    ) {
        double chunkMinX = proxyChunk.chunkX() * CHUNK_SIZE - cameraX;
        double chunkMinZ = proxyChunk.chunkZ() * CHUNK_SIZE - cameraZ;
        double chunkSpan = CHUNK_SIZE * stride;
        double cellSpan = chunkSpan / CELL_GRID;
        int cellStep = resolveRenderCellStep(resolveChunkDistanceFromWorld(proxyChunk.chunkX(), proxyChunk.chunkZ(), cameraX, cameraZ));
        int renderedCells = 0;

        for (int cellZ = 0; cellZ < CELL_GRID; cellZ += cellStep) {
            for (int cellX = 0; cellX < CELL_GRID; cellX += cellStep) {
                int topHeight = proxyChunk.resolveGroupedHeight(cellX, cellZ, cellStep);
                int colorArgb = proxyChunk.resolveGroupedColor(cellX, cellZ, cellStep);
                double topY = topHeight - cameraY;
                double relief = Math.max(8.0D, proxyChunk.maxHeight() - proxyChunk.minHeight() + 6.0D);
                double bottomY = Math.max(levelMinBuildHeight, topHeight - relief) - cameraY;
                double minX = chunkMinX + cellX * cellSpan;
                double minZ = chunkMinZ + cellZ * cellSpan;
                double maxX = minX + cellSpan * cellStep;
                double maxZ = minZ + cellSpan * cellStep;
                appendPrism(bufferBuilder, poseMatrix, minX, bottomY, minZ, maxX, topY, maxZ, colorArgb, alpha);
                renderedCells++;
            }
        }

        return renderedCells;
    }

    private static void appendPrism(
            BufferBuilder bufferBuilder,
            Matrix4f poseMatrix,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            int colorArgb,
            float alpha
    ) {
        if (maxY <= minY) {
            maxY = minY + 1.0D;
        }

        int topColor = withAlpha(colorArgb, alpha);
        int sideColor = shadeColor(withAlpha(colorArgb, alpha), 0.74D);
        int darkSideColor = shadeColor(withAlpha(colorArgb, alpha), 0.60D);

        addQuad(bufferBuilder, poseMatrix, minX, maxY, minZ, maxX, maxY, minZ, maxX, maxY, maxZ, minX, maxY, maxZ, topColor);
        addQuad(bufferBuilder, poseMatrix, minX, minY, minZ, maxX, minY, minZ, maxX, maxY, minZ, minX, maxY, minZ, sideColor);
        addQuad(bufferBuilder, poseMatrix, maxX, minY, minZ, maxX, minY, maxZ, maxX, maxY, maxZ, maxX, maxY, minZ, darkSideColor);
        addQuad(bufferBuilder, poseMatrix, maxX, minY, maxZ, minX, minY, maxZ, minX, maxY, maxZ, maxX, maxY, maxZ, sideColor);
        addQuad(bufferBuilder, poseMatrix, minX, minY, maxZ, minX, minY, minZ, minX, maxY, minZ, minX, maxY, maxZ, darkSideColor);
    }

    private static void addQuad(
            BufferBuilder bufferBuilder,
            Matrix4f poseMatrix,
            double x0,
            double y0,
            double z0,
            double x1,
            double y1,
            double z1,
            double x2,
            double y2,
            double z2,
            double x3,
            double y3,
            double z3,
            int colorArgb
    ) {
        int alpha = colorArgb >>> 24 & 0xFF;
        int red = colorArgb >>> 16 & 0xFF;
        int green = colorArgb >>> 8 & 0xFF;
        int blue = colorArgb & 0xFF;

        bufferBuilder.vertex(poseMatrix, (float) x0, (float) y0, (float) z0).color(red, green, blue, alpha).endVertex();
        bufferBuilder.vertex(poseMatrix, (float) x1, (float) y1, (float) z1).color(red, green, blue, alpha).endVertex();
        bufferBuilder.vertex(poseMatrix, (float) x2, (float) y2, (float) z2).color(red, green, blue, alpha).endVertex();
        bufferBuilder.vertex(poseMatrix, (float) x3, (float) y3, (float) z3).color(red, green, blue, alpha).endVertex();
    }

    private static int withAlpha(int colorArgb, float alpha) {
        int resolvedAlpha = Math.max(0, Math.min(255, Math.round(((colorArgb >>> 24) & 0xFF) * alpha)));
        return (resolvedAlpha << 24) | (colorArgb & 0x00FFFFFF);
    }

    private static int shadeColor(int colorArgb, double factor) {
        int alpha = colorArgb >>> 24 & 0xFF;
        int red = (int) Math.round((colorArgb >>> 16 & 0xFF) * factor);
        int green = (int) Math.round((colorArgb >>> 8 & 0xFF) * factor);
        int blue = (int) Math.round((colorArgb & 0xFF) * factor);
        return (alpha << 24)
                | (Math.max(0, Math.min(255, red)) << 16)
                | (Math.max(0, Math.min(255, green)) << 8)
                | Math.max(0, Math.min(255, blue));
    }

    private static double square(double value) {
        return value * value;
    }

    private record ChunkAnchor(int chunkX, int chunkZ) {
    }

    private static final class TerrainProxyChunk {
        private final long chunkKey;
        private final int chunkX;
        private final int chunkZ;
        private final int[] cellHeights = new int[CELL_COUNT];
        private final int[] cellColors = new int[CELL_COUNT];
        private int minHeight;
        private int maxHeight;
        private int averageHeight;
        private int lastSeenGeneration;

        private TerrainProxyChunk(long chunkKey, int chunkX, int chunkZ) {
            this.chunkKey = chunkKey;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }

        private void setCell(int index, int height, int colorArgb) {
            this.cellHeights[index] = height;
            this.cellColors[index] = colorArgb;
        }

        private void finishUpdate(int minHeight, int maxHeight, int averageHeight, int lastSeenGeneration) {
            this.minHeight = minHeight;
            this.maxHeight = maxHeight;
            this.averageHeight = averageHeight;
            this.lastSeenGeneration = lastSeenGeneration;
        }

        private int chunkX() {
            return this.chunkX;
        }

        private int chunkZ() {
            return this.chunkZ;
        }

        private int minHeight() {
            return this.minHeight;
        }

        private int maxHeight() {
            return this.maxHeight;
        }

        private int averageHeight() {
            return this.averageHeight;
        }

        private int lastSeenGeneration() {
            return this.lastSeenGeneration;
        }

        private int cellHeight(int index) {
            return this.cellHeights[index];
        }

        private int cellColorArgb(int index) {
            return this.cellColors[index];
        }

        private int resolveGroupedHeight(int startCellX, int startCellZ, int cellStep) {
            int sum = 0;
            int count = 0;
            for (int offsetZ = 0; offsetZ < cellStep && startCellZ + offsetZ < CELL_GRID; offsetZ++) {
                for (int offsetX = 0; offsetX < cellStep && startCellX + offsetX < CELL_GRID; offsetX++) {
                    int index = (startCellZ + offsetZ) * CELL_GRID + (startCellX + offsetX);
                    sum += this.cellHeights[index];
                    count++;
                }
            }
            return count <= 0 ? this.averageHeight : Math.round((float) sum / (float) count);
        }

        private int resolveGroupedColor(int startCellX, int startCellZ, int cellStep) {
            int alphaSum = 0;
            int redSum = 0;
            int greenSum = 0;
            int blueSum = 0;
            int count = 0;
            for (int offsetZ = 0; offsetZ < cellStep && startCellZ + offsetZ < CELL_GRID; offsetZ++) {
                for (int offsetX = 0; offsetX < cellStep && startCellX + offsetX < CELL_GRID; offsetX++) {
                    int index = (startCellZ + offsetZ) * CELL_GRID + (startCellX + offsetX);
                    int colorArgb = this.cellColors[index];
                    alphaSum += colorArgb >>> 24 & 0xFF;
                    redSum += colorArgb >>> 16 & 0xFF;
                    greenSum += colorArgb >>> 8 & 0xFF;
                    blueSum += colorArgb & 0xFF;
                    count++;
                }
            }

            if (count <= 0) {
                return 0xFF6E8B5E;
            }

            return ((alphaSum / count) << 24)
                    | ((redSum / count) << 16)
                    | ((greenSum / count) << 8)
                    | (blueSum / count);
        }

        private double centerX() {
            return (this.chunkX << 4) + CHUNK_SIZE * 0.5D;
        }

        private double centerZ() {
            return (this.chunkZ << 4) + CHUNK_SIZE * 0.5D;
        }
    }
}
