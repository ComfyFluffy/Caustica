package dev.comfyfluffy.caustica.build

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

/** Generates the Java view of Vulkan descriptor locations from Slang reflection. */
abstract class GenerateRtBindings extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getShaderRoot()

    @Input abstract Property<String> getSlangc()
    @OutputDirectory abstract DirectoryProperty getOutDir()

    @Inject abstract ExecOperations getExecOps()

    private static final List<Map> PIPELINES = [
            [prefix: "WORLD", source: "pipelines/world/indirect.rgen.slang", resources: [
                    TLAS: "topLevelAS", OUTPUT: "outImage", BLOCK_ALBEDO: "blockAlbedoAtlas",
                    G_NORMAL: "gNormal", G_ALBEDO: "gAlbedo", G_DEPTH: "gDepth", G_MOTION: "gMotion",
                    G_SPEC_ALBEDO: "gSpecAlbedo", G_SPEC_MOTION: "gSpecMotion",
                    RESERVOIR_A: "reservoirA", RESERVOIR_B: "reservoirB",
                    CLOUD_NOISE: "cloudNoiseTex",
                    CELESTIALS: "celestialsAtlas", SKY_VIEW: "skyViewLut", TRANSMITTANCE: "transmittanceLut",
                    ENTITY_ALBEDO: "entityAlbedoTex", MATERIAL_SURFACE0: "materialSurface0Tex",
                    MATERIAL_NORMAL_AO: "materialNormalAoTex", MATERIAL_SURFACE1: "materialSurface1Tex"]],
            [prefix: "DISPLAY", source: "pipelines/display/main.comp.slang", resources: [
                    OUTPUT: "outputImage", RT_IMAGE: "rtImage", EXPOSURE: "exposureImage", HDR_OUTPUT: "hdrImage",
                    SDR_TONE_LUT: "toneLut", HDR_TONE_LUT: "hdrToneLut", LOOK_LUT: "lookLut", BLOOM: "bloomImage"]],
            [prefix: "DEBUG_PRESENT", source: "pipelines/debug_present/main.comp.slang", resources: [
                    OUTPUT: "outputImage", G_NORMAL: "gNormal", G_ALBEDO: "gAlbedo", G_DEPTH: "gDepth",
                    G_MOTION: "gMotion", G_SPEC_ALBEDO: "gSpecAlbedo", G_SPEC_MOTION: "gSpecMotion",
                    SCENE: "sceneImage", EXPOSURE: "exposureImage", EXPOSURE_STATE: "exposureState"]],
            [prefix: "EXPOSURE_HIST", source: "pipelines/exposure_hist/main.comp.slang", resources: [
                    COLOR: "colorImage", BINS: "histBins", DEPTH: "depthImage", ALBEDO: "albedoImage"]],
            [prefix: "EXPOSURE_RESOLVE", source: "pipelines/exposure_resolve/main.comp.slang", resources: [
                    HIST_BINS: "histBins", IMAGE: "exposureImage", STATE: "stateBuf"]],
            [prefix: "BLOOM", source: "pipelines/bloom/main.comp.slang", resources: [
                    OUTPUT: "dstImage", SOURCE: "srcImage", EXPOSURE: "exposureImage"]],
            [prefix: "SKY_LUT", source: "pipelines/sky_lut/view.comp.slang", resources: [
                    TRANSMITTANCE_IMAGE: "transmittanceImage", MULTISCATTER_IMAGE: "multiScatterImage",
                    SKY_VIEW_IMAGE: "skyViewImage", TRANSMITTANCE_SAMPLER: "transmittanceLut",
                    MULTISCATTER_SAMPLER: "multiScatterLut"]],
            [prefix: "PRESENT", source: "pipelines/hdr_composite/main.comp.slang", resources: [
                    OUTPUT: "outputImage", SOURCE: "sourceImage"]],
            [prefix: "OVERLAY_IMAGE", source: "pipelines/overlay_composite/glow.frag.slang", resources: [VALUE: "sourceImage"]],
            [prefix: "OVERLAY_SAMPLER", source: "pipelines/name_tag/fragment.frag.slang", resources: [VALUE: "fontAtlas"]],
            [prefix: "OVERLAY_TLAS", source: "pipelines/block_outline/fragment.frag.slang", resources: [VALUE: "tlas"]]
    ]

    // Gradle decorates task classes; this must remain non-private for Groovy dispatch inside PIPELINES.each.
    Map reflect(File source, File scratchDir) {
        def stem = source.name.replaceAll(/\W+/, "-")
        def reflectionFile = new File(scratchDir, "${stem}.json")
        def spvFile = new File(scratchDir, "${stem}.spv")
        def includeDirs = shaderRoot.get().asFileTree.matching { include "**/*.slang" }.files
                .collect { it.parentFile }.unique().sort { it.absolutePath }
        def includes = [source.parentFile] + includeDirs.findAll { it != source.parentFile }
        execOps.exec {
            commandLine([slangc.get(), source.absolutePath] + includes.collectMany { ["-I", it.absolutePath] } + [
                    "-target", "spirv", "-profile", "spirv_1_5", "-matrix-layout-column-major",
                    "-warnings-as-errors", "all", "-warnings-disable", "41012",
                    "-reflection-json", reflectionFile.absolutePath, "-o", spvFile.absolutePath])
        }
        new JsonSlurper().parse(reflectionFile) as Map
    }

    @TaskAction
    void generate() {
        def constants = new LinkedHashMap<String, Integer>()
        def scratchDir = new File(temporaryDir, "reflection")
        scratchDir.mkdirs()

        PIPELINES.each { spec ->
            def reflection = reflect(new File(shaderRoot.get().asFile, spec.source as String), scratchDir)
            def reflected = reflection.parameters.findAll { it.binding?.kind == "descriptorTableSlot" }
                    .collectEntries { [(it.name): it.binding] }
            def missing = spec.resources.values().findAll { !reflected.containsKey(it) }
            def unexpected = reflected.keySet().findAll { !spec.resources.containsValue(it) }
            if (!missing.isEmpty() || !unexpected.isEmpty()) {
                throw new GradleException("${spec.source} descriptor mismatch; missing=${missing}, unexpected=${unexpected}")
            }

            def locations = spec.resources.collectEntries { suffix, resource ->
                [(suffix): [index: reflected[resource].index as int, set: (reflected[resource].space ?: 0) as int]]
            }
            if (spec.prefix != "WORLD" && (locations.values()*.set as Set).size() != 1) {
                throw new GradleException("${spec.source} resources span unexpected descriptor sets: ${locations}")
            }
            locations.values().groupBy { it.set }.each { set, bindings ->
                def indices = bindings*.index.sort()
                if (indices != (0..<indices.size()).toList()) {
                    throw new GradleException("${spec.source} descriptor set ${set} is not contiguous: ${indices}")
                }
            }

            if (spec.prefix.toString().startsWith("OVERLAY_")) {
                constants[spec.prefix as String] = locations.VALUE.index
                def overlaySet = constants.putIfAbsent("OVERLAY_SET", locations.VALUE.set)
                if (overlaySet != null && overlaySet != locations.VALUE.set) {
                    throw new GradleException("overlay shaders disagree on descriptor set: ${overlaySet} and ${locations.VALUE.set}")
                }
            } else if (spec.prefix == "WORLD") {
                def ordinary = locations.findAll { suffix, ignored -> !(suffix as String).startsWith("ENTITY_") && !(suffix as String).startsWith("MATERIAL_") }
                def bindless = locations.findAll { suffix, ignored -> (suffix as String).startsWith("ENTITY_") || (suffix as String).startsWith("MATERIAL_") }
                if ((ordinary.values()*.set as Set).size() != 1 || (bindless.values()*.set as Set).size() != 1
                        || ordinary.values().first().set == bindless.values().first().set) {
                    throw new GradleException("world resources do not have distinct ordinary and bindless sets: ${locations}")
                }
                constants.WORLD_SET = ordinary.values().first().set
                ordinary.each { suffix, location -> constants["WORLD_${suffix}"] = location.index }
                def guides = ordinary.findAll { suffix, ignored -> (suffix as String).startsWith("G_") }
                // RESERVOIR_* are storage images like the guides, but deliberately not counted as guides:
                // WORLD_GUIDE_COUNT bounds setExtraStorageImage's slot range, which addresses bindings
                // contiguously from WORLD_G_NORMAL. Folding the reservoirs in there would let a guide
                // slot index walk into them.
                def reservoirs = ordinary.findAll { suffix, ignored -> (suffix as String).startsWith("RESERVOIR_") }
                def storageImages = guides + reservoirs + ordinary.findAll { suffix, ignored -> suffix == "OUTPUT" }
                def samplers = ordinary.findAll { suffix, ignored -> suffix != "TLAS" && !storageImages.containsKey(suffix) }
                constants.WORLD_GUIDE_COUNT = guides.size()
                constants.WORLD_RESERVOIR_COUNT = reservoirs.size()
                constants.WORLD_SET_BINDING_COUNT = ordinary.values()*.index.max() + 1
                constants.WORLD_SET_STORAGE_IMAGE_COUNT = storageImages.size()
                constants.WORLD_SET_SAMPLER_COUNT = samplers.size()
                constants.WORLD_BINDLESS_SET = bindless.values().first().set
                bindless.each { suffix, location -> constants["WORLD_${suffix}"] = location.index }
                constants.WORLD_BINDLESS_COUNT = bindless.size()
            } else {
                constants["${spec.prefix}_SET"] = locations.values().first().set
                locations.each { suffix, location -> constants["${spec.prefix}_${suffix}"] = location.index }
                constants["${spec.prefix}_BINDING_COUNT"] = locations.values()*.index.max() + 1
            }
        }

        def output = outDir.get().file("dev/comfyfluffy/caustica/rt/pipeline/RtBindings.java").asFile
        output.parentFile.mkdirs()
        output.setText("""// Generated from Slang descriptor reflection. Do not edit.
package dev.comfyfluffy.caustica.rt.pipeline;

public final class RtBindings {
${constants.collect { name, value -> "    public static final int ${name} = ${value};" }.join('\n')}

    private RtBindings() {
    }
}
""", "UTF-8")
    }
}
