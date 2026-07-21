package fr.hoyatla.pauc.lodengine;

import com.mojang.logging.LogUtils;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL32;
import org.slf4j.Logger;

import java.lang.ref.PhantomReference;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.nio.ByteBuffer;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.StampedLock;

/**
 * GPU buffer lifecycle manager with phantom-reference leak detection, StampedLock render-thread
 * safety, and expansion hysteresis. Ported from Distant Horizons {@code GLBuffer.java} (LGPL v3).
 *
 * <p>Tracks every GL buffer created through {@link #createBuffer()}. When a {@link TrackedBuffer}
 * is GC'd without being {@link TrackedBuffer#close() closed}, a background daemon thread detects the
 * phantom reference and queues the GPU buffer for deletion on the render thread.</p>
 */
public final class PauCGpuBufferTracker {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final PauCGpuBufferTracker INSTANCE = new PauCGpuBufferTracker();

    public static PauCGpuBufferTracker getInstance() { return INSTANCE; }

    /** Buffer growth multiplier when the current buffer is too small. */
    public static final double EXPANSION_MULTIPLIER = 1.3;
    /** Shrink threshold: only shrink when data < currentCapacity / (EXPANSION^2) = 1.69x. */
    public static final double SHRINK_TRIGGER = EXPANSION_MULTIPLIER * EXPANSION_MULTIPLIER;
    /** macOS chunk size for dodging the OpenGL→Metal SIGBUS. */
    public static final int MAC_CHUNK_BYTES = 256 * 1024;

    private static final int PHANTOM_CHECK_MS = 5_000;
    private static final AtomicInteger BUFFER_COUNT = new AtomicInteger(0);

    private static final ConcurrentHashMap<PhantomReference<TrackedBuffer>, Integer> PHANTOM_TO_ID = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Integer, PhantomReference<TrackedBuffer>> ID_TO_PHANTOM = new ConcurrentHashMap<>();
    private static final ReferenceQueue<TrackedBuffer> QUEUE = new ReferenceQueue<>();

    private static final Thread CLEANUP_THREAD;

    static {
        CLEANUP_THREAD = new Thread(PauCGpuBufferTracker::cleanupLoop, "PauC-GPU-Buf-Cleanup");
        CLEANUP_THREAD.setDaemon(true);
        CLEANUP_THREAD.start();
    }

    private PauCGpuBufferTracker() {}

    public int activeBufferCount() { return BUFFER_COUNT.get(); }

    // ---- GPU vendor auto-detection ----

    public enum UploadMethod { BUFFER_STORAGE, SUB_DATA, DATA }

    public UploadMethod detectPreferredUploadMethod() {
        boolean bufferStorageSupported = GL.getCapabilities().glBufferStorage != 0L;
        boolean macOS = System.getProperty("os.name").toLowerCase().contains("mac");
        if (macOS) return UploadMethod.DATA;
        String vendor = GL32.glGetString(GL32.GL_VENDOR);
        if (vendor != null) vendor = vendor.toUpperCase();
        if (vendor != null && (vendor.contains("NVIDIA") || vendor.contains("GEFORCE"))) {
            return bufferStorageSupported ? UploadMethod.BUFFER_STORAGE : UploadMethod.SUB_DATA;
        }
        return bufferStorageSupported ? UploadMethod.BUFFER_STORAGE : UploadMethod.DATA;
    }

    public boolean isVertexAttributeBufferBindingSupported() {
        return GL.getCapabilities().glBindVertexBuffer != 0L;
    }

    // ---- TrackedBuffer ----

    public static final class TrackedBuffer implements AutoCloseable {
        private final StampedLock lock = new StampedLock();
        private volatile int id;
        private int capacity;
        private final int bindingTarget;

        TrackedBuffer(int bindingTarget) {
            this.bindingTarget = bindingTarget;
            long write = lock.writeLock();
            try {
                this.id = GL32.glGenBuffers();
                this.capacity = 0;
                BUFFER_COUNT.incrementAndGet();
                PhantomReference<TrackedBuffer> phantom = new PhantomReference<>(this, QUEUE);
                PHANTOM_TO_ID.put(phantom, this.id);
                ID_TO_PHANTOM.put(this.id, phantom);
            } finally {
                lock.unlockWrite(write);
            }
        }

