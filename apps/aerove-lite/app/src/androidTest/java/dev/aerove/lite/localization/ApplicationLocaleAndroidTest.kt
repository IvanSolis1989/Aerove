package dev.aerove.lite.localization

import android.app.LocaleConfig
import android.app.LocaleManager
import android.app.Notification
import android.content.ComponentCallbacks
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.platform.app.InstrumentationRegistry
import dev.aerove.lite.R
import org.junit.Assert.*
import org.junit.Test
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ApplicationLocaleAndroidTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val application get() = instrumentation.targetContext.applicationContext

    @Test fun manifestDeclaresExactlyTheSevenRealStandardLocales() {
        val declared = LocaleConfig(application).supportedLocales
        assertNotNull(declared)
        assertEquals(LocaleChoice.entries.map { it.platformTag }.toSet(),
            (0 until declared!!.size()).map { declared[it].toLanguageTag() }.toSet())
    }

    @Test fun nativeChoiceUpdatesApplicationNotificationsAndDefaultNumberAndDateFormatting() {
        val manager = application.getSystemService(LocaleManager::class.java)
        val previous = manager.applicationLocales
        val labels = mapOf("en" to "Language", "es" to "Idioma", "fr" to "Langue", "ja" to "言語",
            "ko" to "언어", "ru" to "Язык", "zh_CN" to "语言")
        try {
            for (choice in LocaleChoice.entries) {
                applyAndAwait(manager, LocaleList.forLanguageTags(choice.platformTag), choice.platformTag.substringBefore('-'))
                assertEquals(choice.platformTag, manager.applicationLocales[0].toLanguageTag())
                assertEquals(choice.platformTag.substringBefore('-'), Locale.getDefault().language)
                assertEquals(labels[choice.tag], application.getString(R.string.locale_language))
                val expectedLocale = Locale.forLanguageTag(choice.platformTag)
                assertEquals(NumberFormat.getNumberInstance(expectedLocale).format(12345.5), NumberFormat.getNumberInstance().format(12345.5))
                val sample = Date(1_700_000_000_000L)
                val formattedDate = DateFormat.getDateInstance(DateFormat.LONG).format(sample)
                assertEquals(DateFormat.getDateInstance(DateFormat.LONG, expectedLocale).format(sample), formattedDate)
                val notification = Notification.Builder(application, "locale-test")
                    .setContentTitle(application.getString(R.string.locale_language)).setContentText(formattedDate).build()
                assertEquals(labels[choice.tag], notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                assertEquals(formattedDate, notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
            }
            applyAndAwait(manager, LocaleList.getEmptyLocaleList(), manager.systemLocales[0].language)
            assertTrue(manager.applicationLocales.isEmpty)
        } finally {
            val restoredLanguage = if (previous.isEmpty) manager.systemLocales[0].language else previous[0].language
            applyAndAwait(manager, previous, restoredLanguage)
        }
    }

    private fun applyAndAwait(manager: LocaleManager, locales: LocaleList, language: String) {
        val changed = CountDownLatch(1)
        val callbacks = object : ComponentCallbacks {
            override fun onConfigurationChanged(newConfig: Configuration) {
                if (application.resources.configuration.locales[0].language == language) changed.countDown()
            }
            override fun onLowMemory() = Unit
        }
        application.registerComponentCallbacks(callbacks)
        try {
            instrumentation.runOnMainSync { manager.applicationLocales = locales }
            instrumentation.waitForIdleSync()
            if (application.resources.configuration.locales[0].language != language)
                assertTrue("Application configuration must follow native app language", changed.await(5, TimeUnit.SECONDS))
            assertEquals(language, application.resources.configuration.locales[0].language)
        } finally { application.unregisterComponentCallbacks(callbacks) }
    }
}
