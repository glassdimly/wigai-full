# WigAI API roadmap

What the Bitwig Controller API (v19) can do that WigAI does not expose yet, ordered by how much it helps an agent produce music. The raw method-by-method list is generated into [docs/api-coverage.md](docs/api-coverage.md) by `scripts/api_coverage.py`; this file is the curated version.


## Done

| Area | Tools |
|---|---|
| Read the project | `status`, `list_tracks`, `get_track_details`, `list_devices_on_track`, `get_device_details`, `list_scenes`, `get_clips_in_scene` (upstream) |
| Transport and launching | `transport_start`, `transport_stop`, `launch_clip`, scene launching (upstream), `set_transport`, `stop_track` |
| Tracks and mixer | `create_track`, `delete_track`, `duplicate_track`, `get_track_mixer`, `set_track`, `set_send` |
| Devices | `list_bitwig_devices`, `insert_device`, `copy_device`, `move_device`, `delete_device`, `set_device_enabled`, `set_device_window_open` |
| Chains inside devices | `get_device_chains`, plus `chain` / `chain_device_index` on the device tools (slots such as a Vocoder's Modulator/Carrier, FX slots, layers) |
| Parameters of any device | `get_device_parameters`, `set_device_parameters` (all remote-control pages, by name or page+index) |
| Clips and notes | `insert_file`, `create_clip`, `delete_clip`, `write_notes`, `read_notes`, `set_clip` |
| Listening | `set_engine_active`, `get_levels`, `play_notes` |
| Arrangement | `list_cue_markers`, `go_to_cue_marker`, `set_arrangement` (loop range, time signature), `rename_scene` |
| Projects and actions | `next_project`, `previous_project` (from #19), `list_actions`, `invoke_action`, `undo`, `redo` |
| Project-wide | `project_state`, `reset_project` (unsolo/unmute/unarm all), `create_scene`, `set_groove`, `set_transport_options` (punch, metronome volume, pre-roll, automation write mode, launcher overdub, launch quantization, post-recording action, rewind/fast-forward/tap tempo) |
| Clip management and note editing | `copy_clip`, `move_clip`, `duplicate_clip`, `record_into_slot`, `edit_notes` (velocity, length, gain, pan, timbre, pressure, micro-pitch, chance, mute, repeats), `transform_clip` (transpose, quantize, duplicate content, launch quantization/mode) |
| Presets and the library | `list_presets` (every .bwpreset on disk, searchable), `preset_file` on `insert_device`, `replace_device` (device, plugin or preset in place), `browser_open` / `browser_state` / `browser_filter` / `browser_commit` / `browser_cancel` (Bitwig's own browser: content types, every filter column, paged results; mode `replace` lists a device's presets) |
| Drum Machine and layers | `list_drum_pads`, `set_drum_pad` (name, volume, pan, mute, solo), `insert_into_drum_pad` (device, plugin, preset, or a sample, which becomes a Sampler), `clear_drum_pads`, `list_layers`, `set_layer`; device lists now show each device's loaded preset |
| Device knowledge | `list_installed_devices`, `describe_device` (on-demand control scans with history), `fetch_device_docs`, `catalog_status` |

## Next: high value

1. **Note editing leftovers.** Occurrence/recurrence conditions, velocity spread, release velocity; clip shuffle and accent (`Clip.getShuffle/getAccent`).
2. **Arranger clip editing.** `createArrangerCursorClip` edits the notes of the arranger clip selected in the UI (placement itself is not possible, see below).
3. **Scenes.** `createSceneFromPlayingLauncherClips`, copy/move/delete scenes.
4. **Track order and groups.** Expand/collapse groups (`isGroupExpanded`), crossfade mode. Moving or copying tracks is blocked: Bitwig 6.1.3 accepts `InsertionPoint.moveTracks/copyTracks` from an extension and silently does nothing, on the request thread and on the control thread alike (tested 2026-10-17). `duplicate_track` does work for copies.
5. **Cue mix.** Cue volume/mix for headphone monitoring.

## Later

10. **What is modulated.** Read macros and modulation sources (`Device.getMacro`, `getModulationSource`) so an agent can see why a knob moves.
11. **Playing notes and raw MIDI.** `Channel.playingNotes` (which notes sound right now), `Track.sendMidi`.
12. **UI focus.** Panel layouts (Arrange/Mix/Edit), show a clip or device in the editor (`showInEditor`, `selectInEditor`), make a track visible. Most of this is already reachable through `invoke_action`.
13. **Live state streaming** ([fabb/WigAI#3](https://github.com/fabb/WigAI/issues/3)). Push playhead position, levels and parameter changes as MCP notifications instead of polling.
14. **Device catalog tools.** `describe_device` / `list_installed_devices` backed by the catalog from `scripts/device_catalog.py` (see [docs/DEVICE-KNOWLEDGE.md](docs/DEVICE-KNOWLEDGE.md)).

## Not possible with the Controller API

These need changes in Bitwig itself; tools should tell the user to do them by hand.

- **Audio and MIDI routing:** track inputs/outputs, sidechain sources, an Audio Receiver's source. `Track.sourceSelector` is read-only.
- **Placing clips on the arranger timeline**, or reading where arranger clips are.
- **Adding or removing modulators** (LFOs, envelopes) and modulation routings.
- **Writing automation points.** Only by recording parameter changes with automation write enabled.
- **Parameters that are not on a remote-control page** ([fabb/WigAI#15](https://github.com/fabb/WigAI/issues/15)).
- **Rendering, bouncing or exporting audio**, other than through application actions where Bitwig offers one.
- **Opening a project from disk.** Only cycling through projects that are already open.
- **Adding, removing or duplicating layers, and emptying a drum pad.** `DeviceLayer.duplicate()` / `deleteObject()`, `DrumPad.deleteObject()` and `host.duplicateObjects(layer)` are silently ignored in Bitwig 6.1.3. **`host.deleteObjects(drumPad)` crashes Bitwig** (ClassCastException on its GUI thread), taking every open project with it; never call it. Delete the devices on a pad or layer with `delete_device` and chain `layer:N` instead.
- **Reading the device of a packaged preset from disk.** Presets from installed packages are encrypted; `browser_open` with mode `replace` shows which presets belong to a device.
