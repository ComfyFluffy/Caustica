package dev.comfyfluffy.caustica.build

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations

import javax.inject.Inject

abstract class GenerateShaderRecords extends DefaultTask {
    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getWorldSourceDir()

    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getProbeSource()

    @Input abstract Property<String> getSlangc()
    @Input abstract Property<String> getSpirvVal()
    @OutputDirectory abstract DirectoryProperty getOutDir()

    @Inject abstract ExecOperations getExecOps()

    private static String upperSnake(String name) {
        name.replaceAll(/([a-z0-9])([A-Z])/, '$1_$2').toUpperCase(Locale.ROOT)
    }

    private static String vectorType(Map type) {
        def scalar = type.elementType.scalarType
        def prefix = scalar == "float32" ? "Float" : (scalar in ["int32", "uint32"] ? "Int" : null)
        if (prefix == null || !(type.elementCount in [2, 3, 4])) {
            throw new GradleException("unsupported reflected vector type: ${type}")
        }
        "${prefix}${type.elementCount}"
    }

    private static String javaType(Map type) {
        switch (type.kind) {
            case "scalar":
                switch (type.scalarType) {
                    case "float32": return "float"
                    case "int32":
                    case "uint32": return "int"
                    case "int64":
                    case "uint64": return "long"
                    default: throw new GradleException("unsupported reflected scalar type: ${type.scalarType}")
                }
            case "vector": return vectorType(type)
            case "matrix":
                if (type.rowCount == 4 && type.columnCount == 4 && type.elementType.scalarType == "float32") {
                    return "Matrix4fc"
                }
                throw new GradleException("unsupported reflected matrix type: ${type}")
            case "struct": return type.name
            case "array": return "${javaType(type.elementType as Map)}[]"
            default: throw new GradleException("unsupported reflected type kind: ${type.kind}")
        }
    }

    private static void collectTypes(Map type, Set<String> vectors, Map<String, Map> structs) {
        if (type.kind == "vector") {
            vectors.add(vectorType(type))
        } else if (type.kind == "struct") {
            structs.putIfAbsent(type.name as String, type)
            type.fields.each { collectTypes(it.type as Map, vectors, structs) }
        } else if (type.kind == "array") {
            collectTypes(type.elementType as Map, vectors, structs)
        }
    }

    private static boolean containsKind(Map type, String kind) {
        if (type.kind == kind) return true
        if (type.kind == "array") return containsKind(type.elementType as Map, kind)
        if (type.kind == "struct") return type.fields.any { containsKind(it.type as Map, kind) }
        false
    }

    private static String at(String base, int offset) {
        base == "0" ? "${offset}" : (offset == 0 ? base : "${base} + ${offset}")
    }

    private static void emitWrite(StringBuilder sb, Map type, Map binding, String expr, String base,
                                  String indent, int depth) {
        def address = at(base, (binding.offset ?: 0) as int)
        switch (type.kind) {
            case "scalar":
                def method = type.scalarType == "float32" ? "putFloat"
                        : (type.scalarType in ["int32", "uint32"] ? "putInt"
                        : (type.scalarType in ["int64", "uint64"] ? "putLong" : null))
                if (method == null) throw new GradleException("unsupported scalar writer: ${type.scalarType}")
                sb << "${indent}dst.${method}(${address}, ${expr});\n"
                return
            case "vector":
                def method = type.elementType.scalarType == "float32" ? "putFloat" : "putInt"
                def lanes = ["x", "y", "z", "w"]
                def stride = (binding.elementStride ?: 4) as int
                for (int i = 0; i < (type.elementCount as int); i++) {
                    sb << "${indent}dst.${method}(${at(address, i * stride)}, ${expr}.${lanes[i]}());\n"
                }
                return
            case "matrix":
                if (type.rowCount != 4 || type.columnCount != 4 || type.elementType.scalarType != "float32") {
                    throw new GradleException("unsupported matrix writer: ${type}")
                }
                sb << "${indent}${expr}.get(${address}, dst);\n"
                return
            case "struct":
                type.fields.each { field ->
                    emitWrite(sb, field.type as Map, field.binding as Map,
                            "${expr}.${field.name}()", address, indent, depth + 1)
                }
                return
            case "array":
                def index = "i${depth}"
                def stride = (type.uniformStride ?: binding.elementStride) as int
                sb << "${indent}for (int ${index} = 0; ${index} < ${expr}.length; ${index}++) {\n"
                emitWrite(sb, type.elementType as Map, [offset: 0], "${expr}[${index}]",
                        "${address} + ${index} * ${stride}", indent + "    ", depth + 1)
                sb << "${indent}}\n"
                return
            default:
                throw new GradleException("unsupported writer type: ${type.kind}")
        }
    }

