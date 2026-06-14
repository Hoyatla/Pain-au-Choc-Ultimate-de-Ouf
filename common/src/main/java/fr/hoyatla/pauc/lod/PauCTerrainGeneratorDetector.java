package fr.hoyatla.pauc.lod;

import fr.hoyatla.pauc.platform.PauCPlatformServices;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;

import java.util.Locale;

public final class PauCTerrainGeneratorDetector {
	private PauCTerrainGeneratorDetector() {
	}

	public static GeneratorKind currentClientKind() {
		PauCPlatformServices platform = PauCPlatformServices.getInstance();
		if (platform.isModLoaded("tectonic")) {
			return GeneratorKind.TECTONIC;
		}
		if (platform.isModLoaded("terralith")) {
			return GeneratorKind.TERRALITH;
		}
		if (platform.isModLoaded("continents")) {
			return GeneratorKind.CONTINENTS;
		}
		if (platform.isModLoaded("stratospheric")) {
			return GeneratorKind.STRATOSPHERIC;
		}
		if (platform.isModLoaded("wilderwild") || platform.isModLoaded("wilder_wild") || platform.isModLoaded("william_wythers")) {
			return GeneratorKind.WILDER_WILDS;
		}
		if (platform.isModLoaded("nullscape") && isEndDimension()) {
			return GeneratorKind.NULLSCAPE;
		}
		if (platform.isModLoaded("biomesoplenty")) {
			return GeneratorKind.BOP;
		}
		if (platform.isModLoaded("byg")) {
			return GeneratorKind.BYG;
		}
		return GeneratorKind.VANILLA;
	}

	public static ModpackClass currentModpackClass() {
		String override = System.getProperty("pauc.client.modpackClass");
		if (override != null && !override.isBlank()) {
			try {
				return ModpackClass.valueOf(override.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
			} catch (IllegalArgumentException ignored) {
			}
		}

		PauCPlatformServices platform = PauCPlatformServices.getInstance();
		int modCount = loadedModCount(platform);
		boolean heavyContent = hasHeavyContentMods(platform);
		if (modCount >= readInt("pauc.client.modpackExtremeCount", 260, 1, 1000)) {
			return ModpackClass.EXTREME;
		}
		if (modCount >= readInt("pauc.client.modpackHeavyCount", 160, 1, 1000) || (heavyContent && modCount >= 120)) {
			return ModpackClass.HEAVY;
		}
		if (modCount >= readInt("pauc.client.modpackMediumCount", 80, 1, 1000) || heavyContent) {
			return ModpackClass.MEDIUM;
		}
		return ModpackClass.LIGHT;
	}

	public static String describeCurrentClientContext() {
		GeneratorKind terrain = currentClientKind();
		ModpackClass modpack = currentModpackClass();
		return "terrainProfile[generator="
			+ terrain.id()
			+ ", verticalRelief="
			+ terrain.complexVerticalRelief()
			+ ", biomeTransitions="
			+ terrain.wideBiomeTransitions()
			+ ", modpack="
			+ modpack.id()
			+ ", modCount="
			+ loadedModCount(PauCPlatformServices.getInstance())
			+ "]";
	}

	private static boolean isEndDimension() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft != null
			&& minecraft.level != null
			&& minecraft.level.dimension() == Level.END;
	}

	private static int loadedModCount(PauCPlatformServices platform) {
		try {
			return Math.max(0, platform.loadedModCount());
		} catch (RuntimeException | LinkageError ignored) {
			return 0;
		}
	}

	private static boolean hasHeavyContentMods(PauCPlatformServices platform) {
		return platform.isModLoaded("create")
			|| platform.isModLoaded("alexsmobs")
			|| platform.isModLoaded("iceandfire")
			|| platform.isModLoaded("mowziesmobs")
			|| platform.isModLoaded("born_in_chaos_v1")
			|| platform.isModLoaded("zombieawareness")
			|| platform.isModLoaded("enhancedai")
			|| platform.isModLoaded("epicfight")
			|| platform.isModLoaded("valhelsia_structures")
			|| platform.isModLoaded("when_dungeons_arise");
	}

	private static int readInt(String key, int fallback, int min, int max) {
		String rawValue = System.getProperty(key);
		if (rawValue == null) {
			return Math.max(min, Math.min(max, fallback));
		}
		try {
			return Math.max(min, Math.min(max, Integer.parseInt(rawValue.trim())));
		} catch (NumberFormatException ignored) {
			return Math.max(min, Math.min(max, fallback));
		}
	}

	public enum GeneratorKind {
		VANILLA("vanilla", false, false, 0, 0),
		TECTONIC("tectonic", true, false, 24, 2),
		TERRALITH("terralith", false, true, 16, 2),
		CONTINENTS("continents", false, true, 16, 2),
		STRATOSPHERIC("stratospheric", true, false, 20, 2),
		WILDER_WILDS("wilder_wilds", true, false, 16, 1),
		NULLSCAPE("nullscape", false, false, 8, 0),
		BOP("bop", false, true, 12, 1),
		BYG("byg", false, true, 12, 1);

		private final String id;
		private final boolean complexVerticalRelief;
		private final boolean wideBiomeTransitions;
		private final int generationRateBoost;
		private final int retentionMarginBoost;

		GeneratorKind(String id, boolean complexVerticalRelief, boolean wideBiomeTransitions, int generationRateBoost, int retentionMarginBoost) {
			this.id = id;
			this.complexVerticalRelief = complexVerticalRelief;
			this.wideBiomeTransitions = wideBiomeTransitions;
			this.generationRateBoost = generationRateBoost;
			this.retentionMarginBoost = retentionMarginBoost;
		}

		public String id() {
			return id;
		}

		public boolean complexVerticalRelief() {
			return complexVerticalRelief;
		}

		public boolean wideBiomeTransitions() {
			return wideBiomeTransitions;
		}

		public int generationRateBoost() {
			return generationRateBoost;
		}

		public int retentionMarginBoost() {
			return retentionMarginBoost;
		}
	}

	public enum ModpackClass {
		LIGHT("light", 0.15D, 0, 0, 0),
		MEDIUM("medium", 0.18D, 256, 16, 1),
		HEAVY("heavy", 0.22D, 512, 32, 2),
		EXTREME("extreme", 0.25D, 768, 48, 3);

		private final String id;
		private final double heapBudgetShare;
		private final int memoryBoostMb;
		private final int generationRateBoost;
		private final int retentionMarginBoost;

		ModpackClass(String id, double heapBudgetShare, int memoryBoostMb, int generationRateBoost, int retentionMarginBoost) {
			this.id = id;
			this.heapBudgetShare = heapBudgetShare;
			this.memoryBoostMb = memoryBoostMb;
			this.generationRateBoost = generationRateBoost;
			this.retentionMarginBoost = retentionMarginBoost;
		}

		public String id() {
			return id;
		}

		public double heapBudgetShare() {
			return heapBudgetShare;
		}

		public int memoryBoostMb() {
			return memoryBoostMb;
		}

		public int generationRateBoost() {
			return generationRateBoost;
		}

		public int retentionMarginBoost() {
			return retentionMarginBoost;
		}
	}
}
