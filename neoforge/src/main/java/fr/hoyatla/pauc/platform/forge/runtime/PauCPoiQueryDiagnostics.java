package fr.hoyatla.pauc.platform.forge.runtime;

public final class PauCPoiQueryDiagnostics {
	private static long sectionsToVillageCalls;
	private static long sectionsToVillageHits;
	private static long sectionsToVillageStores;
	private static long invalidations;

	private PauCPoiQueryDiagnostics() {
	}

	public static void recordSectionsToVillageCall() {
		sectionsToVillageCalls++;
	}

	public static void recordSectionsToVillageHit() {
		sectionsToVillageHits++;
	}

	public static void recordSectionsToVillageStore() {
		sectionsToVillageStores++;
	}

	public static void recordInvalidation() {
		invalidations++;
	}

	public static String describeState() {
		return "poiCache[sectionsToVillage="
			+ sectionsToVillageHits
			+ "/"
			+ sectionsToVillageCalls
			+ ", stores="
			+ sectionsToVillageStores
			+ ", invalidations="
			+ invalidations
			+ "]";
	}

	public static void onServerStopped() {
		sectionsToVillageCalls = 0L;
		sectionsToVillageHits = 0L;
		sectionsToVillageStores = 0L;
		invalidations = 0L;
	}
}
