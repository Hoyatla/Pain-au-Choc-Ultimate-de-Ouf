package fr.hoyatla.pauc.shadow;

/**
 * PauC shadow quality gauge — the 4-notch video-settings control (OFF / basic / medium / high).
 * Quality scales the heightfield ray-march: more steps and a longer reach give crisper, longer
 * shadows; strength is the multiply factor applied to shadowed pixels (never full black).
 */
public enum PauCShadowMode {
	OFF("off", 0, 0.0F, 1.0F),
	BASIC("basic", 12, 160.0F, 0.62F),
	MEDIUM("medium", 24, 256.0F, 0.58F),
	HIGH("high", 40, 384.0F, 0.55F);

	private final String id;
	private final int marchSteps;
	private final float reachBlocks;
	private final float strength;

	PauCShadowMode(String id, int marchSteps, float reachBlocks, float strength) {
		this.id = id;
		this.marchSteps = marchSteps;
		this.reachBlocks = reachBlocks;
		this.strength = strength;
	}

	public String id() {
		return id;
	}

	public int index() {
		return ordinal();
	}

	public int marchSteps() {
		return marchSteps;
	}

	public float reachBlocks() {
		return reachBlocks;
	}

	public float strength() {
		return strength;
	}

	public static PauCShadowMode byIndex(int index) {
		PauCShadowMode[] values = values();
		return values[Math.max(0, Math.min(values.length - 1, index))];
	}

	public static PauCShadowMode byId(String id) {
		for (PauCShadowMode mode : values()) {
			if (mode.id.equalsIgnoreCase(id)) {
				return mode;
			}
		}
		return OFF;
	}
}
