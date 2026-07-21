package fr.hoyatla.pauc.lodengine;

import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.PauCTunables;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import org.slf4j.Logger;

/**
 * Locates DISTANT STRUCTURES from the seed WITHOUT generating chunks (singleplayer only).
 *
 * <p>Structure placement is deterministic: {@link StructurePlacement#isStructureChunk} is pure RNG over
 * seed + chunk coords, so we can ask "does a village/temple/monument start here?" for chunks the client
 * has never loaded — cheaply, no worldgen (unlike DH). Runs on the client tick reading the integrated
 * server read-only (same pattern as {@link PauCSurfaceSampler}), budgeted. Results go to
 * {@link PauCStructureMarkerStore}; {@link PauCStructureLodRenderer} draws a blocky archetype at each so
 * a structure is visible from far at its real spot BEFORE it is ever visited.</p>
 */
public final class PauCDistantStructureLocator {
	private static final Logger LOGGER = LogUtils.getLogger();
	private static final String ENABLED_PROPERTY = "pauc.lodengine.distantStructures";
	private static final String BUDGET_PROPERTY = "pauc.lodengine.structureChunksPerTick";

	private static int cursorChunkX;
	private static int cursorChunkZ;
	private static int spiralLeg;
	private static int spiralStep;
	private static String activeDimension = "";
	private static boolean loggedOnce;
	private static int tickCounter;

	private PauCDistantStructureLocator() {
	}

	public static void onClientTick() {
		if (!PauCTunables.readBoolean(ENABLED_PROPERTY, true)) {
			return;
		}
		if ((++tickCounter & 3) != 0) {
			return; // 5 Hz is plenty for distant-structure discovery; spares the main thread
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			return;
		}
		IntegratedServer server = minecraft.getSingleplayerServer();
		if (server == null) {
			return; // multiplayer: no seed access, structures need a visit (persistence handles those)
		}
		ClientLevel level = minecraft.level;
		ServerLevel serverLevel = server.getLevel(level.dimension());
		if (serverLevel == null || serverLevel.dimensionType().hasCeiling()) {
			return; // Nether LOD is off; getBaseHeight is meaningless under a ceiling
		}
		String dimension = level.dimension().location().toString();
		if (!activeDimension.equals(dimension)) {
			activeDimension = dimension;
			resetSpiral(minecraft);
		}

		ChunkGenerator generator = serverLevel.getChunkSource().getGenerator();
		RandomState randomState = serverLevel.getChunkSource().randomState();
		var structureSets = serverLevel.registryAccess().registryOrThrow(Registries.STRUCTURE_SET);

		int vanillaChunks = minecraft.options.getEffectiveRenderDistance();
		int minRing = vanillaChunks; // inside this, real chunks / sampling own it; renderer skips loaded ones
		int maxRing = PauCSurfaceWitnessRenderer.lodRadiusChunks(vanillaChunks);
		if (maxRing <= 0) {
			return;
		}
		int budget = readBudget();
		int playerChunkX = minecraft.player.chunkPosition().x;
		int playerChunkZ = minecraft.player.chunkPosition().z;

		int scanned = 0;
		int guard = 0;
		int maxGuard = budget * 32;
		while (scanned < budget && guard < maxGuard) {
			guard++;
			int dcx = cursorChunkX - playerChunkX;
			int dcz = cursorChunkZ - playerChunkZ;
			int cheb = Math.max(Math.abs(dcx), Math.abs(dcz));
			advanceSpiral(minecraft);
			if (cheb < minRing || cheb > maxRing) {
				continue; // only the ring beyond vanilla, out to the LOD radius
			}
			scanned++;
			long chunkKey = net.minecraft.world.level.ChunkPos.asLong(cursorChunkX, cursorChunkZ);
			if (PauCStructureMarkerStore.hasChunk(dimension, chunkKey)) {
				continue;
			}
			int archetype = locateAt(structureSets, generator, randomState, serverLevel,
				cursorChunkX, cursorChunkZ);
			if (archetype < 0) {
				continue;
			}
			int worldX = (cursorChunkX << 4) + 8;
			int worldZ = (cursorChunkZ << 4) + 8;
			int groundY = generator.getBaseHeight(worldX, worldZ, Heightmap.Types.WORLD_SURFACE_WG, serverLevel, randomState);
			PauCStructureMarkerStore.put(dimension, chunkKey,
				new PauCStructureMarkerStore.Marker(worldX, worldZ, groundY, archetype));
			if (!loggedOnce) {
				loggedOnce = true;
				LOGGER.info("PauC distant structures: first marker at chunk {},{} (archetype {}).",
					cursorChunkX, cursorChunkZ, archetype);
			}
		}
	}

