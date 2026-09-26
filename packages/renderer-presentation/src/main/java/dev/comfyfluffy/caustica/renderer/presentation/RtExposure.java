package dev.comfyfluffy.caustica.renderer.presentation;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanDeviceContext;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.VulkanBarriers;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.RtDebugLabels;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuBuffer;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;
import dev.comfyfluffy.caustica.engine.vulkan.runtime.GraphicsUse;
import dev.comfyfluffy.caustica.renderer.presentation.gen.ExposureStateData;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VK13;
import org.lwjgl.vulkan.VK14;
import org.lwjgl.vulkan.VkBufferCopy2;
import org.lwjgl.vulkan.VkCopyBufferInfo2;
import org.lwjgl.vulkan.VkBufferMemoryBarrier2;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Owns the display exposure value shared by the RT compositor's display-mapping passes. */
public final class RtExposure {
    private static final Logger LOGGER = LoggerFactory.getLogger(RtExposure.class);
    /** {@code log2(100 / 12.5)} maps scene luminance in cd/m² onto the EV100 metering scale. */
    private static final float EV100_OFFSET = (float) (Math.log(8.0) / Math.log(2.0));
    private Settings settings;
    private GpuImage image;
    private GpuBuffer histogram;
    private GpuBuffer state;
    private RtExposureReadbacks<GpuBuffer> stateReadbacks;
    private RtExposureReadbacks<GpuBuffer>.Reservation pendingStateReadback;
    private boolean stateReadbackRecorded;
    private long completedFrameId = -1;
    private ExposureStateData completedState;
    private RtExposurePipeline pipeline;
    private boolean logged;
    private long lastFrameNanos;
    private boolean resetRequested = true;
    private int resetSequence;
    /** This frame's latched pre-exposure; see {@link #beginFrame(GraphicsUse, long)}. */
    private float framePreExposure = 1.0f;
    private long frameId;

    private static final int STATE_READBACK_SLOTS = 6;

    public record Settings(String mode, String response, float manualEv, float key,
            float adaptDarken, float adaptBrighten, float lowPercentile, float highPercentile,
            int stride, float centerWeightSigma, float centerWeightFloor,
            float environmentWeightCap, float emissiveWeightCap,
            boolean preExposure, float gamma) {
    }

