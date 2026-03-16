package pauc.pain_au_choc.render.occlusion;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.Level;
import pauc.pain_au_choc.render.chunk.PauCRenderSection;

import java.util.ArrayDeque;

/**
 * Graph-based occlusion culler using BFS traversal from the camera section.
 * Uses direction-to-direction visibility encoding to determine which adjacent
 * sections can be seen through each traversed section.
 *
 * Adapted from Embeddium's OcclusionCuller with PAUC budget integration.
 */
public class PauCOcclusionCuller {

    /** Callback interface for sections found during traversal. */
    public interface Visitor {
        /**
         * Called for each section reached by the BFS traversal.
         * @param section The section being visited
         * @param visible Whether the section passed frustum culling
         */
        void visit(PauCRenderSection section, boolean visible);
    }

    private final Long2ReferenceMap<PauCRenderSection> sections;
    private final Level world;

    /** Double-buffered BFS queue (read from one, write to the other, then swap). */
    private ArrayDeque<PauCRenderSection> queueRead = new ArrayDeque<>(256);
    private ArrayDeque<PauCRenderSection> queueWrite = new ArrayDeque<>(256);

    /** Whether the camera is currently in an unloaded section. */
    private boolean cameraInUnloadedSection = false;

    public PauCOcclusionCuller(Long2ReferenceMap<PauCRenderSection> sections, Level world) {
        this.sections = sections;
        this.world = world;
    }

    public boolean isCameraInUnloadedSection() {
        return this.cameraInUnloadedSection;
    }

    /**
     * Main visibility determination algorithm.
     * Performs a BFS from the camera section, using the visibility graph to determine
     * which sections can potentially be seen.
     *
     * @param visitor       Callback for each discovered section
     * @param cameraX       Camera position X (block coordinates)
     * @param cameraY       Camera position Y
     * @param cameraZ       Camera position Z
     * @param frustum       View frustum for frustum culling
     * @param searchDistance Maximum search distance in blocks
     * @param useOcclusion  Whether to use occlusion culling (false = frustum only)
     * @param frame         Current frame number (for duplicate detection)
     */
    public void findVisible(Visitor visitor, double cameraX, double cameraY, double cameraZ,
                             Frustum frustum, float searchDistance, boolean useOcclusion, int frame) {

        // Find the section containing the camera
        int camSectionX = (int) Math.floor(cameraX) >> 4;
        int camSectionY = (int) Math.floor(cameraY) >> 4;
        int camSectionZ = (int) Math.floor(cameraZ) >> 4;
        long camKey = sectionKey(camSectionX, camSectionY, camSectionZ);

        PauCRenderSection origin = this.sections.get(camKey);
        this.cameraInUnloadedSection = (origin == null);

        if (origin == null) {
            // Camera is in an unloaded section - search nearby for a valid start
            origin = findNearestSection(camSectionX, camSectionY, camSectionZ, 2);
            if (origin == null) {
                return; // No sections loaded at all
            }
        }

        float maxDistSq = searchDistance * searchDistance;

        // Initialize BFS from camera section
        this.queueRead.clear();
        this.queueWrite.clear();

        origin.setIncomingDirections(GraphDirection.ALL);
        origin.setLastVisibleFrame(frame);
        this.queueRead.add(origin);

        // Visit origin
        boolean originVisible = isSectionInFrustum(origin, frustum, maxDistSq,
                (float) cameraX, (float) cameraY, (float) cameraZ);
        visitor.visit(origin, originVisible);

        // BFS traversal
        while (!this.queueRead.isEmpty()) {
            PauCRenderSection current = this.queueRead.poll();

            // Determine outgoing directions from this section
            int outgoing;
            if (useOcclusion && current.isBuilt()) {
                outgoing = VisibilityEncoding.getConnections(
                        current.getVisibilityData(), current.getIncomingDirections());
            } else {
                outgoing = GraphDirection.ALL; // No occlusion data -> all directions
            }

            // Only traverse directions that have adjacent sections
            outgoing &= current.getAdjacentMask();

            // Traverse each outgoing direction
            for (int dir = 0; dir < GraphDirection.COUNT; dir++) {
                if (!GraphDirection.contains(outgoing, dir)) {
                    continue;
                }

                PauCRenderSection neighbor = current.getAdjacent(dir);
                if (neighbor == null || neighbor.isDisposed()) {
                    continue;
                }

                // Skip if already visited this frame
                if (neighbor.getLastVisibleFrame() == frame) {
                    // But add our incoming direction info
                    neighbor.addIncomingDirections(GraphDirection.flag(GraphDirection.opposite(dir)));
                    continue;
                }

                // Distance check
                float distSq = neighbor.getSquaredDistance(
                        (float) cameraX, (float) cameraY, (float) cameraZ);
                if (distSq > maxDistSq) {
                    continue;
                }

                // Frustum check
                boolean visible = isSectionInFrustum(neighbor, frustum, maxDistSq,
                        (float) cameraX, (float) cameraY, (float) cameraZ);

                // Mark visited
                neighbor.setIncomingDirections(GraphDirection.flag(GraphDirection.opposite(dir)));
                neighbor.setLastVisibleFrame(frame);

                visitor.visit(neighbor, visible);

                // Enqueue for further traversal
                this.queueWrite.add(neighbor);
            }

            // Swap queues when read is empty (breadth-first layer complete)
            if (this.queueRead.isEmpty() && !this.queueWrite.isEmpty()) {
                ArrayDeque<PauCRenderSection> tmp = this.queueRead;
                this.queueRead = this.queueWrite;
                this.queueWrite = tmp;
            }
        }
    }

    /**
     * Check if a section is within the view frustum and distance limit.
     */
    private static boolean isSectionInFrustum(PauCRenderSection section, Frustum frustum,
                                                float maxDistSq, float camX, float camY, float camZ) {
        float distSq = section.getSquaredDistance(camX, camY, camZ);
        if (distSq > maxDistSq) {
            return false;
        }

        // Check against frustum AABB
        int ox = section.getOriginX();
        int oy = section.getOriginY();
        int oz = section.getOriginZ();
        return frustum.isVisible(
                new net.minecraft.world.phys.AABB(ox, oy, oz, ox + 16, oy + 16, oz + 16));
    }

    /**
     * Search for the nearest loaded section within a given radius.
     */
    private PauCRenderSection findNearestSection(int cx, int cy, int cz, int radius) {
        for (int r = 0; r <= radius; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.abs(dx) != r && Math.abs(dy) != r && Math.abs(dz) != r) continue;
                        long key = sectionKey(cx + dx, cy + dy, cz + dz);
                        PauCRenderSection sec = this.sections.get(key);
                        if (sec != null && !sec.isDisposed()) return sec;
                    }
                }
            }
        }
        return null;
    }

    /** Create a position key for section lookup. */
    private static long sectionKey(int x, int y, int z) {
        return ((long) x & 0x3FFFFF) | (((long) y & 0xFFFFF) << 22) | (((long) z & 0x3FFFFF) << 42);
    }
}
