package fr.hoyatla.pauc.lodengine;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.logging.LogUtils;
import fr.hoyatla.pauc.mixin.PauCSpriteContentsAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FlowerBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * LOD engine block colour cache: derives a single representative ARGB colour per {@link BlockState} by
 * averaging the pixels of its top-face texture in LINEAR light space, exactly the way Distant Horizons
 * does — a far more faithful colour than the declarative {@code MapColor}/{@code getGrassColor} values
 * the sampler used before (real sand yellow, real stone grey, canopy-dark leaves, biome-tinted grass).
 *
 * <p>This is a clean-room port of DH's {@code ClientBlockStateColorCache} algorithm into pure PauC
 * (zero {@code com.seibel} reference): read the baked model's up-face quad → average its sprite's
 * pixels (sRGB→linear, alpha-weighted) → linear→sRGB. Blocks that carry a tint index (grass, leaves,
 * water) record it and are multiplied by the biome tint at read time via vanilla {@code BlockColors}.</p>
 *
 * <p>Not wired into the sampler yet (that is the next step); this class is self-contained and safe to
 * call from the render thread with the texture atlas loaded. Every failure falls back gracefully.</p>
 */
public final class PauCBlockColorCache {
	private static final Logger LOGGER = LogUtils.getLogger();

	/** Face resolution order: prefer the up face, then unculled, then the sides, then down. */
	private static final Direction[] FACE_ORDER = {Direction.UP, null, Direction.NORTH, Direction.EAST, Direction.WEST, Direction.SOUTH, Direction.DOWN};
	private static final RandomSource RANDOM = RandomSource.create();
	private static final int FLOWER_COLOR_SCALE = 5;

	// sRGB(0..255) -> linear LUT via the standard sRGB EOTF — produces the exact same values DH bakes
	// into its 256-entry table (verified: [1]=3.0353e-4), so PauC colours match DH's reference output.
	private static final float[] SRGB_TO_LINEAR = buildSrgbToLinearTable();

	private final Map<BlockState, Resolved> cache = new HashMap<>();
	private boolean firstResolveLogged;

	private enum ColorMode {
		DEFAULT, FLOWER, LEAVES, GLASS;

		static ColorMode of(Block block) {
			if (block instanceof LeavesBlock) {
				return LEAVES;
			}
			if (block instanceof FlowerBlock) {
				return FLOWER;
			}
			if (block.toString().contains("glass")) {
				return GLASS;
			}
			return DEFAULT;
		}
	}

	/** Cached per-state result: the texture-average base colour + whether/how it is biome-tinted. */
	private record Resolved(int baseColor, boolean needsTint, int tintIndex) {
	}

	/**
	 * Returns the biome-tinted ARGB colour for a block state at a position (grass/leaves/water get their
	 * biome tint applied). {@code level}/{@code pos} give the biome context. Alpha is preserved.
	 */
	public int tintedColor(BlockState state, BlockAndTintGetter level, BlockPos pos) {
		Resolved resolved = resolve(state);
		if (!resolved.needsTint()) {
			return resolved.baseColor();
		}
		try {
			int tint = Minecraft.getInstance().getBlockColors().getColor(state, level, pos, resolved.tintIndex());
			if (tint != -1) {
				return multiplyArgbWithRgb(resolved.baseColor(), tint);
			}
		} catch (Throwable ignored) {
			// Some states need a full level to tint; skip the tint rather than fail (matches DH's fallback).
		}
		return resolved.baseColor();
	}

	/** Returns the untinted texture-average ARGB colour (no biome context needed). */
	public int baseColor(BlockState state) {
		return resolve(state).baseColor();
	}

	private Resolved resolve(BlockState state) {
		Resolved cached = cache.get(state);
		if (cached != null) {
			return cached;
		}
		Resolved resolved = computeResolved(state);
		cache.put(state, resolved);
		if (!firstResolveLogged) {
			firstResolveLogged = true;
			LOGGER.info("PauC LOD engine block colour cache active: first state resolved [{}] -> #{}.",
				state.getBlock(), Integer.toHexString(resolved.baseColor()));
		}
		return resolved;
	}

	private Resolved computeResolved(BlockState state) {
		try {
			BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
			// Fluids (water/lava): no solid up-face quad — use the particle icon, biome-tinted like DH.
			if (!state.getFluidState().isEmpty()) {
				TextureAtlasSprite particle = dispatcher.getBlockModel(state).getParticleIcon();
				if (isMissingSprite(particle)) {
					// Fluid blockstates map to the MISSING model (fluids have no baked model): averaging that
					// sprite poisoned the store with saturated MAGENTA. White base + tint = the biome water colour.
					return new Resolved(0xFFFFFFFF, true, 0);
				}
				return new Resolved(calculateColorFromTexture(particle, ColorMode.of(state.getBlock())), true, 0);
			}

			List<BakedQuad> quads = null;
			for (Direction direction : FACE_ORDER) {
				quads = quadsForDirection(dispatcher, state, direction);
				boolean pillarUp = state.getBlock() instanceof RotatedPillarBlock && direction == Direction.UP;
				if (quads != null && !quads.isEmpty() && !pillarUp) {
					break;
				}
			}
			if (quads == null || quads.isEmpty() || quads.get(0) == null) {
				TextureAtlasSprite particle = dispatcher.getBlockModel(state).getParticleIcon();
				if (isMissingSprite(particle)) {
					return new Resolved(0xFF7A6A55, false, 0); // model-less (air & friends): neutral earth, never magenta
				}
				return new Resolved(calculateColorFromTexture(particle, ColorMode.of(state.getBlock())), false, 0);
			}

			BakedQuad first = quads.get(0);
			int base = calculateColorFromTexture(first.getSprite(), ColorMode.of(state.getBlock()));
			return new Resolved(base, first.isTinted(), first.getTintIndex());
		} catch (Throwable throwable) {
			// White-ish fallback so a single bad state never poisons the field.
			return new Resolved(0xFFFFFFFF, false, 0);
		}
	}

