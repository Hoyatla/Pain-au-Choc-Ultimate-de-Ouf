package fr.hoyatla.pauc.lod;

import java.util.Locale;

public enum PauCDynamicResolutionMode {
	OFF(0, "off", 1.0D, 0.0D, 0.0D),
	QUALITY(1, "quality", 0.85D, 0.035D, 0.030D),
	BALANCED(2, "balanced", 0.75D, 0.050D, 0.025D),
	PERFORMANCE(3, "performance", 0.65D, 0.070D, 0.020D);

	private final int index;
	private final String id;
	private final double minScale;
	private final double downRatePerSecond;
	private final double upRatePerSecond;

	PauCDynamicResolutionMode(int index, String id, double minScale, double downRatePerSecond, double upRatePerSecond) {
		this.index = index;
		this.id = id;
		this.minScale = minScale;
		this.downRatePerSecond = downRatePerSecond;
		this.upRatePerSecond = upRatePerSecond;
	}

	public int index() {
		return index;
	}

	public String id() {
		return id;
	}

	public double minScale() {
		return minScale;
	}

	public double downRatePerSecond() {
		return downRatePerSecond;
	}

	public double upRatePerSecond() {
		return upRatePerSecond;
	}

	public static PauCDynamicResolutionMode byIndex(int index) {
		for (PauCDynamicResolutionMode mode : values()) {
			if (mode.index == index) {
				return mode;
			}
		}
		return OFF;
	}

	public static PauCDynamicResolutionMode byId(String id) {
		if (id == null || id.isBlank()) {
			return OFF;
		}
		String normalized = id.trim().toLowerCase(Locale.ROOT).replace('_', '-');
		for (PauCDynamicResolutionMode mode : values()) {
			if (mode.id.equals(normalized)) {
				return mode;
			}
		}
		return OFF;
	}
}
