package dev.aerove.lite.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.aerove.lite.R
import dev.aerove.lite.providers.ProviderKind
import dev.aerove.lite.providers.RuntimeProvider
import dev.aerove.lite.resources.ManagedResource

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ProviderManagementScreen(providers: List<RuntimeProvider>, busy: Set<String>, padding: PaddingValues,
    onRefresh: () -> Unit, onUpdate: (RuntimeProvider) -> Unit, onHealthcheck: (RuntimeProvider) -> Unit,
    onPreview: (RuntimeProvider) -> Unit, onEdit: (RuntimeProvider) -> Unit, onSideload: (RuntimeProvider) -> Unit,
    onCancel: () -> Unit, error: String? = null, resources: List<ManagedResource> = emptyList()) {
    val overlay = LocalLiteOverlay.current
    val owned = remember(overlay) { arrayOf<(@Composable () -> Unit)?>(null) }
    fun close() { if (overlay.content === owned[0]) overlay.dismiss(); owned[0] = null }
    DisposableEffect(overlay) { onDispose { close() } }
    LazyColumn(state = LocalLiteListState.current, contentPadding = padding, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { LiteButton(stringResource(if (busy.isEmpty()) R.string.proxy_tools_refresh else R.string.proxy_tools_cancel),
            { if (busy.isEmpty()) onRefresh() else onCancel() }, Modifier.fillMaxWidth()) }
        if (error != null) item { LiteText(error, Modifier.padding(horizontal = 16.dp), style = LiteType.Caption) }
        if (providers.isEmpty()) item {
            EmptyContent(stringResource(R.string.proxy_tools_providers), stringResource(R.string.proxy_tools_providers_empty), FullGlyphs.proxies)
        }
        items(providers, key = { "${it.kind}/${it.name}" }) { provider ->
            val working = "${provider.kind}/${provider.name}" in busy || "*" in busy
            LitePane(Modifier.fillMaxWidth().combinedClickable(enabled = !working, onClick = { onPreview(provider) }, onLongClick = {
                val content: @Composable () -> Unit = {
                    LiteModal(provider.name, ::close) {
                        if (provider.format != "mrs") LiteButton(stringResource(R.string.proxy_tools_edit), { close(); onEdit(provider) }, Modifier.fillMaxWidth())
                        LiteButton(stringResource(R.string.proxy_tools_upload), { close(); onSideload(provider) }, Modifier.fillMaxWidth())
                        LiteButton(stringResource(R.string.proxy_tools_sync), { close(); onUpdate(provider) }, Modifier.fillMaxWidth())
                        if (provider.kind == ProviderKind.Proxy) LiteButton(stringResource(R.string.proxy_tools_healthcheck), { close(); onHealthcheck(provider) }, Modifier.fillMaxWidth())
                        LiteButton(stringResource(R.string.proxy_tools_cancel), ::close, Modifier.fillMaxWidth())
                    }
                }
                owned[0] = content; overlay.content = content
            })) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    LiteText(provider.name, style = LiteType.Headline)
                    if (provider.kind == ProviderKind.Proxy) LiteText(stringResource(R.string.proxy_tools_proxy_count, provider.proxies.size), style = LiteType.Caption)
                    else LiteText(listOfNotNull(provider.behavior, provider.format).joinToString(" · "), style = LiteType.Caption)
                    val key = "$.${if (provider.kind == ProviderKind.Proxy) "proxy-providers" else "rule-providers"}.${provider.name}"
                    if (provider.kind == ProviderKind.Rule) (provider.ruleCount ?: resources.singleOrNull { it.key == key }?.count)?.let {
                        LiteText(stringResource(R.string.proxy_tools_rule_count, it), style = LiteType.Caption)
                    }
                    resources.singleOrNull { it.key == key }?.subscriptionInfo?.let { info ->
                        LiteText(stringResource(R.string.proxy_tools_subscription_bytes, info.used, info.total), style = LiteType.Caption)
                        info.expireSeconds?.let { LiteText(formatTime(it * 1000), style = LiteType.Caption) }
                    }
                    provider.updatedAt?.let { LiteText(it, style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk) }
                    if (working) LiteText(stringResource(R.string.proxy_tools_working), style = LiteType.Caption)
                }
            }
        }
    }
}
