package fr.hoyatla.pauc.platform.forge.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;
import org.lwjgl.system.MemoryStack;
import org.slf4j.Logger;

import java.nio.ByteBuffer;

/**
 * GPU-driven coarse LOD renderer foundation (BETA 0.4.1).
 *
 * <p>Renders the PauC coarse LOD cells (one flat plate per cell) as instanced unit quads issued with a single
 * {@code glMultiDrawElementsIndirect} call, replacing the per-frame {@code BufferBuilder} rebuild of
 * {@link PauCCudaLodProxyRenderer}. The unit quad geometry and index buffer are immutable; only the per-cell
 * instance buffer is updated, and only when the cell set actually changes. This is the foundation for a fully
 * GPU-driven path (GPU frustum culling feeding the indirect draw count, bindless multi-draw) added later.
 *
 * <p>Phase 1: self-contained, default-off, NOT wired into the live render path. Every GL operation is guarded;
 * on any failure the renderer marks itself unavailable so it can never be less stable than the existing path.
 */
public final class PauCGpuLodIndirectRenderer {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final float CELL_SIZE_BLOCKS = 16.0F;
	private static final int CELL_STRIDE_BYTES = 16; // vec3 position (12) + packed rgba (4), std430-tight
	private static final int DRAW_COMMAND_BYTES = 20; // 5 * uint: count, instanceCount, firstIndex, baseVertex, baseInstance
	private static final int CELLS_SSBO_BINDING = 0;

	private static final String VERTEX_SHADER =
		"#version 430 core\n"
			+ "layout(location = 0) in vec2 aLocal;\n"
			+ "struct Cell { float x; float y; float z; uint color; };\n"
			+ "layout(std430, binding = 0) readonly buffer Cells { Cell cells[]; };\n"
			+ "uniform mat4 uProjection;\n"
			+ "uniform mat4 uModelView;\n"
			+ "uniform vec3 uCameraOffset;\n"
			+ "uniform float uCellSize;\n"
			+ "out vec4 vColor;\n"
			+ "void main() {\n"
			+ "    Cell cell = cells[gl_InstanceID];\n"
			+ "    vec3 base = vec3(cell.x, cell.y, cell.z) + uCameraOffset;\n"
			+ "    vec3 world = vec3(base.x + aLocal.x * uCellSize, base.y, base.z + aLocal.y * uCellSize);\n"
			+ "    gl_Position = uProjection * uModelView * vec4(world, 1.0);\n"
			+ "    uint rgba = cell.color;\n"
			+ "    vColor = vec4(float((rgba >> 24) & 0xFFu) / 255.0,\n"
			+ "                  float((rgba >> 16) & 0xFFu) / 255.0,\n"
			+ "                  float((rgba >> 8) & 0xFFu) / 255.0,\n"
			+ "                  float(rgba & 0xFFu) / 255.0);\n"
			+ "}\n";

	private static final String FRAGMENT_SHADER =
		"#version 430 core\n"
			+ "in vec4 vColor;\n"
			+ "out vec4 fragColor;\n"
			+ "void main() { fragColor = vColor; }\n";

	private static boolean initialized;
	private static boolean unavailable;
	private static int program;
	private static int vao;
	private static int quadVbo;
	private static int quadIbo;
	private static int instanceSsbo;
	private static int indirectBuffer;
	private static int uProjection;
	private static int uModelView;
	private static int uCameraOffset;
	private static int uCellSize;
	private static int instanceCapacityCells;
	private static int uploadedCellCount;

	private PauCGpuLodIndirectRenderer() {
	}

	/** True once GL resources exist and the renderer is usable on this context. */
	public static boolean isAvailable() {
		return initialized && !unavailable;
	}

