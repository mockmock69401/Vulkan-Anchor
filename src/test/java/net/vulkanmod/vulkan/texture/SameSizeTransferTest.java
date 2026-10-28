package net.vulkanmod.vulkan.texture;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** How a transfer between two images of one format and one size is recorded. */
class SameSizeTransferTest {
    @Test
    void rowsInTheSameDirectionAreOneCopy() {
        assertEquals(ImageUtil.TransferKind.COPY, ImageUtil.sameSizeTransfer(true, true));
        assertEquals(ImageUtil.TransferKind.COPY, ImageUtil.sameSizeTransfer(true, false));
    }

    @Test
    void mirroredRowsAreOneNearestBlitWhenTheFormatCanBeBlitted() {
        assertEquals(ImageUtil.TransferKind.MIRROR_BLIT, ImageUtil.sameSizeTransfer(false, true));
    }

    @Test
    void mirroredRowsFallBackToACopyPerRowOtherwise() {
        assertEquals(ImageUtil.TransferKind.ROW_COPY, ImageUtil.sameSizeTransfer(false, false));
    }
}
