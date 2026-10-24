-keep class dev.aerove.lite.core.NativeCore { *; }
-keep,includedescriptorclasses class dev.aerove.lite.core.MeowNativeCore {
    native <methods>;
}
-keep class dev.aerove.lite.core.SocketProtector { *; }
# The C bridge exports Java_dev_aerove_lite_regexp2_FilterNative_{batch,cancel,decrypt}.
-keep,includedescriptorclasses class dev.aerove.lite.regexp2.FilterNative {
    native <methods>;
}
# Rust calls this callback by method name and descriptor through JNI call_method.
-keep,includedescriptorclasses interface dev.aerove.lite.core.ConnectionOwnerLookup {
    public java.lang.String resolveConnectionOwner(int,java.lang.String,java.lang.String);
}
-keepclassmembers,includedescriptorclasses class dev.aerove.lite.core.ConnectionOwnerResolver {
    public java.lang.String resolveConnectionOwner(int,java.lang.String,java.lang.String);
}
-keep class dev.aerove.lite.MainActivity { *; }
-keep class dev.aerove.lite.LiteApplication { *; }
-keep class dev.aerove.lite.ui.LiteAppKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteMaterialKt { *; }
-keep class dev.aerove.lite.ui.LiteComponentsKt { *; }
-keep class dev.aerove.lite.ui.LiteVisuals { *; }
-keep class dev.aerove.lite.ui.LiteCorner { *; }
-keep class dev.aerove.lite.ui.LiteType { *; }
-keep class dev.aerove.lite.ui.LiteGlyph { *; }
-keep class dev.aerove.lite.ui.FullGlyphs { *; }
-keep enum dev.aerove.lite.MaterialTier { *; }
-keep enum dev.aerove.lite.ColorMode { *; }
-keep enum dev.aerove.lite.ColorStyle { *; }
-keep enum dev.aerove.lite.DashboardWidget { *; }
-keep enum dev.aerove.lite.ProxyMode { *; }
-keep enum dev.aerove.lite.VpnPhase { *; }
-keep enum dev.aerove.lite.AppFilterMode { *; }
-keep class dev.aerove.lite.LiteSettings { *; }
-keep class dev.aerove.lite.ProfileSummary { *; }
-keep class dev.aerove.lite.ProxyNode { *; }
-keep class dev.aerove.lite.ProxyGroup { *; }
-keep class dev.aerove.lite.ConnectionInfo { *; }
-keep class dev.aerove.lite.Traffic { *; }
-keep class dev.aerove.lite.LogLine { *; }
-keep class dev.aerove.lite.InstalledApp { *; }
-keep class dev.aerove.lite.OutboundIdentity { *; }
-keep class dev.aerove.lite.LiteState { *; }
-keep interface dev.aerove.lite.LiteActions { *; }
-keep class dev.aerove.lite.data.ProfileRepository { *; }
-keep class dev.aerove.lite.data.ProfileSnapshot { *; }
-keep class dev.aerove.lite.data.LiteSettingsStore { *; }
-keep class dev.aerove.lite.data.ConfigYaml { *; }
-keep class dev.aerove.lite.vpn.VpnSnapshot { *; }
-keep class dev.aerove.lite.vpn.CoreEndpoint { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteOpticsKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveSuperellipseShape { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveSuperellipseGeometry { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveGlassStyle { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveGlassCapture { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveOpticsPrograms { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveOpticsProgramsKt { *; }
-keep class dev.aerove.lite.optics.AeroveGlassStyle$Companion { *; }
-keep class dev.aerove.lite.optics.AeroveOpticsPrograms$Companion { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.FullPaletteKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.FullAuroraKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.FullAuroraMotion { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.FullAuroraPose { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.FullAuroraMesh { *; }
-keep class dev.aerove.lite.ui.FullAuroraMesh$Companion { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.colorutilities.Hct { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.DashboardEditorKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.AuroraWallpaperKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.AuroraMotionKt { *; }
-keep class dev.aerove.lite.ui.AuroraMotionActivity { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.EditorScreenKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.ScanScreenKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.BackupScreenKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.OverviewScreenKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.DiagnosticsPanelsKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.DiagnosticsState { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ServiceCheckView { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorDocumentController { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorDocumentState { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorLanguage { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorAssets { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorInput { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorQuery { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.NativeEditorView { *; }
-keep,includedescriptorclasses class dev.aerove.lite.scan.ScanSession { *; }
-keep,includedescriptorclasses class dev.aerove.lite.scan.ScanPreview { *; }
-keep,includedescriptorclasses class dev.aerove.lite.scan.ScanFailure { *; }
-keep,includedescriptorclasses class dev.aerove.lite.scan.AlbumQrDecoder { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupUiState { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupActions { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupOptions { *; }
-keep enum dev.aerove.lite.data.BackupRestoreScope { *; }
-keep enum dev.aerove.lite.data.BackupRestoreStrategy { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupWebDavUi { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupRemoteFile { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupConfirmation { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupConfirmationKind { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupNotice { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupNoticeCode { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupProgress { *; }
-keep,includedescriptorclasses class dev.aerove.lite.LiteController { *; }
-keep,includedescriptorclasses class dev.aerove.lite.LiteFeatureHost { *; }
-keep class dev.aerove.lite.vpn.LiteVpnService { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.CoreApiClient { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.ControlEndpoint { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.MemorySnapshot { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.RouteSnapshot { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.ProfileMaintenanceFacade { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.ProfileValidator { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.SubscriptionDownloader { *; }
-keep,includedescriptorclasses class dev.aerove.lite.profiles.ProfileListFeature { *; }
-keep,includedescriptorclasses class dev.aerove.lite.profiles.ProfileDocuments { *; }
-keep,includedescriptorclasses class dev.aerove.lite.profiles.ProfileListState { *; }
-keep,includedescriptorclasses class dev.aerove.lite.profiles.ProfileListNotice { *; }
-keep,includedescriptorclasses class dev.aerove.lite.profiles.ProfileBatchResult { *; }
-keep,includedescriptorclasses class dev.aerove.lite.connections.ConnectionDecoderKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ipquality.IpQualityFeature { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ipquality.IpQuality { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ipquality.IpQualityFailure { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ipquality.IpQualityLookupException { *; }
-keep enum dev.aerove.lite.ipquality.IpQualitySource { *; }
-keep enum dev.aerove.lite.ipquality.IpQualitySourceStatus { *; }
-keep enum dev.aerove.lite.ipquality.IpType { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteFeatureHostKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteOverlayKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteOverlayState { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.UpdateFeature { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.UpdateState { *; }
-keep enum dev.aerove.lite.updates.UpdatePhase { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.UpdateIdentity { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.LiteRelease { *; }
-keep,includedescriptorclasses interface dev.aerove.lite.updates.UpdateTransport { *; }
-keep,includedescriptorclasses interface dev.aerove.lite.updates.UpdatePlatform { *; }
-keep,includedescriptorclasses interface dev.aerove.lite.updates.UpdateApkVerifier { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.UpdateRepository { *; }
-keep,includedescriptorclasses class dev.aerove.lite.networksettings.NetworkSettings { *; }
-keep,includedescriptorclasses class dev.aerove.lite.networksettings.UserProxyPorts { *; }
-keep,includedescriptorclasses class dev.aerove.lite.networksettings.ProxyAuthentication { *; }
-keep,includedescriptorclasses class dev.aerove.lite.networksettings.EffectiveVpnNetworkOptions { *; }
-keep,includedescriptorclasses class dev.aerove.lite.networksettings.VpnProxyPlan { *; }
-keep,includedescriptorclasses class dev.aerove.lite.networksettings.NetworkRuntimePlanKt {
    public static dev.aerove.lite.networksettings.EffectiveVpnNetworkOptions effectiveVpnNetworkOptions(dev.aerove.lite.networksettings.NetworkSettings);
}
-keep,includedescriptorclasses class dev.aerove.lite.presentation.SystemFontCatalog { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorPalette { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorPreferences { *; }
-keep enum dev.aerove.lite.editor.EditorFontSize { *; }
-keep enum dev.aerove.lite.localization.LocaleChoice { *; }
-keep class androidx.tracing.Trace {
    public static void beginSection(java.lang.String);
    public static void endSection();
    public static void forceEnableAppTracing();
}

# AndroidTest-to-app runtime ABI: exact external types referenced by the release AndroidTest DEX.
# Keep their binary names and members because the test APK resolves through the R8-shrunk app class loader.
-keep,includedescriptorclasses class androidx.activity.ComponentActivity { *; }
-keep,includedescriptorclasses class androidx.activity.OnBackPressedDispatcher { *; }
-keep,includedescriptorclasses class androidx.activity.compose.ComponentActivityKt { *; }
-keep,includedescriptorclasses class androidx.activity.compose.LocalActivityResultRegistryOwner { *; }
-keep,includedescriptorclasses class androidx.activity.result.ActivityResultRegistry { *; }
-keep,includedescriptorclasses class androidx.activity.result.ActivityResultRegistryOwner { *; }
-keep,includedescriptorclasses class androidx.activity.result.contract.ActivityResultContract { *; }
-keep,includedescriptorclasses class androidx.collection.IntObjectMapKt { *; }
-keep,includedescriptorclasses class androidx.collection.IntSet { *; }
-keep,includedescriptorclasses class androidx.collection.IntSetKt { *; }
-keep,includedescriptorclasses class androidx.collection.MutableIntObjectMap { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.BackgroundKt { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.CanvasKt { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.ScrollKt { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.ScrollState { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.gestures.FlingBehavior { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.interaction.MutableInteractionSource { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.Arrangement { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.Arrangement$Vertical { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.BoxKt { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.BoxScope { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.BoxScopeInstance { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.BoxWithConstraintsScope { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.ColumnKt { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.ColumnScopeInstance { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.OffsetKt { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.PaddingKt { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.PaddingValues { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.SizeKt { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.lazy.LazyListState { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.lazy.LazyListStateKt { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.text.TextAutoSize { *; }
-keep,includedescriptorclasses class androidx.compose.material3.ColorScheme { *; }
-keep,includedescriptorclasses class androidx.compose.material3.MaterialTheme { *; }
-keep,includedescriptorclasses class androidx.compose.material3.TextKt { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.Applier { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.ComposablesKt { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.Composer { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.Composer$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.ComposerKt { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.CompositionContext { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.CompositionLocal { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.CompositionLocalKt { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.CompositionLocalMap { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.DisposableEffectResult { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.DisposableEffectScope { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.EffectsKt { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.MonotonicFrameClock { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.MonotonicFrameClock$DefaultImpls { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.MutableState { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.ProvidableCompositionLocal { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.ProvidedValue { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.RecomposeScopeImplKt { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.Recomposer { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.SnapshotMutationPolicy { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.SnapshotStateKt { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.State { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.Updater { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.internal.ComposableLambdaKt { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.saveable.SaveableStateRegistry { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.saveable.SaveableStateRegistry$Entry { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.saveable.SaveableStateRegistryKt { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.snapshots.Snapshot { *; }
-keep,includedescriptorclasses class androidx.compose.runtime.snapshots.Snapshot$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.ui.Alignment { *; }
-keep,includedescriptorclasses class androidx.compose.ui.Alignment$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.ui.Alignment$Horizontal { *; }
-keep,includedescriptorclasses class androidx.compose.ui.ComposedModifierKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.Modifier { *; }
-keep,includedescriptorclasses class androidx.compose.ui.Modifier$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.ui.geometry.Offset { *; }
-keep,includedescriptorclasses class androidx.compose.ui.geometry.Offset$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.ui.geometry.OffsetKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.geometry.Rect { *; }
-keep,includedescriptorclasses class androidx.compose.ui.geometry.RectKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.geometry.Size { *; }
-keep,includedescriptorclasses class androidx.compose.ui.graphics.AndroidImageBitmap_androidKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.graphics.Color { *; }
-keep,includedescriptorclasses class androidx.compose.ui.graphics.Color$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.ui.graphics.ColorFilter { *; }
-keep,includedescriptorclasses class androidx.compose.ui.graphics.ColorKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.graphics.ImageBitmap { *; }
-keep,includedescriptorclasses class androidx.compose.ui.graphics.PathEffect { *; }
-keep,includedescriptorclasses class androidx.compose.ui.graphics.Shape { *; }
-keep,includedescriptorclasses class androidx.compose.ui.graphics.drawscope.DrawScope { *; }
-keep,includedescriptorclasses class androidx.compose.ui.graphics.drawscope.DrawStyle { *; }
-keep,includedescriptorclasses class androidx.compose.ui.graphics.layer.GraphicsLayer { *; }
-keep,includedescriptorclasses class androidx.compose.ui.input.indirect.AndroidIndirectPointerEvent_androidKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.input.indirect.IndirectPointerEvent { *; }
-keep,includedescriptorclasses class androidx.compose.ui.input.indirect.IndirectPointerEventType { *; }
-keep,includedescriptorclasses class androidx.compose.ui.input.indirect.IndirectPointerInputChange { *; }
-keep,includedescriptorclasses class androidx.compose.ui.input.key.Key { *; }
-keep,includedescriptorclasses class androidx.compose.ui.input.key.Key$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.ui.input.key.KeyEvent_androidKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.input.pointer.PointerId { *; }
-keep,includedescriptorclasses class androidx.compose.ui.input.pointer.util.VelocityTracker { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.AlignmentLine { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.LayoutCoordinates { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.LayoutCoordinatesKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.LayoutInfo { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.Measurable { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.MeasurePolicy { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.MeasureResult { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.MeasureScope { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.Placeable { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.Placeable$PlacementScope { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.SubcomposeLayoutKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.SubcomposeMeasureScope { *; }
-keep,includedescriptorclasses class androidx.compose.ui.node.ComposeUiNode { *; }
-keep,includedescriptorclasses class androidx.compose.ui.node.ComposeUiNode$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.ui.node.RootForTest { *; }
-keep,includedescriptorclasses class androidx.compose.ui.node.RootForTest$UncaughtExceptionHandler { *; }
-keep,includedescriptorclasses class androidx.compose.ui.platform.AbstractComposeView { *; }
-keep,includedescriptorclasses class androidx.compose.ui.platform.AndroidCompositionLocals_androidKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.platform.CompositionLocalsKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.platform.InfiniteAnimationPolicy { *; }
-keep,includedescriptorclasses class androidx.compose.ui.platform.PlatformTextInputModifierNodeKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.platform.ViewConfiguration { *; }
-keep,includedescriptorclasses class androidx.compose.ui.platform.ViewRootForTest { *; }
-keep,includedescriptorclasses class androidx.compose.ui.platform.ViewRootForTest$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.ui.platform.WindowInfo { *; }
-keep,includedescriptorclasses class androidx.compose.ui.platform.WindowRecomposerFactory { *; }
-keep,includedescriptorclasses class androidx.compose.ui.platform.WindowRecomposerPolicy { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.AccessibilityAction { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.CustomAccessibilityAction { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.ProgressBarRangeInfo { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.ProgressBarRangeInfo$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.Role { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.ScrollAxisRange { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.SemanticsActions { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.SemanticsConfiguration { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.SemanticsNode { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.SemanticsOwner { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.SemanticsOwnerKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.SemanticsProperties { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.SemanticsPropertyKey { *; }
-keep,includedescriptorclasses class androidx.compose.ui.state.ToggleableState { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.AnnotatedString { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.AnnotatedString$Range { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.TextLayoutInput { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.TextLayoutResult { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.TextRange { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.TextStyle { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.font.FontFamily { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.font.FontFamily$Resolver { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.font.FontFamilyResolver_androidKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.font.FontStyle { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.font.FontWeight { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.input.ImeAction { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.input.ImeAction$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.intl.Locale { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.intl.LocaleList { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.style.TextAlign { *; }
-keep,includedescriptorclasses class androidx.compose.ui.text.style.TextDecoration { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.Constraints { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.Constraints$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.ConstraintsKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.Density { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.DensityKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.Dp { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.Dp$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.DpSize { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.IntSize { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.IntSizeKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.LayoutDirection { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.Velocity { *; }
-keep,includedescriptorclasses class androidx.compose.ui.util.ListUtilsKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.util.MathHelpersKt { *; }
-keep,includedescriptorclasses class androidx.concurrent.futures.CallbackToFutureAdapter { *; }
-keep,includedescriptorclasses class androidx.concurrent.futures.CallbackToFutureAdapter$Completer { *; }
-keep,includedescriptorclasses class androidx.concurrent.futures.CallbackToFutureAdapter$Resolver { *; }
-keep,includedescriptorclasses class androidx.concurrent.futures.ResolvableFuture { *; }
-keep,includedescriptorclasses class androidx.core.app.ActivityOptionsCompat { *; }
-keep,includedescriptorclasses class androidx.core.content.ContextCompat { *; }
-keep,includedescriptorclasses class androidx.core.os.ConfigurationCompat { *; }
-keep,includedescriptorclasses class androidx.core.os.LocaleListCompat { *; }
-keep,includedescriptorclasses class androidx.core.view.ViewConfigurationCompat { *; }
-keep,includedescriptorclasses class androidx.core.view.ViewGroupKt { *; }
-keep,includedescriptorclasses class androidx.core.view.WindowCompat { *; }
-keep,includedescriptorclasses class androidx.core.view.WindowInsetsCompat { *; }
-keep,includedescriptorclasses class androidx.core.view.WindowInsetsControllerCompat { *; }
-keep,includedescriptorclasses class androidx.lifecycle.Lifecycle { *; }
-keep,includedescriptorclasses class androidx.lifecycle.Lifecycle$Event { *; }
-keep,includedescriptorclasses class androidx.lifecycle.Lifecycle$State { *; }
-keep,includedescriptorclasses class androidx.lifecycle.LifecycleEventObserver { *; }
-keep,includedescriptorclasses class androidx.lifecycle.LifecycleObserver { *; }
-keep,includedescriptorclasses class androidx.lifecycle.LifecycleOwner { *; }
-keep,includedescriptorclasses class androidx.lifecycle.LifecycleRegistry { *; }
-keep,includedescriptorclasses class androidx.lifecycle.ViewModel { *; }
-keep,includedescriptorclasses class androidx.lifecycle.ViewModelProvider { *; }
-keep,includedescriptorclasses class androidx.lifecycle.ViewModelStoreOwner { *; }
-keep,includedescriptorclasses class androidx.lifecycle.compose.LocalLifecycleOwnerKt { *; }
-keep,includedescriptorclasses class com.google.common.util.concurrent.ListenableFuture { *; }
-keep,includedescriptorclasses class com.google.zxing.common.BitMatrix { *; }
-keep,includedescriptorclasses class com.kyant.backdrop.Backdrop { *; }
-keep,includedescriptorclasses class com.kyant.backdrop.BackdropEffectScope { *; }
-keep,includedescriptorclasses class com.kyant.backdrop.DrawBackdropModifierKt { *; }
-keep,includedescriptorclasses class com.kyant.backdrop.RuntimeShader { *; }
-keep,includedescriptorclasses class com.kyant.backdrop.backdrops.CombinedBackdropKt { *; }
-keep,includedescriptorclasses class com.kyant.backdrop.backdrops.LayerBackdrop { *; }
-keep,includedescriptorclasses class com.kyant.backdrop.effects.RenderEffectKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.AppFilterMode { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ColorMode { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ColorStyle { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ConnectionInfo { *; }
-keep,includedescriptorclasses class dev.aerove.lite.DashboardWidget { *; }
-keep,includedescriptorclasses class dev.aerove.lite.DiagnosticsState { *; }
-keep,includedescriptorclasses class dev.aerove.lite.InstalledApp { *; }
-keep,includedescriptorclasses class dev.aerove.lite.LiteActions { *; }
-keep,includedescriptorclasses class dev.aerove.lite.LiteApplication { *; }
-keep,includedescriptorclasses class dev.aerove.lite.LiteController { *; }
-keep,includedescriptorclasses class dev.aerove.lite.LiteFeatureHost { *; }
-keep,includedescriptorclasses class dev.aerove.lite.LiteSettings { *; }
-keep,includedescriptorclasses class dev.aerove.lite.LiteState { *; }
-keep,includedescriptorclasses class dev.aerove.lite.MainActivity { *; }
-keep,includedescriptorclasses class dev.aerove.lite.MaterialTier { *; }
-keep,includedescriptorclasses class dev.aerove.lite.OutboundIdentity { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ProfileSummary { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ProxyGroup { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ProxyMode { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ProxyNode { *; }
-keep,includedescriptorclasses class dev.aerove.lite.R$string { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ServiceCheckView { *; }
-keep,includedescriptorclasses class dev.aerove.lite.Traffic { *; }
-keep,includedescriptorclasses class dev.aerove.lite.VpnPhase { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupActions { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupConfirmation { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupConfirmationKind { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupConnection { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupDocumentRequest { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupFeature { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupNotice { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupNoticeCode { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupOptions { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupProgress { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupRemoteFile { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupUiState { *; }
-keep,includedescriptorclasses class dev.aerove.lite.backup.BackupWebDavUi { *; }
-keep,includedescriptorclasses class dev.aerove.lite.connections.ConnectionDecoderKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.core.NativeCore { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.BackupRestoreScope { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.BackupRestoreStrategy { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.ConfigValidationException { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.ConfigYaml { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.ControlEndpoint { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.CoreApiClient { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.LiteSettingsStore { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.MemorySnapshot { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.ProfileEditorSession { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.ProfileMaintenanceFacade { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.ProfileRepository { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.ProfileSnapshot { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.ProfileValidator { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.ResourceBundleManager { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.RouteSnapshot { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.ServiceTarget { *; }
-keep,includedescriptorclasses class dev.aerove.lite.data.SubscriptionDownloader { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorAssets { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorAssets$Companion { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorCompletion { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorDocumentController { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorDocumentState { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorFontSize { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorInput { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorLanguage { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorPalette { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorPreferences { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorQuery { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.EditorSuggestion { *; }
-keep,includedescriptorclasses class dev.aerove.lite.editor.NativeEditorView { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ipquality.IpQuality { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ipquality.IpQualityFailure { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ipquality.IpQualityFeature { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ipquality.IpQualityLookupException { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ipquality.IpQualitySource { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ipquality.IpQualitySourceStatus { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ipquality.IpQualityStore { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ipquality.IpType { *; }
-keep,includedescriptorclasses class dev.aerove.lite.localization.LocaleChoice { *; }
-keep,includedescriptorclasses class dev.aerove.lite.networksettings.EffectiveVpnNetworkOptions { *; }
-keep,includedescriptorclasses class dev.aerove.lite.networksettings.NetworkRuntimePlanKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.networksettings.NetworkSettings { *; }
-keep,includedescriptorclasses class dev.aerove.lite.networksettings.ProxyAuthentication { *; }
-keep,includedescriptorclasses class dev.aerove.lite.networksettings.UserProxyPorts { *; }
-keep,includedescriptorclasses class dev.aerove.lite.networksettings.VpnProxyPlan { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveGlassCapture { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveGlassGlow { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveGlassShine { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveGlassStyle { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveGlassStyle$Companion { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveOpticsPrograms { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveOpticsPrograms$Companion { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveOpticsProgramsKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveSuperellipseGeometry { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.AeroveSuperellipseShape { *; }
-keep,includedescriptorclasses class dev.aerove.lite.optics.OpticsColor { *; }
-keep,includedescriptorclasses class dev.aerove.lite.presentation.SystemFontCatalog { *; }
-keep,includedescriptorclasses class dev.aerove.lite.profiles.ProfileBatchResult { *; }
-keep,includedescriptorclasses class dev.aerove.lite.profiles.ProfileDocuments { *; }
-keep,includedescriptorclasses class dev.aerove.lite.profiles.ProfileListFeature { *; }
-keep,includedescriptorclasses class dev.aerove.lite.profiles.ProfileListNotice { *; }
-keep,includedescriptorclasses class dev.aerove.lite.profiles.ProfileListState { *; }
-keep,includedescriptorclasses class dev.aerove.lite.scan.AlbumQrDecoder { *; }
-keep,includedescriptorclasses class dev.aerove.lite.scan.ScanFailure { *; }
-keep,includedescriptorclasses class dev.aerove.lite.scan.ScanPreview { *; }
-keep,includedescriptorclasses class dev.aerove.lite.scan.ScanSession { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.AuroraMotionActivity { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.AuroraMotionKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.AuroraWallpaperKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.BackupScreenKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.DashboardEditorKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.DiagnosticsPanelsKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.FullAuroraKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.FullAuroraMesh { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.FullAuroraMesh$Companion { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.FullAuroraMotion { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.FullAuroraPose { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.FullGlyphs { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.FullPaletteKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteAppKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteComponentsKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteCorner { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteFeatureHostKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteGlassRim { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteGlyph { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteMaterialKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteMetrics { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteOpticsKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteOverlayKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteOverlayState { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LitePageChromeKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteType { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteVisuals { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.OverviewScreenKt { *; }
-keep,includedescriptorclasses class dev.aerove.lite.ui.colorutilities.Hct { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.LiteRelease { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.UpdateApkVerifier { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.UpdateFeature { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.UpdateIdentity { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.UpdatePhase { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.UpdatePlatform { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.UpdateRepository { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.UpdateState { *; }
-keep,includedescriptorclasses class dev.aerove.lite.updates.UpdateTransport { *; }
-keep,includedescriptorclasses class dev.aerove.lite.vpn.CoreEndpoint { *; }
-keep,includedescriptorclasses class dev.aerove.lite.vpn.LiteVpnService { *; }
-keep,includedescriptorclasses class dev.aerove.lite.vpn.VpnSnapshot { *; }
-keep,includedescriptorclasses class io.github.rosemoe.sora.lang.analysis.AnalyzeManager { *; }
-keep,includedescriptorclasses class io.github.rosemoe.sora.langs.textmate.TextMateLanguage { *; }
-keep,includedescriptorclasses class io.github.rosemoe.sora.text.Content { *; }
-keep,includedescriptorclasses class io.github.rosemoe.sora.text.Cursor { *; }
-keep,includedescriptorclasses class kotlin.Deprecated { *; }
-keep,includedescriptorclasses class kotlin.DeprecationLevel { *; }
-keep,includedescriptorclasses class kotlin.ExceptionsKt { *; }
-keep,includedescriptorclasses class kotlin.Function { *; }
-keep,includedescriptorclasses class kotlin.KotlinNothingValueException { *; }
-keep,includedescriptorclasses class kotlin.Lazy { *; }
-keep,includedescriptorclasses class kotlin.LazyKt { *; }
-keep,includedescriptorclasses class kotlin.LazyKt__LazyJVMKt { *; }
-keep,includedescriptorclasses class kotlin.NoWhenBranchMatchedException { *; }
-keep,includedescriptorclasses class kotlin.NotImplementedError { *; }
-keep,includedescriptorclasses class kotlin.Pair { *; }
-keep,includedescriptorclasses class kotlin.ReplaceWith { *; }
-keep,includedescriptorclasses class kotlin.Result { *; }
-keep,includedescriptorclasses class kotlin.Result$Companion { *; }
-keep,includedescriptorclasses class kotlin.ResultKt { *; }
-keep,includedescriptorclasses class kotlin.Triple { *; }
-keep,includedescriptorclasses class kotlin.TuplesKt { *; }
-keep,includedescriptorclasses class kotlin.ULong { *; }
-keep,includedescriptorclasses class kotlin.Unit { *; }
-keep,includedescriptorclasses class kotlin.collections.ArrayDeque { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt { *; }
-keep,includedescriptorclasses class kotlin.collections.IntIterator { *; }
-keep,includedescriptorclasses class kotlin.collections.MapsKt { *; }
-keep,includedescriptorclasses class kotlin.collections.SetsKt { *; }
-keep,includedescriptorclasses class kotlin.comparisons.ComparisonsKt { *; }
-keep,includedescriptorclasses class kotlin.coroutines.AbstractCoroutineContextElement { *; }
-keep,includedescriptorclasses class kotlin.coroutines.Continuation { *; }
-keep,includedescriptorclasses class kotlin.coroutines.ContinuationInterceptor { *; }
-keep,includedescriptorclasses class kotlin.coroutines.ContinuationInterceptor$DefaultImpls { *; }
-keep,includedescriptorclasses class kotlin.coroutines.ContinuationKt { *; }
-keep,includedescriptorclasses class kotlin.coroutines.CoroutineContext { *; }
-keep,includedescriptorclasses class kotlin.coroutines.CoroutineContext$Element { *; }
-keep,includedescriptorclasses class kotlin.coroutines.CoroutineContext$Element$DefaultImpls { *; }
-keep,includedescriptorclasses class kotlin.coroutines.CoroutineContext$Key { *; }
-keep,includedescriptorclasses class kotlin.coroutines.EmptyCoroutineContext { *; }
-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt { *; }
-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.Boxing { *; }
-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.ContinuationImpl { *; }
-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.DebugMetadata { *; }
-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.DebugProbesKt { *; }
-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.SuspendLambda { *; }
-keep,includedescriptorclasses class kotlin.enums.EnumEntries { *; }
-keep,includedescriptorclasses class kotlin.enums.EnumEntriesKt { *; }
-keep,includedescriptorclasses class kotlin.io.CloseableKt { *; }
-keep,includedescriptorclasses class kotlin.io.FilesKt { *; }
-keep,includedescriptorclasses class kotlin.io.TextStreamsKt { *; }
-keep,includedescriptorclasses class kotlin.jvm.functions.Function0 { *; }
-keep,includedescriptorclasses class kotlin.jvm.functions.Function1 { *; }
-keep,includedescriptorclasses class kotlin.jvm.functions.Function2 { *; }
-keep,includedescriptorclasses class kotlin.jvm.functions.Function3 { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.DefaultConstructorMarker { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.FunctionReferenceImpl { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.InlineMarker { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.Intrinsics { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.Intrinsics$Kotlin { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.Lambda { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.Ref$BooleanRef { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.Ref$FloatRef { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.Ref$IntRef { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.Ref$ObjectRef { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.markers.KMappedMarker { *; }
-keep,includedescriptorclasses class kotlin.math.MathKt { *; }
-keep,includedescriptorclasses class kotlin.random.Random { *; }
-keep,includedescriptorclasses class kotlin.random.Random$Default { *; }
-keep,includedescriptorclasses class kotlin.ranges.IntRange { *; }
-keep,includedescriptorclasses class kotlin.ranges.RangesKt { *; }
-keep,includedescriptorclasses class kotlin.reflect.KFunction { *; }
-keep,includedescriptorclasses class kotlin.sequences.Sequence { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt { *; }
-keep,includedescriptorclasses class kotlin.text.CharsKt { *; }
-keep,includedescriptorclasses class kotlin.text.Charsets { *; }
-keep,includedescriptorclasses class kotlin.text.Regex { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt { *; }
-keep,includedescriptorclasses class kotlin.text.UStringsKt { *; }
-keep,includedescriptorclasses class kotlin.time.Duration { *; }
-keep,includedescriptorclasses class kotlin.time.Duration$Companion { *; }
-keep,includedescriptorclasses class kotlin.time.DurationKt { *; }
-keep,includedescriptorclasses class kotlin.time.DurationUnit { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.AbstractCoroutine { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.BuildersKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CancellableContinuation { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CancellableContinuationImpl { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CancellableContinuationKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CompletableJob { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CoroutineDispatcher { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CoroutineDispatcher$Key { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CoroutineExceptionHandler$DefaultImpls { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CoroutineExceptionHandlerKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CoroutineName { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CoroutineScope { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CoroutineScopeKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CoroutineStart { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.DefaultExecutorKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.Deferred { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.Delay { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.Delay$DefaultImpls { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.DelayKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.Dispatchers { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.DisposableHandle { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.Job { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.Job$DefaultImpls { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.JobImpl { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.JobKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.JobSupport { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.MainCoroutineDispatcher { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.SupervisorKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.TimeoutCancellationException { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.TimeoutKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.YieldContext { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.YieldContext$Key { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.YieldKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.channels.BufferOverflow { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.channels.Channel { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.channels.ChannelKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.flow.Flow { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.flow.FlowCollector { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.flow.FlowKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.flow.StateFlow { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.internal.CoroutineExceptionHandlerImplKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.internal.ExceptionSuccessfullyProcessed { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.internal.MainDispatchersKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.internal.StackTraceRecoveryKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.internal.ThreadSafeHeap { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.selects.SelectImplementation { *; }
-keep,includedescriptorclasses class okhttp3.Call { *; }
-keep,includedescriptorclasses class okhttp3.ConnectionPool { *; }
-keep,includedescriptorclasses class okhttp3.Dispatcher { *; }
-keep,includedescriptorclasses class okhttp3.Interceptor { *; }
-keep,includedescriptorclasses class okhttp3.Interceptor$Chain { *; }
-keep,includedescriptorclasses class okhttp3.MediaType { *; }
-keep,includedescriptorclasses class okhttp3.OkHttpClient { *; }
-keep,includedescriptorclasses class okhttp3.OkHttpClient$Builder { *; }
-keep,includedescriptorclasses class okhttp3.Request { *; }
-keep,includedescriptorclasses class okhttp3.Request$Builder { *; }
-keep,includedescriptorclasses class okhttp3.RequestBody { *; }
-keep,includedescriptorclasses class okhttp3.RequestBody$Companion { *; }
-keep,includedescriptorclasses class okhttp3.Response { *; }
-keep,includedescriptorclasses class okhttp3.ResponseBody { *; }
-keep,includedescriptorclasses class androidx.activity.FullyDrawnReporterOwner { *; }
-keep,includedescriptorclasses class androidx.activity.OnBackPressedDispatcherOwner { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.gestures.ScrollableState { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.ColumnScope { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.IntrinsicMeasureScope { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.Measured { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.SemanticsPropertyReceiver { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.FontScaling { *; }
-keep,includedescriptorclasses class androidx.core.app.ComponentActivity { *; }
-keep,includedescriptorclasses class androidx.lifecycle.HasDefaultViewModelProviderFactory { *; }
-keep,includedescriptorclasses class androidx.navigationevent.NavigationEventDispatcherOwner { *; }
-keep,includedescriptorclasses class androidx.savedstate.SavedStateRegistryOwner { *; }
-keep,includedescriptorclasses class com.kyant.backdrop.RuntimeShaderCache { *; }
-keep,includedescriptorclasses class io.github.rosemoe.sora.lang.EmptyLanguage { *; }
-keep,includedescriptorclasses class io.github.rosemoe.sora.lang.Language { *; }
-keep,includedescriptorclasses class io.github.rosemoe.sora.lang.format.Formatter$FormatResultReceiver { *; }
-keep,includedescriptorclasses class io.github.rosemoe.sora.text.ContentListener { *; }
-keep,includedescriptorclasses class io.github.rosemoe.sora.widget.CodeEditor { *; }
-keep,includedescriptorclasses class kotlin.ExceptionsKt__ExceptionsKt { *; }
-keep,includedescriptorclasses class kotlin.LazyKt__LazyKt { *; }
-keep,includedescriptorclasses class kotlin.collections.AbstractMutableList { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt__ArraysJVMKt { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt__ArraysKt { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysJvmKt { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysKt { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt__CollectionsJVMKt { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt__CollectionsKt { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt__IterablesKt { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt__IteratorsJVMKt { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt__IteratorsKt { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt__MutableCollectionsJVMKt { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt__MutableCollectionsKt { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt__ReversedViewsKt { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt___CollectionsJvmKt { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt___CollectionsKt { *; }
-keep,includedescriptorclasses class kotlin.collections.MapsKt__MapWithDefaultKt { *; }
-keep,includedescriptorclasses class kotlin.collections.MapsKt__MapsJVMKt { *; }
-keep,includedescriptorclasses class kotlin.collections.MapsKt__MapsKt { *; }
-keep,includedescriptorclasses class kotlin.collections.MapsKt___MapsJvmKt { *; }
-keep,includedescriptorclasses class kotlin.collections.MapsKt___MapsKt { *; }
-keep,includedescriptorclasses class kotlin.collections.SetsKt__SetsJVMKt { *; }
-keep,includedescriptorclasses class kotlin.collections.SetsKt__SetsKt { *; }
-keep,includedescriptorclasses class kotlin.collections.SetsKt___SetsKt { *; }
-keep,includedescriptorclasses class kotlin.comparisons.ComparisonsKt__ComparisonsKt { *; }
-keep,includedescriptorclasses class kotlin.comparisons.ComparisonsKt___ComparisonsJvmKt { *; }
-keep,includedescriptorclasses class kotlin.comparisons.ComparisonsKt___ComparisonsKt { *; }
-keep,includedescriptorclasses class kotlin.coroutines.AbstractCoroutineContextKey { *; }
-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt { *; }
-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsKt { *; }
-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.BaseContinuationImpl { *; }
-keep,includedescriptorclasses class kotlin.coroutines.jvm.internal.CoroutineStackFrame { *; }
-keep,includedescriptorclasses class kotlin.io.FilesKt__FilePathComponentsKt { *; }
-keep,includedescriptorclasses class kotlin.io.FilesKt__FileReadWriteKt { *; }
-keep,includedescriptorclasses class kotlin.io.FilesKt__FileTreeWalkKt { *; }
-keep,includedescriptorclasses class kotlin.io.FilesKt__UtilsKt { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.CallableReference { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.FunctionBase { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.FunctionReference { *; }
-keep,includedescriptorclasses class kotlin.math.MathKt__MathHKt { *; }
-keep,includedescriptorclasses class kotlin.math.MathKt__MathJVMKt { *; }
-keep,includedescriptorclasses class kotlin.ranges.ClosedRange { *; }
-keep,includedescriptorclasses class kotlin.ranges.IntProgression { *; }
-keep,includedescriptorclasses class kotlin.ranges.RangesKt__RangesKt { *; }
-keep,includedescriptorclasses class kotlin.ranges.RangesKt___RangesKt { *; }
-keep,includedescriptorclasses class kotlin.reflect.KCallable { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt__SequenceBuilderKt { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt__SequencesJVMKt { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt__SequencesKt { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesJvmKt { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt { *; }
-keep,includedescriptorclasses class kotlin.text.CharsKt__CharJVMKt { *; }
-keep,includedescriptorclasses class kotlin.text.CharsKt__CharKt { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt__AppendableKt { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt__IndentKt { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt__RegexExtensionsJVMKt { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt__RegexExtensionsKt { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt__StringBuilderJVMKt { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt__StringBuilderKt { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt__StringNumberConversionsJVMKt { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt__StringNumberConversionsKt { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt__StringsJVMKt { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt__StringsKt { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt___StringsJvmKt { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt___StringsKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CancelHandler { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.DispatchedTask { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.NotCompleted { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.Waiter { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.channels.ReceiveChannel { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.channels.SendChannel { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.flow.SharedFlow { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.scheduling.Task { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysKt$asIterable$$inlined$Iterable$1 { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt___CollectionsKt$asSequence$$inlined$Sequence$1 { *; }
-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt$createCoroutineUnintercepted$$inlined$createCoroutineFromSuspendFunction$IntrinsicsKt__IntrinsicsJvmKt$3 { *; }
-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt$createCoroutineUnintercepted$$inlined$createCoroutineFromSuspendFunction$IntrinsicsKt__IntrinsicsJvmKt$4 { *; }
-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt$createSimpleCoroutineForSuspendFunction$1 { *; }
-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt$createSimpleCoroutineForSuspendFunction$2 { *; }
-keep,includedescriptorclasses class androidx.collection.IntObjectMap { *; }
-keep,includedescriptorclasses class androidx.concurrent.futures.AbstractResolvableFuture { *; }
-keep,includedescriptorclasses interface androidx.compose.foundation.interaction.InteractionSource { *; }
-keep,includedescriptorclasses class androidx.compose.foundation.layout.BoxWithConstraintsKt { *; }
-keep,includedescriptorclasses interface androidx.compose.runtime.ScopeUpdateScope { *; }
-keep,includedescriptorclasses interface androidx.compose.runtime.internal.ComposableLambda { *; }
-keep,includedescriptorclasses class androidx.compose.ui.input.indirect.IndirectPointerEventType$Companion { *; }
-keep,includedescriptorclasses interface androidx.compose.ui.platform.PlatformTextInputInterceptor { *; }
-keep,includedescriptorclasses interface androidx.compose.ui.platform.PlatformTextInputMethodRequest { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.Role$Companion { *; }
-keep,includedescriptorclasses class androidx.compose.ui.semantics.SemanticsConfigurationKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.DpRect { *; }
-keep,includedescriptorclasses class androidx.compose.ui.viewinterop.AndroidView_androidKt { *; }
-keep,includedescriptorclasses interface androidx.core.view.KeyEventDispatcher$Component { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysJvmKt$asList$1 { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysJvmKt$asList$2 { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysJvmKt$asList$3 { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysJvmKt$asList$4 { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysJvmKt$asList$5 { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysJvmKt$asList$6 { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysJvmKt$asList$7 { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysJvmKt$asList$8 { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysKt$asIterable$$inlined$Iterable$2 { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysKt$asSequence$$inlined$Sequence$1 { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysKt$groupingBy$1 { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt__CollectionsKt$binarySearchBy$1 { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt__IterablesKt$Iterable$1 { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt__IteratorsJVMKt$iterator$1 { *; }
-keep,includedescriptorclasses class kotlin.collections.CollectionsKt___CollectionsKt$groupingBy$1 { *; }
-keep,includedescriptorclasses class kotlin.comparisons.ComparisonsKt__ComparisonsKt$compareBy$2 { *; }
-keep,includedescriptorclasses class kotlin.comparisons.ComparisonsKt__ComparisonsKt$compareBy$3 { *; }
-keep,includedescriptorclasses class kotlin.comparisons.ComparisonsKt__ComparisonsKt$thenBy$1 { *; }
-keep,includedescriptorclasses class kotlin.comparisons.ComparisonsKt__ComparisonsKt$thenBy$2 { *; }
-keep,includedescriptorclasses class kotlin.coroutines.ContinuationInterceptor$Key { *; }
-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt$createCoroutineFromSuspendFunction$1 { *; }
-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt$createCoroutineFromSuspendFunction$2 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function10 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function11 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function13 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function14 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function15 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function16 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function17 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function18 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function19 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function20 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function21 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function4 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function5 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function6 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function7 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function8 { *; }
-keep,includedescriptorclasses interface kotlin.jvm.functions.Function9 { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.StringCompanionObject { *; }
-keep,includedescriptorclasses class kotlin.ranges.LongProgression { *; }
-keep,includedescriptorclasses class kotlin.ranges.LongRange { *; }
-keep,includedescriptorclasses interface kotlin.ranges.OpenEndRange { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt__SequenceBuilderKt$sequence$$inlined$Sequence$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt__SequencesKt$flatMapIndexed$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt__SequencesKt$ifEmpty$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt__SequencesKt$sequenceOf$1$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt__SequencesKt$shuffled$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$filterIsInstance$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$flatMap$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$flatMap$2 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$flatMapIndexed$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$flatMapIndexed$2 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$groupingBy$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$minus$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$runningFold$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$runningFoldIndexed$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$runningReduce$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$runningReduceIndexed$1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$zipWithNext$2 { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt___StringsKt$asIterable$$inlined$Iterable$1 { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt___StringsKt$asSequence$$inlined$Sequence$1 { *; }
-keep,includedescriptorclasses class kotlin.text.StringsKt___StringsKt$groupingBy$1 { *; }
-keep,includedescriptorclasses interface kotlinx.coroutines.ChildJob { *; }
-keep,includedescriptorclasses interface kotlinx.coroutines.CopyableThrowable { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.CoroutineExceptionHandler$Key { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.Job$Key { *; }
-keep,includedescriptorclasses interface kotlinx.coroutines.ParentJob { *; }
-keep,includedescriptorclasses interface kotlinx.coroutines.internal.ThreadSafeHeapNode { *; }
-keep,includedescriptorclasses interface kotlinx.coroutines.selects.SelectBuilder { *; }
-keep,includedescriptorclasses interface kotlinx.coroutines.selects.SelectClause { *; }
-keep,includedescriptorclasses interface kotlinx.coroutines.selects.SelectClause0 { *; }
-keep,includedescriptorclasses interface kotlinx.coroutines.selects.SelectClause1 { *; }

# Runtime META-INF/services entries are a reflection ABI shared across the app and test APK classloaders.
# Preserve service names and method descriptors across the independently minified app and androidTest APKs.
-keep interface kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembers,includedescriptorclasses interface kotlinx.coroutines.CoroutineExceptionHandler { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.android.AndroidExceptionPreHandler {
    public <init>();
    public void handleException(kotlin.coroutines.CoroutineContext,java.lang.Throwable);
}
-keep interface kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepclassmembers,includedescriptorclasses interface kotlinx.coroutines.internal.MainDispatcherFactory {
    public int getLoadPriority();
    public java.lang.String hintOnError();
}
-keepclassmembers,includedescriptorclasses interface kotlinx.coroutines.internal.MainDispatcherFactory {
    public kotlinx.coroutines.MainCoroutineDispatcher createDispatcher(java.util.List);
}
-keep,includedescriptorclasses class kotlinx.coroutines.android.AndroidDispatcherFactory {
    public <init>();
    public kotlinx.coroutines.MainCoroutineDispatcher createDispatcher(java.util.List);
}
-keep class kotlin.coroutines.jvm.internal.SpillingKt { *; }
-keep class org.yaml.snakeyaml.TypeDescription { *; }
-keep class com.google.zxing.qrcode.QRCodeWriter { *; }
# R8 usage.txt also lists removed empty classes without a trailing colon. These exact test-DEX closure rules cover those otherwise-unseen runtime type references; kinds come from resolved classfile access flags.
-keep,includedescriptorclasses interface androidx.activity.contextaware.ContextAware { *; }
-keep,includedescriptorclasses interface androidx.activity.result.ActivityResultCaller { *; }
-keep,includedescriptorclasses interface androidx.compose.ui.layout.IntrinsicMeasurable { *; }
-keep,includedescriptorclasses class androidx.compose.ui.platform.InfiniteAnimationPolicy$DefaultImpls { *; }
-keep,includedescriptorclasses interface androidx.compose.ui.platform.PlatformTextInputSession { *; }
-keep,includedescriptorclasses interface androidx.compose.ui.window.DialogWindowProvider { *; }
-keep,includedescriptorclasses interface androidx.core.app.OnMultiWindowModeChangedProvider { *; }
-keep,includedescriptorclasses interface androidx.core.app.OnNewIntentProvider { *; }
-keep,includedescriptorclasses interface androidx.core.app.OnPictureInPictureModeChangedProvider { *; }
-keep,includedescriptorclasses interface androidx.core.app.OnPictureInPictureUiStateChangedProvider { *; }
-keep,includedescriptorclasses interface androidx.core.app.OnUserLeaveHintProvider { *; }
-keep,includedescriptorclasses interface androidx.core.app.PictureInPictureProvider { *; }
-keep,includedescriptorclasses interface androidx.core.content.OnConfigurationChangedProvider { *; }
-keep,includedescriptorclasses interface androidx.core.content.OnTrimMemoryProvider { *; }
-keep,includedescriptorclasses interface androidx.core.view.MenuHost { *; }
-keep,includedescriptorclasses enum com.google.zxing.BarcodeFormat { *; }
-keep,includedescriptorclasses interface com.google.zxing.Writer { *; }
-keep,includedescriptorclasses class kotlin.LazyKt__LazyJVMKt$WhenMappings { *; }
-keep,includedescriptorclasses interface kotlin.Metadata { *; }
-keep,includedescriptorclasses class kotlin.UInt { *; }
-keep,includedescriptorclasses enum kotlin.annotation.AnnotationRetention { *; }
-keep,includedescriptorclasses interface kotlin.annotation.Retention { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysKt$asIterable$$inlined$Iterable$3 { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysKt$asSequence$$inlined$Sequence$2 { *; }
-keep,includedescriptorclasses class kotlin.comparisons.ComparisonsKt__ComparisonsKt$compareByDescending$1 { *; }
-keep,includedescriptorclasses class kotlin.comparisons.ComparisonsKt__ComparisonsKt$thenByDescending$1 { *; }
-keep,includedescriptorclasses class kotlin.comparisons.ComparisonsKt__ComparisonsKt$thenByDescending$2 { *; }
-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt$createCoroutineUnintercepted$$inlined$createCoroutineFromSuspendFunction$IntrinsicsKt__IntrinsicsJvmKt$1 { *; }
-keep,includedescriptorclasses class kotlin.coroutines.intrinsics.IntrinsicsKt__IntrinsicsJvmKt$createCoroutineUnintercepted$$inlined$createCoroutineFromSuspendFunction$IntrinsicsKt__IntrinsicsJvmKt$2 { *; }
-keep,includedescriptorclasses interface kotlin.coroutines.jvm.internal.SuspendFunction { *; }
-keep,includedescriptorclasses interface kotlin.jvm.JvmInline { *; }
-keep,includedescriptorclasses interface kotlin.jvm.JvmStatic { *; }
-keep,includedescriptorclasses interface kotlin.jvm.internal.KotlinGenericDeclaration { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.PropertyReference { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.PropertyReference1 { *; }
-keep,includedescriptorclasses class kotlin.jvm.internal.PropertyReference1Impl { *; }
-keep,includedescriptorclasses interface kotlin.jvm.internal.markers.KMutableCollection { *; }
-keep,includedescriptorclasses interface kotlin.jvm.internal.markers.KMutableIterable { *; }
-keep,includedescriptorclasses interface kotlin.jvm.internal.markers.KMutableList { *; }
-keep,includedescriptorclasses interface kotlin.reflect.KAnnotatedElement { *; }
-keep,includedescriptorclasses interface kotlin.reflect.KProperty { *; }
-keep,includedescriptorclasses interface kotlin.reflect.KProperty1 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$minus$2 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$sorted$1 { *; }
-keep,includedescriptorclasses class kotlin.time.AbstractLongTimeSource { *; }
-keep,includedescriptorclasses interface kotlin.time.TimeSource { *; }
-keep,includedescriptorclasses interface kotlin.time.TimeSource$WithComparableMarks { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.DebugKt { *; }
-keep,includedescriptorclasses interface kotlinx.coroutines.DelayWithTimeoutDiagnostics { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.ExecutorsKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.debug.internal.DebugProbesImpl { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.internal.MainDispatcherFactory$DefaultImpls { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.internal.MissingMainCoroutineDispatcherFactory { *; }
-keep,includedescriptorclasses interface kotlinx.coroutines.selects.SelectInstance { *; }
-keep,includedescriptorclasses interface kotlinx.coroutines.selects.SelectInstanceInternal { *; }
-keep,includedescriptorclasses interface okhttp3.Call$Factory { *; }
-keep,includedescriptorclasses interface okhttp3.WebSocket$Factory { *; }
# Exact b6 test-DEX-referenced owners absent from the current keep set, classified by resolved classfile flags.
-keep,includedescriptorclasses class androidx.compose.foundation.layout.SpacerKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.draw.AlphaKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.draw.ClipKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.input.key.Key_androidKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.LayoutModifierKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.layout.OnGloballyPositionedModifierKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.platform.TestTagKt { *; }
-keep,includedescriptorclasses class androidx.compose.ui.unit.AndroidDensity_androidKt { *; }
-keep,includedescriptorclasses class androidx.core.graphics.PathParser { *; }
-keep,includedescriptorclasses class androidx.lifecycle.ViewTreeLifecycleOwner { *; }
-keep,includedescriptorclasses class com.kyant.backdrop.backdrops.LayerBackdropKt { *; }
-keep,includedescriptorclasses class com.kyant.backdrop.backdrops.LayerBackdropModifierKt { *; }
-keep,includedescriptorclasses class com.kyant.backdrop.effects.BlurKt { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysKt$asIterable$$inlined$Iterable$4 { *; }
-keep,includedescriptorclasses class kotlin.collections.ArraysKt___ArraysKt$asSequence$$inlined$Sequence$3 { *; }
-keep,includedescriptorclasses class kotlin.sequences.SequencesKt___SequencesKt$minus$3 { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.internal.CoroutineExceptionHandlerImpl_commonKt { *; }
-keep,includedescriptorclasses class kotlinx.coroutines.selects.OnTimeoutKt { *; }

-keep class dev.aerove.lite.overrides.NativeScriptEngine {
    native <methods>;
}

# Exact release/test ABI exercised by the Run77 image, filter and named-input tests.
-keep,includedescriptorclasses class dev.aerove.lite.images.GroupIconLoaderKt {
    public static java.lang.Object decodeGroupIcon(byte[],kotlin.coroutines.Continuation);
    public static java.lang.Object decodeRasterIcon(byte[],kotlin.coroutines.Continuation);
}
-keep,includedescriptorclasses class dev.aerove.lite.images.NetworkGroupIconLoader {
    public <init>(okhttp3.OkHttpClient,dev.aerove.lite.requests.RequestPolicySource,kotlinx.coroutines.CoroutineScope,java.io.File,kotlin.jvm.functions.Function2);
    public <init>(okhttp3.OkHttpClient,dev.aerove.lite.requests.RequestPolicySource,kotlinx.coroutines.CoroutineScope,java.io.File,kotlin.jvm.functions.Function2,int,kotlin.jvm.internal.DefaultConstructorMarker);
    public java.lang.Object load(java.lang.String,kotlin.coroutines.Continuation);
    public java.lang.Object closeAndJoin(kotlin.coroutines.Continuation);
}
-keep,includedescriptorclasses class dev.aerove.lite.images.GroupIconImage$Raster {
    public android.graphics.Bitmap getBitmap();
}
-keep,includedescriptorclasses class dev.aerove.lite.images.GroupIconImage$Encoded {
    public android.graphics.drawable.Drawable$ConstantState getState();
}
-keep,includedescriptorclasses class dev.aerove.lite.images.GroupIconImage$Animated {
    public byte[] getBytes();
}
-keep,includedescriptorclasses class dev.aerove.lite.requests.RequestPreferences {
    public <init>(java.lang.String,java.util.List,boolean,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep,includedescriptorclasses class dev.aerove.lite.regexp2.Regexp2Filters {
    public static final dev.aerove.lite.regexp2.Regexp2Filters INSTANCE;
    public void install(android.content.Context);
    public void cancelActive();
    public java.lang.Object indices(java.util.List,java.util.List,java.util.List,boolean,kotlin.coroutines.Continuation);
    public static java.lang.Object indices$default(dev.aerove.lite.regexp2.Regexp2Filters,java.util.List,java.util.List,java.util.List,boolean,kotlin.coroutines.Continuation,int,java.lang.Object);
}
-keep,includedescriptorclasses class dev.aerove.lite.profilesource.NamedProfileInput {
    public <init>(java.lang.String,java.lang.String,dev.aerove.lite.profilesource.AgeSecret);
    public <init>(java.lang.String,java.lang.String,dev.aerove.lite.profilesource.AgeSecret,int,kotlin.jvm.internal.DefaultConstructorMarker);
    public java.lang.String getLabel();
    public java.lang.String getUrl();
    public dev.aerove.lite.profilesource.AgeSecret getSecret();
}

# Exact remaining-test ABI verified against the Run77 test DEX and app mapping/usage.
-keep,includedescriptorclasses class dev.aerove.lite.backgroundpolicy.AndroidSsidPolicy {
    dev.aerove.lite.backgroundpolicy.AndroidSsidPolicy INSTANCE;
    dev.aerove.lite.backgroundpolicy.AndroidSsidPolicy$Access access(android.content.Context);
}
-keep,includedescriptorclasses class dev.aerove.lite.backgroundpolicy.BackgroundPolicyJson {
    dev.aerove.lite.backgroundpolicy.BackgroundPolicyJson INSTANCE;
}
-keep,includedescriptorclasses class dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences {
    <init>(boolean,boolean,boolean,boolean,boolean,java.util.List,boolean,int,kotlin.jvm.internal.DefaultConstructorMarker);
    dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences copy$default(dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences,boolean,boolean,boolean,boolean,boolean,java.util.List,boolean,int,java.lang.Object);
    dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences$Companion Companion;
    int $stable;
}
-keep,includedescriptorclasses class dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences$Companion {
    java.lang.String ssidFromPlatform(java.lang.String);
}
-keep,includedescriptorclasses class dev.aerove.lite.core.ConnectionOwnerResolver {
    dev.aerove.lite.core.ConnectionOwnerResolver$Companion Companion;
}
-keep,includedescriptorclasses class dev.aerove.lite.core.ConnectionOwnerResolver$Companion {
    dev.aerove.lite.core.ConnectionOwnerResolver forTun(android.content.Context,kotlin.jvm.functions.Function0);
    java.net.InetSocketAddress numericEndpoint$app(java.lang.String);
}
-keep,includedescriptorclasses class dev.aerove.lite.data.GlobalRulePayloadRepository {
    <init>(java.io.File,dev.aerove.lite.profilebuilder.BuilderResourceLibrary,dev.aerove.lite.data.ResourceBundleManager,dev.aerove.lite.data.NativeRulePayloadInspector,dev.aerove.lite.data.ProfileRepository,dev.aerove.lite.data.ResourceDownloader,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep,includedescriptorclasses interface dev.aerove.lite.data.NativeConfigValidator {
    java.lang.String validate(java.lang.String);
}
-keep,includedescriptorclasses class dev.aerove.lite.dnssettings.DnsPreferences {
    <init>(java.util.Map,java.util.Set,java.util.Map,java.util.List,int,kotlin.jvm.internal.DefaultConstructorMarker);
    dev.aerove.lite.dnssettings.DnsPreferences withValue(dev.aerove.lite.dnssettings.DnsField,dev.aerove.lite.dnssettings.DnsValue);
    dev.aerove.lite.dnssettings.DnsValue value(dev.aerove.lite.dnssettings.DnsField);
    int $stable;
}
-keep,includedescriptorclasses class dev.aerove.lite.dnssettings.DnsPreferencesTransformKt {
    java.util.Map transformDnsPreferences$default(java.util.Map,dev.aerove.lite.dnssettings.DnsPreferences,dev.aerove.lite.dnssettings.DnsPreferences,boolean,int,java.lang.Object);
    java.util.Map transformDnsPreferences(java.util.Map,dev.aerove.lite.dnssettings.DnsPreferences,dev.aerove.lite.dnssettings.DnsPreferences,boolean);
}
-keep,includedescriptorclasses class dev.aerove.lite.integration.GlobalResourceWorkspace {
    java.lang.Object closeAndJoin(kotlin.coroutines.Continuation);
}
-keep,includedescriptorclasses class dev.aerove.lite.integration.OverrideWorkspace {
    <init>(dev.aerove.lite.overrides.OverrideRepository,dev.aerove.lite.data.SubscriptionDownloader,kotlinx.coroutines.CoroutineScope,kotlin.jvm.functions.Function0,kotlin.jvm.functions.Function3,int,kotlin.jvm.internal.DefaultConstructorMarker);
    void close();
}
-keep,includedescriptorclasses class dev.aerove.lite.integration.ProfileDetailWorkspace {
    void close();
}
-keep,includedescriptorclasses class dev.aerove.lite.integration.ProfilePropertiesAccess {
    java.lang.Object load(java.lang.String,kotlin.coroutines.Continuation);
}
-keep,includedescriptorclasses class dev.aerove.lite.integration.ProxyPresentationWorkspace {
    java.lang.Object closeAndJoin(kotlin.coroutines.Continuation);
}
-keep,includedescriptorclasses class dev.aerove.lite.integration.ResourcePageWorkspace {
    void close();
}
-keep,includedescriptorclasses class dev.aerove.lite.notices.ClientNoticeAction {
    <init>(java.lang.String,kotlin.jvm.functions.Function0);
}
-keep,includedescriptorclasses class dev.aerove.lite.notices.ClientNotices {
    int $stable;
    java.lang.Long delayUntilNextEvent();
    java.lang.Long post$default(dev.aerove.lite.notices.ClientNotices,java.lang.String,dev.aerove.lite.notices.NoticeLevel,dev.aerove.lite.notices.ClientNoticeAction,int,java.lang.Object);
    void clear();
}
-keep,includedescriptorclasses enum dev.aerove.lite.notices.NoticeLevel {
    dev.aerove.lite.notices.NoticeLevel Info;
    dev.aerove.lite.notices.NoticeLevel Warning;
}
-keep,includedescriptorclasses interface dev.aerove.lite.observability.HistoryDocuments {
    java.lang.Object deleteCreated(java.lang.String,kotlin.coroutines.Continuation);
    java.lang.Object save(java.lang.String,java.util.List,kotlin.jvm.functions.Function0,kotlin.coroutines.Continuation);
    void close();
}
-keep,includedescriptorclasses class dev.aerove.lite.observability.HistoryExportKt {
    dev.aerove.lite.observability.HistoryExportDigest writeHistoryExport(java.io.OutputStream,java.util.List,kotlin.jvm.functions.Function0);
    void verifyHistoryExport(java.io.InputStream,dev.aerove.lite.observability.HistoryExportDigest,kotlin.jvm.functions.Function0);
}
-keep,includedescriptorclasses class dev.aerove.lite.observability.HistoryTablePreferences {
    <init>(java.util.Map,int,kotlin.jvm.internal.DefaultConstructorMarker);
    int $stable;
}
-keep,includedescriptorclasses class dev.aerove.lite.observability.ObservabilityFeature {
    int $stable;
    java.io.Closeable lease(dev.aerove.lite.observability.HistoryKind);
    void requestLogExport();
    void setForeground(boolean);
}
-keep,includedescriptorclasses class dev.aerove.lite.observability.ObservationWindow {
    <init>(java.lang.String,long,long,long,java.util.List,kotlin.ULong,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep,includedescriptorclasses class dev.aerove.lite.observability.ObservationWireKt {
    dev.aerove.lite.observability.ObservationWindow decodeObservationWindow(java.lang.String);
}
-keep,includedescriptorclasses class dev.aerove.lite.overrides.OverrideRepository {
    java.lang.Object snapshot(kotlin.coroutines.Continuation);
}
-keep,includedescriptorclasses class dev.aerove.lite.overrides.ScriptLibrarySession {
    <init>(dev.aerove.lite.overrides.OverrideRepository,dev.aerove.lite.data.SubscriptionDownloader,kotlin.jvm.functions.Function1,int,kotlin.jvm.internal.DefaultConstructorMarker);
    java.lang.Object closeAndJoin(kotlin.coroutines.Continuation);
}
-keep,includedescriptorclasses class dev.aerove.lite.overrides.ScriptLibraryState {
    dev.aerove.lite.overrides.ScriptLibraryState copy$default(dev.aerove.lite.overrides.ScriptLibraryState,java.util.List,java.util.Set,java.lang.String,java.util.Map,int,java.lang.Object);
    int $stable;
}
-keep,includedescriptorclasses class dev.aerove.lite.overrides.ScriptResource {
    <init>(java.lang.String,java.lang.String,java.lang.String,java.lang.String,long,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep,includedescriptorclasses class dev.aerove.lite.overrides.ScriptSaveReceipt {
    <init>(long,boolean,java.lang.String);
}
-keep,includedescriptorclasses class dev.aerove.lite.profilebuilder.BuilderResourceLibrary {
    <init>(java.io.File,dev.aerove.lite.data.SubscriptionDownloader,int,kotlin.jvm.internal.DefaultConstructorMarker);
    java.lang.Object replace(dev.aerove.lite.profilebuilder.BuilderResourceSnapshot,dev.aerove.lite.profilebuilder.BuilderResources,kotlin.coroutines.Continuation);
    java.lang.Object snapshot(kotlin.coroutines.Continuation);
}
-keep,includedescriptorclasses class dev.aerove.lite.profilebuilder.BuilderResources {
    <init>(java.util.List,java.util.List,java.util.List,java.util.List,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep,includedescriptorclasses class dev.aerove.lite.profilebuilder.ProfileBuilderSession {
    boolean isNew();
    dev.aerove.lite.profilebuilder.ProfileBuilderSession$Companion Companion;
    java.lang.String getSavedProfileId();
}
-keep,includedescriptorclasses class dev.aerove.lite.profilebuilder.ProfileBuilderSession$Companion {
    java.lang.Object create(dev.aerove.lite.data.ProfileRepository,java.lang.String,kotlin.coroutines.Continuation);
}
-keep,includedescriptorclasses class dev.aerove.lite.profiles.AndroidProfileDocuments {
    <init>(android.content.ContentResolver);
}
-keep,includedescriptorclasses class dev.aerove.lite.profilesource.BatchImportState {
    int $stable;
}
-keep,includedescriptorclasses class dev.aerove.lite.resources.GeoResourcePreferences {
    dev.aerove.lite.resources.GeoResourcePreferences copy$default(dev.aerove.lite.resources.GeoResourcePreferences,boolean,int,java.util.Map,int,java.lang.Object);
}
-keep,includedescriptorclasses class dev.aerove.lite.resources.ResourceManagementRepository {
    <init>(dev.aerove.lite.data.ProfileRepository,dev.aerove.lite.data.NativeRulePayloadInspector,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep,includedescriptorclasses class dev.aerove.lite.resources.ResourceManagementSession {
    <init>(dev.aerove.lite.resources.ResourceManagementRepository,kotlinx.coroutines.CoroutineScope,kotlin.jvm.functions.Function2,dev.aerove.lite.resources.ResourceManagementLibraryRepository,kotlin.jvm.functions.Function1,dev.aerove.lite.resources.GeoResourceCatalog,kotlin.jvm.functions.Function2,int,kotlin.jvm.internal.DefaultConstructorMarker);
    java.lang.Object closeAndJoin(kotlin.coroutines.Continuation);
}
-keep,includedescriptorclasses class dev.aerove.lite.resources.ResourceManagementState {
    java.lang.String getProfileId();
}
-keep,includedescriptorclasses class dev.aerove.lite.ui.AgeSecretKeyPromptKt {
    void AgeSecretKeyPrompt(boolean,java.lang.String,kotlin.jvm.functions.Function0,kotlin.jvm.functions.Function1,androidx.compose.runtime.Composer,int);
}
-keep,includedescriptorclasses class dev.aerove.lite.ui.BackgroundPolicyScreenKt {
    void BackgroundPolicyScreen(dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences,kotlin.jvm.functions.Function1,boolean,boolean,kotlin.jvm.functions.Function0,kotlin.jvm.functions.Function0,androidx.compose.foundation.layout.PaddingValues,boolean,androidx.compose.runtime.Composer,int,int);
}
-keep,includedescriptorclasses class dev.aerove.lite.ui.BatchImportFormKt {
    void BatchImportForm(dev.aerove.lite.profilesource.BatchImportState,java.lang.String,kotlin.jvm.functions.Function0,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function0,androidx.compose.runtime.Composer,int);
}
-keep,includedescriptorclasses class dev.aerove.lite.ui.DnsPreferencesScreenKt {
    void DnsPreferencesScreen(dev.aerove.lite.dnssettings.DnsPreferences,boolean,androidx.compose.foundation.layout.PaddingValues,kotlin.jvm.functions.Function1,boolean,java.lang.String,androidx.compose.runtime.Composer,int,int);
}
-keep,includedescriptorclasses class dev.aerove.lite.ui.HostsPreferencesScreenKt {
    void HostsPreferencesScreen(dev.aerove.lite.dnssettings.DnsPreferences,boolean,androidx.compose.foundation.layout.PaddingValues,kotlin.jvm.functions.Function1,java.lang.String,androidx.compose.runtime.Composer,int,int);
}
-keep,includedescriptorclasses class dev.aerove.lite.ui.NetworkSettingsScreenKt {
    void NetworkSettingsScreen(dev.aerove.lite.networksettings.NetworkSettings,boolean,androidx.compose.foundation.layout.PaddingValues,kotlin.jvm.functions.Function1,java.lang.String,boolean,kotlin.jvm.functions.Function0,kotlin.jvm.functions.Function0,androidx.compose.runtime.Composer,int,int);
}
-keep,includedescriptorclasses class dev.aerove.lite.ui.OverrideGlobalRulesScreenKt {
    void OverrideGlobalRulesScreen(java.util.List,kotlin.jvm.functions.Function1,androidx.compose.foundation.layout.PaddingValues,boolean,androidx.compose.runtime.Composer,int,int);
}
-keep,includedescriptorclasses class dev.aerove.lite.ui.ScriptLibraryScreenKt {
    void ScriptLibraryScreen(dev.aerove.lite.overrides.ScriptLibraryState,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function2,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function1,kotlin.jvm.functions.Function2,androidx.compose.foundation.layout.PaddingValues,androidx.compose.runtime.Composer,int,int);
}

-keep,includedescriptorclasses class dev.aerove.lite.ui.LiteTabTransitionHostKt {
    androidx.compose.runtime.ProvidableCompositionLocal getLocalLitePageActive();
}
-keep,includedescriptorclasses class dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences {
    java.util.List getExcludeSSIDs();
}
-keep,includedescriptorclasses class dev.aerove.lite.notices.ClientNotices {
    <init>(kotlin.jvm.functions.Function0,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep,includedescriptorclasses class dev.aerove.lite.ui.DashboardPresentationKt {
    java.util.List dashboardPresentationWidgets(java.util.List);
}
-keep,includedescriptorclasses class dev.aerove.lite.dnssettings.DnsValue$Entries {
    java.util.List getValue();
}
-keep,includedescriptorclasses class dev.aerove.lite.dnssettings.DnsPreferences {
    java.util.Set getOverridden();
}
-keep,includedescriptorclasses class dev.aerove.lite.ui.GlobalNetworkRestartNoticeKt {
    void GlobalNetworkRestartNotice(boolean,kotlin.jvm.functions.Function0,boolean,androidx.compose.runtime.Composer,int,int);
}
-keep,includedescriptorclasses class dev.aerove.lite.data.CompatibilityValidator {
    <init>(dev.aerove.lite.data.NativeConfigValidator,java.io.File);
}

# Preserve the six app ABI members invoked by the independently minified #84 test APK.
-keep,includedescriptorclasses class dev.aerove.lite.vpn.LiteVpnService$Companion {
    boolean wasRequested(android.content.Context);
}
-keep,includedescriptorclasses class dev.aerove.lite.profilesource.BatchImportState {
    <init>(long,java.util.List,boolean,int,kotlin.jvm.internal.DefaultConstructorMarker);
}
-keep,includedescriptorclasses class dev.aerove.lite.backgroundpolicy.BackgroundPolicyJson {
    org.json.JSONObject encode(dev.aerove.lite.backgroundpolicy.BackgroundPolicyPreferences);
}
-keep,includedescriptorclasses class dev.aerove.lite.ui.ClientNoticesHostKt {
    androidx.compose.runtime.ProvidableCompositionLocal getLocalClientNotices();
}
-keep,includedescriptorclasses class dev.aerove.lite.ui.DashboardPresentationKt {
    java.util.Set getDashboardStatusMembers();
}
-keep,includedescriptorclasses class dev.aerove.lite.integration.GlobalResourceWorkspace {
    <init>(dev.aerove.lite.profilebuilder.BuilderResourceLibrary,dev.aerove.lite.data.ProfileRepository,dev.aerove.lite.data.GlobalRulePayloadRepository,kotlin.jvm.functions.Function2,kotlin.jvm.functions.Function1);
}
