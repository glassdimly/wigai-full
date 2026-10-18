package io.github.fabb.wigai.mcp;

import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Guards the reasons WigAI requires MCP Java SDK 2.x and a lenient shared mapper:
 *
 * <ul>
 *   <li>Modern MCP clients (2025-11-25 spec revision) advertise client capabilities
 *       such as {@code elicitation.form} during {@code initialize}. SDK 0.11 rejected
 *       those handshakes with HTTP 400 "Invalid message format: Unrecognized field
 *       \"form\" ... ClientCapabilities$Elicitation", breaking every current client.</li>
 *   <li>The MCP specification requires forward compatibility: unknown fields must be
 *       ignored, so the shared mapper disables {@code FAIL_ON_UNKNOWN_PROPERTIES}
 *       (the SDK's default Jackson 2 mapper is strict).</li>
 * </ul>
 */
class McpJsonCompatibilityTest {

    @Test
    void deserializesModernClientCapabilities() throws Exception {
        String initializeParams = """
            {
              "protocolVersion": "2025-06-18",
              "capabilities": {
                "elicitation": {"form": {}},
                "roots": {"listChanged": true}
              },
              "clientInfo": {"name": "opencode", "version": "1.0"}
            }""";

        McpSchema.InitializeRequest request =
            McpJson.MAPPER.readValue(initializeParams, McpSchema.InitializeRequest.class);

        assertNotNull(request.capabilities());
        assertEquals("opencode", request.clientInfo().name());
    }

    @Test
    void ignoresUnknownCapabilityFields() throws Exception {
        String futureClientParams = """
            {
              "protocolVersion": "2025-06-18",
              "capabilities": {
                "elicitation": {"form": {}},
                "some_future_capability": {"mode": "anything"}
              },
              "clientInfo": {"name": "future-client", "version": "9.9"}
            }""";

        McpSchema.InitializeRequest request =
            McpJson.MAPPER.readValue(futureClientParams, McpSchema.InitializeRequest.class);

        assertNotNull(request.capabilities());
        assertEquals("future-client", request.clientInfo().name());
    }
}
