package net.irisshaders.iris.apiimpl;

import fr.hoyatla.pauc.shader.PauCShaders;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.api.v0.IrisApi;
import net.irisshaders.iris.api.v0.IrisApiConfig;
import net.irisshaders.iris.api.v0.IrisTextVertexSink;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.vertices.IrisTextVertexSinkImpl;
import net.minecraft.client.gui.screens.Screen;

import java.nio.ByteBuffer;
import java.util.function.IntFunction;

public class IrisApiV0Impl implements IrisApi {
	public static final IrisApiV0Impl INSTANCE = new IrisApiV0Impl();
	private static final IrisApiV0ConfigImpl CONFIG = new IrisApiV0ConfigImpl();

	@Override
	public int getMinorApiRevision() {
		return 2;
	}

	@Override
	public boolean isShaderPackInUse() {
		return PauCShaders.isShaderPackInUse();
	}

	@Override
	public boolean isRenderingShadowPass() {
		return ShadowRenderingState.areShadowsCurrentlyBeingRendered();
	}

	@Override
	public Object openMainIrisScreenObj(Object parent) {
		return PauCShaders.createShaderConfigScreen((Screen) parent);
	}

	@Override
	public String getMainScreenLanguageKey() {
		return PauCShaders.mainScreenLanguageKey();
	}

	@Override
	public IrisApiConfig getConfig() {
		return CONFIG;
	}

	@Override
	public IrisTextVertexSink createTextVertexSink(int maxQuadCount, IntFunction<ByteBuffer> bufferProvider) {
		return new IrisTextVertexSinkImpl(maxQuadCount, bufferProvider);
	}

	@Override
	public float getSunPathRotation() {
		WorldRenderingPipeline pipeline = Iris.getPipelineManager().getPipelineNullable();

		if (pipeline == null) {
			return 0;
		}

		return pipeline.getSunPathRotation();
	}
}
