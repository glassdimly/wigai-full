package io.github.fabb.wigai.mcp.tool;

import io.github.fabb.wigai.bitwig.BitwigEditFacade;
import io.github.fabb.wigai.common.error.BitwigApiException;
import io.github.fabb.wigai.common.logging.StructuredLogger;
import io.github.fabb.wigai.mcp.McpErrorHandler;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * MCP tools that change the project: tracks, mixer, devices, device parameters, clips, notes,
 * transport settings and Bitwig application actions. All of them go through {@link BitwigEditFacade}.
 */
public class EditTools {

    private static final String TRACK_PROPS = """
                "track_index": {"type": "integer", "minimum": 0, "description": "0-based track index (see list_tracks)"},
                "track_name": {"type": "string", "description": "Exact track name (alternative to track_index)"}""";

    private static final String DEVICE_PROPS = TRACK_PROPS + """
                ,
                "device_index": {"type": "integer", "minimum": 0, "description": "0-based device index on the track (see list_devices_on_track)"}""";

    private static final String CHAIN_PROPS = """
                "chain": {"type": "string", "description": "A chain inside the device, e.g. 'Modulator' or 'Carrier' (Vocoder), 'FX' (FX devices), or 'layer:0' / 'layer:Name' (layer devices). See get_device_chains."},
                "chain_device_index": {"type": "integer", "minimum": 0, "description": "Device index within that chain (required with chain)"}""";

    private static final String POSITION_PROPS = """
                "position": {"type": "string", "enum": ["start", "end"], "description": "Where in the chain to insert (default end)"},
                "after_device_index": {"type": "integer", "minimum": 0, "description": "Insert directly after this device instead"}""";

    @FunctionalInterface
    private interface Op {
        Object run(Args a) throws Exception;
    }

    /** Thin typed accessor over raw MCP arguments. */
    static final class Args {
        private final Map<String, Object> raw;
        private final BitwigEditFacade facade;
        private final String operation;

        Args(Map<String, Object> raw, BitwigEditFacade facade, String operation) {
            this.raw = raw == null ? Map.of() : raw;
            this.facade = facade;
            this.operation = operation;
        }

        Integer integer(String key) {
            Object v = raw.get(key);
            if (v == null) {
                return null;
            }
            if (!(v instanceof Number n)) {
                throw new IllegalArgumentException("Parameter '" + key + "' must be an integer");
            }
            return n.intValue();
        }

        int requiredInt(String key) {
            Integer v = integer(key);
            if (v == null) {
                throw new IllegalArgumentException("Missing required parameter '" + key + "'");
            }
            return v;
        }

        Double number(String key) {
            Object v = raw.get(key);
            if (v == null) {
                return null;
            }
            if (!(v instanceof Number n)) {
                throw new IllegalArgumentException("Parameter '" + key + "' must be a number");
            }
            return n.doubleValue();
        }

        String string(String key) {
            Object v = raw.get(key);
            if (v == null) {
                return null;
            }
            if (!(v instanceof String s)) {
                throw new IllegalArgumentException("Parameter '" + key + "' must be a string");
            }
            return s;
        }

        String requiredString(String key) {
            String v = string(key);
            if (v == null || v.isBlank()) {
                throw new IllegalArgumentException("Missing required parameter '" + key + "'");
            }
            return v;
        }

        Boolean bool(String key) {
            Object v = raw.get(key);
            if (v == null) {
                return null;
            }
            if (!(v instanceof Boolean b)) {
                throw new IllegalArgumentException("Parameter '" + key + "' must be a boolean");
            }
            return b;
        }

        boolean requiredBool(String key) {
            Boolean v = bool(key);
            if (v == null) {
                throw new IllegalArgumentException("Missing required parameter '" + key + "'");
            }
            return v;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> list(String key) {
            Object v = raw.get(key);
            if (!(v instanceof List<?> l) || l.isEmpty()) {
                throw new IllegalArgumentException("Parameter '" + key + "' must be a non-empty array");
            }
            List<Map<String, Object>> out = new ArrayList<>();
            for (Object o : l) {
                if (!(o instanceof Map)) {
                    throw new IllegalArgumentException("Items of '" + key + "' must be objects");
                }
                out.add((Map<String, Object>) o);
            }
            return out;
        }

        int track() throws BitwigApiException {
            return facade.resolveTrackIndex(integer("track_index"), string("track_name"), operation);
        }

        int track(String indexKey, String nameKey) throws BitwigApiException {
            return facade.resolveTrackIndex(integer(indexKey), string(nameKey), operation);
        }
    }