    private static String generateJava(Map rootType, int byteSize, String className) {
        def fields = rootType.fields as List<Map>
        def arrays = fields.findAll { it.type.kind == "array" }
        def vectors = new LinkedHashSet<String>()
        def structs = new LinkedHashMap<String, Map>()
        collectTypes(rootType, vectors, structs)
        structs.remove(rootType.name)

        def sb = new StringBuilder()
        sb << "// GENERATED by generateShaderRecords from Slang reflection — DO NOT EDIT.\n"
        sb << "package dev.comfyfluffy.caustica.rt.gen;\n\n"
        sb << "import java.nio.ByteBuffer;\n"
        sb << "import java.util.Objects;\n"
        if (containsKind(rootType, "matrix")) sb << "import org.joml.Matrix4fc;\n"
        sb << "\npublic record ${className}(\n"
        fields.eachWithIndex { field, i ->
            sb << "        ${javaType(field.type as Map)} ${field.name}${i + 1 == fields.size() ? '' : ','}\n"
        }
        sb << ") {\n"
        sb << "    public static final int BYTE_SIZE = ${byteSize};\n"
        arrays.each { field ->
            sb << "    public static final int ${upperSnake(field.name)}_CAPACITY = ${field.type.elementCount};\n"
        }

        def validatedFields = fields.findAll { it.type.kind in ["matrix", "struct", "array"] }
        if (!validatedFields.isEmpty()) {
            sb << "\n    public ${className} {\n"
            validatedFields.each { field ->
                sb << "        Objects.requireNonNull(${field.name}, \"${field.name}\");\n"
                if (field.type.kind == "array") {
                    sb << "        if (${field.name}.length > ${upperSnake(field.name)}_CAPACITY) {\n"
                    sb << "            throw new IllegalArgumentException(\"${field.name} has \" + ${field.name}.length + \" entries; capacity is \" + ${upperSnake(field.name)}_CAPACITY);\n"
                    sb << "        }\n"
                    sb << "        ${field.name} = ${field.name}.clone();\n"
                    sb << "        for (var value : ${field.name}) Objects.requireNonNull(value, \"${field.name} entry\");\n"
                }
            }
            sb << "    }\n"
        }

        sb << "\n    public void write(ByteBuffer dst) {\n"
        sb << "        Objects.requireNonNull(dst, \"dst\");\n"
        sb << "        if (dst.capacity() < BYTE_SIZE) throw new IllegalArgumentException(\"${className} buffer is too small: \" + dst.capacity());\n"
        sb << "        for (int i = 0; i < BYTE_SIZE; i++) dst.put(i, (byte) 0);\n"
        fields.each { field ->
            emitWrite(sb, field.type as Map, field.binding as Map, "${field.name}()", "0", "        ", 0)
        }
        sb << "    }\n\n"

        vectors.sort().each { name ->
            def count = Integer.parseInt(name.substring(name.length() - 1))
            def primitive = name.startsWith("Float") ? "float" : "int"
            def lanes = ["x", "y", "z", "w"].take(count)
            sb << "    public record ${name}(" + lanes.collect { "${primitive} ${it}" }.join(", ") + ") {}\n"
        }
        if (!vectors.isEmpty() && !structs.isEmpty()) sb << "\n"
        structs.each { name, type ->
            sb << "    public record ${name}("
            sb << (type.fields as List<Map>).collect { "${javaType(it.type as Map)} ${it.name}" }.join(", ")
            sb << ") {}\n"
        }
        sb << "}\n"
        sb.toString()
    }

    @TaskAction
    void generate() {
        def reflectionFile = new File(temporaryDir, "shader-records-reflection.json")
        def probeSpv = new File(temporaryDir, "shader-layout-probe.spv")
        execOps.exec {
            commandLine slangc.get(), probeSource.get().asFile.absolutePath,
                    "-target", "spirv", "-profile", "spirv_1_5", "-matrix-layout-column-major",
                    "-warnings-as-errors", "all", "-warnings-disable", "41012",
                    "-reflection-json", reflectionFile.absolutePath, "-o", probeSpv.absolutePath
        }
        execOps.exec {
            commandLine spirvVal.get(), "--target-env", "vulkan1.2", probeSpv.absolutePath
        }

        def reflection = new JsonSlurper().parse(reflectionFile)
        def parameter = reflection.parameters.find { it.name == "worldPushLayoutProbe" }
        def probeArray = parameter?.type?.resultType?.fields?.find { it.name == "values" }
        if (probeArray?.type?.kind != "array" || probeArray.type.elementType?.name != "WorldPush") {
            throw new GradleException("unexpected WorldPush reflection probe shape")
        }
        Map worldType = probeArray.type.elementType as Map
        int worldByteSize = probeArray.type.uniformStride as int

        def materialParameter = reflection.parameters.find { it.name == "materialHeaderLayoutProbe" }
        def materialProbeArray = materialParameter?.type?.resultType?.fields?.find { it.name == "values" }
        if (materialProbeArray?.type?.kind != "array" || materialProbeArray.type.elementType?.name != "MaterialHeader") {
            throw new GradleException("unexpected MaterialHeader reflection probe shape")
        }
        Map materialHeaderType = materialProbeArray.type.elementType as Map
        int materialHeaderByteSize = materialProbeArray.type.uniformStride as int

        def pushParameter = reflection.parameters.find { it.name == "pushAddrLayoutProbe" }
        if (pushParameter?.type?.elementType?.name != "PushAddr") {
            throw new GradleException("Slang reflection omitted pushAddrLayoutProbe")
        }
        Map pushAddrType = pushParameter.type.elementType as Map
        int pushAddrByteSize = pushParameter.type.elementVarLayout.binding.size as int

        def generatedRoot = outDir.get().asFile
        if (generatedRoot.exists() && !generatedRoot.deleteDir()) {
            throw new GradleException("failed to clear generated shader record sources under ${generatedRoot}")
        }
        def packageDir = new File(generatedRoot, "dev/comfyfluffy/caustica/rt/gen")
        packageDir.mkdirs()
        new File(packageDir, "WorldPushData.java").setText(
                generateJava(worldType, worldByteSize, "WorldPushData"), "UTF-8")
        new File(packageDir, "PushAddrData.java").setText(
                generateJava(pushAddrType, pushAddrByteSize, "PushAddrData"), "UTF-8")
        new File(packageDir, "MaterialHeaderData.java").setText(
                generateJava(materialHeaderType, materialHeaderByteSize, "MaterialHeaderData"), "UTF-8")
    }
}
