package net.irisshaders.iris.mixin.forge.worldgen;

import fr.hoyatla.pauc.platform.forge.worldgen.FarChunkPlacementBroker;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

@Mixin(WorldGenRegion.class)
public abstract class MixinWorldGenRegion {
	@Unique
	private final Map<Long, BlockState> pauc$virtualFarStates = new HashMap<>();

	@Unique
	private static Method pauc$centerMethod;
	@Unique
	private static Field pauc$centerField;
	@Unique
	private static boolean pauc$centerLookupDone;
	@Unique
	private static Field pauc$levelField;
	@Unique
	private static Field pauc$generatingStatusField;
	@Unique
	private static Field pauc$writeRadiusCutoffField;
	@Unique
	private static Field pauc$currentlyGeneratingField;
	@Unique
	private static boolean pauc$fieldLookupDone;

	@Inject(method = "setBlock", at = @At("HEAD"), cancellable = true)
	private void pauc$redirectFarChunkSetBlock(BlockPos pos, BlockState state, int flags, int recursionDepth, CallbackInfoReturnable<Boolean> cir) {
		if (!pauc$isOutOfWriteRadius(pos)) {
			return;
		}

		ServerLevel level = pauc$getLevel();
		ChunkStatus generatingStatus = pauc$getGeneratingStatus();
		if (level == null || generatingStatus == null) {
			return;
		}

		Supplier<String> currentGenerator = pauc$getCurrentGenerator();
		String generationHint = currentGenerator != null ? currentGenerator.get() : null;
		FarChunkPlacementBroker.SubmissionResult result = FarChunkPlacementBroker.submitWorldGenPlacement(
			level,
			generatingStatus,
			generationHint,
			new FarChunkPlacementBroker.BlockPosSnapshot(pos, state, flags, recursionDepth)
		);

		if (result.reportedSuccess()) {
			pauc$virtualFarStates.put(pos.asLong(), state);
		}

		cir.setReturnValue(result.reportedSuccess());
	}

	@Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
	private void pauc$getVirtualFarBlockState(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		if (!pauc$isOutOfWriteRadius(pos)) {
			return;
		}

		BlockState state = pauc$virtualFarStates.get(pos.asLong());
		if (state != null) {
			cir.setReturnValue(state);
		}
	}

	@Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
	private void pauc$getVirtualFarFluidState(BlockPos pos, CallbackInfoReturnable<FluidState> cir) {
		if (!pauc$isOutOfWriteRadius(pos)) {
			return;
		}

		BlockState state = pauc$virtualFarStates.get(pos.asLong());
		if (state != null) {
			cir.setReturnValue(state.getFluidState());
		}
	}

	@Unique
	private boolean pauc$isOutOfWriteRadius(BlockPos pos) {
		ChunkPos center = pauc$getCenterChunkPos();
		Integer writeRadiusCutoff = pauc$getWriteRadiusCutoff();
		if (center == null) {
			return false;
		}
		if (writeRadiusCutoff == null) {
			return false;
		}

		int chunkX = pos.getX() >> 4;
		int chunkZ = pos.getZ() >> 4;

		return Math.abs(center.x - chunkX) > writeRadiusCutoff || Math.abs(center.z - chunkZ) > writeRadiusCutoff;
	}

	@Unique
	private ChunkPos pauc$getCenterChunkPos() {
		pauc$resolveCenterAccessors();

		WorldGenRegion self = (WorldGenRegion) (Object) this;

		if (pauc$centerMethod != null) {
			try {
				Object value = pauc$centerMethod.invoke(self);
				if (value instanceof ChunkPos chunkPos) {
					return chunkPos;
				}
			} catch (ReflectiveOperationException ignored) {
			}
		}

		if (pauc$centerField != null) {
			try {
				Object value = pauc$centerField.get(self);
				if (value instanceof ChunkPos chunkPos) {
					return chunkPos;
				}
			} catch (ReflectiveOperationException ignored) {
			}
		}

		return null;
	}

