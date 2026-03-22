package pauc.pain_au_choc;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

public final class StructureStreamingController {
    private static final int SCAN_INTERVAL_TICKS = 6;
    private static final int BASE_SCAN_BATCH = 24;
    private static final int WORK_PHASE = 3;
    private static final int MAX_STALE_SWEEPS = 2;
    private static final int WORKING_SET_SLACK = 16;
    private static final int RESET_SHRINK_THRESHOLD = 512;
    private static final int ROOT_PADDING_CHUNKS = 2;
    private static final double CHUNK_SIZE = 16.0D;

    private static final BoxOctree<StructureChunkInfo> CHUNK_OCTREE = new BoxOctree<>(6, 5);
    private static HashMap<Long, StructureChunkInfo> knownChunks = new HashMap<>();
    private static ArrayList<StructureChunkInfo> queryBuffer = new ArrayList<>();
    private static HashSet<Long> activeChunkKeys = new HashSet<>();
    private static HashSet<Long> fullDetailChunkKeys = new HashSet<>();

    private static int trackedLevelIdentity;
    private static int centerChunkX = Integer.MIN_VALUE;
    private static int centerChunkZ = Integer.MIN_VALUE;
    private static int scanRadiusChunks = -1;
    private static int scanCursor;
    private static int scanTicker;
    private static int sweepGeneration;
    private static int predictiveBiasChunks;
    private static int lastKnownChunkCount;
    private static int lastActiveChunkCount;
    private static int lastDeferredChunkCount;
    private static int lastFullDetailRadiusChunks;
    private static int lastStreamingRadiusChunks;
    private static boolean ready;

    private StructureStreamingController() {
    }