	private static boolean isMissingSprite(TextureAtlasSprite sprite) {
		return sprite == null || sprite.contents().name()
			.equals(net.minecraft.client.renderer.texture.MissingTextureAtlasSprite.getLocation());
	}

	private static List<BakedQuad> quadsForDirection(BlockRenderDispatcher dispatcher, BlockState state, Direction direction) {
		BlockState effective = state;
		if (state.getBlock() instanceof SlabBlock) {
			effective = state.setValue(SlabBlock.TYPE, SlabType.DOUBLE);
		}
		return dispatcher.getBlockModel(effective).getQuads(effective, direction, RANDOM);
	}

	/**
	 * Averages every pixel of {@code sprite} in linear light space (alpha-weighted), then converts back
	 * to sRGB — the DH colour-derivation kernel. Flowers weight saturated pixels 5x so the bloom colour
	 * dominates the stem; leaves ignore fully-transparent pixels; others skip transparent unless glass.
	 */
	private static int calculateColorFromTexture(TextureAtlasSprite sprite, ColorMode colorMode) {
		SpriteContents contents = sprite.contents();
		NativeImage image = ((PauCSpriteContentsAccessor) (Object) contents).getOriginalImage();
		int width = contents.width();
		int height = contents.height();

		int count = 0;
		long alpha = 0;
		double red = 0.0D;
		double green = 0.0D;
		double blue = 0.0D;
		for (int v = 0; v < height; v++) {
			for (int u = 0; u < width; u++) {
				// NativeImage packs ABGR (R in the low byte), so R = &0xff, ..., A = >>>24.
				int pixel = image.getPixelRGBA(u, v);
				int r = pixel & 0xff;
				int g = (pixel >>> 8) & 0xff;
				int b = (pixel >>> 16) & 0xff;
				int a = (pixel >>> 24) & 0xff;
				int scale = 1;
				if (colorMode == ColorMode.LEAVES) {
					if (a == 0) {
						continue;
					}
					a = 255;
				} else {
					if (a == 0 && colorMode != ColorMode.GLASS) {
						continue;
					}
					if (colorMode == ColorMode.FLOWER && (g + 25 < b || g + 25 < r)) {
						scale = FLOWER_COLOR_SCALE;
					}
				}
				count += scale;
				alpha += (long) a * scale;
				red += SRGB_TO_LINEAR[r] * (float) a * scale;
				green += SRGB_TO_LINEAR[g] * (float) a * scale;
				blue += SRGB_TO_LINEAR[b] * (float) a * scale;
			}
		}

		if (count == 0 || alpha == 0) {
			return 0xFFFFFFFF;
		}
		int outA = (int) (alpha / count);
		int outR = linearToSrgb((float) (red / alpha));
		int outG = linearToSrgb((float) (green / alpha));
		int outB = linearToSrgb((float) (blue / alpha));
		return argb(outA, outR, outG, outB);
	}

	private static int argb(int a, int r, int g, int b) {
		return (a << 24) | (r << 16) | (g << 8) | b;
	}

	/** Multiplies the RGB of an ARGB colour by a 0xRRGGBB tint (per-channel /255), keeping alpha. */
	public static int multiplyArgbWithRgb(int argb, int rgb) {
		int a = (argb >>> 24) & 0xff;
		int r = (((argb >>> 16) & 0xff) * ((rgb >>> 16) & 0xff)) / 255;
		int g = (((argb >>> 8) & 0xff) * ((rgb >>> 8) & 0xff)) / 255;
		int b = ((argb & 0xff) * (rgb & 0xff)) / 255;
		return argb(a, r, g, b);
	}

	/** Standard linear->sRGB transfer, returning an 0..255 channel. */
	private static int linearToSrgb(float c) {
		if (c <= 0.0F) {
			return 0;
		}
		if (c >= 1.0F) {
			return 255;
		}
		float s = c <= 0.0031308F ? c * 12.92F : 1.055F * (float) Math.pow(c, 1.0 / 2.4) - 0.055F;
		return Math.max(0, Math.min(255, Math.round(s * 255.0F)));
	}

	private static float[] buildSrgbToLinearTable() {
		float[] table = new float[256];
		for (int i = 0; i < 256; i++) {
			float s = i / 255.0F;
			table[i] = s <= 0.04045F ? s / 12.92F : (float) Math.pow((s + 0.055F) / 1.055F, 2.4);
		}
		return table;
	}
}