	@Unique
	private static void pauc$resolveCenterAccessors() {
		if (pauc$centerLookupDone) {
			return;
		}

		pauc$centerLookupDone = true;
		Class<WorldGenRegion> clazz = WorldGenRegion.class;

		pauc$centerMethod = pauc$findNoArgChunkPosMethod(clazz, "getCenter", "m_143488_");
		if (pauc$centerMethod != null) {
			return;
		}

		for (Method method : clazz.getDeclaredMethods()) {
			if (method.getParameterCount() == 0 && method.getReturnType() == ChunkPos.class) {
				method.setAccessible(true);
				pauc$centerMethod = method;
				break;
			}
		}

		if (pauc$centerMethod != null) {
			return;
		}

		for (Field field : clazz.getDeclaredFields()) {
			if (field.getType() == ChunkPos.class) {
				field.setAccessible(true);
				pauc$centerField = field;
				break;
			}
		}
	}

	@Unique
	private static void pauc$resolveWorldGenFields() {
		if (pauc$fieldLookupDone) {
			return;
		}

		pauc$fieldLookupDone = true;
		Class<WorldGenRegion> clazz = WorldGenRegion.class;

		pauc$levelField = pauc$findFieldByName(clazz, "level", "f_9479_");
		pauc$generatingStatusField = pauc$findFieldByName(clazz, "generatingStatus", "f_143480_");
		pauc$writeRadiusCutoffField = pauc$findFieldByName(clazz, "writeRadiusCutoff", "f_143481_");
		pauc$currentlyGeneratingField = pauc$findFieldByName(clazz, "currentlyGenerating", "f_143482_");
		if (pauc$levelField != null
			&& pauc$generatingStatusField != null
			&& pauc$writeRadiusCutoffField != null
			&& pauc$currentlyGeneratingField != null) {
			return;
		}

		Field lastIntField = null;
		for (Field field : clazz.getDeclaredFields()) {
			field.setAccessible(true);
			Class<?> type = field.getType();
			if (pauc$levelField == null && ServerLevel.class.isAssignableFrom(type)) {
				pauc$levelField = field;
				continue;
			}
			if (pauc$generatingStatusField == null && ChunkStatus.class.isAssignableFrom(type)) {
				pauc$generatingStatusField = field;
				continue;
			}
			if (type == Integer.TYPE) {
				lastIntField = field;
				if (pauc$writeRadiusCutoffField == null && field.getName().toLowerCase(java.util.Locale.ROOT).contains("cutoff")) {
					pauc$writeRadiusCutoffField = field;
				}
				continue;
			}
			if (pauc$currentlyGeneratingField == null && Supplier.class.isAssignableFrom(type)) {
				pauc$currentlyGeneratingField = field;
			}
		}

		if (pauc$writeRadiusCutoffField == null && lastIntField != null) {
			lastIntField.setAccessible(true);
			pauc$writeRadiusCutoffField = lastIntField;
		}
	}

	@Unique
	private static Method pauc$findNoArgChunkPosMethod(Class<WorldGenRegion> clazz, String... names) {
		for (String name : names) {
			try {
				Method method = clazz.getDeclaredMethod(name);
				if (method.getReturnType() == ChunkPos.class) {
					method.setAccessible(true);
					return method;
				}
			} catch (NoSuchMethodException ignored) {
			}
		}
		return null;
	}

	@Unique
	private static Field pauc$findFieldByName(Class<WorldGenRegion> clazz, String... names) {
		for (String name : names) {
			try {
				Field field = clazz.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
			}
		}
		return null;
	}

	@Unique
	@SuppressWarnings("unchecked")
	private Supplier<String> pauc$getCurrentGenerator() {
		pauc$resolveWorldGenFields();
		if (pauc$currentlyGeneratingField == null) {
			return null;
		}
		try {
			Object value = pauc$currentlyGeneratingField.get(this);
			return (Supplier<String>) value;
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	@Unique
	private Integer pauc$getWriteRadiusCutoff() {
		pauc$resolveWorldGenFields();
		if (pauc$writeRadiusCutoffField == null) {
			return null;
		}
		try {
			return (Integer) pauc$writeRadiusCutoffField.get(this);
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	@Unique
	private ServerLevel pauc$getLevel() {
		pauc$resolveWorldGenFields();
		if (pauc$levelField == null) {
			return null;
		}
		try {
			return (ServerLevel) pauc$levelField.get(this);
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	@Unique
	private ChunkStatus pauc$getGeneratingStatus() {
		pauc$resolveWorldGenFields();
		if (pauc$generatingStatusField == null) {
			return null;
		}
		try {
			return (ChunkStatus) pauc$generatingStatusField.get(this);
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}
}
