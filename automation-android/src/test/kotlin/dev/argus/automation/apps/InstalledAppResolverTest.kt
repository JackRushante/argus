package dev.argus.automation.apps

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstalledAppResolverTest {
    private val apps = listOf(
        InstalledAppCandidate("Messaggi", "com.google.android.apps.messaging"),
        InstalledAppCandidate("Instagram", "com.instagram.android"),
        InstalledAppCandidate("Note", "com.coloros.note"),
    )

    @Test
    fun `matches an Italian label`() {
        val result = matchInstalledApps("controlla l'app Messaggi", apps)

        assertEquals(listOf("com.google.android.apps.messaging"), result.map { it.packageName })
    }

    @Test
    fun `matches Google Messages against the messaging package stem`() {
        val result = matchInstalledApps("RCS ricevuti da Google Messages", apps)

        assertEquals(listOf("com.google.android.apps.messaging"), result.map { it.packageName })
    }

    @Test
    fun `exact package wins without exposing unrelated apps`() {
        val result = matchInstalledApps(
            "usa com.instagram.android e non chiedermi il pacchetto",
            apps,
        )

        assertEquals(listOf("com.instagram.android"), result.map { it.packageName })
    }

    @Test
    fun `generic app language does not expose the installed inventory`() {
        val result = matchInstalledApps("cerca il pacchetto tra le app installate", apps)

        assertTrue(result.isEmpty())
    }
}
