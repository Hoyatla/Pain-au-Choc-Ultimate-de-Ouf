package fr.hoyatla.pauc.lodstore;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.InflaterInputStream;

public final class PauCLodMeshStore {
	private static final int MAGIC = 0x50415543;
	private static final int VERSION = 1;
	private final Path root;
	private long reads;
	private long readHits;
	private long writes;
	private long writeBytes;

	private PauCLodMeshStore(Path root) {
		this.root = root;
	}

	public static PauCLodMeshStore open(Path root) {
		return new PauCLodMeshStore(root);
	}

	public Optional<byte[]> read(PauCLodMeshKey key) {
		if (key == null || !key.valid()) {
			return Optional.empty();
		}
		reads++;
		Path path = pathFor(key);
		if (!Files.isRegularFile(path)) {
			return Optional.empty();
		}
		try {
			byte[] fileBytes = Files.readAllBytes(path);
			if (fileBytes.length < 12 || readInt(fileBytes, 0) != MAGIC || readInt(fileBytes, 4) != VERSION) {
				return Optional.empty();
			}
			int compressedLength = readInt(fileBytes, 8);
			if (compressedLength <= 0 || compressedLength > fileBytes.length - 12) {
				return Optional.empty();
			}
			try (InflaterInputStream inflater = new InflaterInputStream(new ByteArrayInputStream(fileBytes, 12, compressedLength));
				 ByteArrayOutputStream output = new ByteArrayOutputStream(Math.max(8192, compressedLength * 2))) {
				inflater.transferTo(output);
				readHits++;
				return Optional.of(output.toByteArray());
			}
		} catch (IOException ignored) {
			return Optional.empty();
		}
	}

	public boolean write(PauCLodMeshKey key, byte[] renderReadyMesh) {
		if (key == null || !key.valid() || renderReadyMesh == null || renderReadyMesh.length == 0) {
			return false;
		}
		Path path = pathFor(key);
		Path tempPath = path.resolveSibling(path.getFileName() + ".tmp");
		try {
			Files.createDirectories(path.getParent());
			byte[] compressed = compress(renderReadyMesh);
			ByteArrayOutputStream output = new ByteArrayOutputStream(12 + compressed.length);
			writeInt(output, MAGIC);
			writeInt(output, VERSION);
			writeInt(output, compressed.length);
			output.writeBytes(compressed);
			Files.write(tempPath, output.toByteArray());
			try {
				Files.move(tempPath, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (IOException atomicMoveFailed) {
				Files.move(tempPath, path, StandardCopyOption.REPLACE_EXISTING);
			}
			writes++;
			writeBytes += renderReadyMesh.length;
			return true;
		} catch (IOException ignored) {
			return false;
		}
	}

	public String describeState() {
		return "meshStore[root="
			+ root
			+ ", reads="
			+ reads
			+ ", hits="
			+ readHits
			+ ", writes="
			+ writes
			+ ", writeKiB="
			+ (writeBytes / 1024L)
			+ "]";
	}

	private Path pathFor(PauCLodMeshKey key) {
		int bucket = Math.floorMod(key.hashCode(), 256);
		return root.resolve(String.format("%02x", bucket)).resolve(key.fileName());
	}

	private static byte[] compress(byte[] input) throws IOException {
		ByteArrayOutputStream output = new ByteArrayOutputStream(input.length);
		try (DeflaterOutputStream deflater = new DeflaterOutputStream(output)) {
			deflater.write(input);
		}
		return output.toByteArray();
	}

	private static int readInt(byte[] bytes, int offset) {
		return ((bytes[offset] & 0xFF) << 24)
			| ((bytes[offset + 1] & 0xFF) << 16)
			| ((bytes[offset + 2] & 0xFF) << 8)
			| (bytes[offset + 3] & 0xFF);
	}

	private static void writeInt(ByteArrayOutputStream output, int value) {
		output.write((value >>> 24) & 0xFF);
		output.write((value >>> 16) & 0xFF);
		output.write((value >>> 8) & 0xFF);
		output.write(value & 0xFF);
	}
}
