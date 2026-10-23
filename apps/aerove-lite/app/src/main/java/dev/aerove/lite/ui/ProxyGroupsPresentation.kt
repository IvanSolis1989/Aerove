package dev.aerove.lite.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aerove.lite.ProxyGroup
import dev.aerove.lite.ProxyNode
import dev.aerove.lite.R
import dev.aerove.lite.proxies.*
import dev.aerove.lite.ui.colorutilities.TonalPalette
import kotlin.math.ceil
import kotlin.math.PI
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val ProxyBoltGlyph by lazy { LiteGlyph(listOf(glyphPolyline(listOf(vertex(14.5, 2.8, 1), vertex(5, 13.7, 1),
    vertex(11.2, 13.7, 0.6), vertex(9.5, 21.2, 1), vertex(19, 10.3, 1), vertex(12.8, 10.3, 0.6)), closed = true))) }
private val ProxyClockGlyph by lazy { LiteGlyph(listOf(glyphCircle(12, 12, 8.8),
    glyphPolyline(listOf(vertex(12, 7.2), vertex(12, 12, 0.6), vertex(15.2, 14)), role = LiteGlyphRole.Detail))) }
// Standard AppTextStyle.caption2 is not yet part of the shared LiteType surface.
private val ProxyCaption2 = LiteType.Caption.copy(fontSize = 11.sp, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold)

private val ProxyLocateGlyph by lazy { LiteGlyph(listOf(glyphCircle(12, 12, 6.2), glyphCircle(12, 12, 1.8, role = LiteGlyphRole.Detail, solid = true),
    glyphPolyline(listOf(vertex(12, 2.9), vertex(12, 5.8)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(12, 18.2), vertex(12, 21.1)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(2.9, 12), vertex(5.8, 12)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(18.2, 12), vertex(21.1, 12)), role = LiteGlyphRole.Line))) }

private val ProxySortGlyph by lazy { LiteGlyph(listOf(
    glyphPolyline(listOf(vertex(4, 7), vertex(20, 7)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(4, 12), vertex(15, 12)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(4, 17), vertex(10, 17)), role = LiteGlyphRole.Line)), mirrorInRtl = true) }

private val ProxySizeGlyph by lazy { LiteGlyph(listOf(
    glyphPolyline(listOf(vertex(3.2, 4.4), vertex(14, 4.4)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(8.6, 4.4), vertex(8.6, 19.6)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(14.8, 10.8), vertex(21, 10.8)), role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(17.9, 10.8), vertex(17.9, 19.6)), role = LiteGlyphRole.Line))) }

private val ProxyTileGlyph by lazy { LiteGlyph(listOf(glyphBox(3, 3, 21, 21, 5),
    glyphPolyline(listOf(vertex(5.6, 16.8), vertex(9.6, 12.8, 0.8), vertex(12.8, 16, 0.8), vertex(14.6, 14.2, 0.8), vertex(18.4, 18)), role = LiteGlyphRole.Detail),
    glyphCircle(15.2, 8.8, 1.5, role = LiteGlyphRole.Detail, solid = true))) }

private val ProxyHideGlyph by lazy { LiteGlyph(listOf(
    glyphPath("M10.95 5.86A10.08 10.08 0 0 1 20.99 11.32A1.5 1.5 0 0 1 20.99 12.68A10.08 10.08 0 0 1 19.71 14.62", role = LiteGlyphRole.Line),
    glyphPath("M16.92 16.92A10.08 10.08 0 0 1 3.01 12.68A1.5 1.5 0 0 1 3.01 11.32A10.08 10.08 0 0 1 7.08 7.08", role = LiteGlyphRole.Line),
    glyphArc(12, 12, 2.8, PI * 0.25, PI, role = LiteGlyphRole.Line),
    glyphPolyline(listOf(vertex(4, 4), vertex(20, 20)), role = LiteGlyphRole.Line))) }

/** A sliver's rectangular middle shares one continuous outer group silhouette. */
private class ProxyGroupSegmentShape(private val top: Boolean, private val bottom: Boolean) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val rounded = LiteSuperellipse(LiteCorner.Card).createOutline(size, layoutDirection, density)
        val path = Path().apply {
            if (rounded is Outline.Generic) addPath(rounded.path)
            if (!top) addRect(Rect(0f, 0f, size.width, size.height / 2f))
            if (!bottom) addRect(Rect(0f, size.height / 2f, size.width, size.height))
        }
        return Outline.Generic(path)
    }
}

/** Standard common/layout.dart: width is the space available to the node grid. */
internal fun proxyPresentationColumns(width: Float, layout: ProxyPresentationLayout): Int {
    val base = maxOf(ceil(width.coerceAtLeast(0f) / 250f).toInt(), 2)
    return base + when (layout) { ProxyPresentationLayout.tight -> 1; ProxyPresentationLayout.standard -> 0; ProxyPresentationLayout.loose -> -1 }
}

