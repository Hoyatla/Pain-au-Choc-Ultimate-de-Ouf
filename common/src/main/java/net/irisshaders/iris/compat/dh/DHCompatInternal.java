package net.irisshaders.iris.compat.dh;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.interfaces.override.IDhApiOverrideable;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiFramebuffer;
import com.seibel.distanthorizons.api.interfaces.override.rendering.IDhApiGenericObjectShaderProgram;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;
import fr.hoyatla.pauc.lod.PauCLodShaderSafety;
import fr.hoyatla.pauc.lod.PauCLodShaderContext;
import fr.hoyatla.pauc.lod.PauCLodShaderRuntime;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.gl.texture.DepthCopyStrategy;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.properties.CloudSetting;
import net.irisshaders.iris.targets.Blaze3dRenderTargetExt;
import net.irisshaders.iris.targets.DepthTexture;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

import java.io.IOException;
import java.util.Optional;

public class DHCompatInternal {
	public static final DHCompatInternal SHADERLESS = new DHCompatInternal(null, false);
	private static final String VANILLA_DEPTH_PRIORITY_PROPERTY = "pauc.lod.shaderVanillaDepthPriority";
	static boolean dhEnabled;
	private static int guiScale = -1;
	private static boolean vanillaDepthPriorityLogged;
	private static boolean vanillaDepthPriorityFallbackLogged;
	private final IrisRenderingPipeline pipeline;
	private GlFramebuffer dhGenericFramebuffer;
	public boolean shouldOverrideShadow;
	public boolean shouldOverride;
	private IrisLodRenderProgram solidProgram;
	private IrisGenericRenderProgram genericShader;
	private IrisLodRenderProgram translucentProgram;
	private IrisLodRenderProgram shadowProgram;
	private GlFramebuffer dhTerrainFramebuffer;
	private DhFrameBufferWrapper dhTerrainFramebufferWrapper;
	private GlFramebuffer dhWaterFramebuffer;
	private GlFramebuffer dhShadowFramebuffer;
	private DhFrameBufferWrapper dhShadowFramebufferWrapper;
	private DepthTexture depthTexNoTranslucent;
	private boolean translucentDepthDirty;
	private int storedDepthTex;
	private boolean incompatible = false;
	private int cachedVersion;

