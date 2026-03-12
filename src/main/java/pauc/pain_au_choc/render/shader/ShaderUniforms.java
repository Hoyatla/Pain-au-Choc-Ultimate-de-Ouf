package pauc.pain_au_choc.render.shader;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.FloatBuffer;

/**
 * Manages the standard OptiFine-compatible uniform values for shader programs.
 * Updates once per frame and can be applied to any active shader program.
 *
 * Standard uniforms (OptiFine spec):
 * - Time: worldTime, worldDay, frameCounter, frameTime, frameTimeCounter
 * - Camera: cameraPosition, previousCameraPosition, gbufferModelView/Projection
 * - Sun/Moon: sunPosition, moonPosition, shadowLightPosition, sunAngle
 * - Screen: viewWidth, viewHeight, aspectRatio
 * - Weather: rainStrength, wetness
 * - Fog: fogColor, fogDensity, fogMode
 * - Light: shadowModelView, shadowProjection
 * - Custom: isEyeInWater, nightVision, blindness, near, far
 */
public class ShaderUniforms {

    // ---- Time uniforms ----
    private int worldTime;
    private int worldDay;
    private int frameCounter;
    private float frameTime;        // Time since last frame in seconds
    private float frameTimeCounter; // Accumulated frame time

    // ---- Camera uniforms ----
    private final float[] cameraPosition = new float[3];
    private final float[] previousCameraPosition = new float[3];
    private final FloatBuffer gbufferModelView = FloatBuffer.allocate(16);
    private final FloatBuffer gbufferModelViewInverse = FloatBuffer.allocate(16);
    private final FloatBuffer gbufferProjection = FloatBuffer.allocate(16);
    private final FloatBuffer gbufferProjectionInverse = FloatBuffer.allocate(16);
    private final FloatBuffer gbufferPreviousModelView = FloatBuffer.allocate(16);
    private final FloatBuffer gbufferPreviousProjection = FloatBuffer.allocate(16);

    // ---- Sun/Moon uniforms ----
    private final float[] sunPosition = new float[3];
    private final float[] moonPosition = new float[3];
    private final float[] shadowLightPosition = new float[3];
    private float sunAngle;

    // ---- Screen uniforms ----
    private float viewWidth;
    private float viewHeight;
    private float aspectRatio;
    private float near = 0.05f;
    private float far = 256.0f;

    // ---- Weather uniforms ----
    private float rainStrength;
    private float wetness;

    // ---- Fog uniforms ----
    private final float[] fogColor = new float[3];

    // ---- State ----
    private int isEyeInWater;
    private float nightVision;
    private float blindness;

    // ---- Shadow uniforms ----
    private final FloatBuffer shadowModelView = FloatBuffer.allocate(16);
    private final FloatBuffer shadowProjection = FloatBuffer.allocate(16);

    private long lastFrameTimeNanos;

    /**
     * Update all uniform values for the current frame.
     * Call once per frame before rendering.
     */
    public void update(Camera camera, Matrix4f modelView, Matrix4f projection, float partialTick) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;

        // Time
        long now = System.nanoTime();
        this.frameTime = this.lastFrameTimeNanos == 0 ? 0.016f :
                (float) ((now - this.lastFrameTimeNanos) / 1_000_000_000.0);
        this.lastFrameTimeNanos = now;
        this.frameTimeCounter += this.frameTime;
        this.frameCounter++;

        if (level != null) {
            this.worldTime = (int) (level.getDayTime() % 24000L);
            this.worldDay = (int) (level.getDayTime() / 24000L);
            this.rainStrength = level.getRainLevel(partialTick);
            this.wetness = level.getRainLevel(partialTick); // Simplified
        }

        // Camera position
        Vec3 pos = camera.getPosition();
        System.arraycopy(this.cameraPosition, 0, this.previousCameraPosition, 0, 3);
        this.cameraPosition[0] = (float) pos.x;
        this.cameraPosition[1] = (float) pos.y;
        this.cameraPosition[2] = (float) pos.z;

        // Matrices
        this.gbufferPreviousModelView.clear();
        this.gbufferModelView.rewind();
        this.gbufferPreviousModelView.put(this.gbufferModelView);
        this.gbufferPreviousModelView.flip();

        this.gbufferModelView.clear();
        modelView.get(this.gbufferModelView);
        this.gbufferModelView.flip();

        this.gbufferModelViewInverse.clear();
        new Matrix4f(modelView).invert().get(this.gbufferModelViewInverse);
        this.gbufferModelViewInverse.flip();

        this.gbufferPreviousProjection.clear();
        this.gbufferProjection.rewind();
        this.gbufferPreviousProjection.put(this.gbufferProjection);
        this.gbufferPreviousProjection.flip();

        this.gbufferProjection.clear();
        projection.get(this.gbufferProjection);
        this.gbufferProjection.flip();

        this.gbufferProjectionInverse.clear();
        new Matrix4f(projection).invert().get(this.gbufferProjectionInverse);
        this.gbufferProjectionInverse.flip();

        // Screen
        this.viewWidth = mc.getWindow().getWidth();
        this.viewHeight = mc.getWindow().getHeight();
        this.aspectRatio = this.viewWidth / Math.max(1f, this.viewHeight);

