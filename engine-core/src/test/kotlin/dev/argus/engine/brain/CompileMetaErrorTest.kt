package dev.argus.engine.brain

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CompileMetaErrorTest {
    @Test fun `model chosen codes are answers, app and transport codes are errors`() {
        for (code in listOf("clarification_required", "unsupported_capability", "limit_exceeded", "contact_not_whitelisted")) {
            assertTrue(CompileMetaError.isModelAnswer(code), code)
        }
        for (code in listOf(
            "draft_missing", "meta_parse_error", "meta_json_missing", "draft_invalid", "output_truncated",
            "context_truncated", "empty_response", "compile_without_draft", "budget_exceeded",
            "budget_unpriced_model", "bridge_network", "bridge_http_400",
        )) {
            assertFalse(CompileMetaError.isModelAnswer(code), code)
        }
    }
}
