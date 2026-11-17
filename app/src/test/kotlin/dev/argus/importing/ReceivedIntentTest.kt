package dev.argus.importing

import kotlin.test.Test
import kotlin.test.assertEquals

class ReceivedIntentTest {
    private val own = "dev.argus"
    private val otherDoc = UriParts("content", "com.android.providers.downloads.documents")

    private fun decide(
        action: String?,
        text: CharSequence? = null,
        stream: UriParts? = null,
        data: UriParts? = null,
    ) = ReceivedIntent.decide(action, text, stream, data, own)

    @Test
    fun `shared text is read as text, before any attachment`() {
        assertEquals(ReceivedDecision.Text("{}"), decide(ReceivedIntent.ACTION_SEND, text = "{}", stream = otherDoc))
    }

    @Test
    fun `a shared or opened document of another app is read`() {
        assertEquals(ReceivedDecision.Document(DocumentSource.STREAM), decide(ReceivedIntent.ACTION_SEND, stream = otherDoc))
        assertEquals(ReceivedDecision.Document(DocumentSource.DATA), decide(ReceivedIntent.ACTION_VIEW, data = otherDoc))
    }

    @Test
    fun `file addresses and Argus' own providers are never read`() {
        assertEquals(ReceivedDecision.Unsupported, decide(ReceivedIntent.ACTION_VIEW, data = UriParts("file", null)))
        assertEquals(
            ReceivedDecision.Unsupported,
            decide(ReceivedIntent.ACTION_SEND, stream = UriParts("file", "")),
        )
        assertEquals(ReceivedDecision.Unsupported, decide(ReceivedIntent.ACTION_VIEW, data = UriParts("content", "dev.argus")))
        assertEquals(
            ReceivedDecision.Unsupported,
            decide(ReceivedIntent.ACTION_VIEW, data = UriParts("content", "dev.argus.androidx-startup")),
        )
        assertEquals(
            ReceivedDecision.Unsupported,
            decide(ReceivedIntent.ACTION_VIEW, data = UriParts("content", "0@DEV.ARGUS.fileprovider")),
        )
        assertEquals(ReceivedDecision.Unsupported, decide(ReceivedIntent.ACTION_VIEW, data = UriParts("content", null)))
        assertEquals(ReceivedDecision.Unsupported, decide(ReceivedIntent.ACTION_VIEW, data = UriParts("https", "example.org")))
    }

    @Test
    fun `other actions and empty shares are refused`() {
        assertEquals(ReceivedDecision.Unsupported, decide("android.intent.action.MAIN", text = "{}"))
        assertEquals(ReceivedDecision.Unsupported, decide(null, data = otherDoc))
        assertEquals(ReceivedDecision.Unsupported, decide(ReceivedIntent.ACTION_SEND, text = ""))
        assertEquals(ReceivedDecision.Unsupported, decide(ReceivedIntent.ACTION_VIEW))
    }
}