	public DHCompatInternal(IrisRenderingPipeline pipeline, boolean dhShadowEnabled) {
		this.pipeline = pipeline;

		if (pipeline == null || !isDhRenderingEnabled()) {
			return;
		}

		boolean hasExplicitDhTerrainShader = pipeline.hasExplicitDHTerrainShader();
		Optional<ProgramSource> terrainSource = hasExplicitDhTerrainShader ? pipeline.getDHTerrainShader() : Optional.empty();
		boolean detectedRuntimeDhShader = terrainSource.isPresent();
		boolean detectedNativeDhShader = detectedRuntimeDhShader;
		boolean packBlocksSyntheticDhTerrainShader = PauCLodShaderContext.blocksSyntheticDhTerrainShader();
		boolean syntheticDhShaderAllowed = !detectedRuntimeDhShader
			&& !packBlocksSyntheticDhTerrainShader
			&& PauCLodShaderContext.shouldUseSyntheticDhTerrainShader();
		boolean syntheticDhTerrainShader = false;
		if (syntheticDhShaderAllowed) {
			if (terrainSource.isEmpty()) {
				terrainSource = pipeline.getTerrainShader();
			}
			syntheticDhTerrainShader = terrainSource.isPresent();
		}
		boolean cachedFallback = detectedNativeDhShader && PauCLodShaderContext.shouldForceFallbackForCurrentPack();
		boolean conservativeEmbeddedFallback = detectedNativeDhShader
			&& DHCompat.isUsingPauCEmbeddedBridgeOnly()
			&& PauCLodShaderContext.shouldUseConservativeEmbeddedShaderFallback();
		boolean hasNativeDhShader = detectedNativeDhShader && !cachedFallback && !conservativeEmbeddedFallback;
		boolean hasDhTerrainShader = hasNativeDhShader || syntheticDhTerrainShader;
		String compatibilityReason = hasNativeDhShader
			? "native-dh-terrain-shader"
			: syntheticDhTerrainShader
				? "synthetic-dh-terrain-shader"
				: conservativeEmbeddedFallback
					? "pauc-conservative-embedded-dh"
					: cachedFallback
					? "cached-missing-dh-shader"
					: packBlocksSyntheticDhTerrainShader
						? "missing-pack-dh-terrain-shader"
						: hasExplicitDhTerrainShader ? "missing-dh-shader" : "missing-dh-terrain-shader";
		PauCLodShaderContext.markDhShaderCompatibility(
			hasNativeDhShader,
			syntheticDhTerrainShader,
			compatibilityReason,
			!conservativeEmbeddedFallback
		);
		if (!hasDhTerrainShader) {
			if (conservativeEmbeddedFallback) {
				Iris.logger.warn("PauC is using the conservative embedded DH LOD path for this shaderpack so PauC controls fog, visibility, and the vanilla-to-LOD boundary.");
			} else if (cachedFallback) {
				Iris.logger.warn("PauC is keeping this shaderpack on the LOD fallback path because it was previously detected without native DH shaders.");
			} else {
				Iris.logger.warn("No usable DH terrain shader found in this pack; PauC will keep embedded LOD fallback rendering active.");
			}
			incompatible = true;
			return;
		}
		if (syntheticDhTerrainShader) {
			Iris.logger.info("PauC enabled the synthetic DH terrain shader path for this shaderpack.");
		}

		try {
			cachedVersion = ((Blaze3dRenderTargetExt) Minecraft.getInstance().getMainRenderTarget()).iris$getDepthBufferVersion();

			createDepthTex(Minecraft.getInstance().getMainRenderTarget().width, Minecraft.getInstance().getMainRenderTarget().height);
			translucentDepthDirty = true;

			ProgramSource terrain = terrainSource.orElseThrow(RuntimeException::new);
			solidProgram = IrisLodRenderProgram.createProgram(terrain.getName(), false, false, terrain, pipeline.getCustomUniforms(), pipeline);

			Optional<ProgramSource> genericSource = pipeline.getDHGenericShader();
			if (genericSource.isEmpty() && syntheticDhTerrainShader) {
				genericSource = pipeline.getBlockShader();
				if (genericSource.isEmpty()) {
					genericSource = terrainSource;
				}
			}
			ProgramSource generic = genericSource.orElse(terrain);
			genericShader = IrisGenericRenderProgram.createProgram(generic.getName() + "_g", false, false, generic, pipeline.getCustomUniforms(), pipeline);
			dhGenericFramebuffer = pipeline.createDHFramebuffer(generic, false);

			Optional<ProgramSource> waterSource = pipeline.getDHWaterShader();
			if (waterSource.isEmpty() && syntheticDhTerrainShader) {
				waterSource = pipeline.getWaterShader();
				if (waterSource.isEmpty()) {
					waterSource = terrainSource;
				}
			}
			waterSource.ifPresent(this::tryCreateTranslucentProgram);

			Optional<ProgramSource> shadowSource = pipeline.getDHShadowShader();
			if (shadowSource.isEmpty() && syntheticDhTerrainShader) {
				shadowSource = pipeline.getShadowTerrainShader();
			}
			shadowSource.ifPresent(shadow -> tryCreateShadowProgram(shadow, dhShadowEnabled));

			dhTerrainFramebuffer = pipeline.createDHFramebuffer(terrain, false);
			dhTerrainFramebufferWrapper = new DhFrameBufferWrapper(dhTerrainFramebuffer);

			if (translucentProgram == null) {
				translucentProgram = solidProgram;
			}

			shouldOverride = true;
		} catch (Throwable throwable) {
			abortNativeDhOverride("native-dh-shader-init-failed", throwable);
		}
	}

