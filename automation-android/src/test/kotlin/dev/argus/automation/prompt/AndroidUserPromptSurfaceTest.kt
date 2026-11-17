package dev.argus.automation.prompt

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 0.8: i pulsanti delle domande. Il receiver capisce solo URI nostri, id ben formati e risposte chiuse. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidUserPromptSurfaceTest {
    private val id = "0123456789abcdef0123456789abcdef"

    @Test
    fun `only our own answer uris are understood`() {
        val run = AndroidUserPromptSurface.promptUri(id, AndroidUserPromptSurface.RUN)
        assertEquals(id to PromptAnswer.Run, AndroidUserPromptSurface.parse(AndroidUserPromptSurface.ACTION_ANSWER, run))
        assertEquals(
            id to PromptAnswer.Cancel,
            AndroidUserPromptSurface.parse(
                AndroidUserPromptSurface.ACTION_ANSWER,
                AndroidUserPromptSurface.promptUri(id, AndroidUserPromptSurface.CANCEL),
            ),
        )
        assertEquals(
            id to PromptAnswer.Choice(3),
            AndroidUserPromptSurface.parse(AndroidUserPromptSurface.ACTION_ANSWER, AndroidUserPromptSurface.promptUri(id, "3")),
        )
        listOf("4", "-1", "01", "x", "expire").forEach { answer ->
            assertNull(
                AndroidUserPromptSurface.parse(AndroidUserPromptSurface.ACTION_ANSWER, AndroidUserPromptSurface.promptUri(id, answer)),
                answer,
            )
        }
        assertNull(AndroidUserPromptSurface.parse("other.ACTION", run))
        assertNull(AndroidUserPromptSurface.parse(AndroidUserPromptSurface.ACTION_ANSWER, AndroidUserPromptSurface.promptUri("short", "run")))
        assertNull(AndroidUserPromptSurface.parse(AndroidUserPromptSurface.ACTION_ANSWER, null))
        assertEquals(
            id to null,
            AndroidUserPromptSurface.parse(AndroidUserPromptSurface.ACTION_EXPIRE, AndroidUserPromptSurface.promptUri(id, "expire")),
        )
    }
}
