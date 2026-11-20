package lytso.code.data.context

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GlobalContextTest {

    @Before
    fun setUp() {
        GlobalContext.resetForTest(runsOnPhoneOverride = true)
    }

    @After
    fun tearDown() {
        GlobalContext.resetForTest(runsOnPhoneOverride = null)
    }

    @Test
    fun builtInContextIsEditableAndReinjectsWhenModified() {
        val defaultText = GlobalContext.defaultBuiltInText()
        assertTrue(defaultText.isNotBlank())
        assertFalse(GlobalContext.isBuiltInCustomized())
        val origFp = GlobalContext.fingerprint()

        val (firstWrapped, sentFp) = GlobalContext.wrap(
            chatId = "chat-1",
            hasEarlierMessages = false,
            text = "hello world"
        )
        assertEquals(origFp, sentFp)
        assertTrue(firstWrapped.contains("## Where you run"))
        assertTrue(firstWrapped.contains("hello world"))

        // Second message with unchanged built-in context does not resend first-message block.
        val (secondUnchanged, secondFp) = GlobalContext.wrap(
            chatId = "chat-1",
            hasEarlierMessages = true,
            text = "follow up"
        )
        assertNull(secondFp)
        assertEquals("follow up", secondUnchanged)

        // Customizing built-in context changes fingerprint and triggers an updated injection.
        GlobalContext.setBuiltInText("## Custom Environment\n- Use sandboxed runner only.")
        assertTrue(GlobalContext.isBuiltInCustomized())
        assertEquals("## Custom Environment\n- Use sandboxed runner only.", GlobalContext.builtInText())
        val updatedFp = GlobalContext.fingerprint()
        assertNotEquals(origFp, updatedFp)

        val (afterEdit, updatedSentFp) = GlobalContext.wrap(
            chatId = "chat-1",
            hasEarlierMessages = true,
            text = "after edit"
        )
        assertEquals(updatedFp, updatedSentFp)
        assertTrue(afterEdit.contains("Custom Environment"))
        assertTrue(afterEdit.contains("replaces the earlier copy"))

        GlobalContext.resetBuiltInText()
        assertFalse(GlobalContext.isBuiltInCustomized())
        assertEquals(origFp, GlobalContext.fingerprint())
    }

    @Test
    fun perChatSystemPromptReachesEveryTurnAndCopiesOnFork() {
        GlobalContext.setBuiltInEnabled(false)
        GlobalContext.setChatSystemPrompt("chat-main", "Always answer in concise Portuguese.")

        val (turn1, fp1) = GlobalContext.wrap("chat-main", hasEarlierMessages = false, text = "Olá")
        assertNull(fp1)
        assertTrue(turn1.contains("Always answer in concise Portuguese."))
        assertTrue(turn1.contains("Olá"))

        val (turn2, fp2) = GlobalContext.wrap("chat-main", hasEarlierMessages = true, text = "Continuar")
        assertNull(fp2)
        assertTrue(turn2.contains("Always answer in concise Portuguese."))
        assertTrue(turn2.contains("Continuar"))

        GlobalContext.copyChatSystemPrompt("chat-main", "chat-branch")
        assertEquals("Always answer in concise Portuguese.", GlobalContext.chatSystemPrompt("chat-branch"))

        // Updating the original chat's system prompt later leaves the branch's prompt independent.
        GlobalContext.setChatSystemPrompt("chat-main", "Switch to English.")
        assertEquals("Switch to English.", GlobalContext.chatSystemPrompt("chat-main"))
        assertEquals("Always answer in concise Portuguese.", GlobalContext.chatSystemPrompt("chat-branch"))

        GlobalContext.deleteChatSystemPrompt("chat-main")
        assertNull(GlobalContext.chatSystemPrompt("chat-main"))
    }
}
