package pauc.pain_au_choc.render.shader;

public enum DeferredCompatibilityMode {
    STRICT("strict"),
    BALANCED("balanced"),
    FAST("fast");

    private final String configKey;

    DeferredCompatibilityMode(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigKey() {
        return this.configKey;
    }

    public static DeferredCompatibilityMode fromConfigKey(String key, DeferredCompatibilityMode fallback) {
        if (key == null || key.isBlank()) {
            return fallback;
        }

        for (DeferredCompatibilityMode mode : values()) {
            if (mode.configKey.equalsIgnoreCase(key) || mode.name().equalsIgnoreCase(key)) {
                return mode;
            }
        }
        return fallback;
    }
}
