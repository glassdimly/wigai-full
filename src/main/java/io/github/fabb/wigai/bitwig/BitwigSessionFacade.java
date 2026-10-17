package io.github.fabb.wigai.bitwig;

import com.bitwig.extension.controller.api.*;
import io.github.fabb.wigai.common.Logger;
import io.github.fabb.wigai.common.error.BitwigApiException;
import io.github.fabb.wigai.common.error.ErrorCode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.function.BooleanSupplier;

/**
 * Facade for listening to and auditioning a project: track levels, cue markers, the arranger loop and
 * time signature, reading and editing launcher clips, playing notes into a track, and scene names.
 * Like {@link BitwigEditFacade}, every Bitwig object is created in the constructor (during init()).
 */
public class BitwigSessionFacade {

    private static final class Constants {
        public static final int METER_RANGE = 1000;
        public static final int CUE_MARKERS = 64;
        public static final int SCENES = 128;
        public static final int CLIP_GRID_WIDTH = 16384;
        public static final int CLIP_GRID_HEIGHT = 128;
        public static final double CLIP_STEP_BEATS = 1.0 / 16.0;
        public static final long POLL_INTERVAL_MS = 20;
        public static final long CLIP_LOAD_MS = 500;

        private Constants() {}
    }

    private final ControllerHost host;
    private final Logger logger;
    private final Transport transport;
    private final TrackBank trackBank;
    private final MasterTrack master;
    private final AtomicIntegerArray peak;
    private final AtomicIntegerArray rms;
    private final int masterSlot;
    private final CueMarkerBank cueMarkers;
    private final SceneBank scenes;
    private final CursorTrack clipTrack;
    private final Clip clip;
    private final Map<Long, NoteStep> steps = new ConcurrentHashMap<>();
    private final long timeoutMs;

    public BitwigSessionFacade(ControllerHost host, Logger logger, Transport transport, TrackBank trackBank) {
        this(host, logger, transport, trackBank, 2000);
    }

    BitwigSessionFacade(ControllerHost host, Logger logger, Transport transport, TrackBank trackBank, long timeoutMs) {
        this.host = host;
        this.logger = logger;
        this.transport = transport;
        this.trackBank = trackBank;
        this.timeoutMs = timeoutMs;

        int tracks = trackBank.getSizeOfBank();
        this.masterSlot = tracks;
        this.peak = new AtomicIntegerArray(tracks + 1);
        this.rms = new AtomicIntegerArray(tracks + 1);
        for (int t = 0; t < tracks; t++) {
            observeMeter(trackBank.getItemAt(t), t);
        }
        this.master = host.createMasterTrack(0);
        master.name().markInterested();
        observeMeter(master, masterSlot);

        Arranger arranger = host.createArranger();
        this.cueMarkers = arranger.createCueMarkerBank(Constants.CUE_MARKERS);
        for (int i = 0; i < cueMarkers.getSizeOfBank(); i++) {
            CueMarker m = cueMarkers.getItemAt(i);
            m.exists().markInterested();
            m.getName().markInterested();
            m.position().markInterested();
        }

        this.scenes = host.createSceneBank(Constants.SCENES);
        for (int i = 0; i < scenes.getSizeOfBank(); i++) {
            scenes.getItemAt(i).exists().markInterested();
            scenes.getItemAt(i).name().markInterested();
        }

        transport.arrangerLoopStart().markInterested();
        transport.arrangerLoopDuration().markInterested();
        transport.timeSignature().markInterested();
        transport.timeSignature().numerator().markInterested();
        transport.timeSignature().denominator().markInterested();

        this.clipTrack = host.createCursorTrack("WIGAI_CLIP_TRACK", "WigAI clip track", 0, 0, false);
        clipTrack.name().markInterested();
        this.clip = clipTrack.createLauncherCursorClip(Constants.CLIP_GRID_WIDTH, Constants.CLIP_GRID_HEIGHT);
        clip.exists().markInterested();
        clip.getLoopStart().markInterested();
        clip.getLoopLength().markInterested();
        clip.getPlayStart().markInterested();
        clip.getPlayStop().markInterested();
        clip.setStepSize(Constants.CLIP_STEP_BEATS);
        clip.scrollToKey(0);
        clip.scrollToStep(0);
        clip.addNoteStepObserver(step -> {
            long key = stepKey(step.channel(), step.x(), step.y());
            if (step.state() == NoteStep.State.NoteOn) {
                steps.put(key, step);
            } else {
                steps.remove(key);
            }
        });
    }

