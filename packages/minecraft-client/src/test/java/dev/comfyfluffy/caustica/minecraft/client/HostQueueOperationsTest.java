package dev.comfyfluffy.caustica.minecraft.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Vulkan requires each queue to be externally synchronized for submits, presents, and waits, and every
 * queue for {@code vkDeviceWaitIdle}. The client redirects exactly these Blaze3D call sites, with these
 * targets, under the renderer device's queue lock; a host queue operation anywhere else would race
 * renderer threads, and a changed target would fail the required redirect at startup.
 */
final class HostQueueOperationsTest {
    private static final String BLAZE3D = "com/mojang/blaze3d/";
    private static final Set<String> QUEUE_OPERATIONS = Set.of(
            "vkQueueSubmit", "vkQueueSubmit2", "vkQueueSubmit2KHR", "vkQueueBindSparse",
            "vkQueuePresentKHR", "vkQueueWaitIdle", "vkDeviceWaitIdle");

    @Test
    void everyHostQueueOperationIsAtARedirectedCallSite() throws IOException, URISyntaxException {
        assertEquals(Set.of(
                "com/mojang/blaze3d/vulkan/VulkanGpuSurface.present()V -> org/lwjgl/vulkan/KHRSwapchain"
                        + ".vkQueuePresentKHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkPresentInfoKHR;)I",
                "com/mojang/blaze3d/vulkan/VulkanQueue$Submission.close()V -> org/lwjgl/vulkan/KHRSynchronization2"
                        + ".vkQueueSubmit2KHR(Lorg/lwjgl/vulkan/VkQueue;Lorg/lwjgl/vulkan/VkSubmitInfo2$Buffer;J)I",
                "com/mojang/blaze3d/vulkan/VulkanQueue.waitIdle()V -> org/lwjgl/vulkan/VK12"
                        + ".vkQueueWaitIdle(Lorg/lwjgl/vulkan/VkQueue;)I"), hostQueueOperations());
    }

    private static Set<String> hostQueueOperations() throws IOException, URISyntaxException {
        var anchor = HostQueueOperationsTest.class.getClassLoader()
                .getResource(BLAZE3D + "vulkan/VulkanQueue.class");
        assertNotNull(anchor);
        String location = anchor.toString();
        Set<String> operations = new TreeSet<>();
        if (location.startsWith("jar:")) {
            URI jar = URI.create(location.substring(0, location.indexOf("!/")));
            try (FileSystem classes = FileSystems.newFileSystem(jar, Map.of())) {
                scan(classes.getPath("/" + BLAZE3D), operations);
            }
        } else {
            Path queue = Path.of(anchor.toURI());
            scan(queue.getParent().getParent(), operations);
        }
        return operations;
    }

    private static void scan(Path root, Set<String> operations) throws IOException {
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".class")).toList()) {
                try (InputStream input = Files.newInputStream(file)) {
                    collect(new ClassReader(input), operations);
                }
            }
        }
    }

    private static void collect(ClassReader reader, Set<String> operations) {
        String owner = reader.getClassName();
        reader.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public MethodVisitor visitMethod(int access, String method, String descriptor, String signature,
                                             String[] exceptions) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitMethodInsn(int opcode, String callee, String name, String calleeDescriptor,
                                                boolean isInterface) {
                        if (callee.startsWith("org/lwjgl/vulkan/") && QUEUE_OPERATIONS.contains(name)) {
                            operations.add(owner + "." + method + descriptor + " -> " + callee + "." + name
                                    + calleeDescriptor);
                        }
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
    }
}