	private void tryCreateTranslucentProgram(ProgramSource water) {
		if (PauCLodShaderSafety.shouldSuppressShaderTransparentLodPass()) {
			return;
		}

		try {
			translucentProgram = IrisLodRenderProgram.createProgram(water.getName(), false, true, water, pipeline.getCustomUniforms(), pipeline);
			dhWaterFramebuffer = pipeline.createDHFramebuffer(water, true);
		} catch (Throwable throwable) {
			Iris.logger.warn("PauC disabled native DH water LOD pass for this shaderpack; native terrain LODs remain active.", throwable);
			if (translucentProgram != null && translucentProgram != solidProgram) {
				translucentProgram.free();
			}
			translucentProgram = null;
			dhWaterFramebuffer = null;
		}
	}

	private void tryCreateShadowProgram(ProgramSource shadow, boolean dhShadowEnabled) {
		if (!PauCLodShaderRuntime.shouldCreateNativeDhShadowProgram(dhShadowEnabled)
			|| PauCLodShaderSafety.shouldSuppressNativeDhShadowPass()) {
			shouldOverrideShadow = false;
			return;
		}

		try {
			shadowProgram = IrisLodRenderProgram.createProgram(shadow.getName(), true, false, shadow, pipeline.getCustomUniforms(), pipeline);
			if (pipeline.hasShadowRenderTargets()) {
				dhShadowFramebuffer = pipeline.createDHFramebufferShadow(shadow);
				dhShadowFramebufferWrapper = new DhFrameBufferWrapper(dhShadowFramebuffer);
			}
			shouldOverrideShadow = true;
		} catch (Throwable throwable) {
			Iris.logger.warn("PauC disabled native DH shadow LOD pass for this shaderpack; native terrain LODs remain active.", throwable);
			if (shadowProgram != null) {
				shadowProgram.free();
			}
			shadowProgram = null;
			dhShadowFramebuffer = null;
			dhShadowFramebufferWrapper = null;
			shouldOverrideShadow = false;
		}
	}

	public static int getDhBlockRenderDistance() {
		if (DhApi.Delayed.configs == null) {
			// Called before DH has finished setup
			return 0;
		}

		return DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue() * 16;
	}

	public static int getRenderDistance() {
		return getDhBlockRenderDistance();
	}

	public static float getFarPlane() {
		if (DhApi.Delayed.configs == null) {
			// Called before DH has finished setup
			return 0;
		}

		int lodChunkDist = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
		int lodBlockDist = lodChunkDist * 16;
		// sqrt 2 to prevent the corners from being cut off
		return (float) ((lodBlockDist + 512) * Math.sqrt(2));
	}

	public static float getNearPlane() {
		if (DhApi.Delayed.renderProxy == null) {
			// Called before DH has finished setup
			return 0;
		}

		return DhApi.Delayed.renderProxy.getNearClipPlaneDistanceInBlocks(CapturedRenderingState.INSTANCE.getRealTickDelta());
	}

