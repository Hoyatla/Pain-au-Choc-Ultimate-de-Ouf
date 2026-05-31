package net.irisshaders.iris.mixin;

import net.irisshaders.iris.mixinterface.ExtendedBiome;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = Biome.class, priority = 990)
public class MixinBiome implements ExtendedBiome {
	@Unique
	private float pauc$downfall;
	private int biomeCategory = -1;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void pauc$captureClimateSettings(Biome.ClimateSettings climateSettings, BiomeSpecialEffects specialEffects,
			BiomeGenerationSettings generationSettings, MobSpawnSettings mobSpawnSettings, CallbackInfo ci) {
		this.pauc$downfall = climateSettings.downfall();
	}

	@Override
	public int getBiomeCategory() {
		return biomeCategory;
	}

	@Override
	public void setBiomeCategory(int biomeCategory) {
		this.biomeCategory = biomeCategory;
	}

	@Override
	public float getDownfall() {
		return this.pauc$downfall;
	}
}
