package fr.hoyatla.pauc.mixin.forge.compat;

import fr.hoyatla.pauc.platform.forge.runtime.PauCPoiQueryDiagnostics;
import fr.hoyatla.pauc.platform.forge.runtime.PauCRuntimeSwitches;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.SectionPos;
import net.minecraft.world.entity.ai.village.poi.PoiManager;
import net.minecraft.world.entity.ai.village.poi.PoiType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Mixin(PoiManager.class)
public abstract class MixinPoiManagerQueryCache {
	@Unique
	private final Map<Long, Integer> pauc$sectionsToVillageCache = new ConcurrentHashMap<>();
	@Unique
	private final Map<Long, Long> pauc$sectionsToVillageCacheTimes = new ConcurrentHashMap<>();
	@Unique
	private long pauc$lastSectionsToVillagePruneAtMs;

	@Inject(method = "sectionsToVillage", at = @At("HEAD"), cancellable = true)
	private void pauc$reuseRecentSectionsToVillage(SectionPos sectionPos, CallbackInfoReturnable<Integer> cir) {
		if (!PauCRuntimeSwitches.enabled("poiQueryCache.enabled", true)) {
			return;
		}

		PauCPoiQueryDiagnostics.recordSectionsToVillageCall();
		long now = System.currentTimeMillis();
		pauc$pruneSectionsToVillageCache(now);
		long key = sectionPos.asLong();
		Integer cached = this.pauc$sectionsToVillageCache.get(key);
		Long createdAtMs = this.pauc$sectionsToVillageCacheTimes.get(key);
		if (cached == null || createdAtMs == null) {
			return;
		}

		if (now - createdAtMs > pauc$sectionsToVillageTtlMs()) {
			this.pauc$sectionsToVillageCache.remove(key);
			this.pauc$sectionsToVillageCacheTimes.remove(key);
			return;
		}

		PauCPoiQueryDiagnostics.recordSectionsToVillageHit();
		cir.setReturnValue(cached);
	}

	@Inject(method = "sectionsToVillage", at = @At("RETURN"))
	private void pauc$rememberSectionsToVillage(SectionPos sectionPos, CallbackInfoReturnable<Integer> cir) {
		if (!PauCRuntimeSwitches.enabled("poiQueryCache.enabled", true)) {
			return;
		}

		int maxEntries = PauCRuntimeSwitches.readInt("poiQueryCache.maxEntries", 4096, 128, 65536);
		if (this.pauc$sectionsToVillageCache.size() >= maxEntries) {
			return;
		}

		long key = sectionPos.asLong();
		this.pauc$sectionsToVillageCache.put(key, cir.getReturnValue());
		this.pauc$sectionsToVillageCacheTimes.put(key, System.currentTimeMillis());
		PauCPoiQueryDiagnostics.recordSectionsToVillageStore();
	}

	@Inject(method = "add", at = @At("HEAD"))
	private void pauc$invalidateAfterPoiAdd(BlockPos pos, Holder<PoiType> poiType, CallbackInfo ci) {
		pauc$clearSectionsToVillageCache();
	}

	@Inject(method = "remove", at = @At("HEAD"))
	private void pauc$invalidateAfterPoiRemove(BlockPos pos, CallbackInfo ci) {
		pauc$clearSectionsToVillageCache();
	}

	@Inject(method = "release", at = @At("HEAD"))
	private void pauc$invalidateAfterPoiRelease(BlockPos pos, CallbackInfoReturnable<Boolean> cir) {
		pauc$clearSectionsToVillageCache();
	}

	@Inject(method = "setDirty", at = @At("HEAD"))
	private void pauc$invalidateAfterPoiDirty(long section, CallbackInfo ci) {
		pauc$clearSectionsToVillageCache();
	}

	@Unique
	private void pauc$pruneSectionsToVillageCache(long now) {
		long ttlMs = pauc$sectionsToVillageTtlMs();
		if (now - this.pauc$lastSectionsToVillagePruneAtMs < ttlMs) {
			return;
		}

		this.pauc$lastSectionsToVillagePruneAtMs = now;
		this.pauc$sectionsToVillageCacheTimes.entrySet().removeIf(entry -> {
			boolean expired = now - entry.getValue() > ttlMs;
			if (expired) {
				this.pauc$sectionsToVillageCache.remove(entry.getKey());
			}
			return expired;
		});
	}

	@Unique
	private void pauc$clearSectionsToVillageCache() {
		if (this.pauc$sectionsToVillageCache.isEmpty()) {
			return;
		}

		this.pauc$sectionsToVillageCache.clear();
		this.pauc$sectionsToVillageCacheTimes.clear();
		PauCPoiQueryDiagnostics.recordInvalidation();
	}

	@Unique
	private long pauc$sectionsToVillageTtlMs() {
		return PauCRuntimeSwitches.readLong("poiQueryCache.sectionsToVillageTtlMs", 250L, 50L, 2_000L);
	}
}
