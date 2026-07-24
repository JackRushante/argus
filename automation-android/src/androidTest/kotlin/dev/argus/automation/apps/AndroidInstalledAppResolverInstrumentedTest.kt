package dev.argus.automation.apps

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidInstalledAppResolverInstrumentedTest {
    @Test
    fun googleMessagesIsResolvedFromItsFriendlyName() = runBlocking {
        val resolver = AndroidInstalledAppResolver(ApplicationProvider.getApplicationContext())

        val candidates = resolver.candidatesFor("crea la regola RCS per Google Messages")

        assertTrue(
            "Google Messages non risolta: $candidates",
            candidates.any { it.packageName == "com.google.android.apps.messaging" },
        )
    }
}