    public static void tick() {
        if (!PauCClient.isBudgetActive()) {
            reset();
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

        int fullDetailRadius = ManagedChunkRadiusController.getFullDetailRadiusChunks();
        int radiusChunks = ManagedChunkRadiusController.getStreamingRadiusChunks();
        ChunkAnchor scanAnchor = resolveScanAnchor(player);
        if (scanAnchor.chunkX != centerChunkX || scanAnchor.chunkZ != centerChunkZ || radiusChunks != scanRadiusChunks) {
            beginSweep(scanAnchor.chunkX, scanAnchor.chunkZ, radiusChunks);
        }
        lastFullDetailRadiusChunks = fullDetailRadius;
        lastStreamingRadiusChunks = radiusChunks;

        scanTicker++;
        int adaptiveInterval = LatencyController.getAdaptiveInterval(SCAN_INTERVAL_TICKS);
        int mitigationTier = IntegratedServerLoadController.getMitigationTier();
        if (mitigationTier >= 2) {
            adaptiveInterval += 1;
        }
        if (mitigationTier >= 3) {
            adaptiveInterval += 2;
        }
        if (AuthoritativeRuntimeController.shouldThrottleChunkStreaming()) {
            adaptiveInterval += 2;
        }
        if (ready && scanTicker < adaptiveInterval) {
            return;
        }

        if (!LatencyController.shouldRunBackgroundWork(WORK_PHASE)) {
            return;
        }

        scanTicker = 0;
        processScanBatch(level, player);
    }

    public static void reset() {
        trackedLevelIdentity = 0;
        centerChunkX = Integer.MIN_VALUE;
        centerChunkZ = Integer.MIN_VALUE;
        scanRadiusChunks = -1;
        scanCursor = 0;
        scanTicker = 0;
        sweepGeneration = 0;
        predictiveBiasChunks = 0;
        lastKnownChunkCount = 0;
        lastActiveChunkCount = 0;
        lastDeferredChunkCount = 0;
        lastFullDetailRadiusChunks = 0;
        lastStreamingRadiusChunks = 0;
        ready = false;
        if (knownChunks.size() > RESET_SHRINK_THRESHOLD) {
            knownChunks = new HashMap<>(WORKING_SET_SLACK * 8);
        } else {
            knownChunks.clear();
        }

        if (queryBuffer.size() > RESET_SHRINK_THRESHOLD) {
            queryBuffer = new ArrayList<>(WORKING_SET_SLACK * 4);
        } else {
            queryBuffer.clear();
        }

        if (activeChunkKeys.size() > RESET_SHRINK_THRESHOLD) {
            activeChunkKeys = new HashSet<>(WORKING_SET_SLACK * 8);
        } else {
            activeChunkKeys.clear();
        }

        if (fullDetailChunkKeys.size() > RESET_SHRINK_THRESHOLD) {
            fullDetailChunkKeys = new HashSet<>(WORKING_SET_SLACK * 8);
        } else {
            fullDetailChunkKeys.clear();
        }
        CHUNK_OCTREE.clear();
    }

    public static boolean isChunkFullDetail(BlockPos blockPos) {
        return blockPos != null && isChunkFullDetail(blockPos.getX(), blockPos.getZ());
    }

    public static boolean isChunkFullDetail(int blockX, int blockZ) {
        if (!ready || !PauCClient.isBudgetActive()) {
            return false;
        }

        return fullDetailChunkKeys.contains(toChunkKey(blockX, blockZ));
    }

    public static boolean isChunkDeferred(BlockPos blockPos) {
        return blockPos != null && isChunkDeferred(blockPos.getX(), blockPos.getZ());
    }

    public static boolean isChunkDeferred(int blockX, int blockZ) {
        if (!ready || !PauCClient.isBudgetActive()) {
            return false;
        }

        long chunkKey = toChunkKey(blockX, blockZ);
        return knownChunks.containsKey(chunkKey) && !activeChunkKeys.contains(chunkKey);
    }

    public static int getKnownChunkCount() {
        return lastKnownChunkCount;
    }

    public static int getActiveChunkCount() {
        return lastActiveChunkCount;
    }

    public static int getDeferredChunkCount() {
        return lastDeferredChunkCount;
    }

    public static String getStatusLine() {
        if (!PauCClient.isBudgetActive()) {
            return "Streaming: off (budget inactive)";
        }

        if (!ready) {
            return "Streaming: warmup scan=" + scanCursor + " radius=" + Math.max(0, scanRadiusChunks) + "c";
        }

        String throttleLabel = AuthoritativeRuntimeController.shouldThrottleChunkStreaming() ? " throttled" : "";
        int mitigationTier = IntegratedServerLoadController.getMitigationTier();
        int emergencyTicks = IntegratedServerLoadController.getEmergencyHoldTicks();
        return "Streaming: known=" + lastKnownChunkCount
                + " active=" + lastActiveChunkCount
                + " deferred=" + lastDeferredChunkCount
                + " full=" + lastFullDetailRadiusChunks
                + "c stream=" + lastStreamingRadiusChunks
                + "c bias=" + predictiveBiasChunks
                + " tier=" + mitigationTier
                + " emergency=" + emergencyTicks
                + throttleLabel;
    }

    private static void beginSweep(int newCenterChunkX, int newCenterChunkZ, int newRadiusChunks) {
        centerChunkX = newCenterChunkX;
        centerChunkZ = newCenterChunkZ;
        scanRadiusChunks = newRadiusChunks;
        scanCursor = 0;
        scanTicker = SCAN_INTERVAL_TICKS;
    }

    private static void processScanBatch(ClientLevel level, LocalPlayer player) {
        if (scanCursor == 0) {
            sweepGeneration++;
        }

        int sideLength = scanRadiusChunks * 2 + 1;
        int totalCells = sideLength * sideLength;
        int batchSize = ready ? BASE_SCAN_BATCH : BASE_SCAN_BATCH * 2;
        int fullDetailRadius = ManagedChunkRadiusController.getFullDetailRadiusChunks();
        int ringExpansion = Math.max(0, scanRadiusChunks - fullDetailRadius);
        int mitigationTier = IntegratedServerLoadController.getMitigationTier();
        batchSize += ringExpansion / 6;
        if (GlobalPerformanceGovernor.getMode() == GlobalPerformanceMode.TRANSIT) {
            batchSize += 12;
        }
        if (LatencyController.getPressureLevel() >= 2 || IntegratedServerLoadController.getPressureLevel() >= 2) {
            batchSize = Math.max(8, batchSize - 10);
        }
        if (mitigationTier >= 2) {
            batchSize = Math.max(8, batchSize - 8);
        }
        if (mitigationTier >= 3) {
            batchSize = Math.max(6, batchSize - 12);
        }
        if (IntegratedServerLoadController.isEmergencyMitigationActive()) {
            batchSize = Math.min(batchSize, 10);
        }
        if (AuthoritativeRuntimeController.shouldThrottleChunkStreaming()) {
            batchSize = Math.max(8, batchSize - 8);
        }
        if (AuthoritativeRuntimeController.shouldDeferNonCriticalMutations()) {
            batchSize = Math.min(batchSize, 6);
        }
        batchSize = Math.min(192, batchSize);

        for (int processed = 0; processed < batchSize && scanCursor < totalCells; processed++) {
            int linearIndex = scanCursor++;
            int offsetX = linearIndex % sideLength - scanRadiusChunks;
            int offsetZ = linearIndex / sideLength - scanRadiusChunks;
            inspectChunk(level, centerChunkX + offsetX, centerChunkZ + offsetZ);
        }

        if (scanCursor < totalCells) {
            return;
        }

        rebuildPrioritySets(level, player);
        scanCursor = 0;
        ready = true;
    }

    private static void inspectChunk(ClientLevel level, int chunkX, int chunkZ) {
        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        if (!level.hasChunk(chunkX, chunkZ)) {
            knownChunks.remove(chunkKey);
            return;
        }

        LevelChunk chunk = level.getChunk(chunkX, chunkZ);
        int nonEmptySections = countNonEmptySections(chunk.getSections());
        int blockEntityCount = chunk.getBlockEntities().size();
        if (nonEmptySections <= 0 && blockEntityCount <= 0) {
            knownChunks.remove(chunkKey);
            return;
        }

        StructureChunkInfo chunkInfo = knownChunks.get(chunkKey);
        if (chunkInfo == null) {
            knownChunks.put(chunkKey, new StructureChunkInfo(chunkKey, chunkX, chunkZ, nonEmptySections, blockEntityCount, sweepGeneration));
            return;
        }

        chunkInfo.update(nonEmptySections, blockEntityCount, sweepGeneration);
    }

    private static void rebuildPrioritySets(ClientLevel level, LocalPlayer player) {
        trimKnownChunksToWindow();
        prepareWorkingSets(knownChunks.size());

        if (knownChunks.isEmpty()) {
            CHUNK_OCTREE.clear();
            lastKnownChunkCount = 0;
            lastActiveChunkCount = 0;
            lastDeferredChunkCount = 0;
            return;
        }

        int minBuildHeight = level.getMinBuildHeight();
        int maxBuildHeight = level.getMaxBuildHeight();
        int fullDetailRadius = ManagedChunkRadiusController.getFullDetailRadiusChunks();
        int streamingRadius = Math.max(fullDetailRadius, scanRadiusChunks);
        double rootHalfSize = Math.max(32.0D, streamingRadius * CHUNK_SIZE + ROOT_PADDING_CHUNKS * CHUNK_SIZE);
        double centerY = minBuildHeight + (maxBuildHeight - minBuildHeight) * 0.5D;

        CHUNK_OCTREE.reset(player.getX(), centerY, player.getZ(), rootHalfSize);
        for (StructureChunkInfo chunkInfo : knownChunks.values()) {
            CHUNK_OCTREE.insert(chunkInfo.minX(), minBuildHeight, chunkInfo.minZ(), chunkInfo.maxX(), maxBuildHeight, chunkInfo.maxZ(), chunkInfo);
        }

        double queryRadius = streamingRadius * CHUNK_SIZE + CHUNK_SIZE;
        CHUNK_OCTREE.query(
                player.getX() - queryRadius,
                minBuildHeight,
                player.getZ() - queryRadius,
                player.getX() + queryRadius,
                maxBuildHeight,
                player.getZ() + queryRadius,
                queryBuffer
        );

        double fullDetailDistanceSqr = square(fullDetailRadius * CHUNK_SIZE + CHUNK_SIZE);
        double reducedDistanceSqr = DistanceBudgetController.getReducedDetailDistanceSqr();
        double streamingDistanceSqr = square(streamingRadius * CHUNK_SIZE + CHUNK_SIZE);
        for (StructureChunkInfo chunkInfo : queryBuffer) {
            double dx = chunkInfo.centerX() - player.getX();
            double dz = chunkInfo.centerZ() - player.getZ();
            double horizontalDistanceSqr = dx * dx + dz * dz;
            if (horizontalDistanceSqr <= fullDetailDistanceSqr) {
                activeChunkKeys.add(chunkInfo.chunkKey());
                fullDetailChunkKeys.add(chunkInfo.chunkKey());
                continue;
            }

            if (horizontalDistanceSqr <= reducedDistanceSqr) {
                activeChunkKeys.add(chunkInfo.chunkKey());
                continue;
            }

            if (horizontalDistanceSqr > streamingDistanceSqr) {
                continue;
            }

            double bonusDegrees = Math.min(18.0D, chunkInfo.complexityScore() * 0.35D);
            if (isInsideVisibilityCone(chunkInfo.centerX(), player.getY(), chunkInfo.centerZ(), bonusDegrees)) {
                activeChunkKeys.add(chunkInfo.chunkKey());
            }
        }

        lastKnownChunkCount = knownChunks.size();
        lastActiveChunkCount = activeChunkKeys.size();
        lastDeferredChunkCount = Math.max(0, lastKnownChunkCount - lastActiveChunkCount);
        lastFullDetailRadiusChunks = fullDetailRadius;
        lastStreamingRadiusChunks = streamingRadius;
    }

    private static void trimKnownChunksToWindow() {
        if (scanRadiusChunks < 0 || knownChunks.isEmpty()) {
            return;
        }

        Iterator<Map.Entry<Long, StructureChunkInfo>> iterator = knownChunks.entrySet().iterator();
        while (iterator.hasNext()) {
            StructureChunkInfo chunkInfo = iterator.next().getValue();
            boolean outsideWindow = Math.abs(chunkInfo.chunkX() - centerChunkX) > scanRadiusChunks || Math.abs(chunkInfo.chunkZ() - centerChunkZ) > scanRadiusChunks;
            boolean stale = sweepGeneration - chunkInfo.lastSeenSweep() > MAX_STALE_SWEEPS;
            if (outsideWindow || stale) {
                iterator.remove();
            }
        }
    }

    private static void prepareWorkingSets(int expectedSize) {
        int targetCapacity = Math.max(8, expectedSize + WORKING_SET_SLACK);

        if (queryBuffer.size() > targetCapacity * 2) {
            queryBuffer = new ArrayList<>(targetCapacity);
        } else {
            queryBuffer.clear();
            queryBuffer.ensureCapacity(targetCapacity);
        }

        if (activeChunkKeys.size() > targetCapacity * 2) {
            activeChunkKeys = new HashSet<>(targetCapacity);
        } else {
            activeChunkKeys.clear();
        }

        if (fullDetailChunkKeys.size() > targetCapacity * 2) {
            fullDetailChunkKeys = new HashSet<>(targetCapacity);
        } else {
            fullDetailChunkKeys.clear();
        }
    }

    private static int countNonEmptySections(LevelChunkSection[] sections) {
        int count = 0;
        for (LevelChunkSection section : sections) {
            if (section != null && !section.hasOnlyAir()) {
                count++;
            }
        }
        return count;
    }

    private static boolean isInsideVisibilityCone(double targetX, double targetY, double targetZ, double bonusDegrees) {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer == null ? null : minecraft.gameRenderer.getMainCamera();
        if (camera == null) {
            return true;
        }

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

    private static long toChunkKey(int blockX, int blockZ) {
        return ChunkPos.asLong(blockX >> 4, blockZ >> 4);
    }

    private static ChunkAnchor resolveScanAnchor(LocalPlayer player) {
        int playerChunkX = Mth.floor(player.getX()) >> 4;
        int playerChunkZ = Mth.floor(player.getZ()) >> 4;
        int bias = Math.max(0, ManagedChunkRadiusController.getPredictiveBiasChunks() / 2);
        predictiveBiasChunks = bias;
        if (bias <= 0) {
            return new ChunkAnchor(playerChunkX, playerChunkZ);
        }

        Vec3 forward = resolvePreferredForwardVector(player);
        double horizontalLengthSqr = forward.x * forward.x + forward.z * forward.z;
        if (horizontalLengthSqr < 1.0E-4D) {
            return new ChunkAnchor(playerChunkX, playerChunkZ);
        }

        double inverseLength = 1.0D / Math.sqrt(horizontalLengthSqr);
        int anchorX = playerChunkX + (int) Math.round(forward.x * inverseLength * bias);
        int anchorZ = playerChunkZ + (int) Math.round(forward.z * inverseLength * bias);
        return new ChunkAnchor(anchorX, anchorZ);
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

    private static double square(double value) {
        return value * value;
    }

    private record ChunkAnchor(int chunkX, int chunkZ) {
    }

    private static final class StructureChunkInfo {
        private final long chunkKey;
        private final int chunkX;
        private final int chunkZ;
        private int nonEmptySections;
        private int blockEntityCount;
        private int complexityScore;
        private int lastSeenSweep;

        private StructureChunkInfo(long chunkKey, int chunkX, int chunkZ, int nonEmptySections, int blockEntityCount, int lastSeenSweep) {
            this.chunkKey = chunkKey;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.update(nonEmptySections, blockEntityCount, lastSeenSweep);
        }

        private void update(int nonEmptySections, int blockEntityCount, int lastSeenSweep) {
            this.nonEmptySections = nonEmptySections;
            this.blockEntityCount = blockEntityCount;
            this.complexityScore = Math.max(1, nonEmptySections + blockEntityCount * 4);
            this.lastSeenSweep = lastSeenSweep;
        }

        private long chunkKey() {
            return this.chunkKey;
        }

        private int chunkX() {
            return this.chunkX;
        }

        private int chunkZ() {
            return this.chunkZ;
        }

        private int complexityScore() {
            return this.complexityScore;
        }

        private int lastSeenSweep() {
            return this.lastSeenSweep;
        }

        private double minX() {
            return (double) (this.chunkX << 4);
        }

        private double minZ() {
            return (double) (this.chunkZ << 4);
        }

        private double maxX() {
            return this.minX() + CHUNK_SIZE;
        }

        private double maxZ() {
            return this.minZ() + CHUNK_SIZE;
        }

        private double centerX() {
            return this.minX() + CHUNK_SIZE * 0.5D;
        }

        private double centerZ() {
            return this.minZ() + CHUNK_SIZE * 0.5D;
        }
    }
}

