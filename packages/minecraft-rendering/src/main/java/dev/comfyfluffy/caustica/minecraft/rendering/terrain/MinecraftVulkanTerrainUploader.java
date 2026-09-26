package dev.comfyfluffy.caustica.minecraft.rendering.terrain;

import dev.comfyfluffy.caustica.api.geometry.MeshBuild;
import dev.comfyfluffy.caustica.api.vulkan.GpuDevice;
import dev.comfyfluffy.caustica.api.vulkan.VulkanDeviceAddress;
import dev.comfyfluffy.caustica.api.program.ShaderData;
import dev.comfyfluffy.caustica.api.resource.ResourceFactory;
import dev.comfyfluffy.caustica.api.resource.ResourceOwner;
import dev.comfyfluffy.caustica.minecraft.rendering.program.MinecraftPrograms;
import dev.comfyfluffy.caustica.minecraft.api.program.MinecraftProgramTypes;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftInstanceData;
import dev.comfyfluffy.caustica.minecraft.rendering.gen.MinecraftPrimitiveData;
import dev.comfyfluffy.caustica.minecraft.rendering.texture.BorrowedMinecraftTexture;
import dev.comfyfluffy.caustica.support.SharedResource;
import dev.comfyfluffy.caustica.vulkan.VmaMappedBuffer;
import dev.comfyfluffy.caustica.vulkan.ResourceLifetime;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkImageDescriptorInfoEXT;
import org.lwjgl.vulkan.VkImageViewCreateInfo;
import org.lwjgl.vulkan.VkResourceDescriptorInfoEXT;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.KHRAccelerationStructure.VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_ASPECT_COLOR_BIT;
import static org.lwjgl.vulkan.VK10.VK_IMAGE_VIEW_TYPE_2D;
import static dev.comfyfluffy.caustica.vulkan.ResourceLifetime.closeAfterFailure;

/** Host-mapped VMA upload owner for retained Minecraft terrain buffers. */
public final class MinecraftVulkanTerrainUploader implements MinecraftTerrainUploader {
    private static final int TEXTURE_PRESENT = 1;
    private static final int FLIP_BITANGENT = 8;
    private final GpuDevice gpu;
    private final MinecraftPrograms programs;
    private final SharedResource<SharedAtlas> atlas;
    private final ResourceFactory resources;
    private final boolean opacitySamplingCompatible;
    private boolean closed;

    public MinecraftVulkanTerrainUploader(GpuDevice gpu, MinecraftPrograms programs,
                                          BorrowedMinecraftTexture blockAtlas,
                                          ResourceFactory resources) {
        this.gpu = java.util.Objects.requireNonNull(gpu, "gpu");
        this.programs = java.util.Objects.requireNonNull(programs, "programs");
        this.resources = java.util.Objects.requireNonNull(resources, "resources");
        opacitySamplingCompatible = blockAtlas.baseMipLevel() == 0 && blockAtlas.sampler().maxAnisotropy() == 1;
        atlas = SharedAtlas.create(gpu, java.util.Objects.requireNonNull(blockAtlas, "blockAtlas"));
    }

