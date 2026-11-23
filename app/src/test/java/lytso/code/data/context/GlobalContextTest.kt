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
        assertTrue(firstWrapped.contains("~/lytso_home/projects/"))
        assertTrue(firstWrapped.contains("~/lytso_home/keys/"))
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
        assertTrue(turn1.contains("REPLACES your default persona"))
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

    @Test
    fun includeChatSystemPromptFalseSkipsDuplicateInEveryMessageBody() {
        GlobalContext.setBuiltInEnabled(false)
        GlobalContext.setChatSystemPrompt("chat-agy", "You are a specialized reverse-engineering persona.")
        GlobalContext.upsert(
            GlobalContext.Entry(
                title = "Standing",
                text = "Keep replies brief.",
                enabled = true,
                everyMessage = true
            )
        )

        val (wrappedNative, fpNative) = GlobalContext.wrap(
            chatId = "chat-agy",
            hasEarlierMessages = true,
            text = "Analyze this binary",
            includeChatSystemPrompt = false
        )
        assertNull(fpNative)
        assertFalse(wrappedNative.contains("specialized reverse-engineering persona"))
        assertFalse(wrappedNative.contains("REPLACES your default persona"))
        assertTrue(wrappedNative.contains("Keep replies brief."))
        assertTrue(wrappedNative.contains("Analyze this binary"))

        val (wrappedFallback, _) = GlobalContext.wrap(
            chatId = "chat-agy",
            hasEarlierMessages = true,
            text = "Analyze this binary",
            includeChatSystemPrompt = true
        )
        assertTrue(wrappedFallback.contains("specialized reverse-engineering persona"))
        assertTrue(wrappedFallback.contains("Keep replies brief."))
    }

    @Test
    fun globalSystemPromptPrecedenceAndPerChatIsolation() {
        GlobalContext.setBuiltInEnabled(false)

        // 1. Neither per-chat nor global -> null (agent default).
        assertNull(GlobalContext.effectiveSystemPrompt("chat-new"))

        // 2. Set global system prompt -> applies to chats without per-chat override.
        GlobalContext.setGlobalSystemPrompt("You are the Global Assistant.")
        assertEquals("You are the Global Assistant.", GlobalContext.effectiveSystemPrompt("chat-new"))

        // 3. Per-chat override on another chat always wins and stays untouched when global changes.
        GlobalContext.setChatSystemPrompt("chat-custom", "You are a Kotlin Specialist.")
        assertEquals("You are a Kotlin Specialist.", GlobalContext.effectiveSystemPrompt("chat-custom"))

        GlobalContext.setGlobalSystemPrompt("You are the Updated Global Assistant.")
        assertEquals("You are the Updated Global Assistant.", GlobalContext.effectiveSystemPrompt("chat-new"))
        assertEquals("You are a Kotlin Specialist.", GlobalContext.effectiveSystemPrompt("chat-custom"))

        // 4. Clearing a chat's override falls back to global; clearing global falls back to null.
        GlobalContext.deleteChatSystemPrompt("chat-custom")
        assertEquals("You are the Updated Global Assistant.", GlobalContext.effectiveSystemPrompt("chat-custom"))

        // 5. includeChatSystemPrompt = false skips global too (native AGY CustomAgent handles it).
        val (nativeWrapped, _) = GlobalContext.wrap(
            chatId = "chat-new",
            hasEarlierMessages = true,
            text = "Ping",
            includeChatSystemPrompt = false
        )
        assertEquals("Ping", nativeWrapped)

        val (fallbackWrapped, _) = GlobalContext.wrap(
            chatId = "chat-new",
            hasEarlierMessages = true,
            text = "Ping",
            includeChatSystemPrompt = true
        )
        assertTrue(fallbackWrapped.contains("You are the Updated Global Assistant."))

        GlobalContext.setGlobalSystemPrompt(null)
        assertNull(GlobalContext.effectiveSystemPrompt("chat-new"))
    }

    @Test
    fun builtInContextReflectsLiveRootAndCustomExplorerRoots() {
        GlobalContext.resetForTest(runsOnPhoneOverride = true, rootActiveOverride = false)
        val fpNoRoot = GlobalContext.fingerprint()
        val textNoRoot = GlobalContext.defaultBuiltInText()
        assertTrue(textNoRoot.contains("Root access: DISABLED"))
        assertFalse(textNoRoot.contains("Root access: ENABLED"))

        GlobalContext.resetForTest(runsOnPhoneOverride = true, rootActiveOverride = true)
        GlobalContext.setCustomExplorerRoots(listOf("Downloads" to "/sdcard/Download"))
        val fpWithRoot = GlobalContext.fingerprint()
        val textWithRoot = GlobalContext.defaultBuiltInText()
        assertTrue(textWithRoot.contains("Root access: ENABLED (superuser active)"))
        assertTrue(textWithRoot.contains("/sdcard/Download"))
        assertNotEquals(fpNoRoot, fpWithRoot)
    }
}
