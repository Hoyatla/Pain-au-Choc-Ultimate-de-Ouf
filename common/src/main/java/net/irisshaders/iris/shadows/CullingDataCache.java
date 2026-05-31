package net.irisshaders.iris.shadows;

public interface CullingDataCache {
	void saveState();

	void restoreState();

	void useMainCameraChunksIfShadowSetupFailed();
}
