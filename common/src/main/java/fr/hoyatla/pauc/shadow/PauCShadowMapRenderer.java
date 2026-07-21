package fr.hoyatla.pauc.shadow;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.lod.PauCLodClientSettings;
import fr.hoyatla.pauc.lodengine.PauCSurfaceColumnStore;
import fr.hoyatla.pauc.lodengine.PauCSurfaceSampler;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;
import org.slf4j.Logger;

import java.nio.FloatBuffer;

/**
 * PauC shadow map — 100% PauC terrain shadows, no shader pack, no Iris, no borrowed GLSL.
 *
 * <p>Technique: HEIGHTFIELD ray-marched shadows as a screen-space post pass. The LOD engine's surface
 * store already knows the top height of every column around the player (loaded chunks INCLUDING player
 * builds via WORLD_SURFACE, plus generated LOD terrain and tree canopies) — that heightfield is
 * uploaded as a texture window around the camera, and a fullscreen pass reconstructs each pixel's
 * world position from the depth buffer and marches toward the sun: any column top above the ray
 * shadows the pixel (multiply blend). One mechanism shadows vanilla terrain, player builds, the LOD
 * field and makes trees cast — with zero geometry re-render.</p>
 *
 * <p>Conceptually informed by studying how shaderpack shadow passes behave (permitted by their
 * licenses); the implementation is original PauC code and survives the planned removal of the vendored
 * shader pipeline (raw GL program, no pipeline hooks). Player-facing control: the 4-notch gauge in
 * video settings ({@link PauCShadowMode} via {@code PauCLodClientSettings.shadowMode()}).</p>
 */
public final class PauCShadowMapRenderer {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.shadow.renderer";
	private static final int WINDOW_BLOCKS = 1024; // heightfield window around the camera
	private static final long HEIGHT_UPLOAD_INTERVAL_MS = 4_000L;
	private static final double HEIGHT_RECENTER_BLOCKS = 128.0D;

	private static int program;
	private static int vao;
	private static int heightTexture;
	private static int depthCopyTexture;
	private static int depthCopyWidth;
	private static int depthCopyHeight;
	private static int uInvProjView;
	private static int uOriginRel;
	private static int uCamFrac;
	private static int uCamY;
	private static int uSunDir;
	private static int uSteps;
	private static int uReach;
	private static int uStrength;
	private static int uDepthSampler;
	private static int uHeightSampler;
	private static FloatBuffer heightUpload;
	private static double heightOriginX = Double.NaN;
	private static double heightOriginZ = Double.NaN;
	private static long lastHeightUploadMs;
	private static boolean failed;
	private static boolean firstDrawLogged;

	private PauCShadowMapRenderer() {
	}

