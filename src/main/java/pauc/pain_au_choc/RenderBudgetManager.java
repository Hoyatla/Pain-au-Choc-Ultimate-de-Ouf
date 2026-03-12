package pauc.pain_au_choc;

import net.minecraft.client.Camera;
import net.minecraft.client.CloudStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.world.phys.AABB;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ambient.AmbientCreature;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public final class RenderBudgetManager {
    private static final int VANILLA_QUALITY_LEVEL = 10;
    private static final int CACHE_PRUNE_INTERVAL_TICKS = 40;
    private static final int CACHE_TTL_TICKS = 12;
    private static final int CACHE_MAX_ENTRIES = 8192;
    private static final int VISIBILITY_ENTER_HYSTERESIS_TICKS = 1;
    private static final int VISIBILITY_EXIT_HYSTERESIS_TICKS = 3;

    private static final Map<Integer, ConeCacheEntry> ENTITY_CONE_CACHE = new HashMap<>();
    private static final Map<Integer, LosCacheEntry> ENTITY_LOS_CACHE = new HashMap<>();
    private static final Map<Integer, VisibilityHysteresisEntry> ENTITY_VISIBILITY_HYSTERESIS = new HashMap<>();

    private static int cacheLevelIdentity;
    private static long cacheTick = Long.MIN_VALUE;
    private static int cachePruneTicker;

    private RenderBudgetManager() {
    }

    public static boolean shouldRenderSkyMesh() {
        return !isSimplificationActive() || PauCClient.getQualityLevel() >= 2;
    }

    public static boolean shouldRenderClouds() {
        return !isSimplificationActive() || PauCClient.getActiveProfile().cloudStatus() != CloudStatus.OFF;
    }

    public static boolean shouldRenderWeather() {
        return !isSimplificationActive() || PauCClient.getQualityLevel() >= 3;
    }

    public static boolean shouldRenderChunkLayer(RenderType renderType) {
        if (!isSimplificationActive()) {
            return true;
        }

        // Keep core world readability intact. Only trim minor utility layers at low budget.
        if (renderType == RenderType.tripwire()) {
            return PauCClient.getQualityLevel() >= 4;
        }
        return true;
    }

    public static boolean shouldRenderParticles() {
        return !isSimplificationActive() || PauCClient.getQualityLevel() >= 3;
    }

    public static boolean shouldRenderEntity(Entity entity) {
        if (!isSimplificationActive()) {
            return true;
        }

        if (entity == null) {
            return true;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return true;
        }

        if (entity == player || entity.isPassengerOfSameVehicle(player)) {
            return true;
        }

        if (GlobalPerformanceGovernor.shouldProtectCombatEntity(entity, player)) {
            return true;
        }

        if (shouldForceMobReappearance(entity, player)) {
            return true;
        }

        if (!EntitySpatialIndex.isInRenderBroadPhase(entity)) {
            return false;
        }

        double distanceSqr = entity.distanceToSqr(player);
        if (StructureStreamingController.isChunkFullDetail(entity.getBlockX(), entity.getBlockZ()) || distanceSqr <= DistanceBudgetController.getFullDetailDistanceSqr()) {
            return true;
        }

        if (StructureStreamingController.isChunkDeferred(entity.getBlockX(), entity.getBlockZ())) {
            return false;
        }

        boolean insideVisibilityCone = isInsideVisibilityConeCached(entity);

        if (ChunkBudgetController.shouldSoftUnloadSqr(distanceSqr, insideVisibilityCone)) {
            return false;
        }

        if (!insideVisibilityCone) {
            return false;
        }

        double maxDistance = getEntityRenderDistance(entity);
        if (distanceSqr > maxDistance * maxDistance) {
            return false;
        }

        if (PauCClient.getQualityLevel() <= 3 && distanceSqr > DistanceBudgetController.getReducedDetailDistanceSqr() && entity instanceof LivingEntity && !hasLineOfSightCached(entity, player)) {
            return false;
        }

        return true;
    }

    public static boolean shouldAcceptClientSpawn(Entity entity) {
        if (!isSimplificationActive() || PauCClient.getQualityLevel() > 3) {
            return true;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || entity == player) {
            return true;
        }

        if (GlobalPerformanceGovernor.shouldProtectCombatEntity(entity, player)) {
            return true;
        }

        if (PauCClient.getQualityLevel() <= 1 && entity instanceof LivingEntity && !(entity instanceof Player)) {
            return true;
        }

        if (!(entity instanceof Enemy) && !(entity instanceof AmbientCreature)) {
            return true;
        }

        if (StructureStreamingController.isChunkFullDetail(entity.getBlockX(), entity.getBlockZ())) {
            return true;
        }

        if (entity.getY() >= player.getY() - 12.0D) {
            return true;
        }

        double spawnBudgetDistance = Math.max(24.0D, ChunkBudgetController.getSoftUnloadDistanceBlocks());
        if (player.distanceToSqr(entity) > spawnBudgetDistance * spawnBudgetDistance) {
            return true;
        }

        return hasLineOfSightCached(entity, player);
    }

    public static boolean shouldDisableClientCollision(Entity entity) {
        if (!isSimplificationActive() || entity == null) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || entity == player || entity.isPassengerOfSameVehicle(player)) {
            return false;
        }

        if (GlobalPerformanceGovernor.shouldProtectCombatEntity(entity, player)) {
            return false;
        }

        return entity.distanceToSqr(player) > DistanceBudgetController.getCollisionCutoffDistanceSqr();
    }

    public static boolean shouldPruneClientEntity(Entity entity) {
        if (!isSimplificationActive() || entity == null) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        LocalPlayer player = minecraft.player;
        if (player == null || entity == player || entity.isPassenger()) {
            return false;
        }

        if (!GlobalPerformanceGovernor.shouldAllowClientEntityPruning()) {
            return false;
        }

        if (GlobalPerformanceGovernor.shouldProtectCombatEntity(entity, player)) {
            return false;
        }

        if (PauCClient.getQualityLevel() <= 1 && entity instanceof LivingEntity && !(entity instanceof Player)) {
            return false;
        }

        if (!(entity instanceof Enemy) && !(entity instanceof AmbientCreature) && !(entity instanceof ItemEntity) && !(entity instanceof ExperienceOrb)) {
            return false;
        }

        if (StructureStreamingController.isChunkFullDetail(entity.getBlockX(), entity.getBlockZ())) {
            return false;
        }

        double cleanupDistanceSqr = Math.max(DistanceBudgetController.getCollisionCutoffDistanceSqr(), ChunkBudgetController.getSoftUnloadDistanceSqr());
        if (entity.distanceToSqr(player) < cleanupDistanceSqr) {
            return false;
        }

        if (StructureStreamingController.isChunkDeferred(entity.getBlockX(), entity.getBlockZ())) {
            return true;
        }

        return !isInsideVisibilityConeCached(entity) || !(entity instanceof LivingEntity) || !hasLineOfSightCached(entity, player);
    }

    public static boolean shouldRenderBlockEntity(BlockEntity blockEntity, Camera camera) {
        if (!isSimplificationActive() || camera == null || blockEntity == null || !blockEntity.hasLevel()) {
            return true;
        }

        BlockPos blockPos = blockEntity.getBlockPos();
        if (StructureStreamingController.isChunkFullDetail(blockPos)) {
            return true;
        }

        if (StructureStreamingController.isChunkDeferred(blockPos)) {
            return false;
        }

        Vec3 cameraPosition = camera.getPosition();
        double centerX = blockPos.getX() + 0.5D;
        double centerY = blockPos.getY() + 0.5D;
        double centerZ = blockPos.getZ() + 0.5D;
        boolean insideVisibilityCone = isInsideVisibilityCone(centerX, centerY, centerZ, 24.0D);
        double distanceSqr = distanceToSqr(cameraPosition.x, cameraPosition.y, cameraPosition.z, centerX, centerY, centerZ);
        if (distanceSqr <= DistanceBudgetController.getFullDetailDistanceSqr()) {
            return true;
        }

        if (ChunkBudgetController.shouldSoftUnloadSqr(distanceSqr, insideVisibilityCone)) {
            return false;
        }

        if (!insideVisibilityCone) {
            return false;
        }

        double maxDistance = getBlockEntityRenderDistance();
        if (distanceSqr > maxDistance * maxDistance) {
            return false;
        }

        if (PauCClient.getQualityLevel() <= 3) {
            Vec3 center = new Vec3(centerX, centerY, centerZ);
            ClipContext context = new ClipContext(cameraPosition, center, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, Minecraft.getInstance().player);
            HitResult hitResult = blockEntity.getLevel().clip(context);
            if (hitResult.getType() != HitResult.Type.MISS) {
                return false;
            }
        }

        return true;
    }

    public static boolean shouldRenderEntityShadow(Entity entity) {
        if (!isSimplificationActive() || entity == null) {
            return true;
        }

        if (!EntityLodController.shouldRenderShadows(entity)) {
            return false;
        }

        int qualityLevel = PauCClient.getQualityLevel();
        if (qualityLevel <= 3) {
            return false;
        }

        if (entity instanceof ItemEntity || entity instanceof ExperienceOrb) {
            return false;
        }

        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return true;
        }

        double dynamicShadowDistanceLimit = DistanceBudgetController.getEntityShadowDistanceLimit() * ShadowDistanceGovernor.getShadowDistanceScale();
        if (dynamicShadowDistanceLimit <= 0.25D) {
            return false;
        }

        return entity.distanceToSqr(player) <= dynamicShadowDistanceLimit * dynamicShadowDistanceLimit;
    }

    private static boolean isSimplificationActive() {
        return PauCClient.isSimplificationActive();
    }

    private static boolean isInsideVisibilityCone(Entity entity) {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer == null ? null : minecraft.gameRenderer.getMainCamera();
        if (camera == null || minecraft.player == null) {
            return true;
        }

        AABB boundingBox = entity.getBoundingBox();
        Vec3 cameraPosition = camera.getPosition();
        double centerX = (boundingBox.minX + boundingBox.maxX) * 0.5D;
        double centerY = (boundingBox.minY + boundingBox.maxY) * 0.5D;
        double centerZ = (boundingBox.minZ + boundingBox.maxZ) * 0.5D;
        double distanceSqr = distanceToSqr(cameraPosition.x, cameraPosition.y, cameraPosition.z, centerX, centerY, centerZ);
        if (distanceSqr <= 1.0E-4D) {
            return true;
        }

        double radius = Math.max(entity.getBbWidth() * 0.75D, entity.getBbHeight() * 0.50D);
        double sizePaddingDegrees = Math.toDegrees(Math.asin(Math.min(0.95D, radius / Math.sqrt(distanceSqr))));
        return isInsideVisibilityCone(centerX, centerY, centerZ, 8.0D + sizePaddingDegrees);
    }

    private static boolean isInsideVisibilityCone(double targetX, double targetY, double targetZ, double bonusDegrees) {
        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        if (camera == null || minecraft.player == null) {
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

        org.joml.Vector3f lookVector = camera.getLookVector();
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
        double marginDegrees = DistanceBudgetController.getVisibilityConeMarginDegrees(bonusDegrees);
        double halfFovRadians = Math.toRadians(DistanceBudgetController.getCurrentCameraFovDegrees() * 0.5D + marginDegrees);
        double minimumDot = Math.cos(halfFovRadians);
        return dot >= minimumDot;
    }

    private static double getEntityRenderDistance(Entity entity) {
        return DistanceBudgetController.getEntityDistanceLimit(entity);
    }

    private static double getBlockEntityRenderDistance() {
        return DistanceBudgetController.getBlockEntityDistanceLimit();
    }

    private static boolean hasLineOfSight(Entity entity, LocalPlayer player) {
        Vec3 from = entity.getBoundingBox().getCenter();
        Vec3 to = player.getEyePosition();
        ClipContext context = new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, entity);
        return entity.level().clip(context).getType() == HitResult.Type.MISS;
    }

    private static boolean shouldForceMobReappearance(Entity entity, LocalPlayer player) {
        if (PauCClient.getQualityLevel() > 1) {
            return false;
        }

        if (!(entity instanceof LivingEntity) || entity instanceof Player) {
            return false;
        }

        double viewDistance = DistanceBudgetController.getViewDistanceBlocks();
        if (entity.distanceToSqr(player) > viewDistance * viewDistance) {
            return false;
        }

        return isInsideVisibilityConeCached(entity);
    }

    private static double distanceToSqr(double x1, double y1, double z1, double x2, double y2, double z2) {
        double dx = x2 - x1;
        double dy = y2 - y1;
        double dz = z2 - z1;
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean isInsideVisibilityConeCached(Entity entity) {
        if (entity == null) {
            return true;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer == null ? null : minecraft.gameRenderer.getMainCamera();
        if (!prepareCacheContext(minecraft) || camera == null) {
            return isInsideVisibilityCone(entity);
        }

        long cameraSignature = buildCameraSignature(camera);
        int entityId = entity.getId();
        ConeCacheEntry entry = ENTITY_CONE_CACHE.get(entityId);
        if (entry != null && entry.tick == cacheTick && entry.entityTickCount == entity.tickCount && entry.cameraSignature == cameraSignature) {
            return applyVisibilityHysteresis(entityId, entry.insideVisibilityCone);
        }

        boolean insideVisibilityCone = isInsideVisibilityCone(entity);
        ENTITY_CONE_CACHE.put(entityId, new ConeCacheEntry(cacheTick, entity.tickCount, cameraSignature, insideVisibilityCone));
        return applyVisibilityHysteresis(entityId, insideVisibilityCone);
    }

    private static boolean hasLineOfSightCached(Entity entity, LocalPlayer player) {
        if (entity == null || player == null) {
            return true;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!prepareCacheContext(minecraft)) {
            return hasLineOfSight(entity, player);
        }

        long raySignature = buildLosSignature(entity, player);
        int entityId = entity.getId();
        LosCacheEntry entry = ENTITY_LOS_CACHE.get(entityId);
        if (entry != null && entry.tick == cacheTick && entry.entityTickCount == entity.tickCount && entry.raySignature == raySignature) {
            return entry.hasLineOfSight;
        }

        boolean hasLineOfSight = hasLineOfSight(entity, player);
        ENTITY_LOS_CACHE.put(entityId, new LosCacheEntry(cacheTick, entity.tickCount, raySignature, hasLineOfSight));
        return hasLineOfSight;
    }

    private static boolean prepareCacheContext(Minecraft minecraft) {
        if (!PauCClient.isBudgetActive() || minecraft == null || minecraft.level == null || minecraft.player == null) {
            clearVisibilityCaches();
            return false;
        }

        int levelIdentity = System.identityHashCode(minecraft.level);
        long tick = minecraft.level.getGameTime();
        if (cacheLevelIdentity != levelIdentity) {
            clearVisibilityCaches();
            cacheLevelIdentity = levelIdentity;
        }

        if (cacheTick != tick) {
            cacheTick = tick;
            cachePruneTicker++;
            if (cachePruneTicker >= CACHE_PRUNE_INTERVAL_TICKS
                    || ENTITY_CONE_CACHE.size() > CACHE_MAX_ENTRIES
                    || ENTITY_LOS_CACHE.size() > CACHE_MAX_ENTRIES) {
                pruneVisibilityCaches();
                cachePruneTicker = 0;
            }
        }

        return true;
    }

    private static void pruneVisibilityCaches() {
        long oldestValidTick = cacheTick - CACHE_TTL_TICKS;
        if (oldestValidTick <= Long.MIN_VALUE + 16) {
            return;
        }

        Iterator<Map.Entry<Integer, ConeCacheEntry>> coneIterator = ENTITY_CONE_CACHE.entrySet().iterator();
        while (coneIterator.hasNext()) {
            ConeCacheEntry entry = coneIterator.next().getValue();
            if (entry.tick < oldestValidTick) {
                coneIterator.remove();
            }
        }

        Iterator<Map.Entry<Integer, LosCacheEntry>> losIterator = ENTITY_LOS_CACHE.entrySet().iterator();
        while (losIterator.hasNext()) {
            LosCacheEntry entry = losIterator.next().getValue();
            if (entry.tick < oldestValidTick) {
                losIterator.remove();
            }
        }

        Iterator<Map.Entry<Integer, VisibilityHysteresisEntry>> visibilityIterator = ENTITY_VISIBILITY_HYSTERESIS.entrySet().iterator();
        while (visibilityIterator.hasNext()) {
            VisibilityHysteresisEntry entry = visibilityIterator.next().getValue();
            if (entry.lastSeenTick < oldestValidTick) {
                visibilityIterator.remove();
            }
        }
    }

    private static void clearVisibilityCaches() {
        ENTITY_CONE_CACHE.clear();
        ENTITY_LOS_CACHE.clear();
        ENTITY_VISIBILITY_HYSTERESIS.clear();
        cacheLevelIdentity = 0;
        cacheTick = Long.MIN_VALUE;
        cachePruneTicker = 0;
    }

    private static boolean applyVisibilityHysteresis(int entityId, boolean rawVisible) {
        VisibilityHysteresisEntry entry = ENTITY_VISIBILITY_HYSTERESIS.get(entityId);
        if (entry == null) {
            ENTITY_VISIBILITY_HYSTERESIS.put(entityId, new VisibilityHysteresisEntry(cacheTick, rawVisible));
            return rawVisible;
        }

        entry.lastSeenTick = cacheTick;
        if (entry.lastProcessedTick == cacheTick && entry.lastRawVisible == rawVisible) {
            return entry.stableVisible;
        }

        entry.lastProcessedTick = cacheTick;
        entry.lastRawVisible = rawVisible;
        if (rawVisible == entry.stableVisible) {
            entry.pendingVisible = rawVisible;
            entry.pendingTicks = 0;
            return entry.stableVisible;
        }

        if (rawVisible != entry.pendingVisible) {
            entry.pendingVisible = rawVisible;
            entry.pendingTicks = 1;
        } else {
            entry.pendingTicks = Math.min(16, entry.pendingTicks + 1);
        }

        int requiredTicks = rawVisible ? VISIBILITY_ENTER_HYSTERESIS_TICKS : VISIBILITY_EXIT_HYSTERESIS_TICKS;
        if (entry.pendingTicks >= requiredTicks) {
            entry.stableVisible = rawVisible;
            entry.pendingTicks = 0;
        }

        return entry.stableVisible;
    }

    private static long buildCameraSignature(Camera camera) {
        Vec3 position = camera.getPosition();
        org.joml.Vector3f lookVector = camera.getLookVector();

        long signature = 0x9E3779B97F4A7C15L;
        signature = mixSignature(signature, quantizedBits(position.x, 16.0D));
        signature = mixSignature(signature, quantizedBits(position.y, 16.0D));
        signature = mixSignature(signature, quantizedBits(position.z, 16.0D));
        signature = mixSignature(signature, quantizedBits(lookVector.x(), 256.0D));
        signature = mixSignature(signature, quantizedBits(lookVector.y(), 256.0D));
        signature = mixSignature(signature, quantizedBits(lookVector.z(), 256.0D));
        return signature;
    }

    private static long buildLosSignature(Entity entity, LocalPlayer player) {
        AABB boundingBox = entity.getBoundingBox();
        Vec3 playerEye = player.getEyePosition();
        double centerX = (boundingBox.minX + boundingBox.maxX) * 0.5D;
        double centerY = (boundingBox.minY + boundingBox.maxY) * 0.5D;
        double centerZ = (boundingBox.minZ + boundingBox.maxZ) * 0.5D;

        long signature = 0xC2B2AE3D27D4EB4FL;
        signature = mixSignature(signature, quantizedBits(centerX, 32.0D));
        signature = mixSignature(signature, quantizedBits(centerY, 32.0D));
        signature = mixSignature(signature, quantizedBits(centerZ, 32.0D));
        signature = mixSignature(signature, quantizedBits(playerEye.x, 32.0D));
        signature = mixSignature(signature, quantizedBits(playerEye.y, 32.0D));
        signature = mixSignature(signature, quantizedBits(playerEye.z, 32.0D));
        return signature;
    }

    private static long quantizedBits(double value, double factor) {
        double quantized = Math.rint(value * factor) / factor;
        return Double.doubleToLongBits(quantized);
    }

    private static long mixSignature(long hash, long value) {
        hash ^= value + 0x9E3779B97F4A7C15L + (hash << 6) + (hash >>> 2);
        return hash;
    }

    private record ConeCacheEntry(long tick, int entityTickCount, long cameraSignature, boolean insideVisibilityCone) {
    }

    private record LosCacheEntry(long tick, int entityTickCount, long raySignature, boolean hasLineOfSight) {
    }

    private static final class VisibilityHysteresisEntry {
        private long lastSeenTick;
        private long lastProcessedTick;
        private boolean lastRawVisible;
        private boolean stableVisible;
        private boolean pendingVisible;
        private int pendingTicks;

        private VisibilityHysteresisEntry(long tick, boolean visible) {
            this.lastSeenTick = tick;
            this.lastProcessedTick = tick;
            this.lastRawVisible = visible;
            this.stableVisible = visible;
            this.pendingVisible = visible;
            this.pendingTicks = 0;
        }
    }
}