internal enum class ProxyOptionSection { Style, Sort, Layout, Size, Icon }
internal fun proxyOptionSections(value: ProxyPresentationPreferences): List<ProxyOptionSection> =
    listOf(ProxyOptionSection.Style, ProxyOptionSection.Sort, ProxyOptionSection.Layout, ProxyOptionSection.Size) +
        if (value.type == ProxyPresentationType.list) listOf(ProxyOptionSection.Icon) else emptyList()

internal fun proxyOptionChoices(section: ProxyOptionSection): List<String> = when (section) {
    ProxyOptionSection.Style -> listOf("tab", "list")
    ProxyOptionSection.Sort -> listOf("none", "delay", "byName")
    ProxyOptionSection.Layout -> listOf("loose", "standard", "tight")
    ProxyOptionSection.Size -> listOf("loose", "standard", "compact")
    ProxyOptionSection.Icon -> listOf("filled", "plain", "hidden")
}

internal fun proxyOptionSelection(value: ProxyPresentationPreferences, section: ProxyOptionSection, choice: String): ProxyPresentationPreferences {
    require(choice in proxyOptionChoices(section))
    return when (section) {
        ProxyOptionSection.Style -> value.copy(type = ProxyPresentationType.valueOf(choice))
        ProxyOptionSection.Sort -> value.copy(sort = ProxyPresentationSort.valueOf(choice))
        ProxyOptionSection.Layout -> value.copy(layout = ProxyPresentationLayout.valueOf(choice))
        ProxyOptionSection.Size -> value.copy(cardType = ProxyPresentationCard.valueOf(choice))
        ProxyOptionSection.Icon -> value.copy(iconStyle = ProxyPresentationIcon.valueOf(choice))
    }
}

