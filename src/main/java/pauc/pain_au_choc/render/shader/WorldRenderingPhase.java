package pauc.pain_au_choc.render.shader;

/**
 * Enumeration of all rendering phases in the deferred pipeline.
 * Mirrors Oculus/Iris WorldRenderingPhase for shaderpack compatibility.
 *
 * Each phase corresponds to a specific stage where different shader programs
 * can be active and different render targets can be bound.
 */
public enum WorldRenderingPhase {
    /** No active phase (between frames or during setup). */
    NONE,

    /** Sky rendering (sun, moon, stars, skybox). */
    SKY,

    /** Opaque terrain geometry (gbuffers_terrain). */
    TERRAIN_SOLID,

    /** Cutout terrain geometry like leaves, grass (gbuffers_terrain_cutout). */
    TERRAIN_CUTOUT,

    /** Translucent terrain like water, stained glass (gbuffers_water). */
    TERRAIN_TRANSLUCENT,

    /** Solid entity rendering (gbuffers_entities). */
    ENTITIES,

    /** Translucent entity rendering. */
    ENTITIES_TRANSLUCENT,

    /** Block entity rendering (gbuffers_block). */
    BLOCK_ENTITIES,

    /** Hand rendering (gbuffers_hand). */
    HAND_SOLID,

    /** Translucent hand rendering (gbuffers_hand_water). */
    HAND_TRANSLUCENT,

    /** Weather effects (gbuffers_weather). */
    WEATHER,

    /** Particle rendering (gbuffers_textured_lit). */
    PARTICLES,

    /** Cloud rendering (gbuffers_clouds). */
    CLOUDS,

    /** Shadow map generation pass. */
    SHADOW,

    /** Deferred composite passes (deferred0-15). */
    DEFERRED,

    /** Composite passes after geometry (composite0-15). */
    COMPOSITE,

    /** Final composite pass before post-processing. */
    FINAL,

    /** Debug visualization. */
    DEBUG;

    /**
     * Whether this phase writes to GBuffers (deferred shading input).
     */
    public boolean isGBufferPhase() {
        return switch (this) {
            case TERRAIN_SOLID, TERRAIN_CUTOUT, TERRAIN_TRANSLUCENT,
                 ENTITIES, ENTITIES_TRANSLUCENT, BLOCK_ENTITIES,
                 HAND_SOLID, HAND_TRANSLUCENT, WEATHER, PARTICLES, CLOUDS -> true;
            default -> false;
        };
    }

    /**
     * Whether this phase is a full-screen pass (composite/deferred/final).
     */
    public boolean isFullScreenPass() {
        return this == DEFERRED || this == COMPOSITE || this == FINAL;
    }

    /**
     * Get the OptiFine program name for this phase.
     */
    public String getProgramName() {
        return switch (this) {
            case SKY -> "gbuffers_skybasic";
            case TERRAIN_SOLID -> "gbuffers_terrain";
            case TERRAIN_CUTOUT -> "gbuffers_terrain";
            case TERRAIN_TRANSLUCENT -> "gbuffers_water";
            case ENTITIES -> "gbuffers_entities";
            case ENTITIES_TRANSLUCENT -> "gbuffers_entities";
            case BLOCK_ENTITIES -> "gbuffers_block";
            case HAND_SOLID -> "gbuffers_hand";
            case HAND_TRANSLUCENT -> "gbuffers_hand_water";
            case WEATHER -> "gbuffers_weather";
            case PARTICLES -> "gbuffers_textured_lit";
            case CLOUDS -> "gbuffers_clouds";
            case SHADOW -> "shadow";
            default -> null;
        };
    }
}
