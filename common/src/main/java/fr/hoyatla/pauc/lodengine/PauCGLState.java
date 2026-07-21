package fr.hoyatla.pauc.lodengine;

import org.lwjgl.opengl.GL32;

/**
 * AutoCloseable OpenGL state snapshot.
 * Saves all relevant GL state on construction and restores it on {@link #close()}.
 * Use via try-with-resources to prevent GL state leaks between render passes.
 *
 * <p>Ported from Distant Horizons {@code GLState.java} (LGPL v3).</p>
 */
public final class PauCGLState implements AutoCloseable {
    private final int program;
    private final int vao;
    private final int vbo;
    private final int ebo;
    private final int fbo;
    private final int texture2D;
    private final int activeTextureNumber;
    private final int texture0;
    private final int texture1;
    private final int texture2;
    private final int texture3;
    private final int frameBufferTexture0;
    private final int frameBufferTexture1;
    private final int frameBufferDepthTexture;
    private final boolean blend;
    private final boolean scissor;
    private final int blendEqRGB;
    private final int blendEqAlpha;
    private final int blendSrcColor;
    private final int blendSrcAlpha;
    private final int blendDstColor;
    private final int blendDstAlpha;
    private final boolean depth;
    private final boolean writeToDepthBuffer;
    private final int depthFunc;
    private final boolean stencil;
    private final int stencilFunc;
    private final int stencilRef;
    private final int stencilMask;
    private final int[] view;
    private final boolean cull;
    private final int cullMode;
    private final int polyMode;

