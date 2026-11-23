package lytso.code.data.remote.services

import exa.language_server_pb.AgentMessageOrigin
import exa.language_server_pb.McpServerSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgyChatServiceTest {

    @Test
    fun localChatWithCustomSystemPromptUsesCustomAgentAndSdkExecutable() {
        val req = AgyChatService.buildSendUserCascadeMessageRequest(
            cascadeId = "chat-1",
            text = "Hello",
            modelEnum = "MODEL_OPENAI_GPT_OSS_120B_MEDIUM",
            customSystemPrompt = "  You are Lumi. Never mention Antigravity.  "
        )

        assertEquals(AgentMessageOrigin.AGENT_MESSAGE_ORIGIN_SDK_EXECUTABLE, req.message_origin)

        val spec = req.custom_agent_spec
        assertNotNull(spec)
        assertNull(spec!!.builtin_agent)

        val custom = spec.custom_agent
        assertNotNull(custom)
        assertTrue(custom!!.exclude_default_components)
        assertEquals(1, custom.system_prompt_sections.size)
        assertEquals("You are Lumi. Never mention Antigravity.", custom.system_prompt_sections.first().content)
        assertEquals(AgyChatService.CUSTOM_AGENT_TOOL_NAMES, custom.tool_names)
        assertTrue(custom.tool_names.contains("run_command"))
        assertTrue(custom.tool_names.contains("view_file"))

        val discovery = req.cascade_config?.planner_config?.customization_discovery_config
        assertNotNull(discovery)
        assertFalse(discovery!!.skills?.inherit_user ?: true)
        assertFalse(discovery.agents?.inherit_user ?: true)
        assertFalse(discovery.plugins?.inherit_user ?: true)
        assertFalse(discovery.rules?.inherit_user ?: true)
        assertFalse(discovery.hooks?.inherit_user ?: true)
        assertFalse(discovery.workflows?.inherit_user ?: true)
        assertFalse(discovery.mcp?.inherit_user ?: true)
        assertEquals(false, discovery.kind_enablement["skill"])
        assertEquals(false, discovery.kind_enablement["plugin"])
        assertEquals(false, discovery.kind_enablement["mcp"])
    }

    @Test
    fun defaultChatPreservesUserNonJarvisMcpAndExcludesJarvisMcp() {
        val req = AgyChatService.buildSendUserCascadeMessageRequest(
            cascadeId = "chat-2",
            text = "Check browser",
            customSystemPrompt = null,
            isJarvis = false,
            mcpServers = listOf(
                McpServerSpec(server_name = "jarvis_mcp", server_url = "http://127.0.0.1:8770/jarvis"),
                McpServerSpec(server_name = "browser_mcp", server_url = "http://127.0.0.1:8770/browser")
            )
        )

        assertEquals(AgentMessageOrigin.AGENT_MESSAGE_ORIGIN_UNSPECIFIED, req.message_origin)
        assertNotNull(req.custom_agent_spec?.builtin_agent)
        assertNull(req.custom_agent_spec?.custom_agent)

        val discovery = req.cascade_config?.planner_config?.customization_discovery_config!!
        assertFalse(discovery.mcp?.inherit_user ?: true)
        assertEquals(listOf("browser_mcp"), discovery.mcp?.servers?.map { it.server_name })
        assertNull(discovery.kind_enablement["mcp"])
    }

    @Test
    fun jarvisChatInheritsUserMcpWhileKeepingNoiseDisabled() {
        val req = AgyChatService.buildSendUserCascadeMessageRequest(
            cascadeId = "jarvis-1",
            text = "Speak status",
            isJarvis = true
        )

        val discovery = req.cascade_config?.planner_config?.customization_discovery_config!!
        assertTrue(discovery.mcp?.inherit_user == true)
        assertFalse(discovery.skills?.inherit_user ?: true)
        assertFalse(discovery.plugins?.inherit_user ?: true)
        assertNull(discovery.kind_enablement["mcp"])
    }

    @Test
    fun cloudChatFallsBackToBuiltinAgentAndUnspecifiedOrigin() {
        val req = AgyChatService.buildSendUserCascadeMessageRequest(
            cascadeId = "cloud-1",
            text = "Run remote",
            cloud = true,
            customSystemPrompt = "Custom prompt"
        )

        assertEquals(AgentMessageOrigin.AGENT_MESSAGE_ORIGIN_UNSPECIFIED, req.message_origin)
        assertNotNull(req.custom_agent_spec?.builtin_agent)
        assertNull(req.custom_agent_spec?.custom_agent)
        assertNull(req.cascade_config?.planner_config?.customization_discovery_config)
    }
}
