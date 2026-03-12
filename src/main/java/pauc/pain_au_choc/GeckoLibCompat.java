package pauc.pain_au_choc;

import net.minecraft.world.entity.Entity;

public final class GeckoLibCompat {
    private static final String[] GEO_MARKER_TYPES = new String[]{
            "software.bernie.geckolib.animatable.GeoAnimatable",
            "software.bernie.geckolib.core.animatable.GeoAnimatable"
    };

    private static Class<?> geoAnimatableType;
    private static boolean markerResolved;
    private static boolean bridgeLogged;

    private GeckoLibCompat() {
    }

    public static boolean isGeckoEntity(Entity entity) {
        if (entity == null || !CompatibilityGuards.isGeckoLibLoaded()) {
            return false;
        }

        Class<?> markerType = resolveGeoMarkerType();
        return markerType != null && markerType.isInstance(entity);
    }

    private static Class<?> resolveGeoMarkerType() {
        if (markerResolved) {
            return geoAnimatableType;
        }

        markerResolved = true;
        ClassLoader classLoader = GeckoLibCompat.class.getClassLoader();
        for (String markerTypeName : GEO_MARKER_TYPES) {
            try {
                geoAnimatableType = Class.forName(markerTypeName, false, classLoader);
                if (!bridgeLogged) {
                    bridgeLogged = true;
                    Pain_au_Choc.LOGGER.info("PauC Entity LOD: GeckoLib bridge active ({})", markerTypeName);
                }
                return geoAnimatableType;
            } catch (ClassNotFoundException ignored) {
            }
        }

        return null;
    }
}


