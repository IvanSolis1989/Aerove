package dev.aerove.lite.ui

import com.kyant.backdrop.RuntimeShader
import dev.aerove.lite.optics.AeroveGlassCapture
import dev.aerove.lite.optics.AeroveGlassStyle
import dev.aerove.lite.optics.AeroveGlassUniformObserver
import dev.aerove.lite.optics.AeroveSuperellipseGeometry
import dev.aerove.lite.optics.configureAeroveGlass
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class LiteGlassConsumerDiagnosticsTest {
    private fun shader(calls: MutableList<Pair<String, List<Int>>> = mutableListOf()): RuntimeShader =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RuntimeShader::class.java)) { _, method, args ->
            if (method.name == "setFloatUniform") {
                val values = if (args!![1] is FloatArray) args[1] as FloatArray else args.drop(1).map { it as Float }.toFloatArray()
                calls += args[0] as String to values.map(Float::toRawBits)
            }
            null
        } as RuntimeShader

    @Test fun closedOrAbsentSubscriptionCannotObserveAnotherOwner() {
        val root = Any()
        val view = Any()
        assertNull(LiteGlassConsumerDiagnostics.attach(root, view, GlassConsumerRole.SwitchThumb, Any()))
        val first = LiteGlassConsumerDiagnostics.observe(root)
        try {
            assertThrows(IllegalStateException::class.java) { LiteGlassConsumerDiagnostics.observe(root) }
            val old = requireNotNull(LiteGlassConsumerDiagnostics.attach(root, view, GlassConsumerRole.SwitchThumb, Any()))
            assertNull(LiteGlassConsumerDiagnostics.attach(Any(), view, GlassConsumerRole.SwitchThumb, Any()))
            first.close()
            LiteGlassConsumerDiagnostics.observe(root).use { second ->
                first.close()
                LiteGlassConsumerDiagnostics.recordDraw(old)
                assertTrue(second.snapshot(view).active)
                assertTrue(second.snapshot(view).consumers.isEmpty())
            }
        } finally { first.close() }
        assertFalse(LiteGlassConsumerDiagnostics.hasSubscription)
    }

    @Test fun observerMatchesEveryActualSetterAndCopiesArrayBits() {
        val actual = mutableListOf<Pair<String, List<Int>>>()
        val observed = mutableListOf<Pair<String, List<Int>>>()
        val profile = FloatArray(64) { 12f }
        val observer = object : AeroveGlassUniformObserver {
            override fun onUniformSet(name: String, values: FloatArray) { observed += name to values.map(Float::toRawBits) }
        }
        shader(actual).configureAeroveGlass(AeroveSuperellipseGeometry.resolve(24.0, 24.0, 12.0),
            AeroveGlassStyle.regular(false).copy(blur = 0f), AeroveGlassCapture(169f, 169f, 2.625f, 53f),
            profile = profile, uniformObserver = observer)
        profile[0] = 99f
        assertEquals(actual, observed)
        assertEquals(12f.toRawBits(), observed.single { it.first == "uProfile" }.second.first())
        val withoutObserver = mutableListOf<Pair<String, List<Int>>>()
        shader(withoutObserver).configureAeroveGlass(AeroveSuperellipseGeometry.resolve(24.0, 24.0, 12.0),
            AeroveGlassStyle.regular(false).copy(blur = 0f), AeroveGlassCapture(169f, 169f, 2.625f, 53f),
            profile = FloatArray(64) { 12f })
        assertEquals(actual, withoutObserver)
    }

    @Test fun capacityOverflowCannotBeReportedAsLinked() {
        val root = Any()
        val view = Any()
        LiteGlassConsumerDiagnostics.observe(root).use { session ->
            repeat(8) { assertNotNull(LiteGlassConsumerDiagnostics.attach(root, view, GlassConsumerRole.SwitchThumb, Any())) }
            assertNull(LiteGlassConsumerDiagnostics.attach(root, view, GlassConsumerRole.SwitchTrack, Any()))
            val readout = session.snapshot(view)
            assertEquals(8, readout.consumers.size)
            assertTrue(readout.overflow)
            val frame = GlassConsumerFrameIdentity(session.id, 1, System.identityHashCode(view), System.identityHashCode(root),
                null, null, null, true, 1, 2, 1, 2, true)
            assertEquals("unknown", associateGlassConsumers(frame, readout, readout).status)
        }
    }

    @Test fun lateConcurrentCloseCannotCloseTheReplacement() {
        val root = Any()
        val first = LiteGlassConsumerDiagnostics.observe(root)
        first.close()
        LiteGlassConsumerDiagnostics.observe(root).use { replacement ->
            val done = CountDownLatch(1)
            Thread { try { first.close() } finally { done.countDown() } }.start()
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertTrue(replacement.snapshot(Any()).active)
        }
    }

    @Test fun observerFailureCannotChangeRealSetterCalls() {
        val actual = mutableListOf<Pair<String, List<Int>>>()
        var failures = 0
        shader(actual).configureAeroveGlass(AeroveSuperellipseGeometry.resolve(24.0, 24.0, 12.0),
            AeroveGlassStyle.regular(false).copy(blur = 0f), AeroveGlassCapture(169f, 169f, 2.625f, 53f),
            uniformObserver = object : AeroveGlassUniformObserver {
                override fun onUniformSet(name: String, values: FloatArray) { error("diagnostic sink") }
                override fun onObserverFailure(error: Exception) { failures++ }
            })
        assertTrue(actual.isNotEmpty())
        assertEquals(actual.size, failures)
    }

    @Test fun actualSetterFailurePropagatesBeforeAnyObservation() {
        var observed = false
        val failure = IllegalStateException("actual setter failure")
        val target = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(RuntimeShader::class.java)) { _, _, _ -> throw failure } as RuntimeShader
        val thrown = assertThrows(IllegalStateException::class.java) {
            target.configureAeroveGlass(AeroveSuperellipseGeometry.resolve(24.0, 24.0, 12.0),
                AeroveGlassStyle.regular(false).copy(blur = 0f), AeroveGlassCapture(64f, 64f, 1f, 20f),
                uniformObserver = object : AeroveGlassUniformObserver {
                    override fun onUniformSet(name: String, values: FloatArray) { observed = true }
                })
        }
        assertSame(failure, thrown)
        assertFalse(observed)
    }

    @Test fun overlappingConfigurationsCannotProduceACompleteReceipt() {
        val root = Any()
        val view = Any()
        LiteGlassConsumerDiagnostics.observe(root).use { session ->
            val handle = requireNotNull(LiteGlassConsumerDiagnostics.attach(root, view, GlassConsumerRole.SwitchThumb, Any()))
            fun begin() = requireNotNull(LiteGlassConsumerDiagnostics.beginConfiguration(handle, Any(), "fixed shader", 24f, 24f,
                AeroveGlassCapture(64f, 64f, 1f, 20f), floatArrayOf(0.5f, 0.5f, 63.5f, 63.5f)))
            val first = begin()
            val second = begin()
            second.complete()
            first.complete()
            assertFalse(requireNotNull(session.snapshot(view).consumers.single().configuration).complete)
        }
    }

    @Test fun associationRejectsAbaLayoutsAndPosesButAllowsUnchangedCallbacks() {
        val root = Any()
        val view = Any()
        val source = Any()
        val bounds = GlassConsumerRect(1f, 2f, 25f, 26f)
        val layout = GlassConsumerLayout(bounds, 24, 24, 1f, System.identityHashCode(root), true)
        val pose = GlassConsumerPoseObservation().apply {
            sourceLayout(bounds)
            sourceDraw(source, 24f, 24f, 1f)
            rootLayout(root, bounds)
        }
        LiteGlassConsumerDiagnostics.observe(root).use { session ->
            val handles = GlassConsumerRole.entries.map { role ->
                requireNotNull(LiteGlassConsumerDiagnostics.attach(root, view, role, source)).also { handle ->
                    LiteGlassConsumerDiagnostics.recordLayout(handle, layout)
                    val recording = requireNotNull(LiteGlassConsumerDiagnostics.beginConfiguration(handle, Any(), "fixed shader",
                        24f, 24f, AeroveGlassCapture(64f, 64f, 1f, 20f), floatArrayOf(0.5f, 0.5f, 63.5f, 63.5f)))
                    shader().configureAeroveGlass(AeroveSuperellipseGeometry.resolve(24.0, 24.0, 12.0),
                        AeroveGlassStyle.regular(false).copy(blur = 0f), AeroveGlassCapture(64f, 64f, 1f, 20f), uniformObserver = recording)
                    recording.onUniformSet("uHazeOffset", floatArrayOf(0f))
                    recording.complete()
                }
            }
            fun association(change: () -> Unit): GlassConsumerAssociation {
                val before = session.snapshot(view)
                val poseBefore = pose.revision
                val arm = System.nanoTime()
                change()
                handles.forEach(LiteGlassConsumerDiagnostics::recordDraw)
                val frame = GlassConsumerFrameIdentity(session.id, 7, System.identityHashCode(view), System.identityHashCode(root),
                    System.identityHashCode(source), 7, 7, true, arm, System.nanoTime(), 1L, 2L, true, poseBefore, pose.revision)
                return associateGlassConsumers(frame, before, session.snapshot(view))
            }
            assertEquals("linked", association {
                handles.forEach { LiteGlassConsumerDiagnostics.recordLayout(it, layout) }
                pose.sourceLayout(bounds.copy())
                repeat(2) { pose.sourceDraw(source, 24f, 24f, 1f); pose.rootDraw(root) }
                pose.rootLayout(root, bounds.copy())
            }.status)
            assertEquals("unknown", association {
                LiteGlassConsumerDiagnostics.recordLayout(handles.first(), layout.copy(widthPx = 25))
                LiteGlassConsumerDiagnostics.recordLayout(handles.first(), layout)
            }.status)
            assertEquals("unknown", association {
                pose.sourceLayout(bounds.copy(left = 0f))
                pose.sourceLayout(bounds)
            }.status)
            assertEquals("unknown", association {
                pose.sourceDraw(Any(), 24f, 24f, 1f)
                pose.sourceDraw(source, 24f, 24f, 1f)
            }.status)
            for ((width, height, density) in listOf(Triple(25f, 24f, 1f), Triple(24f, 25f, 1f), Triple(24f, 24f, 2f))) {
                assertEquals("unknown", association {
                    pose.sourceDraw(source, width, height, density)
                    pose.sourceDraw(source, 24f, 24f, 1f)
                }.status)
            }
            assertEquals("unknown", association {
                pose.rootLayout(root, bounds.copy(top = 0f))
                pose.rootLayout(root, bounds)
            }.status)
            assertEquals("unknown", association {
                pose.rootDraw(Any())
                pose.rootDraw(root)
            }.status)
            assertEquals("unknown", association {
                pose.rootLayout(Any(), bounds)
                pose.rootLayout(root, bounds)
            }.status)
            assertEquals("linked", association {
                pose.sourceDraw(source, 24f, 24f, 1f)
                pose.rootDraw(root)
            }.status)
        }
    }

    @Test fun associationRequiresUniqueStableActualConsumersAndOriginalCommit() {
        val root = Any()
        val view = Any()
        val source = Any()
        LiteGlassConsumerDiagnostics.observe(root).use { session ->
            val handles = GlassConsumerRole.entries.map { role ->
                requireNotNull(LiteGlassConsumerDiagnostics.attach(root, view, role, source)).also { handle ->
                    LiteGlassConsumerDiagnostics.recordLayout(handle, GlassConsumerLayout(GlassConsumerRect(1f, 2f, 25f, 26f),
                        24, 24, 1f, System.identityHashCode(root), true))
                    val recording = requireNotNull(LiteGlassConsumerDiagnostics.beginConfiguration(handle, Any(), "fixed shader",
                        24f, 24f, AeroveGlassCapture(64f, 64f, 1f, 20f), floatArrayOf(0.5f, 0.5f, 63.5f, 63.5f)))
                    shader().configureAeroveGlass(AeroveSuperellipseGeometry.resolve(24.0, 24.0, 12.0),
                        AeroveGlassStyle.regular(false).copy(blur = 0f), AeroveGlassCapture(64f, 64f, 1f, 20f), uniformObserver = recording)
                    recording.onUniformSet("uHazeOffset", floatArrayOf(0f))
                    recording.complete()
                    LiteGlassConsumerDiagnostics.recordDraw(handle)
                }
            }
            val before = session.snapshot(view)
            val now = System.nanoTime()
            handles.forEach(LiteGlassConsumerDiagnostics::recordDraw)
            val commit = System.nanoTime()
            val identity = GlassConsumerFrameIdentity(session.id, 7, System.identityHashCode(view), System.identityHashCode(root),
                System.identityHashCode(source), 7, 7, true, now, commit, 1L, 2L, true)
            assertEquals("linked", associateGlassConsumers(identity, before, session.snapshot(view)).status)
            assertEquals("unknown", associateGlassConsumers(identity, before, before).status)
            assertEquals("unknown", associateGlassConsumers(identity.copy(rootIdentity = -1), before, before).status)
            assertEquals("unknown", associateGlassConsumers(identity.copy(sourceStageEpoch = 6), before, before).status)
            assertEquals("unknown", associateGlassConsumers(identity.copy(deadlinePassed = false), before, before).status)
            assertEquals("unknown", associateGlassConsumers(identity.copy(posesStable = false), before, before).status)
            assertEquals("unknown", associateGlassConsumers(identity.copy(commitNanos = 1), before, before).status)
            val changed = before.copy(consumers = before.consumers.mapIndexed { index, pane ->
                if (index == 0) pane.copy(configuration = pane.configuration!!.copy(epoch = -1)) else pane
            })
            assertEquals("unknown", associateGlassConsumers(identity, before, changed).status)
            LiteGlassConsumerDiagnostics.detach(handles.first())
            assertEquals("unknown", associateGlassConsumers(identity, before, session.snapshot(view)).status)
        }
    }
}
