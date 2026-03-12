package pauc.pain_au_choc;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;

public final class EntitySpatialIndex {
    private static final int REBUILD_INTERVAL_TICKS = 2;
    private static final int WORK_PHASE = 1;
    private static final int WORKING_SET_SLACK = 64;
    private static final int RESET_SHRINK_THRESHOLD = WORKING_SET_SLACK * 4;
    private static final double ROOT_PADDING_BLOCKS = 16.0D;

    private static final BoxOctree<Entity> ENTITY_OCTREE = new BoxOctree<>(8, 6);
    private static ArrayList<Entity> queryBuffer = new ArrayList<>();
    private static HashSet<Integer> renderCandidateIds = new HashSet<>();

    private static int rebuildTicker;
    private static boolean ready;

    private EntitySpatialIndex() {
    }

    public static void tick() {
        if (!PauCClient.isBudgetActive()) {
            reset();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            reset();
            return;
        }

        rebuildTicker++;
        int adaptiveInterval = LatencyController.getAdaptiveInterval(REBUILD_INTERVAL_TICKS);
        if (ready && rebuildTicker < adaptiveInterval) {
            return;
        }

        if (!LatencyController.shouldRunBackgroundWork(WORK_PHASE)) {
            return;
        }

        rebuildTicker = 0;
        rebuildIndex(minecraft, minecraft.player);
    }

    public static void reset() {
        rebuildTicker = 0;
        ready = false;
        if (queryBuffer.size() > RESET_SHRINK_THRESHOLD) {
            queryBuffer = new ArrayList<>(WORKING_SET_SLACK);
        } else {
            queryBuffer.clear();
        }

        if (renderCandidateIds.size() > RESET_SHRINK_THRESHOLD) {
            renderCandidateIds = new HashSet<>(WORKING_SET_SLACK);
        } else {
            renderCandidateIds.clear();
        }
        ENTITY_OCTREE.clear();
    }

    public static boolean isInRenderBroadPhase(Entity entity) {
        if (!ready || !PauCClient.isBudgetActive() || entity == null) {
            return true;
        }

        if (entity.tickCount <= REBUILD_INTERVAL_TICKS) {
            return true;
        }

        return renderCandidateIds.contains(entity.getId());
    }

    private static void rebuildIndex(Minecraft minecraft, LocalPlayer player) {
        double viewDistance = DistanceBudgetController.getViewDistanceBlocks();
        double rootHalfSize = Math.max(32.0D, viewDistance + ROOT_PADDING_BLOCKS);
        double playerX = player.getX();
        double playerY = player.getY();
        double playerZ = player.getZ();

        ENTITY_OCTREE.reset(playerX, playerY, playerZ, rootHalfSize);
        int expectedCandidates = Math.max(WORKING_SET_SLACK, renderCandidateIds.size() + WORKING_SET_SLACK);
        if (queryBuffer.size() > expectedCandidates * 2) {
            queryBuffer = new ArrayList<>(expectedCandidates);
        } else {
            queryBuffer.clear();
            queryBuffer.ensureCapacity(expectedCandidates);
        }

        if (renderCandidateIds.size() > expectedCandidates * 2) {
            renderCandidateIds = new HashSet<>(expectedCandidates);
        } else {
            renderCandidateIds.clear();
        }

        for (Entity entity : minecraft.level.entitiesForRendering()) {
            if (entity == null || entity.isRemoved()) {
                continue;
            }

            AABB bounds = entity.getBoundingBox();
            ENTITY_OCTREE.insert(bounds.minX, bounds.minY, bounds.minZ, bounds.maxX, bounds.maxY, bounds.maxZ, entity);
        }

        double candidateRadius = Math.max(
                DistanceBudgetController.getFullDetailDistanceBlocks(),
                Math.max(
                        DistanceBudgetController.getAggressiveDetailDistanceBlocks(),
                        viewDistance * PauCClient.getActiveProfile().entityDistanceRatio()
                )
        ) + ROOT_PADDING_BLOCKS;

        ENTITY_OCTREE.query(
                playerX - candidateRadius,
                playerY - candidateRadius,
                playerZ - candidateRadius,
                playerX + candidateRadius,
                playerY + candidateRadius,
                playerZ + candidateRadius,
                queryBuffer
        );

        for (Entity entity : queryBuffer) {
            renderCandidateIds.add(entity.getId());
        }

        ready = true;
    }
}

