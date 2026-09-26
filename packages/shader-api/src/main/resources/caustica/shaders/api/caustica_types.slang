module caustica_types;

/** Must equal dev.comfyfluffy.caustica.api.program.ProgramAbi.VERSION. */
public static const uint CAUSTICA_SHADER_ABI_VERSION = 6u;

// Extension colours and radiance are scene-linear ACEScg. Spatial shader values are in scene units unless
// documented otherwise. metersPerSceneUnit converts them to physical distance. Directions are normalized
// and point away from the shaded position.
/**
 * SurfaceInput.evaluationFlags: this hit is the one the reconstructor sees, so a surface whose normal
 * animates should also fill `previous_geometry_normal` — that pair is what a reflection reprojects with.
 * Unset on every other hit, where the previous normal is never read and evaluating it would be waste.
 */
public static const uint SURFACE_RECONSTRUCTION_ANCHOR = 0x1u;
/** Enables optional time-varying procedural surface and volume evaluation for this frame. */
public static const uint EVALUATION_ANIMATION_ENABLED = 0x2u;

/** Read-only std430 device-address view used to interpret extension-owned shader data words. */
public typealias ShaderDataPtr<T> = Ptr<T, Access.Read, AddressSpace.Device, Std430DataLayout>;

/**
 * Stable pushed root available to every extension shader stage. `compositionData` addresses the generated
 * immutable table of extension roots. Descriptor heaps are SPIR-V built-ins and therefore need no address
 * in pushed data; typed indices and lookup helpers live in `caustica_resources`.
 */
public struct ShaderRootData {
    public uint abiVersion;
    public uint reserved;
    public uint64_t compositionData;
};

/** Three rows of an affine object-to-scene transform. Translation occupies each row's fourth component. */
public struct AffineTransform {
    public float4 row0;
    public float4 row1;
    public float4 row2;
};

/** Inverse-transpose linear transform used for object-space normals. */
public struct NormalTransform {
    public float3 row0;
    public float3 row1;
    public float3 row2;
};

public float3 transformPosition(AffineTransform transform, float3 position) {
    float4 homogeneous = float4(position, 1.0);
    return float3(dot(transform.row0, homogeneous), dot(transform.row1, homogeneous),
            dot(transform.row2, homogeneous));
}

public float3 transformDirection(AffineTransform transform, float3 direction) {
    return float3(dot(transform.row0.xyz, direction), dot(transform.row1.xyz, direction),
            dot(transform.row2.xyz, direction));
}

public float3 transformNormal(NormalTransform transform, float3 normal) {
    return float3(dot(transform.row0, normal), dot(transform.row1, normal), dot(transform.row2, normal));
}

/** Homogeneous transport properties produced by a volume implementation at its boundary. */
public struct VolumeProperties {
    /**
     * Finite, non-negative Beer-Lambert absorption coefficient per scene unit. Vacuum is zero.
     * Volume transport supports homogeneous absorption without participating scattering.
     */
    public float3 absorptionCoefficient = float3(0.0);
    /** Physical index of refraction for boundary reflection and transmission. Vacuum and air are 1. */
    public float indexOfRefraction = 1.0;
};

/** Local transport coefficients for a spatial medium; all colors use scene-linear ACEScg. */
public struct SpatialMediumProperties {
    /** Non-negative absorption plus out-scattering coefficient per scene unit. */
    public float3 extinction = float3(0.0);
    /** Fraction of extinction attributable to scattering, in [0, 1]. */
    public float3 scatteringAlbedo = float3(0.0);
    /** Isotropic emitted source radiance per scene unit, without pre-exposure; additive to traced lighting. */
    public float3 ambientSource = float3(0.0);
    /** Isotropic distant incident radiance, without pre-exposure; subject to material-aware visibility. */
    public float3 ambientRadiance = float3(0.0);
    /** Henyey-Greenstein anisotropy, strictly between -1 and 1. */
    public float anisotropy = 0.0;
};

/**
 * The OpenPBR Surface description a surface implementation produces and the engine's BSDF consumes.
 *
 * Not an input to the implementation: the engine uploads no material record and passes nothing in.
 * `SurfaceInput` is what the implementation is given; this is what it returns.
 *
 * Every field carries the specification's own default. Value-initialize an `OpenPbrSurface` with
 * `OpenPbrSurface material = {};`, then write only the fields the implementation varies. A declaration
 * without `= {}` leaves the value uninitialized.
 *
 * Field names, grouping, and declaration order are the specification's, verbatim, so a parameter here
 * and a parameter in an `open_pbr_surface` node are the same thing under the same name. A value means
 * exactly what the spec says it means — most of all `specular_roughness`, which is OpenPBR r and NOT the
 * GGX alpha the BSDF consumes (alpha = r * r).
 *
 * Unsupported groups are absent rather than present-and-ignored: there is no fuzz, coat, or thin film
 * here because the engine's BSDF has no lobe for them. Normal-incidence reflectance is likewise absent
 * because OpenPBR derives it from `specular_ior` for a dielectric and `base_color` for a metal — use
 * `openPbrSpecularF0`.
 */
