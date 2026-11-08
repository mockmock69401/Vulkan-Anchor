package net.vulkanmod.mixin.compatibility.gl;

import net.vulkanmod.compat.opengl.GlDrawContract;
import net.vulkanmod.compat.gl.GlIntegerState;
import net.vulkanmod.gl.GlBuffer;
import net.vulkanmod.gl.GlFramebuffer;
import net.vulkanmod.gl.GlSync;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.system.NativeType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.ShortBuffer;

@Mixin(GL32C.class)
public class GL32M {
    private static void vulkanmod$put(IntBuffer buffer, int value) {
        if (buffer != null && buffer.remaining() > 0) {
            buffer.put(buffer.position(), value);
        }
    }

    private static void vulkanmod$put(int[] values, int value) {
        if (values != null && values.length > 0) {
            values[0] = value;
        }
    }

    private static void vulkanmod$put(LongBuffer buffer, long value) {
        if (buffer != null && buffer.remaining() > 0) {
            buffer.put(buffer.position(), value);
        }
    }

    private static void vulkanmod$put(long[] values, long value) {
        if (values != null && values.length > 0) {
            values[0] = value;
        }
    }

    private static void vulkanmod$put(FloatBuffer buffer, float value) {
        if (buffer != null && buffer.remaining() > 0) {
            buffer.put(buffer.position(), value);
        }
    }

    private static void vulkanmod$put(float[] values, float value) {
        if (values != null && values.length > 0) {
            values[0] = value;
        }
    }

    @Overwrite(remap = false)
    public static void glFramebufferTexture(@NativeType("GLenum") int target, @NativeType("GLenum") int attachment, @NativeType("GLuint") int texture, @NativeType("GLint") int level) {
        GlFramebuffer.framebufferTexture2D(target, attachment, org.lwjgl.opengl.GL11.GL_TEXTURE_2D, texture, level);
    }

    @Overwrite(remap = false)
    @NativeType("GLsync")
    public static long glFenceSync(@NativeType("GLenum") int condition, @NativeType("GLbitfield") int flags) {
        return GlSync.fenceSync(condition, flags);
    }

    @Overwrite(remap = false)
    @NativeType("GLboolean")
    public static boolean glIsSync(@NativeType("GLsync") long sync) {
        return GlSync.isSync(sync);
    }

    @Overwrite(remap = false)
    public static void glDeleteSync(@NativeType("GLsync") long sync) {
        GlSync.deleteSync(sync);
    }

    @Overwrite(remap = false)
    @NativeType("GLenum")
    public static int glClientWaitSync(@NativeType("GLsync") long sync, @NativeType("GLbitfield") int flags, @NativeType("GLuint64") long timeout) {
        return GlSync.clientWaitSync(sync, flags, timeout);
    }

    @Overwrite(remap = false)
    public static void glWaitSync(@NativeType("GLsync") long sync, @NativeType("GLbitfield") int flags, @NativeType("GLuint64") long timeout) {
        GlSync.waitSync(sync, flags, timeout);
    }

    @Overwrite(remap = false)
    public static int glGetSynci(@NativeType("GLsync") long sync, @NativeType("GLenum") int pname, @NativeType("GLsizei *") IntBuffer length) {
        return GlSync.getSynci(sync, pname);
    }

    @Overwrite(remap = false)
    public static void glDrawElementsBaseVertex(@NativeType("GLenum") int mode, @NativeType("GLsizei") int count, @NativeType("GLenum") int type, @NativeType("void const *") long indices, @NativeType("GLint") int basevertex) {
        if (basevertex == 0) {
            GlDrawContract.drawElements(mode, count, type, indices);
        }
    }

    @Overwrite(remap = false)
    public static void glDrawElementsBaseVertex(@NativeType("GLenum") int mode, @NativeType("GLsizei") int count, @NativeType("void const *") ByteBuffer indices, @NativeType("GLint") int basevertex) {
    }

    @Overwrite(remap = false)
    public static void glDrawElementsBaseVertex(@NativeType("GLenum") int mode, @NativeType("void const *") ByteBuffer indices, @NativeType("GLint") int basevertex) {
    }

    @Overwrite(remap = false)
    public static void glDrawElementsBaseVertex(@NativeType("GLenum") int mode, @NativeType("void const *") ShortBuffer indices, @NativeType("GLint") int basevertex) {
    }