        // Sun/Moon position (simplified)
        if (level != null) {
            this.sunAngle = level.getTimeOfDay(partialTick);
            float celestialAngle = this.sunAngle * (float) (Math.PI * 2.0);
            this.sunPosition[0] = 0f;
            this.sunPosition[1] = (float) Math.cos(celestialAngle) * 100f;
            this.sunPosition[2] = (float) -Math.sin(celestialAngle) * 100f;
            this.moonPosition[0] = -this.sunPosition[0];
            this.moonPosition[1] = -this.sunPosition[1];
            this.moonPosition[2] = -this.sunPosition[2];

            // Shadow light follows sun during day, moon at night
            boolean daytime = this.sunAngle < 0.25f || this.sunAngle > 0.75f;
            float[] lightSrc = daytime ? this.sunPosition : this.moonPosition;
            System.arraycopy(lightSrc, 0, this.shadowLightPosition, 0, 3);
        }

        // Eye in water
        this.isEyeInWater = mc.player != null && mc.player.isUnderWater() ? 1 : 0;
    }

    /**
     * Apply all standard uniforms to the given shader program.
     */
    public void apply(PauCShaderProgram program) {
        // Time
        program.setUniform1i("worldTime", this.worldTime);
        program.setUniform1i("worldDay", this.worldDay);
        program.setUniform1i("frameCounter", this.frameCounter);
        program.setUniform1f("frameTime", this.frameTime);
        program.setUniform1f("frameTimeCounter", this.frameTimeCounter);

        // Camera
        program.setUniform3f("cameraPosition",
                this.cameraPosition[0], this.cameraPosition[1], this.cameraPosition[2]);
        program.setUniform3f("previousCameraPosition",
                this.previousCameraPosition[0], this.previousCameraPosition[1], this.previousCameraPosition[2]);

        // Matrices
        this.gbufferModelView.rewind();
        program.setUniformMatrix4f("gbufferModelView", false, this.gbufferModelView);
        this.gbufferModelViewInverse.rewind();
        program.setUniformMatrix4f("gbufferModelViewInverse", false, this.gbufferModelViewInverse);
        this.gbufferProjection.rewind();
        program.setUniformMatrix4f("gbufferProjection", false, this.gbufferProjection);
        this.gbufferProjectionInverse.rewind();
        program.setUniformMatrix4f("gbufferProjectionInverse", false, this.gbufferProjectionInverse);
        this.gbufferPreviousModelView.rewind();
        program.setUniformMatrix4f("gbufferPreviousModelView", false, this.gbufferPreviousModelView);
        this.gbufferPreviousProjection.rewind();
        program.setUniformMatrix4f("gbufferPreviousProjection", false, this.gbufferPreviousProjection);

        // Sun/Moon
        program.setUniform3f("sunPosition", sunPosition[0], sunPosition[1], sunPosition[2]);
        program.setUniform3f("moonPosition", moonPosition[0], moonPosition[1], moonPosition[2]);
        program.setUniform3f("shadowLightPosition",
                shadowLightPosition[0], shadowLightPosition[1], shadowLightPosition[2]);
        program.setUniform1f("sunAngle", sunAngle);

        // Screen
        program.setUniform1f("viewWidth", viewWidth);
        program.setUniform1f("viewHeight", viewHeight);
        program.setUniform1f("aspectRatio", aspectRatio);
        program.setUniform1f("near", near);
        program.setUniform1f("far", far);

        // Weather
        program.setUniform1f("rainStrength", rainStrength);
        program.setUniform1f("wetness", wetness);

        // State
        program.setUniform1i("isEyeInWater", isEyeInWater);
        program.setUniform1f("nightVision", nightVision);
        program.setUniform1f("blindness", blindness);

        // Shadow matrices
        this.shadowModelView.rewind();
        program.setUniformMatrix4f("shadowModelView", false, this.shadowModelView);
        this.shadowProjection.rewind();
        program.setUniformMatrix4f("shadowProjection", false, this.shadowProjection);
    }

    /**
     * Bind GBuffer texture samplers to standard OptiFine names.
     */
    public void bindGBufferSamplers(PauCShaderProgram program, GBufferTargets gbuffers) {
        // colortex0-7
        for (int i = 0; i < gbuffers.getAllocatedColorTargets(); i++) {
            program.setUniform1i("colortex" + i, i);
            // Also bind legacy names
            if (i == 0) program.setUniform1i("gcolor", i);
            if (i == 1) program.setUniform1i("gdepth", i);
            if (i == 2) program.setUniform1i("gnormal", i);
            if (i == 3) program.setUniform1i("composite", i);
        }

        // depthtex0-2
        for (int i = 0; i < GBufferTargets.MAX_DEPTH_TARGETS; i++) {
            program.setUniform1i("depthtex" + i, 8 + i);
        }

        // Shadow textures
        program.setUniform1i("shadowtex0", 12);
        program.setUniform1i("shadowtex1", 13);
        program.setUniform1i("shadowcolor0", 14);
        program.setUniform1i("noisetex", 15);
    }

    // ---- Shadow matrix setters ----

    public void setShadowModelView(Matrix4f matrix) {
        this.shadowModelView.clear();
        matrix.get(this.shadowModelView);
        this.shadowModelView.flip();
    }

    public void setShadowProjection(Matrix4f matrix) {
        this.shadowProjection.clear();
        matrix.get(this.shadowProjection);
        this.shadowProjection.flip();
    }

    // ---- Getters for pipeline use ----
    public float getSunAngle() { return sunAngle; }
    public float[] getSunPosition() { return sunPosition; }
    public float[] getShadowLightPosition() { return shadowLightPosition; }
    public float getViewWidth() { return viewWidth; }
    public float getViewHeight() { return viewHeight; }
}
