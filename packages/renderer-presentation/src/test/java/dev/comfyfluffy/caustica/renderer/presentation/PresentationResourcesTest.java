package dev.comfyfluffy.caustica.renderer.presentation;

import org.junit.jupiter.api.Test;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

final class PresentationResourcesTest {
    @Test
    void failingImageReleaseStillDestroysEveryImageAndDetachesThem() throws Exception {
        var settings = new RtExposure.Settings("manual", "adaptation", 0, 0.18f, 1, 1,
                0.1f, 0.9f, 1, 1, 0, 1, 1, true, 2.2f);
        var resources = new PresentationResources(settings);
        var releases = new AtomicInteger();
        var failure = new IllegalStateException("image release failed");
        for (var field : PresentationResources.class.getDeclaredFields()) {
            if (field.getType() != GpuImage.class) continue;
            var image = Proxy.newProxyInstance(GpuImage.class.getClassLoader(), new Class<?>[]{GpuImage.class},
                    (proxy, method, args) -> {
                        if (!method.getName().equals("destroy")) throw new AssertionError(method);
                        if (releases.incrementAndGet() <= 2) throw failure;
                        return null;
                    });
            field.setAccessible(true);
            field.set(resources, image);
        }

        assertSame(failure, assertThrows(IllegalStateException.class, resources::destroy));
        assertEquals(4, releases.get());
        resources.destroy();
        assertEquals(4, releases.get());
        assertFalse(resources.matches(1920, 1080));
        assertThrows(NullPointerException.class, resources::displayImage);
    }

    @Test
    void startsUnsizedWithItsOwnExposureController() {
        RtExposure.Settings settings = new RtExposure.Settings("manual", "adaptation", 0.0f, 0.18f,
                1.0f, 1.0f, 0.1f, 0.9f, 1, 1.0f, 0.0f,
                1.0f, 1.0f, true, 2.2f);
        PresentationResources resources = new PresentationResources(settings);
        assertFalse(resources.matches(1920, 1080));
        assertSame(resources.exposure(), resources.exposure());
    }

}
