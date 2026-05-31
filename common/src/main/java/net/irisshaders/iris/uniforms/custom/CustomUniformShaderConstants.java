package net.irisshaders.iris.uniforms.custom;

import kroppeb.stareval.expression.ConstantExpression;
import kroppeb.stareval.expression.Expression;
import kroppeb.stareval.function.FunctionContext;
import kroppeb.stareval.function.FunctionReturn;
import kroppeb.stareval.function.Type;
import net.irisshaders.iris.parsing.BiomeCategories;
import net.irisshaders.iris.uniforms.BiomeUniforms;

import java.util.Locale;
import java.util.Map;

public final class CustomUniformShaderConstants {
	private static final int UNKNOWN_CONSTANT = -1;
	private static final Map<String, Integer> PRECIPITATION_TYPES = Map.of(
		"PPT_NONE", 0,
		"PPT_RAIN", 1,
		"PPT_SNOW", 2
	);

	private CustomUniformShaderConstants() {
	}

	public static Type getType(String name) {
		return resolveIntValue(name) != null ? Type.Int : null;
	}

	public static Expression getExpression(String name) {
		Integer value = resolveIntValue(name);
		if (value == null) {
			return null;
		}

		return new ConstantExpression(Type.Int) {
			@Override
			public void evaluateTo(FunctionContext context, FunctionReturn functionReturn) {
				functionReturn.intReturn = value;
			}
		};
	}

	private static Integer resolveIntValue(String name) {
		Integer precipitation = PRECIPITATION_TYPES.get(name);
		if (precipitation != null) {
			return precipitation;
		}

		if (name.startsWith("CAT_")) {
			return resolveCategory(name);
		}

		if (name.startsWith("BIOME_")) {
			return resolveBiome(name);
		}

		return null;
	}

	private static int resolveCategory(String name) {
		String categoryName = name.substring("CAT_".length());

		for (BiomeCategories category : BiomeCategories.values()) {
			if (category.name().equals(categoryName)) {
				return category.ordinal();
			}
		}

		return UNKNOWN_CONSTANT;
	}

	private static int resolveBiome(String name) {
		String biomeName = name.substring("BIOME_".length());

		for (var entry : BiomeUniforms.getBiomeMap().object2IntEntrySet()) {
			if (entry.getKey() != null && entry.getKey().location().getPath().toUpperCase(Locale.ROOT).equals(biomeName)) {
				return entry.getIntValue();
			}
		}

		return UNKNOWN_CONSTANT;
	}
}
