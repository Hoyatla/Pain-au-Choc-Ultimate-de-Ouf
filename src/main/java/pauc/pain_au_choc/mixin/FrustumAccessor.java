package pauc.pain_au_choc.mixin;

import net.minecraft.client.renderer.culling.Frustum;
import org.joml.FrustumIntersection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Accessor mixin for Frustum to expose internal frustum intersection data.
 * Used by PauCOcclusionCuller for custom frustum culling against sections.
 */
@Mixin(Frustum.class)
public interface FrustumAccessor {

    /**
     * Access the frustum intersection tester for custom AABB tests.
     */
    @Accessor("frustumIntersection")
    FrustumIntersection getFrustumIntersection();

    /**
     * Access the camera X offset used for frustum calculations.
     */
    @Accessor("camX")
    double getCamX();

    /**
     * Access the camera Y offset used for frustum calculations.
     */
    @Accessor("camY")
    double getCamY();

    /**
     * Access the camera Z offset used for frustum calculations.
     */
    @Accessor("camZ")
    double getCamZ();
}
