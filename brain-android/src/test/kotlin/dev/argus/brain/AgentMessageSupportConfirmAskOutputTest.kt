package dev.argus.brain

import kotlin.test.Test
import kotlin.test.assertTrue

/** 0.8: il prompt di compile insegna `confirm`, `ask` e la risposta vincolata di `invoke_llm`. */
class AgentMessageSupportConfirmAskOutputTest {
    private val prompt = AgentMessageSupport.compileSystemText()

    @Test
    fun `the model learns to ask before running when the user says so`() {
        assertTrue("\"confirm\": {\"timeoutMs\":integer}" in prompt)
        assertTrue("ask me first" in prompt)
        assertTrue("Never with immediate" in prompt)
    }

    @Test
    fun `the model learns the ask action and its bounds`() {
        assertTrue("{\"type\":\"ask\", \"prompt\":string, \"choices\":[string,...], \"captureAs\":string" in prompt)
        assertTrue("2-4 unique choices of 1-24 characters" in prompt)
        assertTrue("\${var}" in prompt)
    }

    @Test
    fun `the model prefers a constrained answer for decisions`() {
        assertTrue("\"output\":{\"kind\":\"CHOICE\"|\"NUMBER\"|\"BOOLEAN\"" in prompt)
        assertTrue("PREFER a constrained output whenever the rule only needs" in prompt)
        assertTrue("Exceptions (trusted, CLEAN): the answer of an \"ask\" and an invoke_llm with \"output\"." in prompt)
    }
}