	public static void render(PoseStack poseStack, Vec3 cameraPos, float partialTick) {
		if (failed || !fr.hoyatla.pauc.PauCTunables.readBoolean(ENABLED_PROPERTY, true)) {
			return;
		}
		PauCShadowMode mode = PauCLodClientSettings.shadowMode();
		if (mode == PauCShadowMode.OFF) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level == null || minecraft.player == null || !minecraft.level.dimensionType().hasSkyLight()) {
			return;
		}
		try {
			// Sun direction (world space, shaderpack convention): 0 = sunrise east, 0.25 = noon.
			float timeOfDay = minecraft.level.getTimeOfDay(partialTick);
			float sunAngle = timeOfDay < 0.75F ? timeOfDay + 0.25F : timeOfDay - 0.75F;
			float theta = sunAngle * ((float) Math.PI * 2.0F);
			float sunX = (float) Math.cos(theta);
			float sunY = (float) Math.sin(theta);
			if (sunY < 0.08F) {
				return; // sun at/below the horizon: no directional shadows (night handled by ambient)
			}
			// Fade the shadow strength in at dawn/out at dusk, and wash it out in rain.
			float elevationFade = Math.min(1.0F, (sunY - 0.08F) / 0.12F);
			float rain = minecraft.level.getRainLevel(partialTick);
			float strength = 1.0F - (1.0F - mode.strength()) * elevationFade * (1.0F - rain);
			if (strength >= 0.995F) {
				return;
			}

			if (!ensureProgram()) {
				return;
			}
			long now = System.currentTimeMillis();
			updateHeightTexture(cameraPos, now);
			copyDepth(minecraft);

			// Pixel world position reconstruction: camera-relative (world axes) via inverse(proj*view).
			Matrix4f invProjView = new Matrix4f(RenderSystem.getProjectionMatrix())
				.mul(poseStack.last().pose())
				.invert();

			RenderSystem.disableDepthTest();
			RenderSystem.depthMask(false);
			RenderSystem.enableBlend();
			RenderSystem.blendFunc(GlStateManager.SourceFactor.DST_COLOR, GlStateManager.DestFactor.ZERO);
			RenderSystem.disableCull();

			GlStateManager._glUseProgram(program);
			try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
				GL20.glUniformMatrix4fv(uInvProjView, false, invProjView.get(stack.mallocFloat(16)));
			}
			GL20.glUniform2f(uOriginRel, (float) (heightOriginX - cameraPos.x), (float) (heightOriginZ - cameraPos.z));
			GL20.glUniform2f(uCamFrac, (float) (cameraPos.x - Math.floor(cameraPos.x)), (float) (cameraPos.z - Math.floor(cameraPos.z)));
			GL20.glUniform1f(uCamY, (float) cameraPos.y);
			GL20.glUniform3f(uSunDir, sunX, sunY, 0.0F);
			GL20.glUniform1i(uSteps, mode.marchSteps());
			GL20.glUniform1f(uReach, mode.reachBlocks());
			GL20.glUniform1f(uStrength, strength);
			GL20.glUniform1i(uDepthSampler, 0);
			GL20.glUniform1i(uHeightSampler, 1);
			GlStateManager._activeTexture(GL13.GL_TEXTURE0);
			GlStateManager._bindTexture(depthCopyTexture);
			GlStateManager._activeTexture(GL13.GL_TEXTURE1);
			GlStateManager._bindTexture(heightTexture);
			GlStateManager._activeTexture(GL13.GL_TEXTURE0);

			GlStateManager._glBindVertexArray(vao);
			GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
			GlStateManager._glBindVertexArray(0);
			GlStateManager._glUseProgram(0);

			RenderSystem.enableCull();
			RenderSystem.defaultBlendFunc();
			RenderSystem.disableBlend();
			RenderSystem.depthMask(true);
			RenderSystem.enableDepthTest();