public struct OpenPbrSurface {
    // Base
    public float3 base_color = float3(0.8, 0.8, 0.8);
    public float base_metalness = 0.0;

    // Specular
    /** OpenPBR specular_roughness: perceptual. GGX alpha is its square. */
    public float specular_roughness = 0.3;
    public float specular_ior = 1.5;

    // Transmission
    /**
     * OpenPBR transmission_weight: the mix between the opaque dielectric base and the translucent base.
     * A volume routes the latter through the engine's refractive volume transport. A thin wall resolves
     * it as an absorbing, non-scattering dielectric sheet with a straight-through rough BTDF.
     */
    public float transmission_weight = 0.0;
    /**
     * OpenPBR transmission_color. The supported thin-sheet model treats it as normal-incidence
     * transmittance through the sheet.
     */
    public float3 transmission_color = float3(1.0, 1.0, 1.0);

    // Subsurface
    /** OpenPBR subsurface_weight: the mix between glossy-diffuse and subsurface slabs. */
    public float subsurface_weight = 0.0;
    /** OpenPBR subsurface_color: the dense scattering sheet's observed colour. */
    public float3 subsurface_color = float3(0.8, 0.8, 0.8);
    /**
     * OpenPBR subsurface_scatter_anisotropy. In thin-walled mode it divides subsurface energy between
     * diffuse reflection and diffuse transmission exactly as the specification defines.
     */
    public float subsurface_scatter_anisotropy = 0.0;

    // Emission
    /** OpenPBR emission_luminance, in cd/m². */
    public float emission_luminance = 0.0;
    public float3 emission_color = float3(1.0, 1.0, 1.0);

    // Geometry
    /**
     * OpenPBR geometry_thin_walled. A transmissive thin wall is a two-dimensional sheet and does not
     * change the path's volume stack. A non-thin-walled transmissive surface may pair with a geometry
     * volume slot which describes its interior. A surface evaluated on geometry carrying a volume slot
     * leaves this false.
     */
    public bool geometry_thin_walled = false;
    /**
     * OpenPBR geometry_normal: the shading normal, already carrying the material's normal map. It is
     * written rather than read because a procedural surface's normal IS its description — the engine's
     * transport reads this one for refraction, so perturbing it is what makes a wave a wave.
     *
     * Zero, the default, means "the triangle's own normal": the engine substitutes
     * `SurfaceInput.geometricNormal` after the implementation returns, so a surface with no normal map
     * writes nothing here.
     */
    public float3 geometry_normal = float3(0.0);
    /**
     * `geometry_normal` one frame ago. NOT an OpenPBR parameter: reconstruction reprojects a reflection
     * through the surface plane and needs to know where that plane was, which only the function that
     * produced the normal can answer. Zero, the default, asks the engine to use
     * `SurfaceInput.previousGeometricNormal`. A surface which writes `geometry_normal` also writes this
     * field when SURFACE_RECONSTRUCTION_ANCHOR is set, using `previousNormalToScene` and `previousTime` as
     * needed. No previous evaluation is required elsewhere.
     */
    public float3 previous_geometry_normal = float3(0.0);
};

/**
 * OpenPBR normal-incidence reflectance. Not an authored parameter: a dielectric's follows from
 * `specular_ior`; a metal's is `base_color`. OpenPBR specular_color is fixed at its white default in the
 * supported subset because its conductor F82 edge-tint semantics are not implemented.
 */
public float3 openPbrSpecularF0(float3 baseColor, float baseMetalness, float specularIor) {
    float amplitude = (specularIor - 1.0) / (specularIor + 1.0);
    return lerp(float3(amplitude * amplitude), baseColor, clamp(baseMetalness, 0.0, 1.0));
}

public float3 openPbrSpecularF0(OpenPbrSurface material) {
    return openPbrSpecularF0(material.base_color, material.base_metalness, material.specular_ior);
}

/**
 * Where the surface is being looked at, and the frame constants a procedural one evaluates against. The
 * implementation returns an OpenPbrSurface describing the hit.
 */