    @Overwrite(remap = false)
    public static void glDrawElementsBaseVertex(@NativeType("GLenum") int mode, @NativeType("void const *") IntBuffer indices, @NativeType("GLint") int basevertex) {
    }

    @Overwrite(remap = false)
    public static void glDrawElementsInstancedBaseVertex(@NativeType("GLenum") int mode, @NativeType("GLsizei") int count, @NativeType("GLenum") int type, @NativeType("void const *") long indices, @NativeType("GLsizei") int primcount, @NativeType("GLint") int basevertex) {
        if (basevertex == 0 && primcount > 0) {
            GlDrawContract.drawElements(mode, count, type, indices);
        }
    }

    @Overwrite(remap = false)
    public static void glDrawElementsInstancedBaseVertex(@NativeType("GLenum") int mode, @NativeType("GLsizei") int count, @NativeType("void const *") ByteBuffer indices, @NativeType("GLsizei") int primcount, @NativeType("GLint") int basevertex) {
    }

    @Overwrite(remap = false)
    public static void glDrawElementsInstancedBaseVertex(@NativeType("GLenum") int mode, @NativeType("void const *") ByteBuffer indices, @NativeType("GLsizei") int primcount, @NativeType("GLint") int basevertex) {
    }

    @Overwrite(remap = false)
    public static void glDrawElementsInstancedBaseVertex(@NativeType("GLenum") int mode, @NativeType("void const *") ShortBuffer indices, @NativeType("GLsizei") int primcount, @NativeType("GLint") int basevertex) {
    }

    @Overwrite(remap = false)
    public static void glDrawElementsInstancedBaseVertex(@NativeType("GLenum") int mode, @NativeType("void const *") IntBuffer indices, @NativeType("GLsizei") int primcount, @NativeType("GLint") int basevertex) {
    }

    @Overwrite(remap = false)
    public static void glDrawRangeElementsBaseVertex(@NativeType("GLenum") int mode, @NativeType("GLuint") int start, @NativeType("GLuint") int end, @NativeType("GLsizei") int count, @NativeType("GLenum") int type, @NativeType("void const *") long indices, @NativeType("GLint") int basevertex) {
        if (basevertex == 0) {
            GlDrawContract.drawElements(mode, count, type, indices);
        }
    }

    @Overwrite(remap = false)
    public static void glDrawRangeElementsBaseVertex(@NativeType("GLenum") int mode, @NativeType("GLuint") int start, @NativeType("GLuint") int end, @NativeType("GLsizei") int count, @NativeType("void const *") ByteBuffer indices, @NativeType("GLint") int basevertex) {
    }

    @Overwrite(remap = false)
    public static void glDrawRangeElementsBaseVertex(@NativeType("GLenum") int mode, @NativeType("GLuint") int start, @NativeType("GLuint") int end, @NativeType("void const *") ByteBuffer indices, @NativeType("GLint") int basevertex) {
    }

    @Overwrite(remap = false)
    public static void glDrawRangeElementsBaseVertex(@NativeType("GLenum") int mode, @NativeType("GLuint") int start, @NativeType("GLuint") int end, @NativeType("void const *") ShortBuffer indices, @NativeType("GLint") int basevertex) {
    }

    @Overwrite(remap = false)
    public static void glDrawRangeElementsBaseVertex(@NativeType("GLenum") int mode, @NativeType("GLuint") int start, @NativeType("GLuint") int end, @NativeType("void const *") IntBuffer indices, @NativeType("GLint") int basevertex) {
    }

    @Overwrite(remap = false)
    public static void glMultiDrawElementsBaseVertex(@NativeType("GLenum") int mode, @NativeType("GLsizei const *") IntBuffer count, @NativeType("GLenum") int type, @NativeType("void const **") PointerBuffer indices, @NativeType("GLint const *") IntBuffer basevertex) {
    }

    @Overwrite(remap = false)
    public static void glMultiDrawElementsBaseVertex(@NativeType("GLenum") int mode, @NativeType("GLsizei const *") int[] count, @NativeType("GLenum") int type, @NativeType("void const **") PointerBuffer indices, @NativeType("GLint const *") int[] basevertex) {
    }

    @Overwrite(remap = false)
    public static long glGetInteger64(@NativeType("GLenum") int pname) {
        return GlIntegerState.getInteger(pname);
    }