    private static McpServerFeatures.SyncToolSpecification tool(String name, String description, String properties,
                                                                String required, BitwigEditFacade facade,
                                                                StructuredLogger logger, Op op) {
        String schema = "{\"type\": \"object\", \"properties\": {" + properties + "}"
            + (required == null ? "" : ", \"required\": [" + required + "]")
            + ", \"additionalProperties\": false}";
        var t = McpSchema.Tool.builder().name(name).description(description).inputSchema(schema).build();
        BiFunction<McpSyncServerExchange, CallToolRequest, McpSchema.CallToolResult> handler =
            (exchange, req) -> McpErrorHandler.executeWithErrorHandling(name, logger,
                () -> op.run(new Args(req.arguments(), facade, name)));
        return McpServerFeatures.SyncToolSpecification.builder().tool(t).callHandler(handler).build();
    }

    public static List<McpServerFeatures.SyncToolSpecification> specifications(BitwigEditFacade f, StructuredLogger log) {
        List<McpServerFeatures.SyncToolSpecification> specs = new ArrayList<>();

        // ---- tracks
        specs.add(tool("create_track",
            "Create a new audio, instrument or effect track, optionally at a position and with a name.",
            """
                "type": {"type": "string", "enum": ["audio", "instrument", "effect"]},
                "position": {"type": "integer", "minimum": 0, "description": "Track index to insert at (default: end)"},
                "name": {"type": "string"}""",
            "\"type\"", f, log,
            a -> f.createTrack(a.requiredString("type"), a.integer("position"), a.string("name"))));

        specs.add(tool("delete_track", "Delete a track.", TRACK_PROPS, null, f, log,
            a -> f.deleteTrack(a.track())));

        specs.add(tool("duplicate_track", "Duplicate a track with its devices and clips.", TRACK_PROPS, null, f, log,
            a -> f.duplicateTrack(a.track())));

        specs.add(tool("get_track_mixer", "Read a track's mixer state: volume, pan, mute, solo, arm, monitor mode, sends.",
            TRACK_PROPS, null, f, log, a -> f.trackMixerState(a.track())));

        specs.add(tool("set_track",
            "Change track settings. Only given fields change. volume_db is matched exactly against Bitwig's dB display; "
                + "volume is the raw 0-1 fader position. pan is -1 (left) to 1 (right).",
            TRACK_PROPS + """
                ,
                "name": {"type": "string"},
                "volume": {"type": "number", "minimum": 0, "maximum": 1},
                "volume_db": {"type": "number", "maximum": 6},
                "pan": {"type": "number", "minimum": -1, "maximum": 1},
                "mute": {"type": "boolean"},
                "solo": {"type": "boolean"},
                "arm": {"type": "boolean"},
                "activated": {"type": "boolean"},
                "monitor_mode": {"type": "string", "enum": ["ON", "OFF", "AUTO"]},
                "color": {"type": "string", "description": "#RRGGBB"}""",
            null, f, log,
            a -> f.setTrackProperties(a.track(), a.string("name"), a.number("volume"), a.number("volume_db"),
                a.number("pan"), a.bool("mute"), a.bool("solo"), a.bool("arm"), a.bool("activated"),
                a.string("monitor_mode"), a.string("color"))));

        specs.add(tool("set_send", "Set a track's send level (0-1) and/or enable it.",
            TRACK_PROPS + """
                ,
                "send_index": {"type": "integer", "minimum": 0},
                "value": {"type": "number", "minimum": 0, "maximum": 1},
                "enabled": {"type": "boolean"}""",
            "\"send_index\"", f, log,
            a -> f.setSend(a.track(), a.requiredInt("send_index"), a.number("value"), a.bool("enabled"))));

        // ---- devices
        specs.add(tool("list_bitwig_devices", "List Bitwig's built-in device names usable with insert_device.",
            "", null, f, log, a -> f.listBitwigDeviceNames()));

        specs.add(tool("insert_device",
            "Insert a device on a track: a Bitwig built-in by name (e.g. 'EQ+', 'Audio Receiver', 'Reverb'), "
                + "or a plugin by CLAP id (e.g. 'org.surge-synth-team.surge-xt') or VST3 class id. "
                + "To insert inside a device's chain, give device_index (the container) and chain; "
                + "after_device_index then refers to a device in that chain.",
            TRACK_PROPS + ",\n" + POSITION_PROPS + """
                ,
                "device_index": {"type": "integer", "minimum": 0, "description": "Container device, only with chain"},
                "chain": {"type": "string", "description": "Chain inside device_index, e.g. 'Modulator'. See get_device_chains."},
                "bitwig_device": {"type": "string"},
                "clap_id": {"type": "string"},
                "vst3_id": {"type": "string"}""",
            null, f, log,
            a -> f.insertDevice(a.track(), a.integer("device_index"), a.string("chain"), a.string("bitwig_device"),
                a.string("clap_id"), a.string("vst3_id"), a.string("position"), a.integer("after_device_index"))));

        specs.add(tool("get_device_chains",
            "List the chains inside a device and the devices in each: slots such as a Vocoder's 'Modulator' and "
                + "'Carrier' or an FX device's 'FX', and layers of FX/Instrument Layer devices.",
            DEVICE_PROPS, "\"device_index\"", f, log,
            a -> f.getDeviceChains(a.track(), a.requiredInt("device_index"))));

        String copyProps = """
                "from_track_index": {"type": "integer", "minimum": 0},
                "from_track_name": {"type": "string"},
                "from_device_index": {"type": "integer", "minimum": 0},
                "from_chain": {"type": "string", "description": "Source is inside this chain of from_device_index"},
                "from_chain_device_index": {"type": "integer", "minimum": 0},
                "to_track_index": {"type": "integer", "minimum": 0},
                "to_track_name": {"type": "string"},
                "to_device_index": {"type": "integer", "minimum": 0, "description": "Container device, only with to_chain"},
                "to_chain": {"type": "string", "description": "Destination chain inside to_device_index"},
                """ + POSITION_PROPS;
        specs.add(tool("copy_device", "Copy a device (with all its settings) to another position or track.",
            copyProps, "\"from_device_index\"", f, log,
            a -> f.copyOrMoveDevice(a.track("from_track_index", "from_track_name"), a.requiredInt("from_device_index"),
                a.string("from_chain"), a.integer("from_chain_device_index"),
                a.track("to_track_index", "to_track_name"), a.integer("to_device_index"), a.string("to_chain"),
                a.string("position"), a.integer("after_device_index"), false)));
        specs.add(tool("move_device", "Move a device to another position or track.",
            copyProps, "\"from_device_index\"", f, log,
            a -> f.copyOrMoveDevice(a.track("from_track_index", "from_track_name"), a.requiredInt("from_device_index"),
                a.string("from_chain"), a.integer("from_chain_device_index"),
                a.track("to_track_index", "to_track_name"), a.integer("to_device_index"), a.string("to_chain"),
                a.string("position"), a.integer("after_device_index"), true)));

        specs.add(tool("delete_device", "Delete a device from a track, or from a chain inside a device.",
            DEVICE_PROPS + ",\n" + CHAIN_PROPS, "\"device_index\"", f, log,
            a -> f.deleteDevice(a.track(), a.requiredInt("device_index"), a.string("chain"), a.integer("chain_device_index"))));

        specs.add(tool("set_device_enabled", "Enable (un-bypass) or bypass a device, or a device inside a chain.",
            DEVICE_PROPS + ",\n" + CHAIN_PROPS + ",\n\"enabled\": {\"type\": \"boolean\"}", "\"device_index\", \"enabled\"", f, log,
            a -> f.setDeviceEnabled(a.track(), a.requiredInt("device_index"), a.string("chain"), a.integer("chain_device_index"),
                a.requiredBool("enabled"))));

        specs.add(tool("set_device_window_open", "Open or close a plugin's editor window.",
            DEVICE_PROPS + ",\n\"open\": {\"type\": \"boolean\"}", "\"device_index\", \"open\"", f, log,
            a -> f.setDeviceWindowOpen(a.track(), a.requiredInt("device_index"), a.requiredBool("open"))));

        specs.add(tool("get_device_parameters",
            "Read a device's parameters on any track without changing the user's selection. "
                + "Returns all remote-control pages, or one page if 'page' is given.",
            DEVICE_PROPS + ",\n" + CHAIN_PROPS + ",\n\"page\": {\"type\": \"integer\", \"minimum\": 0}", "\"device_index\"", f, log,
            a -> f.getDeviceParameters(a.track(), a.requiredInt("device_index"), a.string("chain"),
                a.integer("chain_device_index"), a.integer("page"))));

        specs.add(tool("set_device_parameters",
            "Set parameters on any device by name (case-insensitive, searched across pages) or by page+index. "
                + "Values are normalized 0-1; the response shows Bitwig's displayed value so you can check units.",
            DEVICE_PROPS + ",\n" + CHAIN_PROPS + """
                ,
                "parameters": {"type": "array", "items": {"type": "object", "properties": {
                    "name": {"type": "string"},
                    "page": {"type": "integer", "minimum": 0},
                    "index": {"type": "integer", "minimum": 0, "maximum": 7},
                    "value": {"type": "number", "minimum": 0, "maximum": 1}},
                  "required": ["value"]}}""",
            "\"device_index\", \"parameters\"", f, log,
            a -> {
                List<BitwigEditFacade.ParamAssignment> list = new ArrayList<>();
                for (Map<String, Object> p : a.list("parameters")) {
                    Args pa = new Args(p, f, "set_device_parameters");
                    Double v = pa.number("value");
                    if (v == null) {
                        throw new IllegalArgumentException("Each parameter needs a 'value'");
                    }
                    list.add(new BitwigEditFacade.ParamAssignment(pa.integer("page"), pa.integer("index"), pa.string("name"), v));
                }
                return f.setDeviceParameters(a.track(), a.requiredInt("device_index"), a.string("chain"),
                    a.integer("chain_device_index"), list);
            }));

        // ---- clips and files
        specs.add(tool("insert_file",
            "Import an audio or MIDI file. With slot_index it goes into that clip-launcher slot of the track; "
                + "without, Bitwig inserts it as a new track after the given track.",
            TRACK_PROPS + """
                ,
                "path": {"type": "string", "description": "Absolute path to a .wav/.mid/etc file"},
                "slot_index": {"type": "integer", "minimum": 0}""",
            "\"path\"", f, log,
            a -> f.insertFile(a.track(), a.integer("slot_index"), a.requiredString("path"))));

        specs.add(tool("create_clip", "Create an empty launcher clip in a slot.",
            TRACK_PROPS + """
                ,
                "slot_index": {"type": "integer", "minimum": 0},
                "length_beats": {"type": "integer", "minimum": 1}""",
            "\"slot_index\", \"length_beats\"", f, log,
            a -> f.createClip(a.track(), a.requiredInt("slot_index"), a.requiredInt("length_beats"))));

        specs.add(tool("delete_clip", "Delete the launcher clip in a slot.",
            TRACK_PROPS + ",\n\"slot_index\": {\"type\": \"integer\", \"minimum\": 0}", "\"slot_index\"", f, log,
            a -> f.deleteClip(a.track(), a.requiredInt("slot_index"))));

        specs.add(tool("write_notes",
            "Write MIDI notes into an existing launcher clip. Times are in beats from the clip start (1/64-beat grid).",
            TRACK_PROPS + """
                ,
                "slot_index": {"type": "integer", "minimum": 0},
                "clear_first": {"type": "boolean", "description": "Remove existing notes first (default false)"},
                "notes": {"type": "array", "items": {"type": "object", "properties": {
                    "start": {"type": "number", "minimum": 0},
                    "pitch": {"type": "integer", "minimum": 0, "maximum": 127},
                    "velocity": {"type": "integer", "minimum": 1, "maximum": 127},
                    "duration": {"type": "number", "exclusiveMinimum": 0}},
                  "required": ["start", "pitch", "duration"]}}""",
            "\"slot_index\", \"notes\"", f, log,
            a -> {
                List<BitwigEditFacade.Note> notes = new ArrayList<>();
                for (Map<String, Object> n : a.list("notes")) {
                    Args na = new Args(n, f, "write_notes");
                    Integer vel = na.integer("velocity");
                    notes.add(new BitwigEditFacade.Note(na.number("start"), na.requiredInt("pitch"),
                        vel == null ? 100 : vel, na.number("duration")));
                }
                Boolean clear = a.bool("clear_first");
                return f.writeNotes(a.track(), a.requiredInt("slot_index"), notes, Boolean.TRUE.equals(clear));
            }));

        // ---- transport
        specs.add(tool("set_transport", "Set tempo, play position (beats), arranger loop, metronome, record or overdub.",
            """
                "tempo_bpm": {"type": "number", "minimum": 20, "maximum": 666},
                "position_beats": {"type": "number", "minimum": 0},
                "loop": {"type": "boolean"},
                "metronome": {"type": "boolean"},
                "record": {"type": "boolean"},
                "overdub": {"type": "boolean"}""",
            null, f, log,
            a -> f.setTransport(a.number("tempo_bpm"), a.number("position_beats"), a.bool("loop"),
                a.bool("metronome"), a.bool("record"), a.bool("overdub"))));

        // ---- application
        specs.add(tool("list_actions",
            "List Bitwig application actions (menu commands), optionally filtered by a substring of id/name/category.",
            "\"filter\": {\"type\": \"string\"}", null, f, log, a -> f.listActions(a.string("filter"))));

        specs.add(tool("invoke_action", "Run a Bitwig application action by id (see list_actions).",
            "\"id\": {\"type\": \"string\"}", "\"id\"", f, log, a -> f.invokeAction(a.requiredString("id"))));

        specs.add(tool("undo", "Undo the last change in Bitwig.", "", null, f, log, a -> f.undo(false)));
        specs.add(tool("redo", "Redo the last undone change in Bitwig.", "", null, f, log, a -> f.undo(true)));

        return specs;
    }
}
