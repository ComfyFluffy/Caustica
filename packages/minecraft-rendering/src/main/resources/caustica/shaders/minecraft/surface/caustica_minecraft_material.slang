module caustica_minecraft_material;

import caustica_color;
import caustica_resources;
import caustica_types;
import caustica_volume;

public static const uint MINECRAFT_TEXTURE_PRESENT = 1u;
public static const uint MINECRAFT_TEXTURE_LINEAR = 2u;
public static const uint MINECRAFT_DAMAGE_PRESENT = 4u;
public static const uint MINECRAFT_FLIP_BITANGENT = 8u;
public static const uint MINECRAFT_MATERIAL_SPEC = 1u;
public static const uint MINECRAFT_MATERIAL_NORMAL = 2u;
public static const uint MINECRAFT_MATERIAL_EMISSION_MASK = 4u;
public static const uint MINECRAFT_MATERIAL_SUBSURFACE_COLOR_BASE = 8u;
public static const uint MINECRAFT_MATERIAL_EMISSION_COLOR_BASE = 16u;
public static const uint MINECRAFT_MATERIAL_UNIFORM_EMISSION = 32u;

public struct MinecraftImplementationData {
    public uint64_t materialsAddress;
    public SamplerIndex samplerIndex;
    public uint reserved;
};

public struct MinecraftMaterialRecord {
    public uint features;
    public float maxLod;
    public SampledTexture2DIndex surface0Texture;
    public SampledTexture2DIndex surface1Texture;
    public SampledTexture2DIndex normalTexture;
    public SampledTexture2DIndex emissionTexture;
    public float4 materialUv;
    public float4 baseColorUv;
    public float3 baseColor;
    public float baseMetalness;
    public float specularRoughness;
    public float specularIor;
    public float transmissionWeight;
    public float subsurfaceWeight;
    public float3 emissionColor;
    public float emissionLuminance;
};

public struct MinecraftPrimitiveData {
    public float2 textureCoordinates[3];
    // Byte offset from this record to three float4 colors; zero denotes uniform white.
    public uint vertexColorsOffset;
    public float3 tint;
    public uint materialIndex;
    public SampledTexture2DIndex baseTexture;
    public SamplerIndex baseSampler;
    public uint textureFlags;
    public float primitiveEmission;
};

public struct MinecraftInstanceData {
    public float3 tint;
    public uint flags;
    public SampledTexture2DIndex damageTexture;
    public float damageStrength;
};

public struct MinecraftHitData {
    public MinecraftImplementationData implementation;
    public MinecraftPrimitiveData primitive;
    public MinecraftMaterialRecord material;
    public MinecraftInstanceData instance;
    public float2 textureCoordinate;
    public float4 vertexColor;
};

float minecraftIor(float encoded) {
    float amplitude = clamp(encoded, 0.0, 254.0 / 255.0);
    return (1.0 + amplitude) / (1.0 - amplitude);
}

public MinecraftInstanceData minecraftInstanceData(uint64_t address) {
    if (address != 0u) return ShaderDataPtr<MinecraftInstanceData>(address)[0];
    MinecraftInstanceData instance = {};
    instance.tint = float3(1.0);
    return instance;
}

public MinecraftHitData minecraftHitData(uint64_t implementationData, uint64_t bindingData,
        uint64_t instanceData, uint primitiveIndex, float2 barycentrics) {
    MinecraftHitData hit = {};
    hit.implementation = ShaderDataPtr<MinecraftImplementationData>(implementationData)[0];
    hit.primitive = ShaderDataPtr<MinecraftPrimitiveData>(bindingData)[primitiveIndex];
    hit.material = ShaderDataPtr<MinecraftMaterialRecord>(hit.implementation.materialsAddress)
            [hit.primitive.materialIndex];
    hit.instance = minecraftInstanceData(instanceData);
    float3 weights = float3(1.0 - barycentrics.x - barycentrics.y,
            barycentrics.x, barycentrics.y);
    hit.textureCoordinate = hit.primitive.textureCoordinates[0] * weights.x
            + hit.primitive.textureCoordinates[1] * weights.y
            + hit.primitive.textureCoordinates[2] * weights.z;
    hit.vertexColor = 1.0;
    if (hit.primitive.vertexColorsOffset != 0u) {
        uint64_t colorsAddress = bindingData + uint64_t(primitiveIndex) * sizeof(MinecraftPrimitiveData)
                + hit.primitive.vertexColorsOffset;
        hit.vertexColor = ShaderDataPtr<float4>(colorsAddress)[0] * weights.x
                + ShaderDataPtr<float4>(colorsAddress)[1] * weights.y
                + ShaderDataPtr<float4>(colorsAddress)[2] * weights.z;
    }
    return hit;
}