	public static boolean checkFrame() {
		if (guiScale == -1) {
			guiScale = Minecraft.getInstance().options.guiScale().get();
		}

		boolean currentDhEnabled = isDhRenderingEnabled();
		if ((dhEnabled != currentDhEnabled || guiScale != Minecraft.getInstance().options.guiScale().get())
			&& IrisApi.getInstance().isShaderPackInUse()) {
			guiScale = Minecraft.getInstance().options.guiScale().get();
			dhEnabled = currentDhEnabled;
			try {
				Iris.reload();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}

		return dhEnabled;
	}

	private static boolean isDhRenderingEnabled() {
		if (DhApi.Delayed.configs == null) {
			return false;
		}

		return DhApi.Delayed.configs.graphics().renderingEnabled().getValue();
	}

	public boolean incompatiblePack() {
		return incompatible;
	}

	public void reconnectDHTextures(int depthTex) {
		if (((Blaze3dRenderTargetExt) Minecraft.getInstance().getMainRenderTarget()).iris$getDepthBufferVersion() != cachedVersion) {
			cachedVersion = ((Blaze3dRenderTargetExt) Minecraft.getInstance().getMainRenderTarget()).iris$getDepthBufferVersion();
			createDepthTex(Minecraft.getInstance().getMainRenderTarget().width, Minecraft.getInstance().getMainRenderTarget().height);
		}
		if (storedDepthTex != depthTex && dhTerrainFramebuffer != null) {
			storedDepthTex = depthTex;
			dhTerrainFramebuffer.addDepthAttachment(depthTex);
			if (dhWaterFramebuffer != null) {
				dhWaterFramebuffer.addDepthAttachment(depthTex);
			}
			if (dhGenericFramebuffer != null) {
				dhGenericFramebuffer.addDepthAttachment(depthTex);
			}
		}
	}

	public void createDepthTex(int width, int height) {
		if (depthTexNoTranslucent != null) {
			depthTexNoTranslucent.destroy();
			depthTexNoTranslucent = null;
		}

		translucentDepthDirty = true;

		depthTexNoTranslucent = new DepthTexture("DH depth tex", width, height, DepthBufferFormat.DEPTH32F);
	}

	public void clear() {
		IrisLodRenderProgram solidToFree = solidProgram;
		if (solidProgram != null) {
			solidProgram.free();
			solidProgram = null;
		}
		if (translucentProgram != null && translucentProgram != solidToFree) {
			translucentProgram.free();
		}
		translucentProgram = null;
		if (shadowProgram != null) {
			shadowProgram.free();
			shadowProgram = null;
		}
		shouldOverrideShadow = false;
		shouldOverride = false;
		dhTerrainFramebuffer = null;
		dhWaterFramebuffer = null;
		dhShadowFramebuffer = null;
		storedDepthTex = -1;
		translucentDepthDirty = true;

		OverrideInjector.INSTANCE.unbind(IDhApiFramebuffer.class, dhTerrainFramebufferWrapper);
		OverrideInjector.INSTANCE.unbind(IDhApiGenericObjectShaderProgram.class, genericShader);
		OverrideInjector.INSTANCE.unbind(IDhApiFramebuffer.class, dhShadowFramebufferWrapper);
		dhTerrainFramebufferWrapper = null;
		dhShadowFramebufferWrapper = null;
	}

	public void setModelPos(DhApiVec3f modelPos) {
		solidProgram.bind();
		solidProgram.setModelPos(modelPos);
		translucentProgram.bind();
		translucentProgram.setModelPos(modelPos);
		solidProgram.bind();
	}

	public IrisLodRenderProgram getSolidShader() {
		return solidProgram;
	}

	public GlFramebuffer getSolidFB() {
		return dhTerrainFramebuffer;
	}

	public DhFrameBufferWrapper getSolidFBWrapper() {
		return dhTerrainFramebufferWrapper;
	}

	public IrisLodRenderProgram getShadowShader() {
		return shadowProgram;
	}

	public GlFramebuffer getShadowFB() {
		return dhShadowFramebuffer;
	}

	public DhFrameBufferWrapper getShadowFBWrapper() {
		return dhShadowFramebufferWrapper;
	}

	public IrisLodRenderProgram getTranslucentShader() {
		if (translucentProgram == null) {
			return solidProgram;
		}
		return translucentProgram;
	}

	public boolean hasTranslucentOverride() {
		return dhWaterFramebuffer != null && translucentProgram != null && translucentProgram != solidProgram;
	}

	public int getStoredDepthTex() {
		return storedDepthTex;
	}

	public boolean copyVanillaDepthIntoDhDepth(int width, int height) {
		if (!readBoolean(VANILLA_DEPTH_PRIORITY_PROPERTY, false) || dhTerrainFramebuffer == null || storedDepthTex <= 0) {
			return false;
		}

		RenderTarget mainTarget = Minecraft.getInstance().getMainRenderTarget();
		int vanillaDepthTexture = mainTarget.getDepthTextureId();
		if (vanillaDepthTexture <= 0) {
			return false;
		}

		int copyWidth = width > 0 ? width : mainTarget.width;
		int copyHeight = height > 0 ? height : mainTarget.height;
		try {
			GL43C.glCopyImageSubData(
				vanillaDepthTexture,
				GL43C.GL_TEXTURE_2D,
				0,
				0,
				0,
				0,
				storedDepthTex,
				GL43C.GL_TEXTURE_2D,
				0,
				0,
				0,
				0,
				copyWidth,
				copyHeight,
				1
			);
			translucentDepthDirty = true;
			if (!vanillaDepthPriorityLogged) {
				vanillaDepthPriorityLogged = true;
				Iris.logger.info("PauC copied vanilla terrain depth into DH LOD depth so shader LODs stay behind vanilla chunks.");
			}
			return true;
		} catch (RuntimeException | LinkageError throwable) {
			if (!vanillaDepthPriorityFallbackLogged) {
				vanillaDepthPriorityFallbackLogged = true;
				Iris.logger.warn("PauC could not copy vanilla depth into DH LOD depth; falling back to DH depth clear.", throwable);
			}
			return false;
		}
	}

	public void copyTranslucents(int width, int height) {
		if (translucentDepthDirty) {
			translucentDepthDirty = false;
			RenderSystem.bindTexture(depthTexNoTranslucent.getTextureId());
			dhTerrainFramebuffer.bindAsReadBuffer();
			IrisRenderSystem.copyTexImage2D(GL20C.GL_TEXTURE_2D, 0, DepthBufferFormat.DEPTH32F.getGlInternalFormat(), 0, 0, width, height, 0);
		} else {
			DepthCopyStrategy.fastest(false).copy(dhTerrainFramebuffer, storedDepthTex, null, depthTexNoTranslucent.getTextureId(), width, height);
		}
	}

	public GlFramebuffer getTranslucentFB() {
		return dhWaterFramebuffer;
	}

	public GlFramebuffer getGenericFB() {
		return dhGenericFramebuffer;
	}

	public int getDepthTexNoTranslucent() {
		if (depthTexNoTranslucent == null) return 0;

		return depthTexNoTranslucent.getTextureId();
	}

	public IDhApiGenericObjectShaderProgram getGenericShader() {
		return genericShader;
	}

	public boolean avoidRenderingClouds() {
		if (pipeline == null) {
			return false;
		}

		boolean shaderPackWouldHideClouds = pipeline.getDHCloudSetting() == CloudSetting.OFF
			|| (pipeline.getDHCloudSetting() == CloudSetting.DEFAULT && pipeline.getCloudSetting() == CloudSetting.OFF);
		return shaderPackWouldHideClouds && !PauCLodShaderRuntime.shouldKeepPauCLodCloudsVisible();
	}

	private void abortNativeDhOverride(String reason, Throwable throwable) {
		Iris.logger.warn("PauC disabled native DH shader override for this shaderpack and will use the conservative LOD path: " + reason, throwable);
		PauCLodShaderContext.markDhShaderRuntimeFallback(reason);
		incompatible = true;
		shouldOverride = false;
		shouldOverrideShadow = false;
		freeCreatedPrograms();
		dhTerrainFramebuffer = null;
		dhWaterFramebuffer = null;
		dhShadowFramebuffer = null;
		dhGenericFramebuffer = null;
		dhTerrainFramebufferWrapper = null;
		dhShadowFramebufferWrapper = null;
		translucentDepthDirty = true;
	}

	private void freeCreatedPrograms() {
		IrisLodRenderProgram solidToFree = solidProgram;
		if (solidProgram != null) {
			solidProgram.free();
			solidProgram = null;
		}
		if (translucentProgram != null && translucentProgram != solidToFree) {
			translucentProgram.free();
		}
		translucentProgram = null;
		if (shadowProgram != null) {
			shadowProgram.free();
			shadowProgram = null;
		}
		if (genericShader != null) {
			genericShader.free();
			genericShader = null;
		}
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String rawValue = System.getProperty(key);
		return rawValue == null ? fallback : Boolean.parseBoolean(rawValue);
	}
}