/** No settings or request owner: the host supplies the same authoritative state used by its toolbar. */
@Composable
@OptIn(ExperimentalFoundationApi::class)
internal fun ProxyGroupsPresentation(
    groups: List<ProxyGroup>, preferences: ProxyPresentationPreferences, query: String,
    delays: Map<ProxyDelayTarget, Int>, groupUrls: Map<String, String>, currentGroup: String?,
    unfolded: Set<String>, testingGroups: Set<String>, enabled: Boolean, padding: PaddingValues,
    onCurrentGroup: (String) -> Unit, onToggleGroup: (String) -> Unit, onSelect: (String, String) -> Unit,
    onTestGroup: (String) -> Unit, onTestNode: (String, String) -> Unit, onLocateGroup: (String) -> Unit,
    groupIcon: (@Composable (ProxyGroup) -> Unit)? = null,
    locateRequest: Int = 0,
    pendingTargets: Set<ProxyDelayTarget> = emptySet(), runningTargets: Set<ProxyDelayTarget> = emptySet(),
) {
    val projected = remember(groups, preferences, query, delays, groupUrls) {
        projectProxyGroups(groups, preferences, query, delays, groupUrls)
    }
    if (projected.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(padding)) {
            EmptyContent(stringResource(if (query.isBlank()) R.string.lite_no_proxies else R.string.lite_no_matches), stringResource(R.string.lite_group_empty), FullGlyphs.proxies)
        }
        return
    }
    BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
        fun phase(group: ProxyGroup, node: ProxyNode): Int {
            val key = resolveProxyDelayTarget(node.name, groups, groupUrls, groupUrls[group.name] ?: preferences.testUrl)
            return when (key) { in runningTargets -> 2; in pendingTargets -> 1; else -> 0 }
        }
        val columns = proxyPresentationColumns((maxWidth - 32.dp).value, preferences.layout)
        if (preferences.type == ProxyPresentationType.tab) {
            val initial = projected.indexOfFirst { it.name == currentGroup }.coerceAtLeast(0)
            val pager = rememberPagerState(initialPage = initial) { projected.size }
            val tabs = rememberLazyListState()
            val overlay = LocalLiteOverlay.current
            var pickerAnchor by remember { mutableStateOf(Rect.Zero) }
            val latestOnCurrent by rememberUpdatedState(onCurrentGroup)
            LaunchedEffect(pager, projected.map { it.name }) {
                snapshotFlow { pager.settledPage }.distinctUntilChanged().collect { index ->
                    projected.getOrNull(index)?.let { latestOnCurrent(it.name) }
                }
            }
            LaunchedEffect(currentGroup, projected.map { it.name }) {
                val index = projected.indexOfFirst { it.name == currentGroup }
                if (index >= 0 && index != pager.currentPage) pager.animateScrollToPage(index)
            }
            LaunchedEffect(pager.currentPage, projected.map { it.name }) {
                tabs.animateScrollToItem(pager.currentPage)
                val info = tabs.layoutInfo
                val item = info.visibleItemsInfo.firstOrNull { it.index == pager.currentPage }
                if (item != null) tabs.animateScrollBy(item.offset + item.size / 2f - info.viewportEndOffset / 2f)
            }
            Column {
                Row(Modifier.padding(top = 4.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                LazyRow(state = tabs, contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f).testTag("proxy-group-tabs")) {
                    items(projected, key = { it.name }) { group ->
                        val selected = group.name == projected.getOrNull(pager.currentPage)?.name
                        LitePane(Modifier.selectable(selected, role = Role.Tab, onClick = { onCurrentGroup(group.name) }),
                            corner = LiteCorner.Full, selected = selected) {
                            LiteText(group.name, Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = LiteType.Label, maxLines = 1)
                        }
                    }
                }
                if (tabs.canScrollBackward || tabs.canScrollForward) LiteIconButton(stringResource(R.string.lite_tab_proxies), FullGlyphs.chevronDown, {
                    if (pickerAnchor != Rect.Zero && overlay.content == null) {
                        val source = pickerAnchor
                        overlay.content = { ProxyGroupsMenu(source, projected, projected.getOrNull(pager.currentPage)?.name, overlay::dismiss) { name ->
                            overlay.dismiss(); onCurrentGroup(name)
                        } }
                    }
                }, Modifier.padding(end = 16.dp).onGloballyPositioned { pickerAnchor = it.boundsInWindow() })
                }
                HorizontalPager(pager, Modifier.weight(1f), key = { projected[it].name }) { page ->
                    val group = projected[page]
                    val scroll = rememberLazyGridState()
                    LaunchedEffect(locateRequest) {
                        if (locateRequest != 0 && page == pager.currentPage) {
                            val index = group.nodes.indexOfFirst { it.name == group.selected }
                            if (index >= 0) scroll.animateScrollToItem(index)
                        }
                    }
                    LazyVerticalGrid(GridCells.Fixed(columns), state = scroll, contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize().testTag("proxy-tab-${group.name}")) {
                        items(group.nodes, key = { it.name }) { node ->
                            PresentationNode(group, node, preferences.cardType, enabled, onSelect, onTestNode, phase(group, node))
                        }
                    }
                }
            }
        } else {
            val scroll = rememberLazyListState()
            val scope = rememberCoroutineScope()
            val groupColumns = proxyPresentationColumns((maxWidth - 52.dp).value, preferences.layout)
            LazyColumn(state = scroll, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier.testTag("proxy-group-list")) {
                projected.forEach { group ->
                    stickyHeader(key = "header:${group.name}") {
                        PresentationGroupHeader(group, preferences.iconStyle, group.name in unfolded, group.name in testingGroups,
                            enabled, { onToggleGroup(group.name) }, { onTestGroup(group.name) }, {
                                val index = proxySelectedListIndex(projected, unfolded, group.name, groupColumns)
                                if (index != null) scope.launch { scroll.animateScrollToItem(index) }
                                onLocateGroup(group.name)
                            }, groupIcon)
                    }
                    if (group.name in unfolded) {
                        items(group.nodes.chunked(groupColumns), key = { "${group.name}:${it.first().name}" }) { row ->
                            val last = row.last() == group.nodes.lastOrNull()
                            LitePane(Modifier.fillMaxWidth().padding(bottom = if (last) 8.dp else 0.dp),
                                customShape = remember(last) { ProxyGroupSegmentShape(false, last) }) {
                            Row(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, top = 4.dp, bottom = if (last) 10.dp else 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                row.forEach { node -> Box(Modifier.weight(1f)) {
                                    PresentationNode(group, node, preferences.cardType, enabled, onSelect, onTestNode, phase(group, node))
                                } }
                                repeat(groupColumns - row.size) { Spacer(Modifier.weight(1f)) }
                            }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PresentationGroupHeader(group: ProxyGroup, iconStyle: ProxyPresentationIcon, expanded: Boolean, testing: Boolean,
    enabled: Boolean, toggle: () -> Unit, test: () -> Unit, locate: () -> Unit, groupIcon: (@Composable (ProxyGroup) -> Unit)?) {
    val toggleLabel = stringResource(if (expanded) R.string.lite_collapse_group else R.string.lite_expand_group, group.name)
    LitePane(Modifier.fillMaxWidth().padding(bottom = if (expanded) 0.dp else 8.dp), corner = LiteCorner.Card,
        customShape = remember(expanded) { ProxyGroupSegmentShape(true, !expanded) }) {
        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (iconStyle != ProxyPresentationIcon.hidden) {
                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                    if (iconStyle == ProxyPresentationIcon.filled) LitePane(Modifier.matchParentSize(), corner = LiteCorner.Control) {}
                    if (groupIcon != null) groupIcon(group) else LiteText(proxyGroupMark(group.name), style = LiteType.Headline, maxLines = 1)
                }
                Spacer(Modifier.width(12.dp))
            } else Spacer(Modifier.width(4.dp))
            Column(Modifier.weight(1f).clickable(role = Role.Button, onClick = toggle).semantics { contentDescription = toggleLabel }) {
                LiteText(if (iconStyle == ProxyPresentationIcon.hidden) group.name else proxyGroupTitle(group.name),
                    style = LiteType.Callout.copy(fontWeight = FontWeight.SemiBold), maxLines = 1)
                LiteText(group.selected, style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk, maxLines = 1)
            }
            if (expanded) {
                LiteIconButton(stringResource(R.string.proxy_presentation_scroll_to_selected), ProxyLocateGlyph, locate)
                val testingLabel = stringResource(R.string.lite_testing)
                if (testing) Box(Modifier.size(36.dp).semantics { contentDescription = testingLabel }, contentAlignment = Alignment.Center) {
                    if (LocalLitePageActive.current) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else LiteIcon(ProxyClockGlyph, size = 20.dp)
                } else LiteIconButton(stringResource(R.string.lite_test_group), ProxyBoltGlyph, test, enabled = enabled)
            } else LiteText(group.type, style = LiteType.Caption, color = LocalLiteVisuals.current.secondaryInk, maxLines = 1)
            Spacer(Modifier.width(6.dp))
            LiteIconButton(toggleLabel, if (expanded) FullGlyphs.chevronUp else FullGlyphs.chevronDown, toggle)
        }
    }
}

internal fun proxySelectedListIndex(groups: List<ProxyGroup>, unfolded: Set<String>, name: String, columns: Int): Int? {
    require(columns > 0)
    var offset = 0
    for (group in groups) {
        if (group.name == name) {
            val index = group.nodes.indexOfFirst { it.name == group.selected }
            return if (index >= 0 && name in unfolded) offset + 1 + index / columns else null
        }
        offset += 1 + if (group.name in unfolded) (group.nodes.size + columns - 1) / columns else 0
    }
    return null
}

internal fun proxyGroupMark(name: String): String {
    val clusters = ProxyGraphemePattern.matcher(name)
    var first = ""
    while (clusters.find()) {
        val cluster = clusters.group()
        if (first.isEmpty()) first = cluster
        if (proxyGroupEmoji(cluster)) return cluster
    }
    val letter = name.codePoints().toArray().firstOrNull { Character.isLetterOrDigit(it) }
    return (letter?.let { String(Character.toChars(it)) } ?: first).uppercase(java.util.Locale.ROOT)
}

internal fun proxyGroupTitle(name: String): String {
    val clusters = ProxyGraphemePattern.matcher(name)
    val text = buildString { while (clusters.find()) { val cluster = clusters.group(); if (!proxyGroupEmoji(cluster)) append(cluster) } }
    return if (text == name) name else text.trim().replace(Regex("\\s{2,}"), " ")
}

private fun proxyGroupEmoji(cluster: String): Boolean {
    if (ProxyEmojiSingles.containsMatchIn(cluster) || Regex("[#*0-9]\\uFE0F?\\u20E3").containsMatchIn(cluster)) return true
    val points = cluster.codePoints().toArray()
    return (0 until points.size - 1).any { i ->
        val a = points[i] - 0x1f1e6; val b = points[i + 1] - 0x1f1e6
        a in 0..25 && b in 0..25 && "${('A'.code + a).toChar()}${('A'.code + b).toChar()}" in ProxyEmojiFlags
    }
}

/*
Standard emoji_regex 0.0.5 scalar atoms and accepted regional-indicator pairs.
Generated by external evidence generate-emoji.cjs from locked package source SHA256 86329537272cec50333ff2d25c7ce6c86f552e81a6d12320c4abcb74b6cf9762.
MIT License

Copyright (c) 2019 Remi Rousselet

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
*/
private val ProxyEmojiSingles by lazy { Regex("""[\x{a9}\x{ae}\x{203c}\x{2049}\x{2122}\x{2139}\x{2194}-\x{2199}\x{21a9}-\x{21aa}\x{231a}-\x{231b}\x{2328}\x{23cf}\x{23e9}-\x{23f3}\x{23f8}-\x{23fa}\x{24c2}\x{25aa}-\x{25ab}\x{25b6}\x{25c0}\x{25fb}-\x{25fe}\x{2600}-\x{2604}\x{260e}\x{2611}\x{2614}-\x{2615}\x{2618}\x{261d}\x{2620}\x{2622}-\x{2623}\x{2626}\x{262a}\x{262e}-\x{262f}\x{2638}-\x{263a}\x{2640}\x{2642}\x{2648}-\x{2653}\x{265f}-\x{2660}\x{2663}\x{2665}-\x{2666}\x{2668}\x{267b}\x{267e}-\x{267f}\x{2692}-\x{2697}\x{2699}\x{269b}-\x{269c}\x{26a0}-\x{26a1}\x{26a7}\x{26aa}-\x{26ab}\x{26b0}-\x{26b1}\x{26bd}-\x{26be}\x{26c4}-\x{26c5}\x{26c8}\x{26ce}-\x{26cf}\x{26d1}\x{26d3}-\x{26d4}\x{26e9}-\x{26ea}\x{26f0}-\x{26f5}\x{26f7}-\x{26fa}\x{26fd}\x{2702}\x{2705}\x{2708}-\x{270d}\x{270f}\x{2712}\x{2714}\x{2716}\x{271d}\x{2721}\x{2728}\x{2733}-\x{2734}\x{2744}\x{2747}\x{274c}\x{274e}\x{2753}-\x{2755}\x{2757}\x{2763}-\x{2764}\x{2795}-\x{2797}\x{27a1}\x{27b0}\x{27bf}\x{2934}-\x{2935}\x{2b05}-\x{2b07}\x{2b1b}-\x{2b1c}\x{2b50}\x{2b55}\x{3030}\x{303d}\x{3297}\x{3299}\x{1f004}\x{1f0cf}\x{1f170}-\x{1f171}\x{1f17e}-\x{1f17f}\x{1f18e}\x{1f191}-\x{1f19a}\x{1f201}-\x{1f202}\x{1f21a}\x{1f22f}\x{1f232}-\x{1f23a}\x{1f250}-\x{1f251}\x{1f300}-\x{1f321}\x{1f324}-\x{1f393}\x{1f396}-\x{1f397}\x{1f399}-\x{1f39b}\x{1f39e}-\x{1f3f0}\x{1f3f3}-\x{1f3f5}\x{1f3f7}-\x{1f4fd}\x{1f4ff}-\x{1f53d}\x{1f549}-\x{1f54e}\x{1f550}-\x{1f567}\x{1f56f}-\x{1f570}\x{1f573}-\x{1f57a}\x{1f587}\x{1f58a}-\x{1f58d}\x{1f590}\x{1f595}-\x{1f596}\x{1f5a4}-\x{1f5a5}\x{1f5a8}\x{1f5b1}-\x{1f5b2}\x{1f5bc}\x{1f5c2}-\x{1f5c4}\x{1f5d1}-\x{1f5d3}\x{1f5dc}-\x{1f5de}\x{1f5e1}\x{1f5e3}\x{1f5e8}\x{1f5ef}\x{1f5f3}\x{1f5fa}-\x{1f64f}\x{1f680}-\x{1f6c5}\x{1f6cb}-\x{1f6d2}\x{1f6d5}-\x{1f6d7}\x{1f6dc}-\x{1f6e5}\x{1f6e9}\x{1f6eb}-\x{1f6ec}\x{1f6f0}\x{1f6f3}-\x{1f6fc}\x{1f7e0}-\x{1f7eb}\x{1f7f0}\x{1f90c}-\x{1f93a}\x{1f93c}-\x{1f945}\x{1f947}-\x{1f9ff}\x{1fa70}-\x{1fa7c}\x{1fa80}-\x{1fa88}\x{1fa90}-\x{1fabd}\x{1fabf}-\x{1fac5}\x{1face}-\x{1fadb}\x{1fae0}-\x{1fae8}\x{1faf0}-\x{1faf8}]""") }
private val ProxyEmojiFlags by lazy { "AC AD AE AF AG AI AL AM AO AQ AR AS AT AU AW AX AZ BA BB BD BE BF BG BH BI BJ BL BM BN BO BQ BR BS BT BV BW BY BZ CA CC CD CF CG CH CI CK CL CM CN CO CP CR CU CV CW CX CY CZ DE DG DJ DK DM DO DZ EA EC EE EG EH ER ES ET EU FI FJ FK FM FO FR GA GB GD GE GF GG GH GI GL GM GN GP GQ GR GS GT GU GW GY HK HM HN HR HT HU IC ID IE IL IM IN IO IQ IR IS IT JE JM JO JP KE KG KH KI KM KN KP KR KW KY KZ LA LB LC LI LK LR LS LT LU LV LY MA MC MD ME MF MG MH MK ML MM MN MO MP MQ MR MS MT MU MV MW MX MY MZ NA NC NE NF NG NI NL NO NP NR NU NZ OM PA PE PF PG PH PK PL PM PN PR PS PT PW PY QA RE RO RS RU RW SA SB SC SD SE SG SH SI SJ SK SL SM SN SO SR SS ST SV SX SY SZ TA TC TD TF TG TH TJ TK TL TM TN TO TR TT TV TW TZ UA UG UM UN US UY UZ VA VC VE VG VI VN VU WF WS XK YE YT ZA ZM ZW".split(" ").toSet() }

private val ProxyGraphemePattern by lazy { java.util.regex.Pattern.compile("\\X") }
internal fun proxySearchText(raw: String): String {
    val matches = ProxyGraphemePattern.matcher(raw)
    var count = 0
    var end = 0
    while (count < 256 && matches.find()) { end = matches.end(); count++ }
    return raw.substring(0, end)
}

@Composable
private fun PresentationNode(group: ProxyGroup, node: ProxyNode, card: ProxyPresentationCard, enabled: Boolean,
    select: (String, String) -> Unit, test: (String, String) -> Unit, phase: Int) {
    val selected = node.name == group.selected
    LitePane(Modifier.fillMaxWidth().selectable(selected, enabled, Role.RadioButton) { select(group.name, node.name) }
        .testTag("proxy-node-${group.name}-${node.name}"), corner = LiteCorner.Block, selected = false) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                LiteText(node.name, Modifier.weight(1f).heightIn(min = if (card == ProxyPresentationCard.compact) 20.dp else 40.dp),
                    style = LiteType.Label.copy(fontWeight = FontWeight.Normal), maxLines = if (card == ProxyPresentationCard.compact) 1 else 2)
                if (selected) LiteIcon(FullGlyphs.check, size = 16.dp, color = LocalLiteVisuals.current.primary)
            }
            Spacer(Modifier.height(8.dp))
            if (card == ProxyPresentationCard.loose) {
                LiteText(node.type, style = LiteType.Footnote, color = LocalLiteVisuals.current.secondaryInk, maxLines = 1)
                Spacer(Modifier.height(6.dp))
                PresentationDelay(node, group.name, enabled, test, phase)
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                LiteText(node.type, Modifier.weight(1f), style = LiteType.Footnote, color = LocalLiteVisuals.current.secondaryInk, maxLines = 1)
                PresentationDelay(node, group.name, enabled, test, phase)
            }
        }
    }
}

@Composable
private fun PresentationDelay(node: ProxyNode, group: String, enabled: Boolean, test: (String, String) -> Unit, phase: Int) {
    val label = stringResource(R.string.lite_test_group)
    Box(Modifier.clickable(enabled = enabled && phase == 0, role = Role.Button) { test(group, node.name) }.semantics { contentDescription = label }) {
        val colors = androidx.compose.material3.MaterialTheme.colorScheme
        val tone = if (LocalLiteVisuals.current.dark) 80 else 40
        when {
            phase == 2 && LocalLitePageActive.current -> androidx.compose.material3.CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp)
            phase != 0 -> LiteIcon(ProxyClockGlyph, size = 13.dp)
            node.delayMillis == null -> LiteIcon(ProxyBoltGlyph, size = 13.dp)
            else -> {
                val color = when {
                    node.delayMillis < 0 -> colors.error
                    node.delayMillis < 600 -> Color(TonalPalette.fromInt(0xff4caf50.toInt()).tone(tone))
                    else -> Color(TonalPalette.fromInt(0xffff9800.toInt()).tone(tone))
                }
                LiteText(if (node.delayMillis > 0) stringResource(R.string.proxy_tools_milliseconds, node.delayMillis)
                    else stringResource(if (node.delayMillis == -1) R.string.lite_timeout else R.string.proxy_presentation_delay_failed),
                    style = ProxyCaption2, color = color, maxLines = 1)
            }
        }
    }
}

