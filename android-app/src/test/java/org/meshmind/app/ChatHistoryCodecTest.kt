package org.meshmind.app

import org.junit.Assert.*
import org.junit.Test

class ChatHistoryCodecTest {
    @Test fun oldHistoryWithoutMetadataStillLoads() {
        val rows = ChatHistoryCodec.decode("""[{"role":"assistant","content":"**hello**"}]""")
        assertEquals("**hello**", rows.single().content)
        assertFalse(rows.single().reasoning)
    }
    @Test fun reasoningMetadataDoesNotChangeRawContent() {
        val original = listOf(ChatMessage("user", "Question"), ChatMessage("assistant", "work</think>**Answer**", true))
        assertEquals(original, ChatHistoryCodec.decode(ChatHistoryCodec.encode(original)))
    }
}