	/** Lazily creates the GL program and immutable geometry. Must run on the render thread. Returns availability. */
	public static boolean ensureInitialized() {
		if (initialized || unavailable) {
			return isAvailable();
		}
		if (!RenderSystem.isOnRenderThreadOrInit()) {
			return false;
		}
		try {
			program = buildProgram();
			uProjection = GL20C.glGetUniformLocation(program, "uProjection");
			uModelView = GL20C.glGetUniformLocation(program, "uModelView");
			uCameraOffset = GL20C.glGetUniformLocation(program, "uCameraOffset");
			uCellSize = GL20C.glGetUniformLocation(program, "uCellSize");

			vao = GL30C.glGenVertexArrays();
			quadVbo = GL15C.glGenBuffers();
			quadIbo = GL15C.glGenBuffers();
			instanceSsbo = GL15C.glGenBuffers();
			indirectBuffer = GL15C.glGenBuffers();

			GL30C.glBindVertexArray(vao);
			GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, quadVbo);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				// Unit quad in the XZ plane, local coordinates 0..1.
				java.nio.FloatBuffer verts = stack.floats(0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F);
				GL15C.glBufferData(GL15C.GL_ARRAY_BUFFER, verts, GL15C.GL_STATIC_DRAW);
			}
			GL20C.glEnableVertexAttribArray(0);
			GL20C.glVertexAttribPointer(0, 2, GL11C.GL_FLOAT, false, 2 * Float.BYTES, 0L);

			GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, quadIbo);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				java.nio.IntBuffer indices = stack.ints(0, 1, 2, 0, 2, 3);
				GL15C.glBufferData(GL15C.GL_ELEMENT_ARRAY_BUFFER, indices, GL15C.GL_STATIC_DRAW);
			}
			GL30C.glBindVertexArray(0);
			GL15C.glBindBuffer(GL15C.GL_ARRAY_BUFFER, 0);
			GL15C.glBindBuffer(GL15C.GL_ELEMENT_ARRAY_BUFFER, 0);

			int error = GL11C.glGetError();
			if (error != GL11C.GL_NO_ERROR) {
				throw new IllegalStateException("GL error during init: 0x" + Integer.toHexString(error));
			}

			initialized = true;
			LOGGER.info("PauC GPU LOD indirect renderer initialized (MDI foundation, default-off).");
			return true;
		} catch (Throwable throwable) {
			markUnavailable("init-failed", throwable);
			return false;
		}
	}

	/**
	 * Uploads the per-cell instance data. {@code packedCells} holds {@code count} entries, each 4 floats:
	 * camera-relative x, y, z and the rgba colour packed via {@link Float#intBitsToFloat(int)} of an
	 * 0xRRGGBBAA int. Call only when the cell set changes. Must run on the render thread.
	 */
	public static boolean uploadCells(ByteBuffer packedCells, int count) {
		if (!isAvailable() || packedCells == null || count <= 0) {
			uploadedCellCount = 0;
			return false;
		}
		try {
			GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, instanceSsbo);
			if (count > instanceCapacityCells) {
				int newCapacity = Integer.highestOneBit(Math.max(count - 1, 64)) << 1;
				GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER, (long) newCapacity * CELL_STRIDE_BYTES, GL15C.GL_DYNAMIC_DRAW);
				instanceCapacityCells = newCapacity;
			}
			GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0L, packedCells);
			GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
			uploadedCellCount = count;
			return true;
		} catch (Throwable throwable) {
			markUnavailable("upload-failed", throwable);
			return false;
		}
	}

	/**
	 * Issues a single glMultiDrawElementsIndirect for all uploaded cells. The cell positions are stored relative
	 * to the anchor chosen at upload time; {@code cameraOffset} = anchor - camera, applied per frame so the buffer
	 * does not need re-uploading as the camera moves. Must run on the render thread.
	 */
	public static boolean draw(Matrix4f projection, Matrix4f modelView, float cameraOffsetX, float cameraOffsetY, float cameraOffsetZ) {
		if (!isAvailable() || uploadedCellCount <= 0) {
			return false;
		}
		try {
			GL20C.glUseProgram(program);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				GL20C.glUniformMatrix4fv(uProjection, false, projection.get(stack.mallocFloat(16)));
				GL20C.glUniformMatrix4fv(uModelView, false, modelView.get(stack.mallocFloat(16)));
			}
			GL20C.glUniform3f(uCameraOffset, cameraOffsetX, cameraOffsetY, cameraOffsetZ);
			GL20C.glUniform1f(uCellSize, CELL_SIZE_BLOCKS);

			GL30C.glBindVertexArray(vao);
			GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, CELLS_SSBO_BINDING, instanceSsbo);

			GL15C.glBindBuffer(GL43C.GL_DRAW_INDIRECT_BUFFER, indirectBuffer);
			try (MemoryStack stack = MemoryStack.stackPush()) {
				// One DrawElementsIndirectCommand covering all cells via instancing.
				java.nio.IntBuffer cmd = stack.mallocInt(5);
				cmd.put(6);                  // count (indices per quad)
				cmd.put(uploadedCellCount);  // instanceCount
				cmd.put(0);                  // firstIndex
				cmd.put(0);                  // baseVertex
				cmd.put(0);                  // baseInstance
				cmd.flip();
				GL15C.glBufferData(GL43C.GL_DRAW_INDIRECT_BUFFER, cmd, GL15C.GL_DYNAMIC_DRAW);
			}
			GL43C.glMultiDrawElementsIndirect(GL11C.GL_TRIANGLES, GL11C.GL_UNSIGNED_INT, 0L, 1, 0);

			GL15C.glBindBuffer(GL43C.GL_DRAW_INDIRECT_BUFFER, 0);
			GL43C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, CELLS_SSBO_BINDING, 0);
			GL30C.glBindVertexArray(0);
			GL20C.glUseProgram(0);

			int error = GL11C.glGetError();
			if (error != GL11C.GL_NO_ERROR) {
				throw new IllegalStateException("GL error during draw: 0x" + Integer.toHexString(error));
			}
			return true;
		} catch (Throwable throwable) {
			markUnavailable("draw-failed", throwable);
			return false;
		}
	}

	public static int cellStrideBytes() {
		return CELL_STRIDE_BYTES;
	}

	public static void dispose() {
		try {
			if (program != 0) {
				GL20C.glDeleteProgram(program);
			}
			if (vao != 0) {
				GL30C.glDeleteVertexArrays(vao);
			}
			for (int buffer : new int[] {quadVbo, quadIbo, instanceSsbo, indirectBuffer}) {
				if (buffer != 0) {
					GL15C.glDeleteBuffers(buffer);
				}
			}
		} catch (Throwable ignored) {
			// best-effort cleanup
		} finally {
			program = vao = quadVbo = quadIbo = instanceSsbo = indirectBuffer = 0;
			initialized = false;
			instanceCapacityCells = 0;
			uploadedCellCount = 0;
		}
	}

	private static int buildProgram() {
		int vertex = compileShader(GL20C.GL_VERTEX_SHADER, VERTEX_SHADER);
		int fragment = compileShader(GL20C.GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
		int prog = GL20C.glCreateProgram();
		GL20C.glAttachShader(prog, vertex);
		GL20C.glAttachShader(prog, fragment);
		GL20C.glLinkProgram(prog);
		int linked = GL20C.glGetProgrami(prog, GL20C.GL_LINK_STATUS);
		// Shaders can be flagged for deletion once linked.
		GL20C.glDeleteShader(vertex);
		GL20C.glDeleteShader(fragment);
		if (linked == GL11C.GL_FALSE) {
			String log = GL20C.glGetProgramInfoLog(prog);
			GL20C.glDeleteProgram(prog);
			throw new IllegalStateException("Program link failed: " + log);
		}
		return prog;
	}

	private static int compileShader(int type, String source) {
		int shader = GL20C.glCreateShader(type);
		GL20C.glShaderSource(shader, source);
		GL20C.glCompileShader(shader);
		if (GL20C.glGetShaderi(shader, GL20C.GL_COMPILE_STATUS) == GL11C.GL_FALSE) {
			String log = GL20C.glGetShaderInfoLog(shader);
			GL20C.glDeleteShader(shader);
			throw new IllegalStateException("Shader compile failed: " + log);
		}
		return shader;
	}

	private static void markUnavailable(String reason, Throwable throwable) {
		LOGGER.warn("PauC GPU LOD indirect renderer disabled ({}); falling back to the BufferBuilder path.", reason, throwable);
		dispose();
		unavailable = true; // dispose() resets initialized; keep the unavailable kill-switch latched
	}
}
