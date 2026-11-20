package lytso.code.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThoughtMarkerParsingTest {

    @Test
    fun parsesDurationWithStepMetadataWithoutCollision() {
        val meta = parseThoughtHeader("<!-- thought:1500:step=7 -->")
        assertFalse(meta.isStreaming)
        assertEquals(1500L, meta.durationMs)
        assertEquals(7, meta.stepIndex)
        assertNull(meta.refId)
    }

    @Test
    fun doesNotConfuseStepIndexWithDuration() {
        val meta = parseThoughtHeader("<!-- thought:step=42 -->")
        assertFalse(meta.isStreaming)
        assertNull(meta.durationMs)
        assertEquals(42, meta.stepIndex)
        assertNull(meta.refId)
    }

    @Test
    fun parsesClaudeRefMetadataWithoutConfusingContentIndexWithDuration() {
        val meta = parseThoughtHeader("<!-- thought:ref=11111111-1111-4111-8111-111111111111:0 -->")
        assertFalse(meta.isStreaming)
        assertNull(meta.durationMs)
        assertNull(meta.stepIndex)
        assertEquals("11111111-1111-4111-8111-111111111111:0", meta.refId)
    }

    @Test
    fun parsesStreamingAndStepIndexTogether() {
        val meta = parseThoughtHeader("<!-- thought:streaming:step=9 -->")
        assertTrue(meta.isStreaming)
        assertNull(meta.durationMs)
        assertEquals(9, meta.stepIndex)
    }

    @Test
    fun parseMarkdownBlocksPreservesThoughtStepMetadataAndAnswer() {
        val md = """
            <!-- thought:2300:step=11 -->
            Checking loop break behavior.
            <!-- /thought -->

            Final clean answer.
        """.trimIndent()

        val blocks = parseMarkdownBlocks(md, toolCalls = emptyList())
        assertEquals(2, blocks.size)
        val thought = blocks[0] as MarkdownBlock.AgentThought
        assertEquals("Checking loop break behavior.", thought.thought)
        assertEquals(2300L, thought.durationMs)
        assertEquals(11, thought.stepIndex)
        assertFalse(thought.isStreaming)

        val paragraph = blocks[1] as MarkdownBlock.Paragraph
        assertEquals("Final clean answer.", paragraph.text)
    }
}
