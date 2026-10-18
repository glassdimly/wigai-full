package io.github.fabb.wigai.mcp;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.json.jackson2.JacksonMcpJsonMapper;

/**
 * Shared MCP JSON mapper for WigAI, backed by Jackson 2 to stay consistent with
 * WigAI's own Jackson-based data types. The MCP Java SDK 2.x uses pluggable JSON
 * modules; {@code mcp-json-jackson2} provides this implementation.
 */
public final class McpJson {

    private static final JacksonMcpJsonMapper JACKSON =
        new JacksonMcpJsonMapper(new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false));

    /**
     * Shared Jackson-2-backed mapper for MCP wire serialization and tool schemas.
     *
     * <p>Configured to ignore unknown JSON properties: newer MCP clients advertise
     * capabilities and fields that older SDK revisions do not know, and the MCP
     * specification requires forward compatibility by ignoring unrecognized
     * fields. (The SDK's own {@code JacksonMcpJsonMapperSupplier} default mapper
     * is strict, which caused HTTP 400 "Invalid message format" handshakes with
     * modern clients against SDK 0.11.)
     */
    public static final McpJsonMapper MAPPER = JACKSON;

    /** The underlying Jackson 2 {@link ObjectMapper} behind {@link #MAPPER}, for reuse. */
    public static ObjectMapper jacksonMapper() {
        return JACKSON.getObjectMapper();
    }

    private McpJson() {
    }
}