    @Overwrite(remap = false)
    public static void glGetInteger64v(@NativeType("GLenum") int pname, @NativeType("GLint64 *") LongBuffer params) {
        vulkanmod$put(params, GlIntegerState.getInteger(pname));
    }

    @Overwrite(remap = false)
    public static void glGetInteger64v(@NativeType("GLenum") int pname, @NativeType("GLint64 *") long[] params) {
        vulkanmod$put(params, GlIntegerState.getInteger(pname));
    }

    @Overwrite(remap = false)
    public static long glGetInteger64i(@NativeType("GLenum") int target, @NativeType("GLuint") int index) {
        return GlIntegerState.getInteger(target, index);
    }

    @Overwrite(remap = false)
    public static void glGetInteger64i_v(@NativeType("GLenum") int target, @NativeType("GLuint") int index, @NativeType("GLint64 *") LongBuffer data) {
        vulkanmod$put(data, GlIntegerState.getInteger(target, index));
    }

    @Overwrite(remap = false)
    public static void glGetInteger64i_v(@NativeType("GLenum") int target, @NativeType("GLuint") int index, @NativeType("GLint64 *") long[] data) {
        vulkanmod$put(data, GlIntegerState.getInteger(target, index));
    }

    @Overwrite(remap = false)
    public static long glGetBufferParameteri64(@NativeType("GLenum") int target, @NativeType("GLenum") int pname) {
        return GlBuffer.glGetBufferParameteri(target, pname);
    }

    @Overwrite(remap = false)
    public static void glGetBufferParameteri64v(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLint64 *") LongBuffer params) {
        vulkanmod$put(params, GlBuffer.glGetBufferParameteri(target, pname));
    }

    @Overwrite(remap = false)
    public static void glGetBufferParameteri64v(@NativeType("GLenum") int target, @NativeType("GLenum") int pname, @NativeType("GLint64 *") long[] params) {
        vulkanmod$put(params, GlBuffer.glGetBufferParameteri(target, pname));
    }

    @Overwrite(remap = false)
    public static void glTexImage2DMultisample(@NativeType("GLenum") int target, @NativeType("GLsizei") int samples, @NativeType("GLint") int internalformat, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height, @NativeType("GLboolean") boolean fixedsamplelocations) {
    }

    @Overwrite(remap = false)
    public static void glTexImage3DMultisample(@NativeType("GLenum") int target, @NativeType("GLsizei") int samples, @NativeType("GLint") int internalformat, @NativeType("GLsizei") int width, @NativeType("GLsizei") int height, @NativeType("GLsizei") int depth, @NativeType("GLboolean") boolean fixedsamplelocations) {
    }

    @Overwrite(remap = false)
    public static float glGetMultisamplef(@NativeType("GLenum") int pname, @NativeType("GLuint") int index) {
        return 0.0F;
    }

    @Overwrite(remap = false)
    public static void glGetMultisamplefv(@NativeType("GLenum") int pname, @NativeType("GLuint") int index, @NativeType("GLfloat *") FloatBuffer val) {
        vulkanmod$put(val, 0.0F);
    }

    @Overwrite(remap = false)
    public static void glGetMultisamplefv(@NativeType("GLenum") int pname, @NativeType("GLuint") int index, @NativeType("GLfloat *") float[] val) {
        vulkanmod$put(val, 0.0F);
    }

    @Overwrite(remap = false)
    public static void glGetSynciv(@NativeType("GLsync") long sync, @NativeType("GLenum") int pname, @NativeType("GLsizei *") IntBuffer length, @NativeType("GLint *") IntBuffer values) {
        vulkanmod$put(length, 1);
        vulkanmod$put(values, GlSync.getSynci(sync, pname));
    }

    @Overwrite(remap = false)
    public static void glGetSynciv(@NativeType("GLsync") long sync, @NativeType("GLenum") int pname, @NativeType("GLsizei *") int[] length, @NativeType("GLint *") int[] values) {
        vulkanmod$put(length, 1);
        vulkanmod$put(values, GlSync.getSynci(sync, pname));
    }

    @Overwrite(remap = false)
    public static void glSampleMaski(@NativeType("GLuint") int maskNumber, @NativeType("GLbitfield") int mask) {
    }

    @Overwrite(remap = false)
    public static void glProvokingVertex(@NativeType("GLenum") int mode) {
    }

    private static final int GL_COLOR_CLEAR_VALUE = 0x0C22;
}