public struct SurfaceInput {
    /** Versioned pushed roots and descriptor-heap address-table root for this composition. */
    public ShaderRootData shaderRoot;
    /**
     * The registered surface implementation's word, commonly the address of the extension's session-wide
     * material and texture table. Copied verbatim from SurfaceDefinition.
     */
    public uint64_t implementationData;
    /**
     * This surface slot's word, copied verbatim from MeshBuild.SurfaceSlot. Normally the address of the
     * geometry's own attribute slice — per geometry rather than per mesh precisely so that
     * `attributes[primitiveIndex]` is correct with no base to add.
     */
    public uint64_t bindingData;
    /**
     * This placement's word, copied verbatim from SetInstance. What lets one mesh be placed many times and
     * still shade differently: a team colour, an animation frame, an index into a buffer the source
     * published. Shading only — geometry that differs per placement is a different mesh.
     */
    public uint64_t instanceData;
    /**
     * Index of the hit triangle within its geometry. Geometry-local: it restarts at zero for each
     * geometry, which is what makes `bindingData` a usable base pointer.
     */
    public uint primitiveIndex;
    /** Weights for triangle vertices one and two; vertex zero has weight `1 - x - y`. */
    public float2 barycentrics;
    /** Triangle position1 - position0 and position2 - position0 in object coordinates. */
    public float3 objectEdgeU;
    public float3 objectEdgeV;
    /** Current object-to-scene transform for positions and tangent vectors. */
    public AffineTransform objectToScene;
    /** Previous-frame object-to-scene transform, equal to current when no history exists. */
    public AffineTransform previousObjectToScene;
    /** Inverse-transpose object-to-scene transform for normal-map vectors. */
    public NormalTransform normalToScene;
    /** Previous-frame inverse-transpose transform; equal to normalToScene when no history exists. */
    public NormalTransform previousNormalToScene;
    /** Hit position in the current scene-origin coordinate system, in scene units. */
    public float3 position;
    /** Stable conversion supplied when this scene was created. */
    public float metersPerSceneUnit;
    /** The triangle's own normal, oriented toward the viewer. Unperturbed by any normal map. */
    public float3 geometricNormal;
    /** Previous-frame triangle normal in previous scene coordinates, or geometricNormal without history. */
    public float3 previousGeometricNormal;
    public float3 outgoingDirection;
    /**
     * Ray footprint width at the hit, in scene units, measured across the ray rather than along the
     * surface. Engine-produced from the ray cone, which is the one filtering fact an extension cannot
     * derive for itself — everything downstream of it, the texture coordinates and the mip choice
     * included, is the extension's own. Zero for shadow-visibility evaluations, whose rays carry no
     * pixel cone.
     */
    public float textureFootprint;
    /**
     * A world-pinned, precision-safe position for procedural evaluation. It follows `position` while
     * compensating for scene-origin rebases, so a pattern remains fixed in all three scene dimensions.
     * Its modulus is an engine constant, so a very low-frequency field can alias across it where an
     * ordinary one does not.
     */
    public float3 proceduralPosition;
    /** Animation time in seconds, wrapped. Paired with `previousTime` so motion is derivable. */
    public float time;
    /** `time` one frame ago; equal to `time` when no adjacent frame exists to reproject from. */
    public float previousTime;
    /** SURFACE_* and EVALUATION_* bits describing this evaluation. */
    public uint evaluationFlags;
};

/** Inputs for the spatial medium captured by a view. */
public struct SpatialMediumInput {
    public ShaderRootData shaderRoot;
    /** Registered implementation word, supplied by the volume dispatch. */
    public uint64_t implementationData;
    public uint64_t bindingData;
    public uint64_t instanceData;
    /** Position in scene units relative to the captured spatial-medium origin. */
    public float3 position;
    /** Precision-safe scene position using the renderer's periodic procedural domain. */
    public float3 proceduralPosition;
    public float metersPerSceneUnit;
    /** Renderer animation time in seconds, matching volume boundary evaluation. */
    public float time;
};

/** Inputs used to describe the homogeneous interior selected by one geometry volume slot. */
public struct VolumeInput {
    public ShaderRootData shaderRoot;
    /** The registered volume implementation's word, copied verbatim from VolumeDefinition. */
    public uint64_t implementationData;
    /** This volume slot's binding word; the placement word follows it. */
    public uint64_t bindingData;
    public uint64_t instanceData;
    /** Boundary triangle within its geometry; zero for an initial view-medium evaluation. */
    public uint primitiveIndex;
    /** Boundary position in the current scene-origin coordinate system. */
    public float3 boundaryPosition;
    /** Precision-safe boundary position which compensates for scene-origin rebases. */
    public float3 proceduralPosition;
    /** Stable conversion supplied when this scene was created. */
    public float metersPerSceneUnit;
    public float time;
    public float previousTime;
    /** Shared EVALUATION_* frame facts supplied at the boundary. */
    public uint evaluationFlags;
};

/** Direct-light context for a volume's optional response to a tracked boundary crossing. */
public struct VolumeBoundaryLightingInput {
    public VolumeInput volume;
    public float3 lightDirection;
    /** Distance through the volume in scene units. */
    public float boundaryDistance;
    public float interiorIor;
};

public struct EnvironmentQuery {
    public ShaderRootData shaderRoot;
    public float3 direction;
    /** Whether discrete environment-emitter lobes represented by direct-light sampling remain visible. */
    public bool showEnvironmentEmitters;
    /**
     * The word the scene carried when it named this implementation, copied verbatim. The engine assigns it
     * no meaning: it is whatever the extension put there — a bindless slot, packed bits, or the address of
     * a buffer holding this scene's atmosphere.
     */
    public uint64_t bindingData;
};
