package fr.hoyatla.pauc.lodengine;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.Deflater;
import java.util.zip.InflaterInputStream;

/**
 * Disk persistence for {@link PauCSurfaceColumnStore} (LOD engine phase 2). Region files accumulate
 * the surfaces you have visited across sessions, so coverage builds up instead of resetting each join.
 *
 * <p>Threading contract: encode/decode of the store happen on the MAIN (render) thread — the only
 * thread that touches the store map. File reads/writes and (de)compression run on a single daemon I/O
 * thread. Loaded regions cross back to the main thread through a lock-free queue drained per tick.
 * Every file operation is guarded; on any failure persistence self-disables for the session and the
 * game keeps running (the LOD engine must never crash the client).</p>
 */
public final class PauCSurfaceStoreIO {
	private static final String FILE_SUFFIX = ".pauclod";

	private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "PauC-LodEngine-IO");
		thread.setDaemon(true);
		return thread;
	});
	private final ConcurrentLinkedQueue<LoadedRegion> loaded = new ConcurrentLinkedQueue<>();
	private final Long2LongOpenHashMap savedRevision = new Long2LongOpenHashMap();
	private volatile Path baseDir;
	private volatile boolean failed;
	// TRUE when no initial disk load is pending (never configured, finished, or failed). Starts true:
	// if configure() never runs (multiplayer, persistence off) nothing will ever stream in.
	private volatile boolean scanDone = true;

	public PauCSurfaceStoreIO() {
		savedRevision.defaultReturnValue(-1L);
	}

	private record LoadedRegion(long key, byte[] raw) {
	}

	/** Points the store at a world/dimension directory and kicks off the async load of its region files. */
	public void configure(Path dir) {
		baseDir = dir;
		savedRevision.clear();
		loaded.clear();
		scanDone = false;
		Path target = dir;
		writer.submit(() -> {
			try {
				loadAll(target);
			} finally {
				scanDone = true;
			}
		});
	}

	/**
	 * TRUE once the initial region-file load is fully SETTLED: the directory scan finished AND every
	 * loaded region has been drained into the store (or persistence is off/failed). Anyone treating an
	 * ABSENT region as a data hole MUST wait for this — during the load window, absence only means
	 * "not streamed in yet". 07-19: the hole-repair planner fired 5525 ghost regenerations against
	 * regions that were still on their way from disk, and the session-wins column merge then let those
	 * coarse fills overwrite the real data.
	 */
	public boolean initialLoadSettled() {
		return failed || (scanDone && loaded.isEmpty());
	}

	private void loadAll(Path dir) {
		try {
			if (!Files.isDirectory(dir)) {
				return;
			}
			try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir, "r.*" + FILE_SUFFIX)) {
				for (Path path : stream) {
					try {
						long key = keyFromFilename(path.getFileName().toString());
						byte[] raw = inflate(Files.readAllBytes(path));
						if (raw != null) {
							loaded.add(new LoadedRegion(key, raw));
						}
					} catch (Throwable ignored) {
						// Skip a single corrupt/foreign file without failing the whole load.
					}
				}
			}
		} catch (Throwable ignored) {
			// Directory listing failed; nothing loads this session.
		}
	}

	/** Main thread: inserts up to {@code budget} decoded regions into the store. @return count inserted. */
	public int drainLoaded(PauCSurfaceColumnStore store, int budget) {
		int inserted = 0;
		LoadedRegion region;
		while (inserted < budget && (region = loaded.poll()) != null) {
			// MERGE: session-written columns win, still-empty columns restore from disk.
			long revision = store.loadRegionRawMerge(region.key(), region.raw());
			if (revision >= 0) {
				savedRevision.put(region.key(), revision);
			}
			inserted++;
		}
		return inserted;
	}

	/** Main thread: queues up to {@code budget} changed regions for background writing. */
	public int flushDirty(PauCSurfaceColumnStore store, int budget) {
		if (baseDir == null || failed) {
			return 0;
		}
		int queued = 0;
		for (long key : store.regionKeys()) {
			if (queued >= budget) {
				break;
			}
			if (store.regionRevision(key) <= savedRevision.get(key)) {
				continue;
			}
			if (queueWrite(store, key)) {
				queued++;
			}
		}
		return queued;
	}

	/** Main thread: queues ALL changed regions (session/dimension end). */
	public void flushAll(PauCSurfaceColumnStore store) {
		if (baseDir == null || failed) {
			return;
		}
		for (long key : store.regionKeys()) {
			if (store.regionRevision(key) > savedRevision.get(key)) {
				queueWrite(store, key);
			}
		}
	}

	private boolean queueWrite(PauCSurfaceColumnStore store, long key) {
		byte[] encoded = store.encodeRegion(key);
		if (encoded == null) {
			return false;
		}
		savedRevision.put(key, store.regionRevision(key));
		Path path = baseDir.resolve(filename(key));
		writer.submit(() -> write(path, encoded));
		return true;
	}

	private void write(Path path, byte[] raw) {
		try {
			Files.createDirectories(path.getParent());
			Path tmp = path.resolveSibling(path.getFileName() + ".tmp");
			Files.write(tmp, deflate(raw),
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING);
		} catch (Throwable ignored) {
			failed = true;
		}
	}

	public boolean isFailed() {
		return failed;
	}

	private static byte[] deflate(byte[] raw) {
		Deflater deflater = new Deflater(Deflater.BEST_SPEED);
		deflater.setInput(raw);
		deflater.finish();
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(raw.length / 2);
		byte[] chunk = new byte[8192];
		while (!deflater.finished()) {
			int written = deflater.deflate(chunk);
			out.write(chunk, 0, written);
		}
		deflater.end();
		return out.toByteArray();
	}

	private static byte[] inflate(byte[] compressed) {
		try (InputStream in = new InflaterInputStream(new ByteArrayInputStream(compressed))) {
			return in.readAllBytes();
		} catch (Throwable ignored) {
			return null;
		}
	}

	private static String filename(long key) {
		return "r." + PauCSurfaceColumnStore.regionXFromKey(key) + "." + PauCSurfaceColumnStore.regionZFromKey(key) + FILE_SUFFIX;
	}

	private static long keyFromFilename(String name) {
		String body = name.substring(2, name.length() - FILE_SUFFIX.length());
		int separator = body.lastIndexOf('.');
		int regionX = Integer.parseInt(body.substring(0, separator));
		int regionZ = Integer.parseInt(body.substring(separator + 1));
		return ((long) regionX << 32) | (regionZ & 0xffffffffL);
	}
}