public float4 minecraftSampleMaterial(MinecraftHitData hit, SampledTexture2DIndex texture,
        float2 coordinates, float lod) {
    return sampleTexture2DLod(texture, hit.implementation.samplerIndex, coordinates, lod);
}

public float4 minecraftSampleBase(MinecraftHitData hit, float2 coordinates, float lod) {
    return sampleTexture2DLod(hit.primitive.baseTexture, hit.primitive.baseSampler, coordinates, lod);
}

/**
 * Squared ray-cone footprint per unit area of the primitive's texture coordinates. A texture of
 * width by height texels addressed by those coordinates takes LOD 0.5 * log2(area * width * height).
 * The cosine enters once, matching the area of the elliptical surface footprint rather than its major
 * axis, which keeps slopes sharper; RTXPT uses the same slope term.
 */
float minecraftFootprintArea(SurfaceInput input, MinecraftHitData hit) {
    float2 uvU = hit.primitive.textureCoordinates[1] - hit.primitive.textureCoordinates[0];
    float2 uvV = hit.primitive.textureCoordinates[2] - hit.primitive.textureCoordinates[0];
    float3 sceneU = transformDirection(input.objectToScene, input.objectEdgeU);
    float3 sceneV = transformDirection(input.objectToScene, input.objectEdgeV);
    float cosine = abs(dot(input.geometricNormal, input.outgoingDirection));
    return input.textureFootprint * input.textureFootprint * abs(uvU.x * uvV.y - uvU.y * uvV.x)
            / (length(cross(sceneU, sceneV)) * cosine);
}

// Footprints narrower than a texel, including the zero footprint, magnify level zero.
float minecraftTextureLod(SampledTexture2DIndex texture, float footprintArea) {
    uint width, height;
    sampledTexture2D(texture).GetDimensions(width, height);
    return 0.5 * log2(max(footprintArea * float(width) * float(height), 1.0));
}

