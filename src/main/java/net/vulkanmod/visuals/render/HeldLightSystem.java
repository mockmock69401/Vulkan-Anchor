package net.vulkanmod.visuals.render;

import net.vulkanmod.visuals.vk.LooseUniformWriter;
import net.vulkanmod.visuals.vk.VkGpuBuffer;
import net.vulkanmod.visuals.vk.VkUploadStream;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT;

/**
 * P7k design 3: the held light's grid around the player's eye, its ways worked out again when the eye block or the held light changes
 * and every REFRESH_TICKS while there is light (blocks change); uploaded only then. scene_copy reads it at COPY_HELD and, each frame,
 * the eye's exact position and the held level at COPY_HELD_EYE (2026-10-31: the light follows the eye between spreads).
 */
public final class HeldLightSystem {
    /** scene_copy.comp: the grid's corner minus the light volume's camera block, 1 when on. */
    public static final int COPY_HELD = 24;
    /** scene_copy.comp: the eye's exact position minus the light volume's camera block, and the held level (0 when off). */
    public static final int COPY_HELD_EYE = 34;
    static final int REFRESH_TICKS = 10;

    private final VkGpuBuffer buffer = VkGpuBuffer.device(HeldLight.BYTES, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
    private int x, y, z, level;
    private long spreadTick = Long.MIN_VALUE;
    private boolean on;

    /** Before the frame's upload commit. enabled: the setting and the per-pixel light. */
    public void update(VkUploadStream upload, boolean enabled) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        int held = enabled && player != null && level != null ? HeldLight.held(emission(player.getMainHandItem()), emission(player.getOffhandItem())) : 0;
        if (held <= 0) {
            this.on = false;
            this.level = 0;
            return;
        }
        BlockPos eye = BlockPos.containing(player.getEyePosition());
        long tick = level.getGameTime();
        boolean moved = !this.on || eye.getX() != this.x || eye.getY() != this.y || eye.getZ() != this.z || held != this.level;
        //2026-10-30 (audit): a game time behind the last spread (another world) spreads again (it never refreshed there)
        if (!moved && tick >= this.spreadTick && tick - this.spreadTick < REFRESH_TICKS) return;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        byte[] cells = HeldLight.distances(eye.getX(), eye.getY(), eye.getZ(),
                (bx, by, bz) -> level.getBlockState(pos.set(bx, by, bz)).getLightBlock(level, pos));
        ByteBuffer bytes = MemoryUtil.memAlloc(HeldLight.BYTES);
        try {
            HeldLight.pack(cells, bytes);
            upload.upload(this.buffer, 0, bytes);
        } finally {
            MemoryUtil.memFree(bytes);
        }
        this.x = eye.getX();
        this.y = eye.getY();
        this.z = eye.getZ();
        this.level = held;
        this.spreadTick = tick;
        this.on = true;
    }

    /** An item's light: a block item's block's, a lava bucket's 15, else 0. */
    static int emission(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0;
        if (stack.is(Items.LAVA_BUCKET)) return 15;
        if (stack.getItem() instanceof BlockItem block) return block.getBlock().defaultBlockState().getLightEmission();
        return 0;
    }

    /** The grid's corner relative to the light volume's camera block (cameraBlock), and whether it is on; the eye and the level. */
    public void writeCopy(LooseUniformWriter w, float[] cameraBlock) {
        Minecraft mc = Minecraft.getInstance();
        if (!this.on || cameraBlock == null || mc.player == null) {
            w.putVec4(COPY_HELD, 0, 0, 0, 0);
            if (w.has(COPY_HELD_EYE)) w.putVec4(COPY_HELD_EYE, 0, 0, 0, 0);
            return;
        }
        //2026-10-31: the eye this frame (interpolated as the camera is), so the light moves with it, not in block steps
        net.minecraft.world.phys.Vec3 eye = mc.player.getEyePosition(mc.getTimer().getGameTimeDeltaPartialTick(true));
        if (w.has(COPY_HELD_EYE)) w.putVec4(COPY_HELD_EYE, (float) (eye.x - cameraBlock[0]), (float) (eye.y - cameraBlock[1]),
                (float) (eye.z - cameraBlock[2]), this.level);
        //2026-10-30 (audit): in double, so the corner stays exact far from the origin (an int past 2^24 is not a float)
        w.putVec4(COPY_HELD, (float) (this.x - HeldLight.RADIUS - (double) cameraBlock[0]), (float) (this.y - HeldLight.RADIUS - (double) cameraBlock[1]),
                (float) (this.z - HeldLight.RADIUS - (double) cameraBlock[2]), 1);
    }

    public boolean on() {
        return this.on;
    }

    public VkGpuBuffer buffer() {
        return this.buffer;
    }

    public void free() {
        this.buffer.free();
    }
}
