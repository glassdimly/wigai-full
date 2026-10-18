# wigai-full: WigAI with full project editing

> **This is a fork of [fabb/WigAI](https://github.com/fabb/WigAI).** We would much rather have these features in WigAI itself and delete this fork. They have been offered upstream:
>
> - [fabb/WigAI#21](https://github.com/fabb/WigAI/pull/21): project editing tools (tracks, mixer, devices, parameters of any device, clips and notes, transport, actions)
> - [fabb/WigAI#19](https://github.com/fabb/WigAI/pull/19): `next_project` / `previous_project`, by [@0reo](https://github.com/0reo)
>
> Until they are merged, this repo carries both, and its releases contain the combined extension. It is a drop-in replacement: same extension ID, name and MCP port as WigAI, so Bitwig treats it as the same controller.

What the fork adds beyond those PRs (92 tools in all; see [docs/AGENT-GUIDE.md](docs/AGENT-GUIDE.md) for how they fit together and [ROADMAP.md](ROADMAP.md) for what Bitwig's API cannot do):

- Device knowledge: every installed Bitwig device and plugin (CLAP, VST3, VST2 from Bitwig's plugin cache), on-demand control scans, Bitwig's own device descriptions
- Presets and Bitwig's browser, Drum Machine pads, layer mixers, device chains (Vocoder carrier/modulator and the like)
- Note expressions and conditions, clip settings, scenes, arranger clips selected by the user, groove, cue mix
- Following the user: an event log of changes (`get_events`, long-poll) and `watch`, which streams them as MCP notifications
- Listening: meters, playing notes, auditioning notes, sending MIDI

Original README follows.

# WigAI - Bitwig Studio Extension for AI Control

WigAI is a Model Context Protocol (MCP) server implemented as a Bitwig Studio extension. It provides an interface for external AI assistants to control Bitwig Studio through simple text-based commands.

## Overview

The extension enables external AI agents (e.g., IDE-based copilots, standalone AI assistants) to interact with and control Bitwig Studio using the Model Context Protocol. This allows for hands-free control via text commands interpreted by an AI assistant.

## Key Features

-   MCP server implementation
-   Transport control (start/stop playback)
-   Device parameter control for selected devices
-   Clip and scene launching
-   Project editing: create/delete/duplicate tracks; volume (exact dB), pan, mute, solo, arm, color, sends
-   Devices: insert Bitwig built-ins by name or plugins by CLAP/VST3 id; copy, move, bypass, delete
-   Parameters of any device on any track, by name or page, without moving the user's selection
-   Clips: import audio/MIDI files into launcher slots, create clips, write MIDI notes
-   Tempo, loop, metronome, record; run any Bitwig menu action; undo/redo
-   Switching between open projects

## Requirements

-   Bitwig Studio (5.2.7 or later)
-   Java 21 LTS
-   External AI agent supporting Model Context Protocol

## Building

To build the extension:

```bash
./gradlew build
```

This will generate a `.bwextension` file in the `build/extensions` directory.

## Installation

1. Copy the `WigAI.bwextension` file from `build/extensions` to your Bitwig Studio extensions directory.
2. Launch Bitwig Studio
3. Open Bitwig preferences, navigate to Extensions, and activate WigAI

## Usage

Once the extension is activated in Bitwig Studio, the MCP server will be available at `http://localhost:61169/mcp`. External AI agents can connect to this endpoint to send commands and receive responses.

## Testing

```bash
./gradlew test                            # unit tests
python3 scripts/live_test.py              # end-to-end against a running Bitwig with WigAI enabled
python3 scripts/live_test_project.py      # project-wide tools, groove, clip management
python3 scripts/live_test_container.py    # presets, browser, drum pads, layers
python3 scripts/live_test_notes.py        # note expressions, clip settings, scenes, cue mix
python3 scripts/live_test_midi.py         # playing notes, MIDI, modulation readout, show
python3 scripts/live_test_events.py       # plugin inventory, get_events, watch streaming
```

The live tests create, edit and delete their own tracks, so they refuse to run unless an empty
project is open (`live_test.py --force` overrides). Never run them against a song you care about.

## Development

This project is developed using the [BMAD v2 method](https://github.com/bmadcode/BMAD-METHOD) with AI Agents. The files in folders `agents`, `ai` and `docs` used for this development method.

## Releases

This project uses [Nyx](https://github.com/mooltiverse/nyx) for semantic versioning and automated releases. The release process follows [Conventional Commits](https://www.conventionalcommits.org/) specification:

- `feat:` - new features (minor version bump)
- `fix:` - bug fixes (patch version bump)
- `feat!:` or `BREAKING CHANGE:` - breaking changes (major version bump)
- `chore:`, `docs:`, `style:`, `refactor:`, `test:` - no version bump

Releases are automatically created on pushes to the `main` branch when commits follow the conventional commit format.

## License

[MIT License](LICENSE)

## Author

fabb
