package dev.comfyfluffy.caustica.rt.accel;

import org.lwjgl.util.vma.Vma;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkDevice;

/**
 * A VMA-backed image + view, created in {@code VK_IMAGE_LAYOUT_GENERAL}. Used for RT output
 * storage images. Created via {@link dev.comfyfluffy.caustica.rt.RtContext#createStorageImage} (or
 * {@link dev.comfyfluffy.caustica.rt.RtContext#createStorageImage3D} for a volume); freed with
 * {@link #destroy()}.
 */
public final class RtImage {
    public final long image;
    public final long allocation;
    public final long view;
    public final int width;
    public final int height;
    /** Slice count of a 3D image; 1 for the ordinary 2D case. */
    public final int depth;

    private final long vma;
    private final VkDevice vk;
    private boolean destroyed;

    public RtImage(long vma, VkDevice vk, long image, long allocation, long view, int width, int height) {
        this(vma, vk, image, allocation, view, width, height, 1);
    }

    public RtImage(long vma, VkDevice vk, long image, long allocation, long view, int width, int height,
                   int depth) {
        this.depth = depth;
        this.vma = vma;
        this.vk = vk;
        this.image = image;
        this.allocation = allocation;
        this.view = view;
        this.width = width;
        this.height = height;
    }

    public void destroy() {
        if (destroyed) {
            return;
        }
        if (view != 0L) {
            VK10.vkDestroyImageView(vk, view, null);
        }
        if (image != 0L) {
            Vma.vmaDestroyImage(vma, image, allocation);
        }
        destroyed = true;
    }
}