    public PauCGLState() {
        this.program = GL32.glGetInteger(GL32.GL_CURRENT_PROGRAM);
        this.vao = GL32.glGetInteger(GL32.GL_VERTEX_ARRAY_BINDING);
        this.vbo = GL32.glGetInteger(GL32.GL_ARRAY_BUFFER_BINDING);
        this.ebo = GL32.glGetInteger(GL32.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        this.fbo = GL32.glGetInteger(GL32.GL_FRAMEBUFFER_BINDING);
        this.texture2D = GL32.glGetInteger(GL32.GL_TEXTURE_BINDING_2D);
        this.activeTextureNumber = GL32.glGetInteger(GL32.GL_ACTIVE_TEXTURE);

        GL32.glActiveTexture(GL32.GL_TEXTURE0);
        this.texture0 = GL32.glGetInteger(GL32.GL_TEXTURE_BINDING_2D);
        GL32.glActiveTexture(GL32.GL_TEXTURE1);
        this.texture1 = GL32.glGetInteger(GL32.GL_TEXTURE_BINDING_2D);
        GL32.glActiveTexture(GL32.GL_TEXTURE2);
        this.texture2 = GL32.glGetInteger(GL32.GL_TEXTURE_BINDING_2D);
        GL32.glActiveTexture(GL32.GL_TEXTURE3);
        this.texture3 = GL32.glGetInteger(GL32.GL_TEXTURE_BINDING_2D);
        GL32.glActiveTexture(this.activeTextureNumber);

        if (this.fbo != 0) {
            this.frameBufferTexture0 = GL32.glGetFramebufferAttachmentParameteri(GL32.GL_FRAMEBUFFER, GL32.GL_COLOR_ATTACHMENT0, GL32.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            this.frameBufferTexture1 = GL32.glGetFramebufferAttachmentParameteri(GL32.GL_FRAMEBUFFER, GL32.GL_COLOR_ATTACHMENT1, GL32.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME);
            int depthType = GL32.glGetFramebufferAttachmentParameteri(GL32.GL_FRAMEBUFFER, GL32.GL_DEPTH_ATTACHMENT, GL32.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE);
            this.frameBufferDepthTexture = (depthType == GL32.GL_TEXTURE) ? GL32.glGetFramebufferAttachmentParameteri(GL32.GL_FRAMEBUFFER, GL32.GL_DEPTH_ATTACHMENT, GL32.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME) : 0;
        } else {
            this.frameBufferTexture0 = 0;
            this.frameBufferTexture1 = 0;
            this.frameBufferDepthTexture = 0;
        }

        this.blend = GL32.glIsEnabled(GL32.GL_BLEND);
        this.scissor = GL32.glIsEnabled(GL32.GL_SCISSOR_TEST);
        this.blendEqRGB = GL32.glGetInteger(GL32.GL_BLEND_EQUATION_RGB);
        this.blendEqAlpha = GL32.glGetInteger(GL32.GL_BLEND_EQUATION_ALPHA);
        this.blendSrcColor = GL32.glGetInteger(GL32.GL_BLEND_SRC_RGB);
        this.blendSrcAlpha = GL32.glGetInteger(GL32.GL_BLEND_SRC_ALPHA);
        this.blendDstColor = GL32.glGetInteger(GL32.GL_BLEND_DST_RGB);
        this.blendDstAlpha = GL32.glGetInteger(GL32.GL_BLEND_DST_ALPHA);
        this.depth = GL32.glIsEnabled(GL32.GL_DEPTH_TEST);
        this.writeToDepthBuffer = GL32.glGetInteger(GL32.GL_DEPTH_WRITEMASK) == GL32.GL_TRUE;
        this.depthFunc = GL32.glGetInteger(GL32.GL_DEPTH_FUNC);
        this.stencil = GL32.glIsEnabled(GL32.GL_STENCIL_TEST);
        this.stencilFunc = GL32.glGetInteger(GL32.GL_STENCIL_FUNC);
        this.stencilRef = GL32.glGetInteger(GL32.GL_STENCIL_REF);
        this.stencilMask = GL32.glGetInteger(GL32.GL_STENCIL_VALUE_MASK);
        this.view = new int[4];
        GL32.glGetIntegerv(GL32.GL_VIEWPORT, this.view);
        this.cull = GL32.glIsEnabled(GL32.GL_CULL_FACE);
        this.cullMode = GL32.glGetInteger(GL32.GL_CULL_FACE_MODE);
        this.polyMode = GL32.glGetInteger(GL32.GL_POLYGON_MODE);
    }

    @Override
    public void close() {
        GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, 0);
        boolean frameBufferSet = false;

        if (this.fbo != 0 && GL32.glIsFramebuffer(this.fbo)) {
            GL32.glBindFramebuffer(GL32.GL_FRAMEBUFFER, this.fbo);
            frameBufferSet = true;
        }

        if (this.blend) GL32.glEnable(GL32.GL_BLEND); else GL32.glDisable(GL32.GL_BLEND);
        if (this.scissor) GL32.glEnable(GL32.GL_SCISSOR_TEST); else GL32.glDisable(GL32.GL_SCISSOR_TEST);

        GL32.glActiveTexture(GL32.GL_TEXTURE0);
        GL32.glBindTexture(GL32.GL_TEXTURE_2D, GL32.glIsTexture(this.texture0) ? this.texture0 : 0);
        GL32.glActiveTexture(GL32.GL_TEXTURE1);
        GL32.glBindTexture(GL32.GL_TEXTURE_2D, GL32.glIsTexture(this.texture1) ? this.texture1 : 0);
        GL32.glActiveTexture(GL32.GL_TEXTURE2);
        GL32.glBindTexture(GL32.GL_TEXTURE_2D, GL32.glIsTexture(this.texture2) ? this.texture2 : 0);
        GL32.glActiveTexture(GL32.GL_TEXTURE3);
        GL32.glBindTexture(GL32.GL_TEXTURE_2D, GL32.glIsTexture(this.texture3) ? this.texture3 : 0);
        GL32.glActiveTexture(this.activeTextureNumber);
        GL32.glBindTexture(GL32.GL_TEXTURE_2D, GL32.glIsTexture(this.texture2D) ? this.texture2D : 0);

        if (frameBufferSet) {
            if (GL32.glIsTexture(this.frameBufferTexture0)) {
                GL32.glFramebufferTexture2D(GL32.GL_FRAMEBUFFER, GL32.GL_COLOR_ATTACHMENT0, GL32.GL_TEXTURE_2D, this.frameBufferTexture0, 0);
            }
            if (this.frameBufferTexture1 != 0 && GL32.glIsTexture(this.frameBufferTexture1)) {
                GL32.glFramebufferTexture2D(GL32.GL_FRAMEBUFFER, GL32.GL_COLOR_ATTACHMENT1, GL32.GL_TEXTURE_2D, this.frameBufferTexture1, 0);
            }
            if (GL32.glIsTexture(this.frameBufferDepthTexture)) {
                GL32.glFramebufferTexture2D(GL32.GL_FRAMEBUFFER, GL32.GL_DEPTH_ATTACHMENT, GL32.GL_TEXTURE_2D, this.frameBufferDepthTexture, 0);
            }
        }

        GL32.glBindVertexArray(GL32.glIsVertexArray(this.vao) ? this.vao : 0);
        GL32.glBindBuffer(GL32.GL_ARRAY_BUFFER, GL32.glIsBuffer(this.vbo) ? this.vbo : 0);
        GL32.glBindBuffer(GL32.GL_ELEMENT_ARRAY_BUFFER, GL32.glIsBuffer(this.ebo) ? this.ebo : 0);
        GL32.glUseProgram(GL32.glIsProgram(this.program) ? this.program : 0);

        if (this.writeToDepthBuffer) GL32.glDepthMask(true); else GL32.glDepthMask(false);
        GL32.glBlendFuncSeparate(this.blendSrcColor, this.blendDstColor, this.blendSrcAlpha, this.blendDstAlpha);
        GL32.glBlendEquationSeparate(this.blendEqRGB, this.blendEqAlpha);
        if (this.depth) GL32.glEnable(GL32.GL_DEPTH_TEST); else GL32.glDisable(GL32.GL_DEPTH_TEST);
        GL32.glDepthFunc(this.depthFunc);
        if (this.stencil) GL32.glEnable(GL32.GL_STENCIL_TEST); else GL32.glDisable(GL32.GL_STENCIL_TEST);
        GL32.glStencilFunc(this.stencilFunc, this.stencilRef, this.stencilMask);
        GL32.glViewport(this.view[0], this.view[1], this.view[2], this.view[3]);
        if (this.cull) GL32.glEnable(GL32.GL_CULL_FACE); else GL32.glDisable(GL32.GL_CULL_FACE);
        GL32.glCullFace(this.cullMode);
        GL32.glPolygonMode(GL32.GL_FRONT_AND_BACK, this.polyMode);
    }
}