	/** @return the archetype for a structure starting at this chunk, or -1 if none (or hidden/underground). */
	private static int locateAt(net.minecraft.core.Registry<StructureSet> sets,
			ChunkGenerator generator, RandomState randomState, ServerLevel serverLevel, int cx, int cz) {
		var state = serverLevel.getChunkSource().getGeneratorState();
		for (StructureSet set : sets) {
			StructurePlacement placement = set.placement();
			if (!(placement instanceof net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement)) {
				continue;
			}
			if (!placement.isStructureChunk(state, cx, cz)) {
				continue;
			}
			// Placement proposes the chunk; a structure only generates if the biome also matches. Check the
			// noise biome at the spot so we do not stamp markers where nothing would actually spawn.
			Holder<Biome> biome = generator.getBiomeSource().getNoiseBiome(
				net.minecraft.core.QuartPos.fromBlock((cx << 4) + 8),
				net.minecraft.core.QuartPos.fromBlock(generator.getSeaLevel()),
				net.minecraft.core.QuartPos.fromBlock((cz << 4) + 8),
				randomState.sampler());
			for (StructureSet.StructureSelectionEntry entry : set.structures()) {
				Structure structure = entry.structure().value();
				if (!structure.biomes().contains(biome)) {
					continue;
				}
				int arch = archetypeFor(entry.structure());
				if (arch >= 0) {
					return arch;
				}
			}
		}
		return -1;
	}

	private static int archetypeFor(Holder<Structure> holder) {
		ResourceLocation id = holder.unwrapKey().map(k -> k.location()).orElse(null);
		if (id == null) {
			return -1; // unknown structure: NO gray placeholder boxes (ugly scattered cubes)
		}
		String p = id.getPath();
		// Hidden / underground / unsupported: no surface silhouette.
		if (p.contains("stronghold") || p.contains("mineshaft") || p.contains("ancient_city")
			|| p.contains("buried_treasure") || p.contains("nether_fossil") || p.contains("dungeon")) {
			return -1;
		}
		if (p.contains("village")) {
			return PauCStructureMarkerStore.ARCH_VILLAGE;
		}
		if (p.contains("desert_pyramid") || p.contains("desert_temple")) {
			return PauCStructureMarkerStore.ARCH_DESERT_PYRAMID;
		}
		if (p.contains("jungle")) {
			return PauCStructureMarkerStore.ARCH_JUNGLE_TEMPLE;
		}
		if (p.contains("pillager_outpost") || p.contains("outpost")) {
			return PauCStructureMarkerStore.ARCH_OUTPOST;
		}
		if (p.contains("monument")) {
			return PauCStructureMarkerStore.ARCH_MONUMENT;
		}
		if (p.contains("mansion")) {
			return PauCStructureMarkerStore.ARCH_MANSION;
		}
		if (p.contains("swamp_hut") || p.contains("witch")) {
			return PauCStructureMarkerStore.ARCH_WITCH_HUT;
		}
		if (p.contains("igloo")) {
			return PauCStructureMarkerStore.ARCH_IGLOO;
		}
		if (p.contains("ruined_portal")) {
			return PauCStructureMarkerStore.ARCH_RUINED_PORTAL;
		}
		if (p.contains("shipwreck")) {
			return PauCStructureMarkerStore.ARCH_SHIPWRECK;
		}
		if (p.contains("ocean_ruin")) {
			return PauCStructureMarkerStore.ARCH_OCEAN_RUIN;
		}
		if (p.contains("fortress")) {
			return PauCStructureMarkerStore.ARCH_NETHER_FORTRESS;
		}
		if (p.contains("bastion")) {
			return PauCStructureMarkerStore.ARCH_BASTION;
		}
		if (p.contains("trail_ruins")) {
			return PauCStructureMarkerStore.ARCH_TRAIL_RUINS;
		}
		return -1; // unknown structure: NO gray placeholder boxes (ugly scattered cubes)
	}

	private static int readBudget() {
		String raw = PauCTunables.raw(BUDGET_PROPERTY);
		if (raw == null) {
			return 24;
		}
		try {
			return Math.max(4, Math.min(512, Integer.parseInt(raw.trim())));
		} catch (NumberFormatException ignored) {
			return 24;
		}
	}

	private static void resetSpiral(Minecraft minecraft) {
		cursorChunkX = minecraft.player.chunkPosition().x;
		cursorChunkZ = minecraft.player.chunkPosition().z;
		spiralLeg = 0;
		spiralStep = 0;
	}

	private static void advanceSpiral(Minecraft minecraft) {
		int px = minecraft.player.chunkPosition().x;
		int pz = minecraft.player.chunkPosition().z;
		if (Math.abs(cursorChunkX - px) > 128 || Math.abs(cursorChunkZ - pz) > 128) {
			resetSpiral(minecraft);
			return;
		}
		int leg = spiralLeg / 2 + 1;
		switch (spiralLeg & 3) {
			case 0 -> cursorChunkX++;
			case 1 -> cursorChunkZ++;
			case 2 -> cursorChunkX--;
			case 3 -> cursorChunkZ--;
		}
		spiralStep++;
		if (spiralStep >= leg) {
			spiralStep = 0;
			spiralLeg++;
			if (spiralLeg >= 512) {
				resetSpiral(minecraft);
			}
		}
	}

	public static void reset() {
		activeDimension = "";
		spiralLeg = 0;
		spiralStep = 0;
		PauCStructureMarkerStore.clear();
	}
}
