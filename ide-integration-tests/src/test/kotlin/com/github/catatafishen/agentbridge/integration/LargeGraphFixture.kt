package com.github.catatafishen.agentbridge.integration

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.exists
import kotlin.io.path.isDirectory
import kotlin.io.path.writeText

/**
 * Generates a large, densely linked class graph inside a fixture project so reference searches have
 * real work to do. Every node owns a `Widget`, links to three other nodes, and carries a method body
 * with loops and branches — so `Widget` ends up with one usage per node while the PSI/index has
 * thousands of files to walk.
 *
 * The output lives in a git-ignored `generated/` directory (see `.gitignore`) and is removed again by
 * [delete]; committing 10,000 files would only bloat the repository.
 */
internal object LargeGraphFixture {

    const val DEFAULT_NODE_COUNT = 10_000

    private const val NODE_PREFIX = "Node"
    private const val GENERATED_DIR = "generated"
    private val LINK_OFFSETS = listOf(1, 97, 1_013)

    /** Whether a generator exists for [ide]'s fixture language (Java for IU, C++ for CL). */
    fun supports(ide: IdeUnderTest): Boolean = ide.key == "IU" || ide.key == "CL"

    private fun directoryFor(ide: IdeUnderTest, fixtureRoot: Path): Path = when (ide.key) {
        "IU" -> fixtureRoot.resolve("src/fixture/$GENERATED_DIR")
        else -> fixtureRoot.resolve(GENERATED_DIR)
    }

    fun generate(ide: IdeUnderTest, fixtureRoot: Path, nodeCount: Int = DEFAULT_NODE_COUNT): Path {
        require(supports(ide)) { "No large-graph generator for ${ide.key}" }
        val dir = directoryFor(ide, fixtureRoot)
        delete(dir)
        dir.createDirectories()
        for (i in 0 until nodeCount) {
            if (ide.key == "IU") {
                dir.resolve("${name(i)}.java").writeText(javaNode(i, nodeCount))
            } else {
                dir.resolve("${name(i)}.cpp").writeText(cppNode(i, nodeCount))
            }
        }
        println("[integration] ${ide.key}: generated $nodeCount graph nodes in $dir")
        return dir
    }

    fun delete(dir: Path) {
        if (!dir.exists()) return
        require(dir.isDirectory() && dir.fileName.toString() == GENERATED_DIR) {
            "Refusing to delete unexpected directory $dir"
        }
        Files.walk(dir).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach { it.deleteIfExists() } }
    }

    private fun name(index: Int) = "$NODE_PREFIX%05d".format(index)

    private fun links(index: Int, count: Int) = LINK_OFFSETS.map { name((index + it) % count) }

    private fun javaNode(index: Int, count: Int): String {
        val links = links(index, count)
        return buildString {
            appendLine("package fixture.generated;")
            appendLine()
            appendLine("import fixture.Widget;")
            appendLine()
            appendLine("public class ${name(index)} {")
            appendLine("    private final Widget widget = new Widget();")
            links.forEachIndexed { n, type -> appendLine("    private $type link$n;") }
            appendLine()
            appendLine("    public int weigh(int depth) {")
            appendLine("        int sum = widget.area();")
            appendLine("        for (int i = 0; i < 16; i++) {")
            appendLine("            sum += i * depth;")
            links.indices.forEach { n ->
                appendLine("            if (link$n != null && depth > 0) sum += link$n.weigh(depth - 1);")
            }
            appendLine("            switch (i % 4) {")
            appendLine("                case 0 -> sum ^= depth;")
            appendLine("                case 1 -> sum += i;")
            appendLine("                case 2 -> sum -= depth;")
            appendLine("                default -> sum *= 2;")
            appendLine("            }")
            appendLine("        }")
            appendLine("        return sum;")
            appendLine("    }")
            appendLine("}")
        }
    }

    private fun cppNode(index: Int, count: Int): String {
        val self = name(index)
        val links = links(index, count)
        return buildString {
            appendLine("#include \"classdef.h\"")
            appendLine()
            appendLine("namespace vsc {")
            appendLine()
            links.forEach { appendLine("class $it;") }
            appendLine()
            appendLine("class $self {")
            appendLine("public:")
            appendLine("    Widget widget;")
            links.forEachIndexed { n, type -> appendLine("    $type* link$n = nullptr;") }
            appendLine()
            appendLine("    int weigh(int depth) const {")
            appendLine("        int sum = widget.width * widget.height;")
            appendLine("        for (int i = 0; i < 16; i++) {")
            appendLine("            sum += i * depth;")
            links.indices.forEach { n -> appendLine("            if (link$n != nullptr) sum += i;") }
            appendLine("            switch (i % 4) {")
            appendLine("                case 0: sum ^= depth; break;")
            appendLine("                case 1: sum += i; break;")
            appendLine("                case 2: sum -= depth; break;")
            appendLine("                default: sum *= 2; break;")
            appendLine("            }")
            appendLine("        }")
            appendLine("        return sum;")
            appendLine("    }")
            appendLine("};")
            appendLine()
            appendLine("int touch$self() {")
            appendLine("    $self node;")
            appendLine("    return node.weigh(1);")
            appendLine("}")
            appendLine()
            appendLine("} // namespace vsc")
        }
    }
}