    @Override public UploadedSection upload(MinecraftTerrainMesh source) {
        java.util.Objects.requireNonNull(source, "source");
        float[] positions = source.positions();
        int[] indices = source.indices();
        float[] cornerUvs = source.cornerUvs();
        float[] primitive = source.primitiveData();
        SharedResource<SharedAtlas> atlasLease;
        synchronized (this) {
            if (closed) throw new IllegalStateException("Minecraft terrain uploader is closed");
            atlasLease = atlas.retain();
        }
        var allocated = new ArrayList<Runnable>();
        allocated.add(atlasLease::close);
        VmaMappedBuffer positionsBuffer;
        VmaMappedBuffer indicesBuffer;
        VmaMappedBuffer primitiveBuffer;
        VmaMappedBuffer instanceBuffer;
        try {
            positionsBuffer = createAsync((long) positions.length * Float.BYTES,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    bytes -> { for (float value : positions) bytes.putFloat(value); });
            allocated.add(positionsBuffer::close);
            indicesBuffer = createAsync((long) indices.length * Integer.BYTES,
                    VK_BUFFER_USAGE_ACCELERATION_STRUCTURE_BUILD_INPUT_READ_ONLY_BIT_KHR,
                    bytes -> { for (int index : indices) bytes.putInt(index); });
            allocated.add(indicesBuffer::close);
            primitiveBuffer = create((long) source.triangleCount() * MinecraftPrimitiveData.BYTE_SIZE,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    bytes -> writePrimitiveRecords(bytes, source.triangleCount(), positions, indices,
                            cornerUvs, primitive, atlasLease.get().descriptorIndex(),
                            atlasLease.get().samplerDescriptorIndex()));
            allocated.add(primitiveBuffer::close);
            instanceBuffer = create(MinecraftInstanceData.BYTE_SIZE, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    bytes -> new MinecraftInstanceData(new MinecraftInstanceData.Float3(1f, 1f, 1f), 0,
                            new MinecraftInstanceData.SampledTexture2DIndex(0), 0f).write(bytes));
            allocated.add(instanceBuffer::close);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure, allocated.reversed().toArray(Runnable[]::new));
            throw failure;
        }
        return uploaded(source, programs, positionsBuffer, indicesBuffer, primitiveBuffer, instanceBuffer,
                atlasLease);
    }

    static long primitiveRecordOffset(int firstIndex) {
        return Math.multiplyExact((long) firstIndex / 3L, MinecraftPrimitiveData.BYTE_SIZE);
    }

    static List<MeshBuild.Geometry<MinecraftProgramTypes.InstanceData>> geometries(
            MinecraftTerrainMesh source, MinecraftPrograms programs, VulkanDeviceAddress primitiveAddress,
            ResourceOwner resource) {
        return geometries(source, programs, primitiveAddress, resource, true);
    }

    private static List<MeshBuild.Geometry<MinecraftProgramTypes.InstanceData>> geometries(
            MinecraftTerrainMesh source, MinecraftPrograms programs, VulkanDeviceAddress primitiveAddress,
            ResourceOwner resource, boolean opacitySamplingCompatible) {
        List<MeshBuild.Geometry<MinecraftProgramTypes.InstanceData>> geometries = new ArrayList<>();
        var claims = new ArrayList<Runnable>();
        try {
            for (MinecraftTerrainMesh.Geometry geometry : source.geometries()) {
                long primitiveOffset = primitiveRecordOffset(geometry.firstIndex());
                ShaderData<MinecraftProgramTypes.PrimitiveData> binding = MinecraftProgramTypes.PRIMITIVE_DATA.data(
                        primitiveAddress.addBytes(primitiveOffset).value(), resource);
                claims.add(binding::close);
                MeshBuild.CoveragePolicy policy = coveragePolicy(geometry);
                var surface = switch (geometry.program()) {
                    case MATERIAL, DIELECTRIC -> new MeshBuild.SurfaceSlot<>(programs.materialSurface(), binding, policy,
                            geometry.guaranteedShadowBlocker() ? MeshBuild.ShadowPolicy.GUARANTEED_BLOCKER
                                    : MeshBuild.ShadowPolicy.EVALUATE_SURFACE);
                    case WATER -> new MeshBuild.SurfaceSlot<>(programs.waterSurface(), binding, policy);
                    case PORTAL -> new MeshBuild.SurfaceSlot<>(programs.portalSurface(), binding, policy);
                };
                var volume = switch (geometry.program()) {
                    case WATER -> new MeshBuild.VolumeSlot<>(programs.waterVolume(), binding);
                    case DIELECTRIC -> new MeshBuild.VolumeSlot<>(programs.dielectricVolume(), binding);
                    default -> null;
                };
                geometries.add(new MeshBuild.Geometry<>(surface, volume, geometry.firstIndex(), geometry.indexCount(),
                        opacitySamplingCompatible ? geometry.opacityMicromap() : null));
            }
            return List.copyOf(geometries);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure, claims.toArray(Runnable[]::new));
            throw failure;
        }
    }

    static MeshBuild.CoveragePolicy coveragePolicy(MinecraftTerrainMesh.Geometry geometry) {
        return switch (geometry.coverage()) {
            case OPAQUE -> new MeshBuild.CoveragePolicy.Opaque();
            case CUTOUT -> new MeshBuild.CoveragePolicy.Cutout(geometry.alphaCutoff());
            case STOCHASTIC -> new MeshBuild.CoveragePolicy.Stochastic(geometry.alphaCutoff());
        };
    }

    private UploadedSection uploaded(MinecraftTerrainMesh source, MinecraftPrograms programs,
                                     VmaMappedBuffer positions, VmaMappedBuffer indices,
                                     VmaMappedBuffer primitive, VmaMappedBuffer instance,
                                     SharedResource<SharedAtlas> atlasLease) {
        Runnable releasePositions = positions::close;
        Runnable releaseIndices = indices::close;
        Runnable releaseBinding = new ResourceLifetime(primitive::close, atlasLease::close)::close;
        Runnable releaseInstance = instance::close;
        var shaderClaims = new ArrayList<Runnable>();
        try {
            // Registration transfers destruction to the resource owner; rollback releases that claim.
            var positionGeneration = resources.create(releasePositions);
            releasePositions = positionGeneration::close;
            var indexGeneration = resources.create(releaseIndices);
            releaseIndices = indexGeneration::close;
            var bindingGeneration = resources.create(releaseBinding);
            releaseBinding = bindingGeneration::close;
            var instanceGeneration = resources.create(releaseInstance);
            releaseInstance = instanceGeneration::close;
            List<MeshBuild.Geometry<MinecraftProgramTypes.InstanceData>> geometries = geometries(
                    source, programs, primitive.deviceRange().address(), bindingGeneration, opacitySamplingCompatible);
            // Surface and volume slots borrow the same binding claim for each geometry.
            for (var geometry : geometries) shaderClaims.add(geometry.surface().bindingData()::close);
            MeshBuild<MinecraftProgramTypes.InstanceData> build = new MeshBuild<>(
                    new MeshBuild.Stream(positions.deviceRange(), 12, positionGeneration),
                    new MeshBuild.Stream(indices.deviceRange(), 4, indexGeneration), source.vertexCount(),
                    new MeshBuild.IndexRevision(source.indexRevision()), MeshBuild.BuildPolicy.STATIC, geometries);
            ShaderData<MinecraftProgramTypes.InstanceData> instanceData =
                    MinecraftProgramTypes.INSTANCE_DATA.data(instance.deviceRange().address().value(),
                            instanceGeneration);
            shaderClaims.add(instanceData::close);
            var lifetime = new ResourceLifetime(
                    new ResourceLifetime(shaderClaims.toArray(Runnable[]::new))::close,
                    releasePositions, releaseIndices, releaseBinding, releaseInstance);
            return new Uploaded(build, instanceData, lifetime);
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure, new ResourceLifetime(shaderClaims.toArray(Runnable[]::new))::close,
                    releasePositions, releaseIndices, releaseBinding, releaseInstance);
            throw failure;
        }
    }

    static void writePrimitiveRecords(ByteBuffer bytes, int triangles, float[] positions, int[] indices,
                                      float[] uvs, float[] primitive, int atlasDescriptor,
                                      int atlasSamplerDescriptor) {
        for (int triangle = 0; triangle < triangles; triangle++) {
            int uv = triangle * 6;
            int data = triangle * MinecraftTerrainMesh.PRIMITIVE_FLOATS;
            var uvValues = new MinecraftPrimitiveData.Float2[]{
                    new MinecraftPrimitiveData.Float2(uvs[uv], uvs[uv + 1]),
                    new MinecraftPrimitiveData.Float2(uvs[uv + 2], uvs[uv + 3]),
                    new MinecraftPrimitiveData.Float2(uvs[uv + 4], uvs[uv + 5])};
            boolean textured = primitive[data + MinecraftTerrainMesh.PRIMITIVE_ATLAS_PRESENT_OFFSET] != 0.0f;
            int textureFlags = textured ? TEXTURE_PRESENT : 0;
            textureFlags |= tangentOrientation(positions, indices, uvs, triangle,
                    primitive[data], primitive[data + 1], primitive[data + 2]);
            var record = new MinecraftPrimitiveData(uvValues, 0,
                    new MinecraftPrimitiveData.Float3(primitive[data + 4], primitive[data + 5], primitive[data + 6]),
                    (int) primitive[data + 8],
                    new MinecraftPrimitiveData.SampledTexture2DIndex(textured ? atlasDescriptor : 0),
                    new MinecraftPrimitiveData.SamplerIndex(textured ? atlasSamplerDescriptor : 0),
                    textureFlags,
                    primitive[data + 3]);
            record.write(bytes.slice(triangle * MinecraftPrimitiveData.BYTE_SIZE,
                    MinecraftPrimitiveData.BYTE_SIZE).order(ByteOrder.LITTLE_ENDIAN));
        }
        bytes.position(triangles * MinecraftPrimitiveData.BYTE_SIZE);
    }

    /** Flips the UV-derived bitangent when its basis normal opposes the source's outward normal. */
    static int tangentOrientation(float[] positions, int[] indices, float[] uvs, int triangle,
                                  float normalX, float normalY, float normalZ) {
        int indexOffset = triangle * 3;
        int uvOffset = triangle * 6;
        int p0 = indices[indexOffset] * 3;
        int p1 = indices[indexOffset + 1] * 3;
        int p2 = indices[indexOffset + 2] * 3;
        float x1 = positions[p1] - positions[p0];
        float y1 = positions[p1 + 1] - positions[p0 + 1];
        float z1 = positions[p1 + 2] - positions[p0 + 2];
        float x2 = positions[p2] - positions[p0];
        float y2 = positions[p2 + 1] - positions[p0 + 1];
        float z2 = positions[p2 + 2] - positions[p0 + 2];
        float u1 = uvs[uvOffset + 2] - uvs[uvOffset];
        float v1 = uvs[uvOffset + 3] - uvs[uvOffset + 1];
        float u2 = uvs[uvOffset + 4] - uvs[uvOffset];
        float v2 = uvs[uvOffset + 5] - uvs[uvOffset + 1];
        float determinant = u1 * v2 - u2 * v1;
        float orientation = (y1 * z2 - z1 * y2) * normalX
                + (z1 * x2 - x1 * z2) * normalY + (x1 * y2 - y1 * x2) * normalZ;
        return determinant * orientation < 0.0f ? FLIP_BITANGENT : 0;
    }

    private VmaMappedBuffer create(long size, int extraUsage, Writer writer) {
        return create(size, extraUsage, writer, false);
    }

    private VmaMappedBuffer createAsync(long size, int extraUsage, Writer writer) {
        return create(size, extraUsage, writer, true);
    }

    private VmaMappedBuffer create(long size, int extraUsage, Writer writer, boolean asyncShared) {
        VmaMappedBuffer buffer = asyncShared
                ? VmaMappedBuffer.createAsync(gpu, size, extraUsage, "Minecraft terrain upload")
                : VmaMappedBuffer.create(gpu, size, extraUsage, "Minecraft terrain upload");
        try {
            ByteBuffer bytes = buffer.mapped().order(ByteOrder.LITTLE_ENDIAN);
            writer.write(bytes);
            buffer.flush(0L, size);
            return buffer;
        } catch (RuntimeException | Error failure) {
            closeAfterFailure(failure, buffer::close);
            throw failure;
        }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        atlas.close();
    }

    private record Uploaded(MeshBuild<MinecraftProgramTypes.InstanceData> build,
                            ShaderData<MinecraftProgramTypes.InstanceData> instanceData,
                            ResourceLifetime lifetime) implements UploadedSection {
        @Override public void close() { lifetime.close(); }
    }

    static final class SharedAtlas {
        private final int descriptorIndex;
        private final int samplerDescriptorIndex;
        private final Runnable cleanup;

        SharedAtlas(int descriptorIndex, int samplerDescriptorIndex, Runnable cleanup) {
            if (descriptorIndex < 0 || samplerDescriptorIndex < 0) {
                throw new IllegalArgumentException("descriptor indices must be non-negative");
            }
            this.descriptorIndex = descriptorIndex;
            this.samplerDescriptorIndex = samplerDescriptorIndex;
            this.cleanup = java.util.Objects.requireNonNull(cleanup, "cleanup");
        }

        static SharedResource<SharedAtlas> create(GpuDevice gpu, BorrowedMinecraftTexture borrowed) {
            var allocated = new ArrayList<Runnable>();
            allocated.add(borrowed::close);
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var range = gpu.descriptorHeap().allocateResources(1);
                allocated.add(range::destroy);
                var samplerRange = gpu.descriptorHeap().allocateSamplers(1);
                allocated.add(samplerRange::destroy);
                VkImageViewCreateInfo view = VkImageViewCreateInfo.calloc(stack).sType$Default()
                        .image(borrowed.vkImage()).viewType(VK_IMAGE_VIEW_TYPE_2D).format(borrowed.format());
                view.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(borrowed.baseMipLevel()).levelCount(borrowed.mipLevels())
                        .baseArrayLayer(0).layerCount(1);
                VkImageDescriptorInfoEXT image = VkImageDescriptorInfoEXT.calloc(stack).sType$Default()
                        .pView(view).layout(borrowed.imageLayout());
                gpu.descriptorHeap().writer().writeResource(range, 0,
                        VkResourceDescriptorInfoEXT.calloc(stack).sType$Default()
                                 .type(VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE).data(data -> data.pImage(image)));
                // Minecraft's atlas sampler stops at level zero. Shading reads the atlas mip chain at its
                // ray-cone LOD, while coverage requests LOD zero and keeps the opacity-micromap contract.
                gpu.descriptorHeap().writer().writeSampler(samplerRange, 0, borrowed.sampler()
                        .withMipLevels(borrowed.mipLevels()).write(VkSamplerCreateInfo.calloc(stack).sType$Default()));
                SharedAtlas atlas = new SharedAtlas(range.firstIndex().value(), samplerRange.firstIndex().value(),
                        new ResourceLifetime(range::destroy, samplerRange::destroy, borrowed::close)::close);
                return SharedResource.owned(atlas, SharedAtlas::cleanup);
            } catch (RuntimeException | Error failure) {
                closeAfterFailure(failure, allocated.reversed().toArray(Runnable[]::new));
                throw failure;
            }
        }

        int descriptorIndex() {
            return descriptorIndex;
        }

        int samplerDescriptorIndex() {
            return samplerDescriptorIndex;
        }

        void cleanup() { cleanup.run(); }
    }

    @FunctionalInterface private interface Writer { void write(ByteBuffer bytes); }
}
