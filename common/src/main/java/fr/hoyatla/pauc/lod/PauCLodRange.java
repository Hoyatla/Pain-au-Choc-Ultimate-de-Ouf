package fr.hoyatla.pauc.lod;

public record PauCLodRange(
	boolean enabled,
	int vanillaRenderDistanceChunks,
	int lodStartChunk,
	int lodEndChunk
) {
	public static final int DEFAULT_TARGET_DISTANCE_CHUNKS = 32;
	public static final int MIN_RENDER_DISTANCE_CHUNKS = 2;
	public static final int MAX_TARGET_DISTANCE_CHUNKS = 96;

	public static PauCLodRange disabled(int vanillaRenderDistanceChunks, int targetDistanceChunks) {
		int vanilla = sanitizeVanillaDistance(vanillaRenderDistanceChunks);
		int targetExtra = sanitizeTargetDistance(targetDistanceChunks);
		return new PauCLodRange(false, vanilla, vanilla + 1, vanilla + targetExtra);
	}

	public static PauCLodRange fromVanillaDistance(int vanillaRenderDistanceChunks, int targetDistanceChunks, boolean enabled) {
		int vanilla = sanitizeVanillaDistance(vanillaRenderDistanceChunks);
		int target = vanilla + sanitizeTargetDistance(targetDistanceChunks);
		int start = vanilla + 1;
		return new PauCLodRange(enabled, vanilla, start, target);
	}

	public int lodRadiusChunks() {
		return enabled ? Math.max(0, lodEndChunk - lodStartChunk + 1) : 0;
	}

	public int configuredExtraDistanceChunks() {
		return Math.max(0, lodEndChunk - vanillaRenderDistanceChunks);
	}

	public int filledSquareCornerDistanceChunks() {
		return filledSquareCornerDistanceChunks(lodEndChunk);
	}

	public int roundHorizonEndChunk() {
		return filledSquareCornerDistanceChunks();
	}

	public boolean containsChebyshevDistance(int distanceChunks) {
		return enabled && distanceChunks >= lodStartChunk && distanceChunks <= lodEndChunk;
	}

	public boolean containsRadialDistance(double distanceChunks) {
		return enabled && distanceChunks >= lodStartChunk && distanceChunks <= lodEndChunk;
	}

	public boolean containsRoundHorizonDistance(double distanceChunks) {
		return enabled && distanceChunks >= lodStartChunk && distanceChunks <= roundHorizonEndChunk();
	}

	public boolean containsFilledSquareOffset(int deltaChunkX, int deltaChunkZ) {
		return containsChebyshevDistance(chebyshevDistanceChunks(deltaChunkX, deltaChunkZ));
	}

	public boolean containsRadialOffset(int deltaChunkX, int deltaChunkZ) {
		return containsRadialDistance(radialDistanceChunks(deltaChunkX, deltaChunkZ));
	}

	public boolean containsRoundHorizonOffset(int deltaChunkX, int deltaChunkZ) {
		return containsRoundHorizonDistance(radialDistanceChunks(deltaChunkX, deltaChunkZ));
	}

	public int detailLevelForDistance(int distanceChunks) {
		if (!containsChebyshevDistance(distanceChunks)) {
			return 0;
		}

		int lodDistance = distanceChunks - lodStartChunk;
		if (lodDistance < 4) {
			return 1;
		}
		if (lodDistance < 10) {
			return 2;
		}
		if (lodDistance < 18) {
			return 3;
		}
		return 4;
	}

	public int detailLevelForRadialDistance(double distanceChunks) {
		if (!containsRadialDistance(distanceChunks)) {
			return enabled && distanceChunks > lodEndChunk && distanceChunks <= roundHorizonEndChunk() ? 4 : 0;
		}

		double lodDistance = distanceChunks - lodStartChunk;
		if (lodDistance < 4.0D) {
			return 1;
		}
		if (lodDistance < 10.0D) {
			return 2;
		}
		if (lodDistance < 18.0D) {
			return 3;
		}
		return 4;
	}

	public String describe() {
		return "lodRange[enabled="
			+ enabled
			+ ", vanilla=1-"
			+ vanillaRenderDistanceChunks
			+ ", lod="
			+ (enabled ? lodStartChunk + "-" + lodEndChunk : "inactive")
			+ ", target="
			+ configuredExtraDistanceChunks()
			+ ", radius="
			+ lodRadiusChunks()
			+ ", absoluteTarget="
			+ lodEndChunk
			+ ", radialTarget="
			+ lodEndChunk
			+ ", roundHorizon="
			+ roundHorizonEndChunk()
			+ "]";
	}

	public static int chebyshevDistanceChunks(int deltaChunkX, int deltaChunkZ) {
		return Math.max(Math.abs(deltaChunkX), Math.abs(deltaChunkZ));
	}

	public static double radialDistanceChunks(int deltaChunkX, int deltaChunkZ) {
		return Math.sqrt((double) deltaChunkX * deltaChunkX + (double) deltaChunkZ * deltaChunkZ);
	}

	public static int filledSquareCornerDistanceChunks(int squareRadiusChunks) {
		return (int) Math.ceil(Math.max(0, squareRadiusChunks) * Math.sqrt(2.0D));
	}

	private static int sanitizeVanillaDistance(int vanillaRenderDistanceChunks) {
		return Math.max(MIN_RENDER_DISTANCE_CHUNKS, vanillaRenderDistanceChunks);
	}

	private static int sanitizeTargetDistance(int targetDistanceChunks) {
		return Math.max(MIN_RENDER_DISTANCE_CHUNKS, Math.min(MAX_TARGET_DISTANCE_CHUNKS, targetDistanceChunks));
	}
}
