package pauc.pain_au_choc.render.shader;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;

import java.util.HashMap;
import java.util.Map;

/**
 * Wrapper around an OpenGL shader program (vertex + fragment + optional geometry).
 * Manages compilation, linking, uniform lookup caching, and lifecycle.
 *
 * Adapted from Oculus/Iris shader program management for OptiFine-compatible
 * shaderpack support.
 */
public class PauCShaderProgram implements AutoCloseable {

    private int programId;
    private final String name;
    private final Map<String, Integer> uniformLocationCache = new HashMap<>();
    private boolean valid;

    /**
     * Create and link a shader program from source.
     *
     * @param name           Human-readable name for debugging
     * @param vertexSource   GLSL vertex shader source
     * @param fragmentSource GLSL fragment shader source
     * @param geometrySource GLSL geometry shader source (null if not used)
     */
    public PauCShaderProgram(String name, String vertexSource, String fragmentSource, String geometrySource) {
        this.name = name;
        this.programId = GL20.glCreateProgram();

        int vertexShader = compileShader(GL20.GL_VERTEX_SHADER, vertexSource, name + ".vsh");
        int fragmentShader = compileShader(GL20.GL_FRAGMENT_SHADER, fragmentSource, name + ".fsh");
        int geometryShader = 0;

        if (geometrySource != null) {
            geometryShader = compileShader(0x8DD9 /* GL_GEOMETRY_SHADER */, geometrySource, name + ".gsh");
        }

        GL20.glAttachShader(this.programId, vertexShader);
        GL20.glAttachShader(this.programId, fragmentShader);
        if (geometryShader != 0) {
            GL20.glAttachShader(this.programId, geometryShader);
        }

        // Bind standard attribute locations before linking
        bindStandardAttributes();

        GL20.glLinkProgram(this.programId);

        if (GL20.glGetProgrami(this.programId, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(this.programId, 4096);
            System.err.println("[PAUC Shader] Failed to link program '" + name + "': " + log);
            GL20.glDeleteProgram(this.programId);
            this.programId = 0;
            this.valid = false;
        } else {
            this.valid = true;
        }

        // Cleanup shader objects (they're linked into the program now)
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);
        if (geometryShader != 0) {
            GL20.glDeleteShader(geometryShader);
        }
    }

    /**
     * Bind this shader program for rendering.
     */
    public void bind() {
        if (this.valid) {
            GL20.glUseProgram(this.programId);
        }
    }

    /**
     * Unbind any shader program (revert to fixed-function).
     */
    public static void unbind() {
        GL20.glUseProgram(0);
    }

    /**
     * Get a uniform location by name (cached).
     * Returns -1 if the uniform doesn't exist or the program is invalid.
     */
    public int getUniformLocation(String uniformName) {
        if (!this.valid) return -1;
        return this.uniformLocationCache.computeIfAbsent(uniformName,
                n -> GL20.glGetUniformLocation(this.programId, n));
    }

    /**
     * Set a float uniform.
     */
    public void setUniform1f(String name, float value) {
        int loc = getUniformLocation(name);
        if (loc >= 0) GL20.glUniform1f(loc, value);
    }

    /**
     * Set an int uniform (also used for sampler bindings).
     */
    public void setUniform1i(String name, int value) {
        int loc = getUniformLocation(name);
        if (loc >= 0) GL20.glUniform1i(loc, value);
    }

    /**
     * Set a vec2 uniform.
     */
    public void setUniform2f(String name, float x, float y) {
        int loc = getUniformLocation(name);
        if (loc >= 0) GL20.glUniform2f(loc, x, y);
    }

    /**
     * Set a vec3 uniform.
     */
    public void setUniform3f(String name, float x, float y, float z) {
        int loc = getUniformLocation(name);
        if (loc >= 0) GL20.glUniform3f(loc, x, y, z);
    }

    /**
     * Set a vec4 uniform.
     */
    public void setUniform4f(String name, float x, float y, float z, float w) {
        int loc = getUniformLocation(name);
        if (loc >= 0) GL20.glUniform4f(loc, x, y, z, w);
    }

    /**
     * Set a mat4 uniform from a float array (column-major).
     */
    public void setUniformMatrix4f(String name, boolean transpose, float[] matrix) {
        int loc = getUniformLocation(name);
        if (loc >= 0) GL20.glUniformMatrix4fv(loc, transpose, matrix);
    }

    /**
     * Set a mat4 uniform from a java.nio.FloatBuffer.
     */
    public void setUniformMatrix4f(String name, boolean transpose, java.nio.FloatBuffer matrix) {
        int loc = getUniformLocation(name);
        if (loc >= 0) GL20.glUniformMatrix4fv(loc, transpose, matrix);
    }

    public int getProgramId() { return programId; }
    public String getName() { return name; }
    public boolean isValid() { return valid; }

    @Override
    public void close() {
        if (this.programId != 0) {
            GL20.glDeleteProgram(this.programId);
            this.programId = 0;
            this.valid = false;
        }
        this.uniformLocationCache.clear();
    }

    // ---- Internal ----

    /**
     * Bind standard OptiFine-compatible attribute locations.
     * These match the attribute layout expected by shaderpack programs.
     */
    private void bindStandardAttributes() {
        // Standard Minecraft/OptiFine attribute locations
        GL20.glBindAttribLocation(this.programId, 0, "Position");
        GL20.glBindAttribLocation(this.programId, 1, "Color");
        GL20.glBindAttribLocation(this.programId, 2, "UV0");       // texture coords
        GL20.glBindAttribLocation(this.programId, 3, "UV1");       // overlay coords
        GL20.glBindAttribLocation(this.programId, 4, "UV2");       // lightmap coords
        GL20.glBindAttribLocation(this.programId, 5, "Normal");

        // OptiFine extended attributes
        GL20.glBindAttribLocation(this.programId, 10, "mc_Entity");
        GL20.glBindAttribLocation(this.programId, 11, "mc_midTexCoord");
        GL20.glBindAttribLocation(this.programId, 12, "at_tangent");

        // PAUC compact format attributes (for terrain)
        GL20.glBindAttribLocation(this.programId, 6, "pauc_Position");
        GL20.glBindAttribLocation(this.programId, 7, "pauc_Color");
        GL20.glBindAttribLocation(this.programId, 8, "pauc_TexCoord");
    }

    private static int compileShader(int type, String source, String debugName) {
        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);

        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader, 4096);
            System.err.println("[PAUC Shader] Failed to compile '" + debugName + "': " + log);
        }

        return shader;
    }

    @Override
    public String toString() {
        return "PauCShaderProgram[" + name + " id=" + programId + " valid=" + valid + "]";
    }
}