        public int getId() { return id; }
        public int getCapacity() { return capacity; }
        public int getBindingTarget() { return bindingTarget; }
        public StampedLock getLock() { return lock; }

        public void bind() { GL32.glBindBuffer(bindingTarget, id); }
        public void unbind() { GL32.glBindBuffer(bindingTarget, 0); }

        /**
         * Upload data to this buffer with hysteresis-based resizing.
         * Only reallocates when the buffer needs to grow by {@link #EXPANSION_MULTIPLIER} or shrink below
         * {@link #SHRINK_TRIGGER} of current capacity.
         */
        public void upload(ByteBuffer data, int usage) {
            int dataSize = data.limit() - data.position();
            if (dataSize == 0) return;

            long write = lock.writeLock();
            try {
                if (id == 0) {
                    id = GL32.glGenBuffers();
                    BUFFER_COUNT.incrementAndGet();
                }
                bind();
                if (dataSize > capacity || capacity > (int)(dataSize * SHRINK_TRIGGER)) {
                    int newSize = (int)(dataSize * EXPANSION_MULTIPLIER);
                    GL32.glBufferData(bindingTarget, (long) newSize, usage);
                    capacity = newSize;
                }
                GL32.glBufferSubData(bindingTarget, 0, data);
            } finally {
                lock.unlockWrite(write);
            }
        }

        public void uploadFull(ByteBuffer data, int usage) {
            int dataSize = data.limit() - data.position();
            if (dataSize == 0) return;
            long write = lock.writeLock();
            try {
                if (id == 0) {
                    id = GL32.glGenBuffers();
                    BUFFER_COUNT.incrementAndGet();
                }
                bind();
                GL32.glBufferData(bindingTarget, data, usage);
                capacity = dataSize;
            } finally {
                lock.unlockWrite(write);
            }
        }

        @Override
        public void close() {
            long write = lock.writeLock();
            try {
                if (id == 0) return;
                int oldId = id;
                id = 0;
                capacity = 0;
                tryRemoveFromPhantom(oldId);
                GL32.glDeleteBuffers(oldId);
                BUFFER_COUNT.decrementAndGet();
            } finally {
                lock.unlockWrite(write);
            }
        }
    }

    public TrackedBuffer createBuffer(int bindingTarget) {
        return new TrackedBuffer(bindingTarget);
    }

    public TrackedBuffer createArrayBuffer() {
        return createBuffer(GL32.GL_ARRAY_BUFFER);
    }

    public TrackedBuffer createElementBuffer() {
        return createBuffer(GL32.GL_ELEMENT_ARRAY_BUFFER);
    }

    private static void tryRemoveFromPhantom(int bufferId) {
        PhantomReference<TrackedBuffer> phantom = ID_TO_PHANTOM.remove(bufferId);
        if (phantom != null) {
            phantom.clear();
            PHANTOM_TO_ID.remove(phantom);
        }
    }

    private static void cleanupLoop() {
        while (true) {
            try {
                Thread.sleep(PHANTOM_CHECK_MS);
                Reference<? extends TrackedBuffer> ref;
                int count = 0;
                while ((ref = QUEUE.poll()) != null) {
                    @SuppressWarnings("unchecked")
                    PhantomReference<TrackedBuffer> phantom = (PhantomReference<TrackedBuffer>) ref;
                    Integer bufferId = PHANTOM_TO_ID.remove(phantom);
                    if (bufferId != null) {
                        ID_TO_PHANTOM.remove(bufferId);
                        final int id = bufferId;
                        GL32.glDeleteBuffers(id);
                        BUFFER_COUNT.decrementAndGet();
                        count++;
                    }
                }
                if (count > 0) {
                    LOGGER.warn("PauCGpuBufferTracker: phantom-recovered {} leaked GPU buffer(s), remaining active: {}", count, BUFFER_COUNT.get());
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                LOGGER.error("PauCGpuBufferTracker cleanup error", e);
            }
        }
    }
}
