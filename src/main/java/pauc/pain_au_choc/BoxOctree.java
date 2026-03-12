package pauc.pain_au_choc;

import java.util.ArrayList;
import java.util.List;

public final class BoxOctree<T> {
    private final int splitThreshold;
    private final int maxDepth;
    private Node<T> root;

    public BoxOctree(int splitThreshold, int maxDepth) {
        this.splitThreshold = Math.max(2, splitThreshold);
        this.maxDepth = Math.max(1, maxDepth);
    }

    public void reset(double centerX, double centerY, double centerZ, double halfSize) {
        this.root = new Node<>(centerX, centerY, centerZ, Math.max(1.0D, halfSize), 0);
    }

    public void clear() {
        this.root = null;
    }

    public void insert(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, T value) {
        if (this.root == null || value == null) {
            return;
        }

        this.root.insert(new Entry<>(minX, minY, minZ, maxX, maxY, maxZ, value), this.splitThreshold, this.maxDepth);
    }

    public void query(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<T> output) {
        if (this.root == null || output == null) {
            return;
        }

        this.root.query(minX, minY, minZ, maxX, maxY, maxZ, output);
    }

    private static final class Node<T> {
        private final double centerX;
        private final double centerY;
        private final double centerZ;
        private final double halfSize;
        private final int depth;
        private final List<Entry<T>> entries = new ArrayList<>();
        private Node<T>[] children;

        private Node(double centerX, double centerY, double centerZ, double halfSize, int depth) {
            this.centerX = centerX;
            this.centerY = centerY;
            this.centerZ = centerZ;
            this.halfSize = halfSize;
            this.depth = depth;
        }

        private void insert(Entry<T> entry, int splitThreshold, int maxDepth) {
            if (!intersects(entry.minX, entry.minY, entry.minZ, entry.maxX, entry.maxY, entry.maxZ)) {
                return;
            }

            if (this.children != null) {
                int childIndex = getContainingChildIndex(entry);
                if (childIndex >= 0) {
                    this.children[childIndex].insert(entry, splitThreshold, maxDepth);
                    return;
                }
            }

            this.entries.add(entry);
            if (this.children == null && this.entries.size() > splitThreshold && this.depth < maxDepth) {
                subdivide(splitThreshold, maxDepth);
            }
        }

        private void query(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, List<T> output) {
            if (!intersects(minX, minY, minZ, maxX, maxY, maxZ)) {
                return;
            }

            for (Entry<T> entry : this.entries) {
                if (entry.intersects(minX, minY, minZ, maxX, maxY, maxZ)) {
                    output.add(entry.value);
                }
            }

            if (this.children == null) {
                return;
            }

            for (Node<T> child : this.children) {
                child.query(minX, minY, minZ, maxX, maxY, maxZ, output);
            }
        }

        private void subdivide(int splitThreshold, int maxDepth) {
            this.children = createChildren();
            int index = 0;
            while (index < this.entries.size()) {
                Entry<T> entry = this.entries.get(index);
                int childIndex = getContainingChildIndex(entry);
                if (childIndex < 0) {
                    index++;
                    continue;
                }

                this.entries.remove(index);
                this.children[childIndex].insert(entry, splitThreshold, maxDepth);
            }
        }

        @SuppressWarnings("unchecked")
        private Node<T>[] createChildren() {
            Node<T>[] childArray = (Node<T>[]) new Node[8];
            double childHalfSize = this.halfSize * 0.5D;
            int childDepth = this.depth + 1;
            int arrayIndex = 0;

            for (int x = 0; x < 2; x++) {
                double childCenterX = this.centerX + (x == 0 ? -childHalfSize : childHalfSize);
                for (int y = 0; y < 2; y++) {
                    double childCenterY = this.centerY + (y == 0 ? -childHalfSize : childHalfSize);
                    for (int z = 0; z < 2; z++) {
                        double childCenterZ = this.centerZ + (z == 0 ? -childHalfSize : childHalfSize);
                        childArray[arrayIndex++] = new Node<>(childCenterX, childCenterY, childCenterZ, childHalfSize, childDepth);
                    }
                }
            }

            return childArray;
        }

        private int getContainingChildIndex(Entry<T> entry) {
            if (this.children == null) {
                return -1;
            }

            int xSide = getSide(entry.minX, entry.maxX, this.centerX);
            int ySide = getSide(entry.minY, entry.maxY, this.centerY);
            int zSide = getSide(entry.minZ, entry.maxZ, this.centerZ);
            if (xSide < 0 || ySide < 0 || zSide < 0) {
                return -1;
            }

            return xSide * 4 + ySide * 2 + zSide;
        }

        private int getSide(double min, double max, double axisCenter) {
            if (max <= axisCenter) {
                return 0;
            }
            if (min >= axisCenter) {
                return 1;
            }
            return -1;
        }

        private boolean intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            return minX <= this.centerX + this.halfSize
                    && maxX >= this.centerX - this.halfSize
                    && minY <= this.centerY + this.halfSize
                    && maxY >= this.centerY - this.halfSize
                    && minZ <= this.centerZ + this.halfSize
                    && maxZ >= this.centerZ - this.halfSize;
        }
    }

    private record Entry<T>(
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            T value
    ) {
        private boolean intersects(double queryMinX, double queryMinY, double queryMinZ, double queryMaxX, double queryMaxY, double queryMaxZ) {
            return this.minX <= queryMaxX
                    && this.maxX >= queryMinX
                    && this.minY <= queryMaxY
                    && this.maxY >= queryMinY
                    && this.minZ <= queryMaxZ
                    && this.maxZ >= queryMinZ;
        }
    }
}

