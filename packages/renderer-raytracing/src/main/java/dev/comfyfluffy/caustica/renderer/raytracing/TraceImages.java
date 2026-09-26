package dev.comfyfluffy.caustica.renderer.raytracing;

import dev.comfyfluffy.caustica.engine.vulkan.runtime.GpuImage;

import java.util.Objects;

/** Images produced by tracing and consumed by reconstruction or presentation. */
public record TraceImages(
        GpuImage traceColor,
        GpuImage stablePlaneMetadata,
        GpuImage normalRoughness,
        GpuImage diffuseAlbedo,
        GpuImage depth,
        GpuImage primaryDepth,
        GpuImage motion,
        GpuImage specularAlbedo,
        GpuImage specularMotion,
        GpuImage responsivity,
        GpuImage diffuseRadianceHitDistance,
        GpuImage specularRadianceHitDistance,
        GpuImage nrdViewZ,
        GpuImage nrdMotion,
        GpuImage nrdDisocclusionThresholdMix,
        GpuImage denoisedDiffuseRadianceHitDistance,
        GpuImage denoisedSpecularRadianceHitDistance,
        GpuImage nrdStableRadiance,
        GpuImage reconstructedColor,
        GpuImage sceneEffectColorA, GpuImage sceneEffectColorB) {
    public TraceImages {
        Objects.requireNonNull(traceColor, "traceColor");
        Objects.requireNonNull(stablePlaneMetadata, "stablePlaneMetadata");
        Objects.requireNonNull(normalRoughness, "normalRoughness");
        Objects.requireNonNull(diffuseAlbedo, "diffuseAlbedo");
        Objects.requireNonNull(depth, "depth");
        Objects.requireNonNull(primaryDepth, "primaryDepth");
        Objects.requireNonNull(motion, "motion");
        Objects.requireNonNull(specularAlbedo, "specularAlbedo");
        Objects.requireNonNull(specularMotion, "specularMotion");
        Objects.requireNonNull(responsivity, "responsivity");
        Objects.requireNonNull(diffuseRadianceHitDistance, "diffuseRadianceHitDistance");
        Objects.requireNonNull(specularRadianceHitDistance, "specularRadianceHitDistance");
        Objects.requireNonNull(nrdViewZ, "nrdViewZ");
        Objects.requireNonNull(nrdMotion, "nrdMotion");
        Objects.requireNonNull(nrdDisocclusionThresholdMix, "nrdDisocclusionThresholdMix");
        Objects.requireNonNull(denoisedDiffuseRadianceHitDistance, "denoisedDiffuseRadianceHitDistance");
        Objects.requireNonNull(denoisedSpecularRadianceHitDistance, "denoisedSpecularRadianceHitDistance");
        Objects.requireNonNull(nrdStableRadiance, "nrdStableRadiance");
        Objects.requireNonNull(reconstructedColor, "reconstructedColor");
        Objects.requireNonNull(sceneEffectColorA, "sceneEffectColorA");
        Objects.requireNonNull(sceneEffectColorB, "sceneEffectColorB");
    }
}