    public RtExposure(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void configure(Settings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public GpuImage image() {
        return image;
    }

    public boolean ready() {
        return image != null;
    }

    public GpuBuffer stateBuffer() {
        return state;
    }

    /** Immutable exposure values attached to a residual-exposed EXR capture. */
    public record CaptureMetadata(
            float preExposure,
            float residualExposure,
            float absoluteExposure,
            String mode,
            float evScene,
            float evTarget,
            float evApplied
    ) {
    }

    /**
     * Snapshot the controller after the capture copy has completed.
     *
     * <p>{@code residualExposure} is read from the same 1x1 GPU image that the display shader samples.
     * The absolute multiplier can therefore be reconstructed exactly as
     * {@code preExposure * residualExposure}, even when auto exposure corrected a stale prediction.
     */
    public CaptureMetadata captureMetadata(float residualExposure) {
        if (!Float.isFinite(residualExposure) || residualExposure <= 0.0f) {
            throw new IllegalArgumentException("Invalid residual exposure " + residualExposure);
        }
        Mode currentMode = mode();
        float pre = preExposure();
        float absolute = pre * residualExposure;
        if (currentMode != Mode.AUTO || state == null || state.mapped() == 0L) {
            float ev = settings.manualEv();
            return new CaptureMetadata(pre, residualExposure, absolute, currentMode.configName,
                    Float.NaN, ev, ev);
        }
        state.invalidate();
        ExposureStateData snapshot = readState();
        return new CaptureMetadata(pre, residualExposure, absolute, currentMode.configName,
                snapshot.evScene(), snapshot.evTarget(), snapshot.evApplied());
    }

    public void ensureResources(VulkanDeviceContext ctx) {
        if (image == null) {
            image = ctx.createStorageImage(1, 1, VK10.VK_FORMAT_R32_SFLOAT, "display exposure");
        }
        // The final debug pass always binds the state buffer, including in manual mode. Keep this tiny
        // resource permanently available; histogram/pipeline allocation remains auto-only.
        if (state == null) {
            state = ctx.createBuffer(ExposureStateData.BYTE_SIZE,
                    VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                    true, "exposure state");
            resetAutoHistory();
        }
        if (mode() == Mode.AUTO) {
            if (stateReadbacks == null) {
                var created = new java.util.ArrayList<GpuBuffer>(STATE_READBACK_SLOTS);
                try {
                    for (int i = 0; i < STATE_READBACK_SLOTS; i++) {
                        created.add(ctx.createReadbackBuffer(
                                ExposureStateData.BYTE_SIZE, "exposure state readback " + i));
                    }
                } catch (Throwable t) {
                    created.forEach(GpuBuffer::destroy);
                    throw t;
                }
                stateReadbacks = new RtExposureReadbacks<>(created, buffer -> {
                    buffer.invalidate();
                    return ExposureStateData.read(MemoryUtil.memByteBuffer(
                            buffer.mapped(), ExposureStateData.BYTE_SIZE).order(ByteOrder.nativeOrder()));
                }, buffer -> ctx.deferDestroy(buffer::destroy));
            }
            if (histogram == null) {
                // Separate ordinary-surface/environment/emissive histograms let resolve enforce both
                // population caps exactly without a second full-image dispatch.
                histogram = ctx.createBuffer(768L * Integer.BYTES,
                        VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT, false,
                        "exposure histogram");
            }
            if (pipeline == null) {
                pipeline = RtExposurePipeline.create(ctx);
            }
        }
        logOnce();
    }

    public void record(VulkanDeviceContext ctx, VkCommandBuffer cmd, MemoryStack stack,
                       GpuImage traceColor, GpuImage guideDepth, GpuImage guideAlbedo) {
        if (image == null) {
            throw new IllegalStateException("RT exposure image not created");
        }
        if (mode() == Mode.AUTO) {
            recordAuto(ctx, cmd, stack, traceColor, guideDepth, guideAlbedo);
            return;
        }
        try (var ignored = RtDebugLabels.scope(ctx, cmd, "exposure manual write")) {
            VkClearColorValue color = VkClearColorValue.calloc(stack);
            // Divide out raygen's latched scale. With manual pre-exposure enabled the residual is 1;
            // with it disabled this is the full manual exposure multiplier.
            color.float32(0, manualExposureScale() / Math.max(preExposure(), 1.0e-12f));
            VkImageSubresourceRange.Buffer range = VkImageSubresourceRange.calloc(1, stack);
            range.get(0).aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT)
                    .baseMipLevel(0).levelCount(1).baseArrayLayer(0).layerCount(1);
            VK10.vkCmdClearColorImage(cmd, image.image(), VK10.VK_IMAGE_LAYOUT_GENERAL, color, range);
        }
    }

    public void destroy() {
        if (pipeline != null) {
            pipeline.destroy();
            pipeline = null;
        }
        if (histogram != null) {
            histogram.destroy();
            histogram = null;
        }
        if (state != null) {
            state.destroy();
            state = null;
        }
        if (stateReadbacks != null) {
            stateReadbacks.close();
            stateReadbacks = null;
        }
        if (image != null) {
            image.destroy();
            image = null;
        }
        resetRequested = true;
        resetSequence = 0;
        pendingStateReadback = null;
        stateReadbackRecorded = false;
        completedFrameId = -1;
        completedState = null;
        framePreExposure = 1.0f;
    }

    // Manual mode's exposure scale, also used as the auto-history seed (resetAutoHistory) so the very
    // first auto-exposure frame starts from the dialed-in EV bias instead of a bare 1.0.
    private float manualExposureScale() {
        return Math.clamp((float) Math.pow(2.0, settings.manualEv()), 1.0e-8f, 1.0e8f);
    }

    private void recordAuto(VulkanDeviceContext ctx, VkCommandBuffer cmd, MemoryStack stack,
                            GpuImage traceColor, GpuImage guideDepth, GpuImage guideAlbedo) {
        if (pipeline == null || histogram == null || state == null) {
            throw new IllegalStateException("RT auto exposure resources not created");
        }
        try (var ignored = RtDebugLabels.scope(ctx, cmd, "exposure histogram clear")) {
            VK10.vkCmdFillBuffer(cmd, histogram.handle(), 0, histogram.size(), 0);
        }
        VulkanBarriers.memoryBarrier(cmd, stack);
        AutoConfig config = autoConfig();
        pipeline.dispatchHistogram(cmd, traceColor, guideDepth, guideAlbedo, histogram, config);
        VulkanBarriers.memoryBarrier(cmd, stack);
        pipeline.dispatchResolve(cmd, histogram, image, state, config, frameTimeSeconds());
    }

    /**
     * Copies the GPU-owned controller state into this frame's guarded host-readback slot. The slot is not
     * consumed until its graphics timeline value completes, so the host never races the live storage buffer.
     */
    public void recordStateReadback(VkCommandBuffer cmd, MemoryStack stack) {
        if (mode() != Mode.AUTO || pendingStateReadback == null) {
            return;
        }
        VkBufferMemoryBarrier2.Buffer toTransfer = VkBufferMemoryBarrier2.calloc(1, stack);
        toTransfer.get(0).sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_TRANSFER_READ_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(state.handle())
                .offset(0L)
                .size(ExposureStateData.BYTE_SIZE);
        VK14.vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack)
                .sType$Default().pBufferMemoryBarriers(toTransfer));

