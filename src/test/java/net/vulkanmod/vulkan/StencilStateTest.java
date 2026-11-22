package net.vulkanmod.vulkan;

import net.vulkanmod.compat.gl.GlIntegerState;
import net.vulkanmod.vulkan.shader.PipelineState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 2026-11-22 (Tensura 1.1.3c): Cosmic Entropy restores the stencil state with glStencilFuncSeparate(face, 0, 0, 0) every frame. GL
 * rejects the 0 (GL_INVALID_ENUM) and keeps its state; VulkanMod stored it and the next pipeline threw "unknown stencil compare
 * function: 0" on the title screen. The back-face queries (GL_STENCIL_BACK_*) also answered 0.
 */
class StencilStateTest {
    private boolean test;
    private int func, ref, mask, fail, depthFail, pass, writeMask;

    @BeforeEach
    void save() {
        test = VRenderSystem.stencilTest;
        func = VRenderSystem.stencilFunc;
        ref = VRenderSystem.stencilRef;
        mask = VRenderSystem.stencilFuncMask;
        fail = VRenderSystem.stencilFailOp;
        depthFail = VRenderSystem.stencilDepthFailOp;
        pass = VRenderSystem.stencilPassOp;
        writeMask = VRenderSystem.stencilWriteMask;
    }

    @AfterEach
    void restore() {
        VRenderSystem.stencilTest = test;
        VRenderSystem.stencilFunc = func;
        VRenderSystem.stencilRef = ref;
        VRenderSystem.stencilFuncMask = mask;
        VRenderSystem.stencilFailOp = fail;
        VRenderSystem.stencilDepthFailOp = depthFail;
        VRenderSystem.stencilPassOp = pass;
        VRenderSystem.stencilWriteMask = writeMask;
    }

    @Test
    void anInvalidFunctionKeepsTheStateItHadAndThePipelineStillEncodes() {
        VRenderSystem.stencilFunc(GL11.GL_EQUAL, 3, 0x0F);
        VRenderSystem.stencilFunc(0, 0, 0);

        assertEquals(GL11.GL_EQUAL, VRenderSystem.stencilFunc);
        assertEquals(3, VRenderSystem.stencilRef);
        assertEquals(0x0F, VRenderSystem.stencilFuncMask);
        VRenderSystem.stencilTest = true;
        assertDoesNotThrow(PipelineState::getStencilState);
    }

    @Test
    void everyValidFunctionIsTaken() {
        for (int f = GL11.GL_NEVER; f <= GL11.GL_ALWAYS; f++) {
            VRenderSystem.stencilFunc(f, 1, 0xFF);
            assertEquals(f, VRenderSystem.stencilFunc);
        }
    }

    @Test
    void anInvalidOperationKeepsTheStateButZeroIsAValidOne() {
        VRenderSystem.stencilOp(GL11.GL_ZERO, GL11.GL_REPLACE, GL14.GL_INCR_WRAP);
        assertEquals(GL11.GL_ZERO, VRenderSystem.stencilFailOp);

        VRenderSystem.stencilOp(GL11.GL_KEEP, 0x1234, GL11.GL_KEEP);

        assertEquals(GL11.GL_ZERO, VRenderSystem.stencilFailOp);
        assertEquals(GL11.GL_REPLACE, VRenderSystem.stencilDepthFailOp);
        assertEquals(GL14.GL_INCR_WRAP, VRenderSystem.stencilPassOp);
        VRenderSystem.stencilTest = true;
        assertDoesNotThrow(PipelineState::getStencilState);
    }

    @Test
    void theBackFaceQueriesAnswerTheSharedState() {
        VRenderSystem.stencilFunc(GL11.GL_LEQUAL, 7, 0x3F);
        VRenderSystem.stencilOp(GL11.GL_INVERT, GL11.GL_DECR, GL11.GL_INCR);
        VRenderSystem.stencilMask(0x55);

        assertEquals(GL11.GL_LEQUAL, GlIntegerState.getInteger(0x8800)); // GL_STENCIL_BACK_FUNC
        assertEquals(GL11.GL_INVERT, GlIntegerState.getInteger(0x8801)); // GL_STENCIL_BACK_FAIL
        assertEquals(GL11.GL_DECR, GlIntegerState.getInteger(0x8802)); // GL_STENCIL_BACK_PASS_DEPTH_FAIL
        assertEquals(GL11.GL_INCR, GlIntegerState.getInteger(0x8803)); // GL_STENCIL_BACK_PASS_DEPTH_PASS
        assertEquals(7, GlIntegerState.getInteger(0x8CA3)); // GL_STENCIL_BACK_REF
        assertEquals(0x3F, GlIntegerState.getInteger(0x8CA4)); // GL_STENCIL_BACK_VALUE_MASK
        assertEquals(0x55, GlIntegerState.getInteger(0x8CA5)); // GL_STENCIL_BACK_WRITEMASK
        assertEquals(GlIntegerState.getInteger(GL11.GL_STENCIL_FUNC), GlIntegerState.getInteger(0x8800));
    }
}
