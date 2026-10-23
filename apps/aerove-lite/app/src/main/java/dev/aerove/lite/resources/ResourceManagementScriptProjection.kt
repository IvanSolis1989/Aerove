package dev.aerove.lite.resources

import dev.aerove.lite.overrides.ScriptResource

/** Presentation projection only; the script library remains the sole persistence/update owner. */
fun scriptResourceItems(scripts: List<ScriptResource>): List<ResourceManagementRemoteResource> = scripts.mapNotNull { script ->
    val url = script.url ?: return@mapNotNull null
    ResourceManagementRemoteResource("script:${script.id}", ResourceManagementRemoteKind.Script,
        script.name, url, script.byteSize.toLong(), script.updatedAt, null)
}
