package agentdock.history

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import java.nio.file.Files

class HistoryStorageTest {
    @Test
    fun `safe conversation ids remain valid filename tokens`() {
        assertEquals("conv-1-1712345678901", HistoryStorage.requireSafeConversationId("conv-1-1712345678901"))
        assertEquals("conv_0123456789abcdef", HistoryStorage.requireSafeConversationId("conv_0123456789abcdef"))
        assertEquals(
            "3f8d8c9e-cc1f-49c1-bb42-9d9d1a5ad842",
            HistoryStorage.requireSafeConversationId("3f8d8c9e-cc1f-49c1-bb42-9d9d1a5ad842")
        )
    }

    @Test
    fun `conversation ids cannot contain path traversal or separators`() {
        listOf(
            "",
            ".",
            "..",
            "../outside",
            "..\\outside",
            "nested/conversation",
            "nested\\conversation",
            "C:\\temp\\conversation",
            "/tmp/conversation"
        ).forEach { value ->
            assertFailsWith<IllegalArgumentException>(value) {
                HistoryStorage.requireSafeConversationId(value)
            }
        }
    }

    @Test
    fun `invalid conversation ids are rejected before replay files are saved`() {
        val projectDir = Files.createTempDirectory("agent-dock-history-project-").toFile()

        val saved = AgentDockHistoryService.saveConversationReplay(
            projectPath = projectDir.absolutePath,
            conversationId = "../outside",
            data = ConversationReplayData()
        )

        assertFalse(saved)
        assertFalse(projectDir.parentFile.resolve("outside.json").exists())
    }
}