public OpenPbrSurface evaluateMinecraftMaterial(SurfaceInput input) {
    MinecraftHitData hit = minecraftHitData(input.implementationData, input.bindingData,
            input.instanceData, input.primitiveIndex, input.barycentrics);
    MinecraftMaterialRecord data = hit.material;
    OpenPbrSurface material = {};
    material.base_color = data.baseColor;
    material.base_metalness = data.baseMetalness;
    material.specular_roughness = data.specularRoughness;
    material.specular_ior = data.specularIor;
    material.transmission_weight = data.transmissionWeight;
    material.subsurface_weight = data.subsurfaceWeight;
    material.emission_color = data.emissionColor;
    material.emission_luminance = data.emissionLuminance;

    float3 geometryTint = hit.primitive.tint * hit.instance.tint * hit.vertexColor.rgb;
    float footprintArea = minecraftFootprintArea(input, hit);
    if ((hit.primitive.textureFlags & MINECRAFT_TEXTURE_PRESENT) == 0u) {
        material.base_color *= geometryTint;
    } else {
        float4 texel = minecraftSampleBase(hit, hit.textureCoordinate,
                minecraftTextureLod(hit.primitive.baseTexture, footprintArea));
        float3 sampled = (hit.primitive.textureFlags & MINECRAFT_TEXTURE_LINEAR) != 0u
                ? texel.rgb : srgbToLinear(texel.rgb);
        sampled = bt709ToAcesCg(sampled);
        material.base_color *= sampled * geometryTint;
    }
    float3 emissionBaseColor = material.base_color;

    material.emission_luminance *= (data.features & MINECRAFT_MATERIAL_UNIFORM_EMISSION) != 0u
            ? 1.0 : hit.primitive.primitiveEmission;
    float2 localUv = clamp((hit.textureCoordinate - data.baseColorUv.xy) * data.baseColorUv.zw,
            float2(0.0), float2(1.0));
    float2 pageUv = data.materialUv.xy + localUv * data.materialUv.zw;
    // Page textures share one size, and surface0 is a page whenever a page feature is set.
    float pageLod = 0.0;
    if ((data.features & (MINECRAFT_MATERIAL_SPEC | MINECRAFT_MATERIAL_NORMAL
            | MINECRAFT_MATERIAL_EMISSION_MASK)) != 0u) {
        float pageScale = abs(data.baseColorUv.z * data.baseColorUv.w * data.materialUv.z * data.materialUv.w);
        pageLod = min(minecraftTextureLod(data.surface0Texture, footprintArea * pageScale), data.maxLod);
    }
    if ((data.features & (MINECRAFT_MATERIAL_SPEC | MINECRAFT_MATERIAL_EMISSION_MASK)) != 0u) {
        float4 surface0 = minecraftSampleMaterial(hit, data.surface0Texture, pageUv, pageLod);
        if ((data.features & MINECRAFT_MATERIAL_SPEC) != 0u) {
            float4 surface1 = minecraftSampleMaterial(hit, data.surface1Texture, pageUv, pageLod);
            material.specular_roughness *= surface0.r;
            material.base_metalness *= surface0.g;
            material.subsurface_weight *= surface0.a;
            if (data.transmissionWeight == 0.0) material.specular_ior = minecraftIor(surface1.a);
            float3 authored = clamp(bt709ToAcesCg(surface1.rgb), float3(0.0), float3(1.0));
            material.base_color = lerp(material.base_color, authored * data.baseColor,
                    material.base_metalness);
        }
        if ((data.features & MINECRAFT_MATERIAL_EMISSION_MASK) != 0u) {
            material.emission_luminance *= surface0.b;
            material.emission_color *= bt709ToAcesCg(
                    minecraftSampleMaterial(hit, data.emissionTexture, pageUv, pageLod).rgb);
        }
    }
    // Base-bound emission follows the live atlas animation and geometry tint.
    if ((data.features & MINECRAFT_MATERIAL_EMISSION_COLOR_BASE) != 0u) {
        material.emission_color *= emissionBaseColor;
    }
    if ((data.features & MINECRAFT_MATERIAL_SUBSURFACE_COLOR_BASE) != 0u) {
        material.subsurface_color = material.base_color;
    }
    if ((data.features & MINECRAFT_MATERIAL_NORMAL) != 0u) {
        float2 encoded = minecraftSampleMaterial(hit, data.normalTexture, pageUv, pageLod).xy * 2.0 - 1.0;
        float z = sqrt(max(0.0, 1.0 - dot(encoded, encoded)));
        float2 uvU = hit.primitive.textureCoordinates[1] - hit.primitive.textureCoordinates[0];
        float2 uvV = hit.primitive.textureCoordinates[2] - hit.primitive.textureCoordinates[0];
        float determinant = uvU.x * uvV.y - uvU.y * uvV.x;
        float3 tangent = 0.0, bitangent = 0.0;
        if (determinant != 0.0) {
            tangent = normalize((input.objectEdgeU * uvV.y - input.objectEdgeV * uvU.y) / determinant);
            bitangent = normalize((input.objectEdgeV * uvU.x - input.objectEdgeU * uvV.x) / determinant);
        }
        if ((hit.primitive.textureFlags & MINECRAFT_FLIP_BITANGENT) != 0u) bitangent = -bitangent;
        float3 objectNormal = normalize(tangent * encoded.x + bitangent * encoded.y
                + cross(tangent, bitangent) * z);
        material.geometry_normal = normalize(transformNormal(input.normalToScene, objectNormal));
        if ((input.evaluationFlags & SURFACE_RECONSTRUCTION_ANCHOR) != 0u) {
            material.previous_geometry_normal = normalize(
                    transformNormal(input.previousNormalToScene, objectNormal));
        }
    }
    if ((hit.instance.flags & MINECRAFT_DAMAGE_PRESENT) != 0u) {
        float damage = minecraftSampleMaterial(hit, hit.instance.damageTexture,
                hit.textureCoordinate, 0.0).a * clamp(hit.instance.damageStrength, 0.0, 1.0);
        material.base_color = lerp(material.base_color, material.base_color * 0.18, damage);
        material.specular_roughness = lerp(material.specular_roughness, 0.92, damage);
    }
    // Minecraft's base texture supplies the dielectric tint; its alpha does not select an optical lobe.
    material.transmission_color = material.base_color;
    return material;
}

/** A fully covered dielectric boundary uses its material's IOR and carries no additional absorption. */
public struct MinecraftDielectricVolume : IVolumeModel {
    public override VolumeProperties evaluateVolume(VolumeInput input) {
        MinecraftImplementationData implementation = ShaderDataPtr<MinecraftImplementationData>(input.implementationData)[0];
        MinecraftPrimitiveData primitive = ShaderDataPtr<MinecraftPrimitiveData>(input.bindingData)[input.primitiveIndex];
        MinecraftMaterialRecord material = ShaderDataPtr<MinecraftMaterialRecord>(implementation.materialsAddress)[primitive.materialIndex];
        VolumeProperties properties = {};
        properties.indexOfRefraction = material.specularIor;
        return properties;
    }
};