        VkBufferCopy2.Buffer copy = VkBufferCopy2.calloc(1, stack);
        copy.get(0).sType$Default().srcOffset(0L).dstOffset(0L).size(ExposureStateData.BYTE_SIZE);
        VK13.vkCmdCopyBuffer2(cmd, VkCopyBufferInfo2.calloc(stack).sType$Default()
                .srcBuffer(state.handle()).dstBuffer(pendingStateReadback.buffer().handle()).pRegions(copy));
        VkBufferMemoryBarrier2.Buffer toHost = VkBufferMemoryBarrier2.calloc(1, stack);
        toHost.get(0).sType$Default()
                .srcStageMask(VK13.VK_PIPELINE_STAGE_2_COPY_BIT)
                .srcAccessMask(VK13.VK_ACCESS_2_TRANSFER_WRITE_BIT)
                .dstStageMask(VK13.VK_PIPELINE_STAGE_2_HOST_BIT)
                .dstAccessMask(VK13.VK_ACCESS_2_HOST_READ_BIT)
                .srcQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .dstQueueFamilyIndex(VK10.VK_QUEUE_FAMILY_IGNORED)
                .buffer(pendingStateReadback.buffer().handle())
                .offset(0L).size(ExposureStateData.BYTE_SIZE);
        VK14.vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack)
                .sType$Default().pBufferMemoryBarriers(toHost));
        stateReadbackRecorded = true;
    }

    /** Attach the readback copy only after the command buffer has been accepted for frame submission. */
    public void markStateReadbackUse(GraphicsUse graphicsUse) {
        if (pendingStateReadback == null) {
            return;
        }
        if (stateReadbackRecorded) {
            var slot = pendingStateReadback;
            long submittedFrame = frameId;
            float submittedPreExposure = framePreExposure;
            int submittedResetSequence = resetSequence;
            graphicsUse.whenSubmitted(() -> slot.submitted(
                    submittedFrame, submittedPreExposure, submittedResetSequence));
        }
        pendingStateReadback = null;
    }

    /** Latest completed controller values for the optional F3 exposure entry. */
    public String debugSummaryLine() {
        if (state == null) {
            return null;
        }
        if (mode() != Mode.AUTO) {
            return String.format(java.util.Locale.ROOT, "Exposure: manual %.2f EV", settings.manualEv());
        }
        ExposureStateData snapshot = completedState;
        if (snapshot == null) {
            return null;
        }
        float evScene = snapshot.evScene();
        float evTarget = snapshot.evTarget();
        float evApplied = snapshot.evApplied();
        Response response = response();
        String clamp = evTarget <= response.minEv() + 0.01f ? " (min clamp)"
                : evTarget >= response.maxEv() - 0.01f ? " (max clamp)" : "";
        return String.format(java.util.Locale.ROOT, "Exposure: EV100 %.2f, applied %.2f EV%s",
                evScene, evApplied, clamp);
    }

    private float frameTimeSeconds() {
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 1.0f / 60.0f
                : Math.clamp((now - lastFrameNanos) / 1_000_000_000.0f, 1.0f / 240.0f, 0.25f);
        lastFrameNanos = now;
        return dt;
    }

    private void resetAutoHistory() {
        if (state == null || state.mapped() == 0L) {
            return;
        }
        // The uninitialized state makes resolve snap to its computed target. Pre-exposure adopts that
        // result once readback completes; the residual multiplier corrects the prediction until then.
        new ExposureStateData(
                manualExposureScale(), 0,
                0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0,
                1.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f
        ).write(stateDataBuffer());
        state.flush(0L, ExposureStateData.BYTE_SIZE);
        lastFrameNanos = 0L;
    }

    private void logOnce() {
        if (logged) {
            return;
        }
        logged = true;
        Mode mode = mode();
        AutoConfig autoConfig = autoConfig();
        String exposureText = mode == Mode.AUTO
                ? "auto(response=" + autoConfig.response.configName + ", key=" + autoConfig.key
                + ", minEv=" + autoConfig.minEv + ", maxEv=" + autoConfig.maxEv
                + ", adaptDarken=" + autoConfig.adaptDarken + ", adaptBrighten=" + autoConfig.adaptBrighten
                + ", evBias=" + autoConfig.evBias + ", percentiles=" + autoConfig.lowPercentile
                + ".." + autoConfig.highPercentile + ", stride=" + autoConfig.stride
                + ", centerWeight=" + autoConfig.centerWeightSigma + "/" + autoConfig.centerWeightFloor
                + ", environmentCap=" + autoConfig.environmentWeightCap
                + ", emissiveCap=" + autoConfig.emissiveWeightCap
                + ")"
                : Float.toString(manualExposureScale());
        LOGGER.info("RT display exposure: mode={}, exposure={}, "
                        + "tonemap=aces2.0(gamma={}), DLSS-RR pre-exposure=renderer",
                mode.configName, exposureText, settings.gamma());
    }

    private Mode mode() {
        return Mode.parse(settings.mode());
    }

    private Response response() {
        return Response.parse(settings.response());
    }

    private AutoConfig autoConfig() {
        Response response = response();
        return new AutoConfig(
                response,
                settings.key(),
                response.minEv(),
                response.maxEv(),
                settings.adaptDarken(),
                settings.adaptBrighten(),
                settings.manualEv(),
                settings.lowPercentile(),
                settings.highPercentile(),
                settings.stride(),
                settings.centerWeightSigma(),
                settings.centerWeightFloor(),
                settings.environmentWeightCap(),
                settings.emissiveWeightCap(),
                preExposure(),
                resetSequence);
    }

    /**
     * Latches this frame's pre-exposure. MUST be called once per frame before the world push
     * constants are written, and must not be re-latched afterwards.
     *
     * <p>The raygen multiply and the resolve's divide have to use the <em>same</em> value or they
     * stop cancelling and the frame comes out mis-scaled. Both read {@link #preExposure()}, but at
     * different points in CPU time. The completed readback can be several frames old, so latching once
     * ensures both consumers use one prediction; the residual absorbs whatever it failed to predict.
     */
    public void beginFrame(GraphicsUse graphicsUse, long frameId) {
        this.frameId = frameId;
        Mode currentMode = mode();
        boolean reset = currentMode == Mode.AUTO && resetRequested;
        if (reset) {
            resetSequence++;
            lastFrameNanos = 0L;
            completedState = null;
            resetRequested = false;
        }

        pendingStateReadback = null;
        stateReadbackRecorded = false;
        if (currentMode == Mode.AUTO && stateReadbacks != null) {
            var feedback = stateReadbacks.latest(resetSequence);
            if (feedback != null && feedback.frameId() > completedFrameId) {
                completedFrameId = feedback.frameId();
                completedState = feedback.state();
                ExposureEvent.record(feedback.frameId(), frameId, feedback.preExposure(),
                        feedback.resetSequence(), completedState);
            }
            pendingStateReadback = stateReadbacks.acquire();
            if (pendingStateReadback != null) graphicsUse.keepAlive(pendingStateReadback);
        }

        // On a reset frame the previous scene's exposure is a poor storage-scale prediction. Unity is
        // neutral and the resolve removes it exactly; subsequent frames use completed feedback.
        framePreExposure = reset ? 1.0f : computePreExposure();
        if (currentMode == Mode.MANUAL) {
            ExposureEvent.recordManual(frameId, framePreExposure, manualExposureScale());
        }
    }

    /** Request a GPU-side history reset on the next auto-exposure frame. */
    public void requestReset() {
        resetRequested = true;
    }

    /**
     * The scalar raygen multiplies into scene radiance before the fp16 write, so stored values sit near
     * {@code key} at any absolute
     * scene brightness instead of spanning the ~26 EV that physical units require.
     *
     * <p>Correctness does not depend on this being <em>current</em> — the display pass divides by
     * exactly the same latched value, so any pre-exposure cancels algebraically. Staleness only
     * affects how well-centred the stored values are, which is why completed feedback is sufficient and
     * no fence is needed. 1.0 disables the mechanism.
     */
    public float preExposure() {
        return framePreExposure;
    }

    private float computePreExposure() {
        if (!settings.preExposure()) {
            return 1.0f;
        }
        // Manual mode has a known fixed absolute exposure, so pre-exposing by it makes the residual
        // exactly 1.0 -- the best-centred choice available, and it needs no readback.
        if (mode() != Mode.AUTO) {
            return manualExposureScale();
        }
        if (completedState == null) {
            return 1.0f;
        }
        // The controller's EV limits already bound this scale. Bright scenes need values below 1e-4,
        // so preserve every positive finite prediction without an additional exposure floor.
        float previous = completedState.previous();
        return Float.isFinite(previous) && previous > 0.0f ? previous : 1.0f;
    }

    private ByteBuffer stateDataBuffer() {
        return MemoryUtil.memByteBuffer(state.mapped(), ExposureStateData.BYTE_SIZE).order(ByteOrder.nativeOrder());
    }

    private ExposureStateData readState() {
        return ExposureStateData.read(stateDataBuffer());
    }

    record AutoConfig(Response response, float key, float minEv, float maxEv, float adaptDarken, float adaptBrighten,
                      float evBias,
                      float lowPercentile, float highPercentile, int stride,
                      float centerWeightSigma, float centerWeightFloor, float environmentWeightCap,
                      float emissiveWeightCap,
                      float preExposure, int resetSequence) {
        /**
         * Offset taking the resolve's {@code log2(metered stored luminance)} to EV100. The metered
         * buffer holds {@code L * preExposure}, so the pre-exposure has to come back out before the
         * unit convention's offset applies.
         */
        float evOffset() {
            return EV100_OFFSET - (float) (Math.log(Math.max(preExposure, 1.0e-12f)) / Math.log(2.0));
        }
    }

    /**
     * Auto-exposure response: compensation EV as a function of metered scene EV100, linear between four
     * strictly increasing knots and constant beyond the end knots, with the absolute exposure bounds that
     * belong to it. The resolve renders the metered mean at {@code key * 2^(evBias + compensation)} and
     * clamps the absolute multiplier to {@code [2^minEv, 2^maxEv]}, where a multiplier of 1 maps 1 cd/m²
     * to display-linear 1.
     */
    enum Response {
        /**
         * The 1:5 adaptation law {@code clamp(0.2 * (EV100 - 17.45), -5, 0)}: a scene metering at the noon
         * reference EV100 17.45 renders at key, darker scenes render darker by one fifth of their luminance
         * ratio down to the -5 EV floor, and compensation never brightens. The upper bound is the night
         * ceiling: below about 6.8 cd/m² (EV100 5.8) the law demands more than 2^-7.5734, so darker scenes
         * render at their luminance times that bound. The lower bound is a guard rail outside the law's
         * working range.
         */
        ADAPTATION("adaptation", new float[]{-7.55f, 4.95f, 17.45f, 18.45f},
                new float[]{-5.0f, -2.5f, 0.0f, 0.0f}, -30.5734f, -7.5734f),
        /** Brightens scenes above EV100 8 by up to one EV. */
        CURVE("curve", new float[]{-2.0f, 2.0f, 8.0f, 15.0f},
                new float[]{-3.0f, -2.0f, 0.0f, 1.0f}, -15.0f, -2.0f);

        private final String configName;
        private final float[] sceneEv;
        private final float[] compensationEv;
        private final float minEv;
        private final float maxEv;

        Response(String configName, float[] sceneEv, float[] compensationEv, float minEv, float maxEv) {
            this.configName = configName;
            this.sceneEv = sceneEv;
            this.compensationEv = compensationEv;
            this.minEv = minEv;
            this.maxEv = maxEv;
        }

        float sceneEv(int knot) {
            return sceneEv[knot];
        }

        float compensationEv(int knot) {
            return compensationEv[knot];
        }

        float minEv() {
            return minEv;
        }

        float maxEv() {
            return maxEv;
        }

        static Response parse(String value) {
            for (Response response : values()) {
                if (response.configName.equalsIgnoreCase(value)) {
                    return response;
                }
            }
            return ADAPTATION;
        }
    }

    private enum Mode {
        MANUAL("manual"),
        AUTO("auto");

        private final String configName;

        Mode(String configName) {
            this.configName = configName;
        }

        static Mode parse(String value) {
            if (value != null) {
                for (Mode mode : values()) {
                    if (mode.configName.equalsIgnoreCase(value)) {
                        return mode;
                    }
                }
            }
            return AUTO;
        }
    }
}