    private void observeMeter(Channel channel, int slot) {
        channel.addVuMeterObserver(Constants.METER_RANGE, -1, true, v -> peak.set(slot, v));
        channel.addVuMeterObserver(Constants.METER_RANGE, -1, false, v -> rms.set(slot, v));
    }

    private static long stepKey(int channel, int x, int y) {
        return ((long) channel << 40) | ((long) x << 8) | y;
    }

    private boolean waitFor(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(Constants.POLL_INTERVAL_MS);
        }
        return condition.getAsBoolean();
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ========================================
    // Levels
    // ========================================

    /**
     * Current meter readings. Values are Bitwig's meter scale normalized to 0-1 (not dB); they are only
     * meaningful while the transport is playing or audio is otherwise flowing.
     */
    public Map<String, Object> getLevels() {
        List<Map<String, Object>> tracks = new ArrayList<>();
        for (int t = 0; t < trackBank.getSizeOfBank(); t++) {
            Track track = trackBank.getItemAt(t);
            if (!track.exists().get() || "Master".equalsIgnoreCase(track.trackType().get())) {
                continue;
            }
            tracks.add(level(t, track.name().get(), t));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("playing", transport.isPlaying().get());
        result.put("scale", "0-1 of Bitwig's meter range (not dB)");
        result.put("master", level(-1, master.name().get(), masterSlot));
        result.put("tracks", tracks);
        return result;
    }

    private Map<String, Object> level(int index, String name, int slot) {
        Map<String, Object> m = new LinkedHashMap<>();
        if (index >= 0) {
            m.put("track_index", index);
        }
        m.put("name", name);
        m.put("peak", Math.round(peak.get(slot) * 1000.0 / Constants.METER_RANGE) / 1000.0);
        m.put("rms", Math.round(rms.get(slot) * 1000.0 / Constants.METER_RANGE) / 1000.0);
        return m;
    }

    // ========================================
    // Cue markers, loop, time signature
    // ========================================

    public List<Map<String, Object>> listCueMarkers() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < cueMarkers.getSizeOfBank(); i++) {
            CueMarker m = cueMarkers.getItemAt(i);
            if (!m.exists().get()) {
                continue;
            }
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("index", i);
            e.put("name", m.getName().get());
            e.put("position_beats", m.position().get());
            out.add(e);
        }
        return out;
    }

    public Map<String, Object> goToCueMarker(Integer index, String name, boolean quantized) throws BitwigApiException {
        for (int i = 0; i < cueMarkers.getSizeOfBank(); i++) {
            CueMarker m = cueMarkers.getItemAt(i);
            if (m.exists().get() && ((index != null && index == i) || (name != null && name.equalsIgnoreCase(m.getName().get())))) {
                m.launch(quantized);
                return Map.of("cue_marker", m.getName().get(), "position_beats", m.position().get());
            }
        }
        throw new BitwigApiException(ErrorCode.INVALID_PARAMETER, "goToCueMarker", "No such cue marker");
    }

    public Map<String, Object> setArrangement(Double loopStartBeats, Double loopLengthBeats, String timeSignature) {
        if (loopStartBeats != null) {
            transport.arrangerLoopStart().set(loopStartBeats);
        }
        if (loopLengthBeats != null) {
            transport.arrangerLoopDuration().set(loopLengthBeats);
        }
        if (timeSignature != null) {
            transport.timeSignature().set(timeSignature);
        }
        sleep(timeoutMs > 0 ? 120 : 0);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loop_start_beats", transport.arrangerLoopStart().get());
        m.put("loop_length_beats", transport.arrangerLoopDuration().get());
        m.put("time_signature", transport.timeSignature().get());
        return m;
    }

    // ========================================
    // Scenes
    // ========================================

    public Map<String, Object> renameScene(int sceneIndex, String name) throws BitwigApiException {
        if (sceneIndex < 0 || sceneIndex >= scenes.getSizeOfBank() || !scenes.getItemAt(sceneIndex).exists().get()) {
            throw new BitwigApiException(ErrorCode.SCENE_NOT_FOUND, "renameScene", "No scene at index " + sceneIndex);
        }
        Scene scene = scenes.getItemAt(sceneIndex);
        scene.name().set(name);
        waitFor(() -> name.equals(scene.name().get()));
        return Map.of("scene_index", sceneIndex, "name", scene.name().get());
    }

    // ========================================
    // Tracks: stop, return to arrangement, audition notes
    // ========================================

    public Map<String, Object> stopTrack(int trackIndex, boolean returnToArrangement) {
        Track track = trackBank.getItemAt(trackIndex);
        if (returnToArrangement) {
            track.returnToArrangement();
        } else {
            track.stop();
        }
        return Map.of("track", track.name().get(), "action", returnToArrangement ? "return_to_arrangement" : "stop");
    }

    /** A note to audition: MIDI pitch, velocity, start offset and length in milliseconds. */
    public record AuditionNote(int pitch, int velocity, long startMs, long durationMs) {}

    /**
     * Plays notes into a track's instrument right now, without a clip. Each note is started and stopped
     * on Bitwig's control thread by scheduled tasks, so the call returns immediately.
     */
    public Map<String, Object> playNotes(int trackIndex, List<AuditionNote> notes) {
        Track track = trackBank.getItemAt(trackIndex);
        long end = 0;
        for (AuditionNote n : notes) {
            int pitch = Math.max(0, Math.min(127, n.pitch()));
            int velocity = Math.max(1, Math.min(127, n.velocity()));
            host.scheduleTask(() -> track.startNote(pitch, velocity), Math.max(0, n.startMs()));
            host.scheduleTask(() -> track.stopNote(pitch, 0), Math.max(0, n.startMs()) + Math.max(1, n.durationMs()));
            end = Math.max(end, n.startMs() + n.durationMs());
        }
        return Map.of("track", track.name().get(), "notes", notes.size(), "total_ms", end);
    }

    // ========================================
    // Launcher clips: read notes and properties
    // ========================================

    private ClipLauncherSlot focusClip(int trackIndex, int slotIndex, String operation) throws BitwigApiException {
        Track track = trackBank.getItemAt(trackIndex);
        ClipLauncherSlotBank slots = track.clipLauncherSlotBank();
        if (slotIndex < 0 || slotIndex >= slots.getSizeOfBank()) {
            throw new BitwigApiException(ErrorCode.INVALID_RANGE, operation, "slot_index must be 0.." + (slots.getSizeOfBank() - 1));
        }
        ClipLauncherSlot slot = slots.getItemAt(slotIndex);
        if (!slot.hasContent().get()) {
            throw new BitwigApiException(ErrorCode.CLIP_NOT_FOUND, operation, "Slot " + slotIndex + " is empty");
        }
        clipTrack.selectChannel(track);
        waitFor(() -> track.name().get().equals(clipTrack.name().get()));
        steps.clear();
        slot.select();
        if (!waitFor(() -> clip.exists().get())) {
            throw new BitwigApiException(ErrorCode.BITWIG_TIMEOUT, operation, "Could not focus the clip");
        }
        sleep(timeoutMs > 0 ? Constants.CLIP_LOAD_MS : 0);
        return slot;
    }

    /** Reads the notes of a launcher clip (beats from the clip start, on a 1/16-beat grid). */
    public Map<String, Object> readNotes(int trackIndex, int slotIndex) throws BitwigApiException {
        focusClip(trackIndex, slotIndex, "readNotes");
        List<NoteStep> found = new ArrayList<>(steps.values());
        found.sort(Comparator.comparingInt(NoteStep::x).thenComparingInt(NoteStep::y));
        List<Map<String, Object>> notes = new ArrayList<>();
        for (NoteStep s : found) {
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("start", s.x() * Constants.CLIP_STEP_BEATS);
            n.put("pitch", s.y());
            n.put("velocity", (int) Math.round(s.velocity() * 127));
            n.put("duration", s.duration());
            n.put("channel", s.channel());
            notes.add(n);
        }
        Map<String, Object> result = clipProperties();
        result.put("track_index", trackIndex);
        result.put("slot_index", slotIndex);
        result.put("note_count", notes.size());
        result.put("notes", notes);
        return result;
    }

    public Map<String, Object> setClip(int trackIndex, int slotIndex, String name, Double loopStartBeats,
                                       Double loopLengthBeats) throws BitwigApiException {
        focusClip(trackIndex, slotIndex, "setClip");
        if (name != null) {
            clip.setName(name);
        }
        if (loopStartBeats != null) {
            clip.getLoopStart().set(loopStartBeats);
        }
        if (loopLengthBeats != null) {
            clip.getLoopLength().set(loopLengthBeats);
        }
        sleep(timeoutMs > 0 ? 120 : 0);
        Map<String, Object> result = clipProperties();
        result.put("track_index", trackIndex);
        result.put("slot_index", slotIndex);
        return result;
    }

    private Map<String, Object> clipProperties() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("loop_start_beats", clip.getLoopStart().get());
        m.put("loop_length_beats", clip.getLoopLength().get());
        m.put("play_start_beats", clip.getPlayStart().get());
        m.put("play_stop_beats", clip.getPlayStop().get());
        return m;
    }
}
