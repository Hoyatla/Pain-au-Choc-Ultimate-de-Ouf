package fr.hoyatla.pauc.platform.forge.worldgen;

public enum FarChunkPlacementMode {
	DEFER(false, false),
	DEFER_AND_SUCCEED(true, false),
	FORCE_LOAD_AND_SUCCEED(true, true);

	private final boolean fakeSuccess;
	private final boolean forceLoad;

	FarChunkPlacementMode(boolean fakeSuccess, boolean forceLoad) {
		this.fakeSuccess = fakeSuccess;
		this.forceLoad = forceLoad;
	}

	public boolean shouldReportSuccess() {
		return fakeSuccess;
	}

	public boolean shouldForceLoad() {
		return forceLoad;
	}
}