@Composable
internal fun ProxyPresentationOptionsButton(preferences: ProxyPresentationPreferences, enabled: Boolean = true,
    onChanged: (ProxyPresentationPreferences) -> Unit) {
    val overlay = LocalLiteOverlay.current
    val latest by rememberUpdatedState(preferences)
    val latestEnabled by rememberUpdatedState(enabled)
    val changed by rememberUpdatedState(onChanged)
    var anchor by remember { mutableStateOf(Rect.Zero) }
    LiteIconButton(stringResource(R.string.editor_options), FullGlyphs.settings, {
        if (latestEnabled && anchor != Rect.Zero && overlay.content == null) {
            val source = anchor
            overlay.content = { ProxyOptionsMenu(source, latest, latestEnabled, overlay::dismiss) {
                overlay.dismiss(); changed(it)
            } }
        }
    }, Modifier.onGloballyPositioned { anchor = it.boundsInWindow() }.testTag("proxy-presentation-options"), enabled)
}

@Composable
private fun ProxyOptionsMenu(anchor: Rect, value: ProxyPresentationPreferences, enabled: Boolean,
    dismiss: () -> Unit, change: (ProxyPresentationPreferences) -> Unit) {
    var section by remember { mutableStateOf<ProxyOptionSection?>(null) }
    BackHandler { if (section == null) dismiss() else section = null }
    val density = LocalDensity.current
    val visuals = LocalLiteVisuals.current
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val title = stringResource(R.string.editor_options)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().clickable(onClick = dismiss))
        val width = minOf(280.dp, maxWidth - 32.dp)
        val left = (with(density) { anchor.right.toDp() } - width).coerceIn(16.dp, maxOf(16.dp, maxWidth - width - 16.dp))
        val top = with(density) { anchor.top.toDp() }.coerceIn(16.dp, maxOf(16.dp, maxHeight - 360.dp))
        val availableHeight = maxHeight - top - 16.dp
        val fill = if (visuals.dark) colors.surfaceContainerHigh else colors.surfaceBright
        LitePane(Modifier.absoluteOffset(left, top).width(width).testTag("proxy-options-menu")
            .semantics { paneTitle = title }.pointerInput(Unit) { detectTapGestures {} }, corner = LiteCorner.Control,
            solidColor = fill, glassStyle = dev.aerove.lite.optics.AeroveGlassStyle.menu(fill.copy(alpha = 0.24f).opticsColor()),
            rim = LiteGlassRim.menu(visuals.dark), shadows = fullMenuShadows(visuals.dark)) {
            Column(Modifier.heightIn(max = availableHeight).verticalScroll(rememberScrollState()).padding(8.dp)) {
                val current = section
                if (current == null) {
                    proxyOptionSections(value).forEach { item ->
                        val leading = when (item) { ProxyOptionSection.Style -> FullGlyphs.appsList; ProxyOptionSection.Sort -> ProxySortGlyph
                            ProxyOptionSection.Layout -> FullGlyphs.dashboard; ProxyOptionSection.Size -> ProxySizeGlyph; ProxyOptionSection.Icon -> ProxyTileGlyph }
                        ProxyMenuRow(proxySectionLabel(item), false, enabled, FullGlyphs.chevronForward, leading) { section = item }
                    }
                    Spacer(Modifier.height(8.dp))
                    ProxyMenuRow(stringResource(R.string.proxy_tools_hide_timeout), value.hideTimeoutProxies, enabled, leading = ProxyHideGlyph) {
                        change(value.copy(hideTimeoutProxies = !value.hideTimeoutProxies))
                    }
                } else {
                    ProxyMenuRow(proxySectionLabel(current), false, true, FullGlyphs.arrowBack) { section = null }
                    proxyOptionChoices(current).forEach { choice ->
                        val selected = proxyOptionSelection(value, current, choice) == value
                        ProxyMenuRow(proxyChoiceLabel(current, choice), selected, enabled) { change(proxyOptionSelection(value, current, choice)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun proxySectionLabel(section: ProxyOptionSection): String = stringResource(when (section) {
    ProxyOptionSection.Style -> R.string.proxy_tools_style; ProxyOptionSection.Sort -> R.string.proxy_tools_sort
    ProxyOptionSection.Layout -> R.string.proxy_tools_layout; ProxyOptionSection.Size -> R.string.proxy_tools_size
    ProxyOptionSection.Icon -> R.string.proxy_tools_icon
})

@Composable
private fun ProxyGroupsMenu(anchor: Rect, groups: List<ProxyGroup>, selected: String?, dismiss: () -> Unit, select: (String) -> Unit) {
    BackHandler(onBack = dismiss)
    val density = LocalDensity.current
    val visuals = LocalLiteVisuals.current
    val colors = androidx.compose.material3.MaterialTheme.colorScheme
    val title = stringResource(R.string.lite_tab_proxies)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(Modifier.matchParentSize().clickable(onClick = dismiss))
        val width = minOf(280.dp, maxWidth - 32.dp)
        val safeTop = with(density) { WindowInsets.safeDrawing.getTop(this).toDp() }
        val safeBottom = with(density) { WindowInsets.safeDrawing.getBottom(this).toDp() }
        val height = minOf((44 * 7.5f + 16).dp, maxHeight - safeTop - safeBottom - 32.dp)
        val left = (with(density) { anchor.right.toDp() } - width).coerceIn(16.dp, maxOf(16.dp, maxWidth - width - 16.dp))
        val top = with(density) { anchor.top.toDp() }.coerceIn(safeTop + 16.dp, maxOf(safeTop + 16.dp, maxHeight - safeBottom - height - 16.dp))
        val fill = if (visuals.dark) colors.surfaceContainerHigh else colors.surfaceBright
        LitePane(Modifier.absoluteOffset(left, top).width(width).semantics { paneTitle = title }.pointerInput(Unit) { detectTapGestures {} },
            corner = LiteCorner.Control, solidColor = fill,
            glassStyle = dev.aerove.lite.optics.AeroveGlassStyle.menu(fill.copy(alpha = 0.24f).opticsColor()),
            rim = LiteGlassRim.menu(visuals.dark), shadows = fullMenuShadows(visuals.dark)) {
            Column(Modifier.heightIn(max = height).verticalScroll(rememberScrollState()).padding(8.dp)) {
                groups.forEach { group -> ProxyMenuRow(group.name, group.name == selected, true) { select(group.name) } }
            }
        }
    }
}

@Composable
private fun proxyChoiceLabel(section: ProxyOptionSection, choice: String): String = stringResource(when (choice) {
    "tab" -> R.string.proxy_tools_tab; "list" -> R.string.proxy_tools_list; "none" -> R.string.proxy_tools_default
    "delay" -> R.string.proxy_tools_delay; "byName" -> R.string.proxy_tools_name; "loose" -> R.string.proxy_tools_loose
    "standard" -> R.string.proxy_tools_standard; "tight" -> R.string.proxy_tools_tight; "compact" -> R.string.proxy_tools_compact
    "filled" -> R.string.proxy_tools_filled; "plain" -> R.string.proxy_tools_plain; "hidden" -> R.string.proxy_tools_hidden
    else -> error("Invalid choice for $section")
})

@Composable
private fun ProxyMenuRow(label: String, checked: Boolean, enabled: Boolean, glyph: LiteGlyph? = null, leading: LiteGlyph? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(checked, enabled, Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) { LiteIcon(leading, size = 20.dp); Spacer(Modifier.width(12.dp)) }
        LiteText(label, Modifier.weight(1f), style = LiteType.Label.copy(fontWeight = FontWeight.Normal), maxLines = 1)
        if (checked || glyph != null) LiteIcon(if (checked) FullGlyphs.check else glyph!!, size = 16.dp)
    }
}

@Composable
internal fun ProxyPresentationSearchAction(query: String, onQuery: (String) -> Unit, enabled: Boolean = true,
    onExpandedChange: (Boolean) -> Unit) {
    LiteIconButton(stringResource(R.string.lite_search), FullGlyphs.search, {
        if (query.isNotEmpty()) onQuery("")
        onExpandedChange(true)
    }, enabled = enabled)
}

/** Replaces the toolbar title and actions, matching CommonScaffold's search app-bar layer. */
@Composable
internal fun ProxyPresentationSearchBar(query: String, onQuery: (String) -> Unit, onExpandedChange: (Boolean) -> Unit,
    enabled: Boolean = true, modifier: Modifier = Modifier, actions: @Composable () -> Unit = {}) {
    val focus = remember { FocusRequester() }
    val searchLabel = stringResource(R.string.lite_search)
    val visuals = LocalLiteVisuals.current
    fun exit() { onQuery(""); onExpandedChange(false) }
    BackHandler(onBack = ::exit)
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LiteIconButton(stringResource(R.string.lite_back), FullGlyphs.arrowBack, ::exit)
        BasicTextField(query, { raw ->
            onQuery(proxySearchText(raw))
        }, Modifier.weight(1f).focusRequester(focus).semantics { contentDescription = searchLabel }.testTag("proxy-search-field"),
            enabled = enabled, singleLine = true, textStyle = LiteType.Body.copy(color = visuals.ink, fontFamily = LocalLiteFontFamily.current),
            cursorBrush = SolidColor(visuals.primary), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            decorationBox = { field -> Box(Modifier.padding(vertical = 10.dp)) {
                if (query.isEmpty()) LiteText(searchLabel, style = LiteType.Body, color = visuals.secondaryInk)
                field()
            } })
        LiteIconButton(stringResource(R.string.proxy_presentation_clear_search), FullGlyphs.close, {
            if (query.isNotEmpty()) onQuery("") else exit()
        }, enabled = enabled)
        actions()
    }
}

@Composable
internal fun ProxyPresentationLocateAction(enabled: Boolean = true, onLocate: () -> Unit) {
    LiteIconButton(stringResource(R.string.proxy_presentation_scroll_to_selected), ProxyLocateGlyph, onLocate, enabled = enabled)
}
