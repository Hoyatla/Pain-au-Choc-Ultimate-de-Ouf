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

        int radiusChunks = ManagedChunkRadiusController.getFullDetailRadiusChunks();
        int playerChunkX = Mth.floor(player.getX()) >> 4;
        int playerChunkZ = Mth.floor(player.getZ()) >> 4;
        if (playerChunkX != centerChunkX || playerChunkZ != centerChunkZ || radiusChunks != scanRadiusChunks) {
            beginSweep(playerChunkX, playerChunkZ, radiusChunks);
        }

        scanTicker++;
        int adaptiveInterval = LatencyController.getAdaptiveInterval(SCAN_INTERVAL_TICKS);
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
        if (AuthoritativeRuntimeController.shouldThrottleChunkStreaming()) {
            batchSize = Math.max(8, batchSize - 8);
        }

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
            return;
        }

        int minBuildHeight = level.getMinBuildHeight();
        int maxBuildHeight = level.getMaxBuildHeight();
        double rootHalfSize = Math.max(32.0D, DistanceBudgetController.getViewDistanceBlocks() + ROOT_PADDING_CHUNKS * CHUNK_SIZE);
        double centerY = minBuildHeight + (maxBuildHeight - minBuildHeight) * 0.5D;

        CHUNK_OCTREE.reset(player.getX(), centerY, player.getZ(), rootHalfSize);
        for (StructureChunkInfo chunkInfo : knownChunks.values()) {
            CHUNK_OCTREE.insert(chunkInfo.minX(), minBuildHeight, chunkInfo.minZ(), chunkInfo.maxX(), maxBuildHeight, chunkInfo.maxZ(), chunkInfo);
        }

        double queryRadius = Math.max(DistanceBudgetController.getAggressiveDetailDistanceBlocks(), DistanceBudgetController.getReducedDetailDistanceBlocks()) + CHUNK_SIZE;
        CHUNK_OCTREE.query(
                player.getX() - queryRadius,
                minBuildHeight,
                player.getZ() - queryRadius,
                player.getX() + queryRadius,
                maxBuildHeight,
                player.getZ() + queryRadius,
                queryBuffer
        );

        double fullDetailDistanceSqr = DistanceBudgetController.getFullDetailDistanceSqr();
        double aggressiveDistanceSqr = DistanceBudgetController.getAggressiveDetailDistanceSqr();
        for (StructureChunkInfo chunkInfo : queryBuffer) {
            double dx = chunkInfo.centerX() - player.getX();
            double dz = chunkInfo.centerZ() - player.getZ();
            double horizontalDistanceSqr = dx * dx + dz * dz;
            if (horizontalDistanceSqr <= fullDetailDistanceSqr) {
                activeChunkKeys.add(chunkInfo.chunkKey());
                fullDetailChunkKeys.add(chunkInfo.chunkKey());
                continue;
            }

            if (horizontalDistanceSqr > aggressiveDistanceSqr) {
                continue;
            }

            double bonusDegrees = Math.min(18.0D, chunkInfo.complexityScore() * 0.35D);
            if (isInsideVisibilityCone(chunkInfo.centerX(), player.getY(), chunkInfo.centerZ(), bonusDegrees)) {
                activeChunkKeys.add(chunkInfo.chunkKey());
            }
        }
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

