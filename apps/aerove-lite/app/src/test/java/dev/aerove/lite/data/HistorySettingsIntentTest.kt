package dev.aerove.lite.data

import dev.aerove.lite.ControllerSettingsUpdates
import dev.aerove.lite.LiteSettings
import dev.aerove.lite.observability.HistoryTableChange
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test

class HistorySettingsIntentTest {
    @Test fun queuedResetClearsResizeEvenBeforeItsPublication() = runBlocking {
        var persisted = LiteSettings()
        var visible = persisted
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var writes = 0
        val owner = ControllerSettingsUpdates(read = { persisted }, save = {
            if (++writes == 1) { entered.complete(Unit); release.await() }
            persisted = it; it
        }, rotate = { persisted }, shuffle = { persisted }, current = { visible },
            publish = { settings, _ -> visible = settings })
        val resize = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.historyTable(HistoryTableChange("dns", mapOf("domain" to 170.0)))
        }
        entered.await()
        val reset = launch(start = CoroutineStart.UNDISPATCHED) {
            owner.historyTable(HistoryTableChange("dns", reset = true))
        }
        assertTrue(visible.historyTables.widths.isEmpty())
        release.complete(Unit)
        resize.join(); reset.join()
        assertTrue(persisted.historyTables.widths.isEmpty())
        assertTrue(visible.historyTables.widths.isEmpty())
    }
}
