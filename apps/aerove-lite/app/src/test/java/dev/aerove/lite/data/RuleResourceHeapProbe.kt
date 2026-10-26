package dev.aerove.lite.data

import java.io.File
import java.nio.file.Files
import java.util.UUID
import kotlinx.coroutines.runBlocking

/** One production rule read in this JVM; [RuleResourceHeapTest] starts it under a fixed -Xmx and reads the last line. */
object RuleResourceHeapProbe {
    @JvmStatic fun main(args: Array<String>) {
        val file = File(args[1])
        val outcome = runCatching {
            when (args[0]) {
                "lines" -> file.forEachRuleLine(MAX_RULE_INPUT_BYTES) { _, _ -> }.requireEntryBound("$.probe")
                "count" -> file.countRuleLines(MAX_RULE_INPUT_BYTES)
                "yaml" -> (file.readRuleYaml(MAX_RULE_INPUT_BYTES)["payload"] as List<*>).size
                "loader" -> file.ruleTextReader(MAX_RULE_INPUT_BYTES).use {
                    (ConfigYaml.parseRule(BoundedYamlStream(it), MAX_RULE_INPUT_BYTES.toInt(), MAX_RULE_YAML_VALUES)["payload"] as List<*>).size
                }
                "preview" -> file.readEditorRuleText(MAX_RULE_TEXT_BYTES)?.length ?: "oversize"
                "prepare-text", "prepare-yaml" -> prepare(file, args[0].removePrefix("prepare-"))
                else -> error(args[0])
            }
        }.fold({ "ok $it" }, { "${it.javaClass.name}: ${it.message}" })
        println(outcome)
    }

    private fun prepare(file: File, format: String): Int = runBlocking {
        val runtime = Files.createTempDirectory("rule-heap").toFile()
        try {
            val manager = ResourceBundleManager(runtime, CompatibilityValidator(NativeConfigValidator { null }, runtime), NativeResourceValidator { _, _, _, _ -> null })
            val profile = UUID.randomUUID().toString()
            file.inputStream().use { manager.importLocal(profile, "big.$format", it) }
            val source = "rule-providers:\n  big: {type: file, path: big.$format, behavior: domain, format: $format}\nrules: ['RULE-SET,big,DIRECT', 'MATCH,DIRECT']\n"
            manager.prepare(profile, source).let { bundle -> File(bundle.directory, "manifest.json").length().toInt() }
        } finally { runtime.deleteRecursively() }
    }
}
