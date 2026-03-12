package pauc.pain_au_choc;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

public final class EntityLodBillboardRenderer {
    private static final ResourceLocation WHITE_TEXTURE = ResourceLocation.fromNamespaceAndPath("minecraft", "textures/misc/white.png");

    private EntityLodBillboardRenderer() {
    }

    public static void render(Entity entity, double cameraX, double cameraY, double cameraZ, PoseStack poseStack, MultiBufferSource bufferSource) {
        if (entity == null || poseStack == null || bufferSource == null) {
            return;
        }

        float halfWidth = EntityLodController.getBillboardHalfWidth(entity);
        float height = EntityLodController.getBillboardHeight(entity);
        int colorArgb = EntityLodController.getBillboardColorArgb(entity);
        int alpha = colorArgb >>> 24 & 0xFF;
        int red = colorArgb >>> 16 & 0xFF;
        int green = colorArgb >>> 8 & 0xFF;
        int blue = colorArgb & 0xFF;

        double relativeX = entity.getX() - cameraX;
        double relativeY = entity.getY() - cameraY + entity.getBbHeight() * 0.5D;
        double relativeZ = entity.getZ() - cameraZ;

        poseStack.pushPose();
        poseStack.translate(relativeX, relativeY, relativeZ);
        poseStack.mulPose(Minecraft.getInstance().getEntityRenderDispatcher().cameraOrientation());

        PoseStack.Pose pose = poseStack.last();
        Matrix4f poseMatrix = pose.pose();
        Matrix3f normalMatrix = pose.normal();
        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.entityCutoutNoCull(WHITE_TEXTURE));

        vertexConsumer.vertex(poseMatrix, -halfWidth, 0.0F, 0.0F)
                .color(red, green, blue, alpha)
                .uv(0.0F, 1.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normalMatrix, 0.0F, 1.0F, 0.0F)
                .endVertex();
        vertexConsumer.vertex(poseMatrix, -halfWidth, height, 0.0F)
                .color(red, green, blue, alpha)
                .uv(0.0F, 0.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normalMatrix, 0.0F, 1.0F, 0.0F)
                .endVertex();
        vertexConsumer.vertex(poseMatrix, halfWidth, height, 0.0F)
                .color(red, green, blue, alpha)
                .uv(1.0F, 0.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normalMatrix, 0.0F, 1.0F, 0.0F)
                .endVertex();
        vertexConsumer.vertex(poseMatrix, halfWidth, 0.0F, 0.0F)
                .color(red, green, blue, alpha)
                .uv(1.0F, 1.0F)
                .overlayCoords(OverlayTexture.NO_OVERLAY)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(normalMatrix, 0.0F, 1.0F, 0.0F)
                .endVertex();

        poseStack.popPose();
    }
}

