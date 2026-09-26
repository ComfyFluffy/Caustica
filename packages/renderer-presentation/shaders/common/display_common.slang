// Shared display-chain definitions. presentation_probe.slang generates matching Java records from
// compiler-reflected layouts, with one entry point per push-constant type to satisfy SPIR-V's
// single reachable push-constant block requirement.

public typealias DevicePtr<T> = Ptr<T, Access.ReadWrite, AddressSpace.Device, Std430DataLayout>;

public static const float3 ACESCG_LUMA = float3(0.27222872, 0.67408177, 0.05368952);

// Normalized image coordinates keep the metering profile independent of render resolution.
public float exposureCenterWeight(float2 uv, float sigma, float floorWeight) {
    float2 centered = uv * 2.0 - 1.0;
    sigma = max(sigma, 0.01);
    float gaussian = exp(-0.5 * dot(centered, centered) / (sigma * sigma));
    return lerp(clamp(floorWeight, 0.0, 1.0), 1.0, gaussian);
}

public struct ExposureHistPush {
    public uint colorImageIndex;
    public uint depthImageIndex;
    public uint albedoImageIndex;
    public uint64_t histogramAddress;
    public uint stride;
    public float centerWeightSigma;
    public float centerWeightFloor;
};

public struct ExposureResolvePush {
    public uint64_t histogramAddress;
    public uint64_t stateAddress;
    public uint exposureImageIndex;
    public float key;
    public float minEv;
    public float maxEv;
    // EV-space adaptation time constants in seconds. A darkening scene requires increasing exposure.
    public float adaptDarken;
    public float adaptBrighten;
    public float frameTimeSeconds;
    public float evBias;
    public float lowPercentile;
    public float highPercentile;
    public float environmentWeightCap;
    public float curveScene0;
    public float curveCompensation0;
    public float curveScene1;
    public float curveCompensation1;
    public float curveScene2;
    public float curveCompensation2;
    public float curveScene3;
    public float curveCompensation3;
    public float emissiveWeightCap;
    // EV100_OFFSET - log2(preExposure) converts log2 of stored luminance to absolute scene EV100.
    public float evOffset;
    // Positive frame-latched multiplier already applied to the stored radiance; 1.0 disables it.
    public float preExposure;
    // resetSeq invalidates GPU exposure history without a host write or fence.
    public uint resetSeq;
};

// Persistent auto-exposure controller state. This shared definition is also reflected into
// ExposureStateData.java so host reads and initialization follow Slang's Std430 layout.
public struct ExposureState {
    public float previous;
    public uint initialized;
    public float evScene;
    public float evTarget;
    public float evApplied;
    public float clipLowFrac;
    public float clipHighFrac;
    public uint resetSeq;
    public float meteringEnvironmentScale;
    public float meteringEnvironmentFrac;
    public float curveCompensation;
    public float effectiveSlope;
    public float meteringEmissiveScale;
    public float meteringEmissiveFrac;
};

public struct DisplayPush {
    public uint outputImageIndex;
    public uint rtImageIndex;
    public uint exposureImageIndex;
    public uint hdrImageIndex;
    public uint toneLutIndex;
    public uint toneSamplerIndex;
    public uint hdrToneLutIndex;
    public uint hdrToneSamplerIndex;
    public uint lookLutIndex;
    public uint lookSamplerIndex;
    public float lutSize;       // toneLut/hdrToneLut texels per axis; both LUTs share one size (asserted at load)
    public float gamma;         // post-display artistic gamma; below 1 brightens midtones
    public float hdrPeakNits;   // mastering peak baked into the currently bound HDR ACES LUT
    public int lookEnabled;     // 0 = identity, 1 = apply the scene-referred ACES look LUT
    public float lookLutSize;   // lookLut texels per axis (independent of the output-transform LUT size)
    // Analytic tone-mapper inputs; the selected SDR and HDR mappers are specialization constants of
    // the display shader variant. Each mapper reads its controls from the leading parameters in order.
    public float paperWhiteNits;
    public float headroom;      // display peak over paper white
    public float sdrParam0;
    public float sdrParam1;
    public float sdrParam2;
    public float sdrParam3;
    public float sdrParam4;
    public float sdrParam5;
    public float sdrParam6;
    public float sdrParam7;
    public float hdrParam0;
    public float hdrParam1;
    public float hdrParam2;
    public float hdrParam3;
    public float hdrParam4;
    public float hdrParam5;
    public float hdrParam6;
    public float hdrParam7;
};

public struct DebugPresentPush {
    public uint outputImageIndex;
    public uint normalImageIndex;
    public uint albedoImageIndex;
    public uint depthImageIndex;
    public uint motionImageIndex;
    public uint specAlbedoImageIndex;
    public uint specMotionImageIndex;
    public uint sceneImageIndex;
    public uint exposureImageIndex;
    public uint traceRadianceImageIndex;
    public uint stablePlaneMetadataImageIndex;
    public uint64_t exposureStateAddress;
    public uint debugView;     // 1..15; this pass is only dispatched for debugView != 0
    public float centerWeightSigma;
    public float centerWeightFloor;
};