			if (!firstDrawLogged) {
				firstDrawLogged = true;
				LOGGER.info("PauC shadow map: first heightfield shadow pass on screen (mode={}).", mode.id());
			}
		} catch (Throwable throwable) {
			failed = true;
			LOGGER.warn("PauC shadow map failed; disabled for this session.", throwable);
		}
	}

	/** Dedicated builder for the player blob decal (sharing the Tesselator mid-frame is unsafe). */
	private static com.mojang.blaze3d.vertex.BufferBuilder blobBuilder;

	/**
	 * Native-style ROUND blob shadow for the LOCAL PLAYER in first person — vanilla only draws the blob
	 * for RENDERED entities, and your own model isn't rendered in first person, so the player never had
	 * one. Same look as the mob blob: soft dark disc at the feet, opacity fading with height above
	 * ground. Independent of the PauC shadow gauge (it's the native shadow, not a directional one);
	 * respects the vanilla Entity Shadows video option. Third person unchanged (vanilla handles it).
	 */
	public static void renderPlayerBlob(Minecraft minecraft, PoseStack poseStack, Vec3 cameraPos, float partialTick) {
		try {
			if (minecraft.level == null || minecraft.player == null
				|| !minecraft.options.entityShadows().get()
				|| !minecraft.options.getCameraType().isFirstPerson()
				|| minecraft.player.isSpectator() || minecraft.player.isInvisible() || !minecraft.player.isAlive()) {
				return;
			}
			var player = minecraft.player;
			double ex = net.minecraft.util.Mth.lerp(partialTick, player.xOld, player.getX());
			double ey = net.minecraft.util.Mth.lerp(partialTick, player.yOld, player.getY());
			double ez = net.minecraft.util.Mth.lerp(partialTick, player.zOld, player.getZ());
			// Ground just below the feet, vanilla-style (short scan down through the block heightmap).
			var level = minecraft.level;
			var pos = net.minecraft.core.BlockPos.containing(ex, ey + 0.2D, ez);
			double groundY = Double.NaN;
			for (int dy = 0; dy <= 2; dy++) {
				var check = pos.below(dy);
				var shape = level.getBlockState(check).getCollisionShape(level, check);
				if (!shape.isEmpty()) {
					groundY = check.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
					break;
				}
			}
			if (Double.isNaN(groundY) || ey - groundY > 2.0D) {
				return; // airborne: vanilla fades the blob out quickly, we just drop it
			}
			float opacity = (float) (0.32D * (1.0D - (ey - groundY) / 2.0D));
			if (opacity <= 0.02F) {
				return;
			}
			if (blobBuilder == null) {
				blobBuilder = new com.mojang.blaze3d.vertex.BufferBuilder(4 * 1024);
			}
			float radius = 0.5F; // vanilla player shadow radius
			float coreRadius = radius * 0.7F; // SOLID dark core like the mob blob, fade only on the rim
			float cx = (float) (ex - cameraPos.x);
			float cz = (float) (ez - cameraPos.z);
			float cy = (float) (groundY + 0.02D - cameraPos.y);
			int aCore = (int) (opacity * 255.0F);
			blobBuilder.begin(com.mojang.blaze3d.vertex.VertexFormat.Mode.TRIANGLES, com.mojang.blaze3d.vertex.DefaultVertexFormat.POSITION_COLOR);
			int segments = 24;
			for (int i = 0; i < segments; i++) {
				float a0 = (float) (i * Math.PI * 2.0D / segments);
				float a1 = (float) ((i + 1) * Math.PI * 2.0D / segments);
				float c0x = cx + coreRadius * (float) Math.cos(a0);
				float c0z = cz + coreRadius * (float) Math.sin(a0);
				float c1x = cx + coreRadius * (float) Math.cos(a1);
				float c1z = cz + coreRadius * (float) Math.sin(a1);
				float r0x = cx + radius * (float) Math.cos(a0);
				float r0z = cz + radius * (float) Math.sin(a0);
				float r1x = cx + radius * (float) Math.cos(a1);
				float r1z = cz + radius * (float) Math.sin(a1);
				// solid core wedge
				blobBuilder.vertex(cx, cy, cz).color(0, 0, 0, aCore).endVertex();
				blobBuilder.vertex(c0x, cy, c0z).color(0, 0, 0, aCore).endVertex();
				blobBuilder.vertex(c1x, cy, c1z).color(0, 0, 0, aCore).endVertex();
				// fading rim band (two triangles)
				blobBuilder.vertex(c0x, cy, c0z).color(0, 0, 0, aCore).endVertex();
				blobBuilder.vertex(r0x, cy, r0z).color(0, 0, 0, 0).endVertex();
				blobBuilder.vertex(r1x, cy, r1z).color(0, 0, 0, 0).endVertex();
				blobBuilder.vertex(c0x, cy, c0z).color(0, 0, 0, aCore).endVertex();
				blobBuilder.vertex(r1x, cy, r1z).color(0, 0, 0, 0).endVertex();
				blobBuilder.vertex(c1x, cy, c1z).color(0, 0, 0, aCore).endVertex();
			}
			com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer rendered = blobBuilder.end();
			poseStack.pushPose();
			RenderSystem.enableBlend();
			RenderSystem.defaultBlendFunc();
			RenderSystem.enableDepthTest();
			RenderSystem.depthMask(false);
			RenderSystem.disableCull();
			RenderSystem.setShader(net.minecraft.client.renderer.GameRenderer::getPositionColorShader);
			var modelView = RenderSystem.getModelViewStack();
			modelView.pushPose();
			modelView.mulPoseMatrix(poseStack.last().pose());
			RenderSystem.applyModelViewMatrix();
			com.mojang.blaze3d.vertex.BufferUploader.drawWithShader(rendered);
			modelView.popPose();
			RenderSystem.applyModelViewMatrix();
			RenderSystem.enableCull();
			RenderSystem.depthMask(true);
			RenderSystem.disableBlend();
			poseStack.popPose();
		} catch (Throwable ignored) {
			// never crash the frame for a cosmetic blob
		}
	}

	/** Copies the main framebuffer depth into our own texture (sampling the bound depth is UB). */
	private static void copyDepth(Minecraft minecraft) {
		RenderTarget target = minecraft.getMainRenderTarget();
		if (depthCopyTexture == 0 || depthCopyWidth != target.width || depthCopyHeight != target.height) {
			if (depthCopyTexture != 0) {
				GlStateManager._deleteTexture(depthCopyTexture);
			}
			depthCopyTexture = GlStateManager._genTexture();
			GlStateManager._bindTexture(depthCopyTexture);
			GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_BORDER);
			GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_BORDER);
			GlStateManager._texImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_DEPTH_COMPONENT24, target.width, target.height,
				0, GL11.GL_DEPTH_COMPONENT, GL11.GL_UNSIGNED_INT, null);
			depthCopyWidth = target.width;
			depthCopyHeight = target.height;
		} else {
			GlStateManager._bindTexture(depthCopyTexture);
		}
		GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, depthCopyWidth, depthCopyHeight);
	}

	/**
	 * Uploads the heightfield window (WINDOW_BLOCKS x WINDOW_BLOCKS column tops around the camera) from
	 * the LOD surface store — region arrays are copied row-wise, no per-column hashmap lookups.
	 */
	private static void updateHeightTexture(Vec3 cameraPos, long now) {
		boolean recenter = Double.isNaN(heightOriginX)
			|| Math.abs(cameraPos.x - (heightOriginX + WINDOW_BLOCKS / 2.0D)) > HEIGHT_RECENTER_BLOCKS
			|| Math.abs(cameraPos.z - (heightOriginZ + WINDOW_BLOCKS / 2.0D)) > HEIGHT_RECENTER_BLOCKS;
		if (heightTexture != 0 && !recenter && now - lastHeightUploadMs < HEIGHT_UPLOAD_INTERVAL_MS) {
			return;
		}
		lastHeightUploadMs = now;
		int originX = (((int) Math.floor(cameraPos.x)) - WINDOW_BLOCKS / 2) & ~63; // region-aligned
		int originZ = (((int) Math.floor(cameraPos.z)) - WINDOW_BLOCKS / 2) & ~63;
		heightOriginX = originX;
		heightOriginZ = originZ;

		if (heightUpload == null) {
			heightUpload = MemoryUtil.memAllocFloat(WINDOW_BLOCKS * WINDOW_BLOCKS);
		}
		PauCSurfaceColumnStore store = PauCSurfaceSampler.store();
		int regionSpan = WINDOW_BLOCKS >> PauCSurfaceColumnStore.REGION_SHIFT;
		for (int rz = 0; rz < regionSpan; rz++) {
			for (int rx = 0; rx < regionSpan; rx++) {
				PauCSurfaceColumnStore.Region region = store.region(PauCSurfaceColumnStore.regionKey(
					originX + (rx << PauCSurfaceColumnStore.REGION_SHIFT),
					originZ + (rz << PauCSurfaceColumnStore.REGION_SHIFT)));
				int baseX = rx << PauCSurfaceColumnStore.REGION_SHIFT;
				int baseZ = rz << PauCSurfaceColumnStore.REGION_SHIFT;
				for (int lz = 0; lz < PauCSurfaceColumnStore.REGION_SIZE; lz++) {
					int row = (baseZ + lz) * WINDOW_BLOCKS + baseX;
					if (region == null) {
						for (int lx = 0; lx < PauCSurfaceColumnStore.REGION_SIZE; lx++) {
							heightUpload.put(row + lx, -30000.0F);
						}
						continue;
					}
					int spanRow = (lz << PauCSurfaceColumnStore.REGION_SHIFT) * PauCSurfaceColumnStore.MAX_SPANS;
					for (int lx = 0; lx < PauCSurfaceColumnStore.REGION_SIZE; lx++) {
						short top = region.spanY[spanRow + lx * PauCSurfaceColumnStore.MAX_SPANS];
						heightUpload.put(row + lx, top == Short.MIN_VALUE ? -30000.0F : top + 1.0F);
					}
				}
			}
		}
		heightUpload.position(0);

		// CRITICAL: reset the pixel-unpack state. Vanilla's NativeImage uploads leave GL_UNPACK_ROW_LENGTH /
		// SKIP_* set for THEIR textures; inheriting a wider row stride here makes the driver read past the
		// end of our tightly-packed buffer — a native EXCEPTION_ACCESS_VIOLATION in nvoglv64 (seen live).
		GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
		GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
		GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);

		if (heightTexture == 0) {
			heightTexture = GlStateManager._genTexture();
			GlStateManager._bindTexture(heightTexture);
			// NEAREST, not LINEAR: interpolating column heights bends shadow edges into waves — in a
			// world made of right angles the shadows must be straight, block-aligned lines.
			GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
			GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
			GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
			GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
			GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_R32F, WINDOW_BLOCKS, WINDOW_BLOCKS,
				0, GL11.GL_RED, GL11.GL_FLOAT, heightUpload);
		} else {
			GlStateManager._bindTexture(heightTexture);
			GL11.glTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, WINDOW_BLOCKS, WINDOW_BLOCKS,
				GL11.GL_RED, GL11.GL_FLOAT, heightUpload);
		}
	}

	private static boolean ensureProgram() {
		if (program != 0) {
			return true;
		}
		int vs = compile(GL20.GL_VERTEX_SHADER, """
			#version 150
			out vec2 vUv;
			void main() {
				vec2 pos = vec2(gl_VertexID == 1 ? 3.0 : -1.0, gl_VertexID == 2 ? 3.0 : -1.0);
				vUv = pos * 0.5 + 0.5;
				gl_Position = vec4(pos, 0.0, 1.0);
			}
			""");
		int fs = compile(GL20.GL_FRAGMENT_SHADER, """
			#version 150
			uniform sampler2D uDepth;
			uniform sampler2D uHeight;
			uniform mat4 uInvProjView;
			uniform vec2 uOriginRel;
			uniform vec2 uCamFrac;
			uniform float uCamY;
			uniform vec3 uSunDir;
			uniform int uSteps;
			uniform float uReach;
			uniform float uStrength;
			in vec2 vUv;
			out vec4 fragColor;
			float heightAt(vec2 relXz) {
				vec2 t = (relXz - uOriginRel) / %WINDOW%.0;
				if (t.x <= 0.001 || t.x >= 0.999 || t.y <= 0.001 || t.y >= 0.999) {
					return -30000.0;
				}
				return texture(uHeight, t).r;
			}
			void main() {
				float depth = texture(uDepth, vUv).r;
				if (depth >= 1.0) {
					fragColor = vec4(1.0);
					return;
				}
				vec4 clip = vec4(vUv * 2.0 - 1.0, depth * 2.0 - 1.0, 1.0);
				vec4 rel4 = uInvProjView * clip;
				vec3 rel = rel4.xyz / rel4.w;
				// Shadow fog: crisp marched shadows near the player melt into a mild uniform shade with
				// distance, so the far LODs neither brighten up (unshadowed) nor show ugly noisy pixel
				// shadows — the distant field keeps one calm averaged tone that blends with the sky fog.
				float dist = length(rel.xz);
				// 0.55: the distant LOD rings must sit clearly DARKER than full daylight — matching the
				// average tone of the shadowed vanilla foreground instead of glowing brighter than it.
				float avgShade = 1.0 - (1.0 - uStrength) * 0.55;
				float distFade = smoothstep(280.0, 470.0, dist);
				// VERTICAL FACES: a pixel well below its own column top sits on a cliff/wall embedded in
				// the heightfield — the march there is numerically unstable (the shimmering mountain
				// walls). March those pixels from the column top: the geometric face shading already
				// darkens walls consistently. Also skips the march cost on every wall pixel.
				float ownH = heightAt(rel.xz);
				if (distFade >= 1.0) {
					fragColor = vec4(vec3(avgShade), 1.0);
					return;
				}
				// ONE SHADOW PER COLUMN. The march origin is the BLOCK-SNAPPED column centre in XZ AND the
				// COLUMN TOP in Y — for EVERY fragment, top surface or vertical face alike. A vertical face
				// belongs to the same column as its top, so it must receive the SAME shadow; deriving the
				// origin Y from the fragment's own height (or blending toward it) made the shadow vary DOWN
				// the face — the diagonal hatching on ice cliffs. One origin per column => one deterministic
				// shadow per column: uniform faces, block-aligned edges, no stripes, no camera flicker.
				vec3 origin = vec3(floor(rel.x + uCamFrac.x) - uCamFrac.x + 0.5, ownH - uCamY, floor(rel.z + uCamFrac.y) - uCamFrac.y + 0.5);
				float shade = 1.0;
				float t = 0.0;
				float grow = pow(uReach / 12.8, 1.0 / float(max(uSteps - 8, 1)));
				for (int i = 0; i < uSteps; i++) {
					t = i < 8 ? t + 1.6 : t * grow;
					vec3 p = origin + uSunDir * t;
					float h = heightAt(p.xz);
					// Bias > 1 block: single-block surface bumps must NOT cast; slope term keeps
					// stair-stepped hillsides from self-shadowing.
					if (h - 1.15 - t * 0.03 > uCamY + p.y) {
						shade = uStrength;
						break;
					}
				}
				fragColor = vec4(vec3(mix(shade, avgShade, distFade)), 1.0);
			}
			""".replace("%WINDOW%", Integer.toString(WINDOW_BLOCKS)));
		if (vs == 0 || fs == 0) {
			failed = true;
			return false;
		}
		int p = GL20.glCreateProgram();
		GL20.glAttachShader(p, vs);
		GL20.glAttachShader(p, fs);
		GL20.glLinkProgram(p);
		GL20.glDeleteShader(vs);
		GL20.glDeleteShader(fs);
		if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
			LOGGER.warn("PauC shadow map: program link failed: {}", GL20.glGetProgramInfoLog(p));
			GL20.glDeleteProgram(p);
			failed = true;
			return false;
		}
		program = p;
		uInvProjView = GL20.glGetUniformLocation(p, "uInvProjView");
		uOriginRel = GL20.glGetUniformLocation(p, "uOriginRel");
		uCamFrac = GL20.glGetUniformLocation(p, "uCamFrac");
		uCamY = GL20.glGetUniformLocation(p, "uCamY");
		uSunDir = GL20.glGetUniformLocation(p, "uSunDir");
		uSteps = GL20.glGetUniformLocation(p, "uSteps");
		uReach = GL20.glGetUniformLocation(p, "uReach");
		uStrength = GL20.glGetUniformLocation(p, "uStrength");
		uDepthSampler = GL20.glGetUniformLocation(p, "uDepth");
		uHeightSampler = GL20.glGetUniformLocation(p, "uHeight");
		vao = GlStateManager._glGenVertexArrays();
		LOGGER.info("PauC shadow map: heightfield shadow program compiled (window {} blocks).", WINDOW_BLOCKS);
		return true;
	}

	private static int compile(int type, String source) {
		int shader = GL20.glCreateShader(type);
		GL20.glShaderSource(shader, source);
		GL20.glCompileShader(shader);
		if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
			LOGGER.warn("PauC shadow map: shader compile failed: {}", GL20.glGetShaderInfoLog(shader));
			GL20.glDeleteShader(shader);
			return 0;
		}
		return shader;
	}
}
