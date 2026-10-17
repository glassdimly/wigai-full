package io.github.fabb.wigai.bitwig;

import com.bitwig.extension.controller.api.*;
import io.github.fabb.wigai.common.Logger;
import io.github.fabb.wigai.common.error.BitwigApiException;
import io.github.fabb.wigai.common.error.ErrorCode;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Facade for operations that change the project: creating tracks, inserting and editing devices,
 * mixer settings, clips, notes and application actions.
 *
 * All Bitwig objects are created in the constructor (Bitwig only allows that during init()).
 * Bitwig applies changes asynchronously, so mutating methods poll for the result before returning.
 * Device parameters are reached through a private cursor track and cursor device that do not
 * follow the user's selection, so reading or editing a device never moves the user's focus.
 */
public class BitwigEditFacade {

    private static final class Constants {
        public static final int REMOTE_CONTROL_COUNT = 8;
        public static final int CLIP_GRID_WIDTH = 16384;
        public static final int CLIP_GRID_HEIGHT = 128;
        public static final double CLIP_STEP_BEATS = 1.0 / 16.0; // 64th-note resolution
        public static final long POLL_INTERVAL_MS = 20;
        public static final long DEFAULT_TIMEOUT_MS = 2000;
        public static final long SETTLE_MS = 120;
        public static final String DEVICE_LIST_RESOURCE = "/bitwig-devices.txt";

        private Constants() {}
    }

    private final ControllerHost host;
    private final Application application;
    private final Transport transport;
    private final Logger logger;
    private final TrackBank trackBank;
    private final List<DeviceBank> trackDeviceBanks;
    private final CursorTrack editTrack;
    private final PinnableCursorDevice editDevice;
    private final CursorRemoteControlsPage editPage;
    private final Clip editClip;
    private final Map<String, UUID> bitwigDevices;
    private final long timeoutMs;

    public BitwigEditFacade(ControllerHost host, Logger logger, Application application, Transport transport,
                            TrackBank trackBank, List<DeviceBank> trackDeviceBanks) {
        this(host, logger, application, transport, trackBank, trackDeviceBanks, Constants.DEFAULT_TIMEOUT_MS);
    }

    BitwigEditFacade(ControllerHost host, Logger logger, Application application, Transport transport,
                     TrackBank trackBank, List<DeviceBank> trackDeviceBanks, long timeoutMs) {
        this.host = host;
        this.logger = logger;
        this.application = application;
        this.transport = transport;
        this.trackBank = trackBank;
        this.trackDeviceBanks = trackDeviceBanks;
        this.timeoutMs = timeoutMs;
        this.bitwigDevices = loadBitwigDevices();

        this.editTrack = host.createCursorTrack("WIGAI_EDIT_TRACK", "WigAI edit track", 0, 0, false);
        this.editDevice = editTrack.createCursorDevice("WIGAI_EDIT_DEVICE", "WigAI edit device", 0,
            CursorDeviceFollowMode.FIRST_DEVICE);
        this.editPage = editDevice.createCursorRemoteControlsPage(Constants.REMOTE_CONTROL_COUNT);
        this.editClip = editTrack.createLauncherCursorClip(Constants.CLIP_GRID_WIDTH, Constants.CLIP_GRID_HEIGHT);

        editTrack.position().markInterested();
        editTrack.name().markInterested();
        editDevice.exists().markInterested();
        editDevice.name().markInterested();
        editDevice.position().markInterested();
        editPage.pageNames().markInterested();
        editPage.selectedPageIndex().markInterested();
        for (int i = 0; i < editPage.getParameterCount(); i++) {
            RemoteControl p = editPage.getParameter(i);
            p.exists().markInterested();
            p.name().markInterested();
            p.value().markInterested();
            p.displayedValue().markInterested();
        }
        editClip.exists().markInterested();
        editClip.getLoopLength().markInterested();
        editClip.setStepSize(Constants.CLIP_STEP_BEATS);
        editClip.scrollToKey(0);
        editClip.scrollToStep(0);

        for (int t = 0; t < trackBank.getSizeOfBank(); t++) {
            Track track = trackBank.getItemAt(t);
            track.position().markInterested();
            track.trackType().markInterested();
            track.color().markInterested();
            track.isActivated().markInterested();
            track.solo().markInterested();
            track.arm().markInterested();
            track.mute().markInterested();
            track.volume().value().markInterested();
            track.volume().displayedValue().markInterested();
            track.pan().value().markInterested();
            track.pan().displayedValue().markInterested();
            track.monitorMode().markInterested();
            ClipLauncherSlotBank slots = track.clipLauncherSlotBank();
            for (int s = 0; s < slots.getSizeOfBank(); s++) {
                slots.getItemAt(s).hasContent().markInterested();
            }
            DeviceBank bank = trackDeviceBanks.get(t);
            for (int d = 0; d < bank.getSizeOfBank(); d++) {
                bank.getItemAt(d).position().markInterested();
                bank.getItemAt(d).presetName().markInterested();
            }
        }
        transport.tempo().value().markInterested();
        transport.tempo().displayedValue().markInterested();
        transport.getPosition().markInterested();
        transport.isArrangerLoopEnabled().markInterested();
        transport.isMetronomeEnabled().markInterested();
        transport.isArrangerRecordEnabled().markInterested();
        transport.isArrangerOverdubEnabled().markInterested();
        transport.isAutomationOverrideActive().markInterested();
        transport.timeSignature().numerator().markInterested();
        transport.timeSignature().denominator().markInterested();
    }

    // ========================================
    // Lookup helpers
    // ========================================

    /** Resolves a track by index or exact name to its index in the flat track bank. */
    public int resolveTrackIndex(Integer trackIndex, String trackName, String operation) throws BitwigApiException {
        if (trackIndex != null) {
            if (trackIndex < 0 || trackIndex >= trackBank.getSizeOfBank() || !trackBank.getItemAt(trackIndex).exists().get()) {
                throw new BitwigApiException(ErrorCode.TRACK_NOT_FOUND, operation, "No track at index " + trackIndex);
            }
            return trackIndex;
        }
        if (trackName != null) {
            for (int i = 0; i < trackBank.getSizeOfBank(); i++) {
                Track t = trackBank.getItemAt(i);
                if (t.exists().get() && trackName.equals(t.name().get())) {
                    return i;
                }
            }
            throw new BitwigApiException(ErrorCode.TRACK_NOT_FOUND, operation, "No track named '" + trackName + "'");
        }
        throw new BitwigApiException(ErrorCode.MISSING_REQUIRED_PARAMETER, operation, "Provide track_index or track_name");
    }

    private Device resolveDevice(int trackIndex, int deviceIndex, String operation) throws BitwigApiException {
        DeviceBank bank = trackDeviceBanks.get(trackIndex);
        if (deviceIndex < 0 || deviceIndex >= bank.getSizeOfBank() || !bank.getItemAt(deviceIndex).exists().get()) {
            throw new BitwigApiException(ErrorCode.DEVICE_NOT_FOUND, operation,
                "No device at index " + deviceIndex + " on track " + trackIndex);
        }
        return bank.getItemAt(deviceIndex);
    }

    /** Number of tracks in the flat track list, not counting the master track (which Bitwig lists last). */
    public int countTracks() {
        int n = 0;
        for (int i = 0; i < trackBank.getSizeOfBank(); i++) {
            Track t = trackBank.getItemAt(i);
            if (t.exists().get() && !isMaster(t)) {
                n = i + 1;
            }
        }
        return n;
    }

    static boolean isMaster(Track track) {
        return "Master".equalsIgnoreCase(track.trackType().get());
    }

    public int countDevices(int trackIndex) {
        DeviceBank bank = trackDeviceBanks.get(trackIndex);
        int n = 0;
        for (int i = 0; i < bank.getSizeOfBank(); i++) {
            if (bank.getItemAt(i).exists().get()) {
                n = i + 1;
            }
        }
        return n;
    }

    private List<Map<String, Object>> deviceSummaries(int trackIndex) {
        List<Map<String, Object>> out = new ArrayList<>();
        DeviceBank bank = trackDeviceBanks.get(trackIndex);
        for (int i = 0; i < bank.getSizeOfBank(); i++) {
            Device d = bank.getItemAt(i);
            if (!d.exists().get()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("name", d.name().get());
            m.put("enabled", d.isEnabled().get());
            out.add(m);
        }
        return out;
    }

    /**
     * Runs a change on Bitwig's control thread and waits for it to have run. Some setters (notably
     * remote-control values) are dropped when called from the MCP request thread.
     */
    void onHost(Runnable change) {
        if (timeoutMs <= 0) {
            change.run();
            return;
        }
        CountDownLatch done = new CountDownLatch(1);
        host.scheduleTask(() -> {
            try {
                change.run();
            } finally {
                done.countDown();
            }
        }, 0);
        try {
            done.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    boolean waitFor(BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            sleep(Constants.POLL_INTERVAL_MS);
        }
        return condition.getAsBoolean();
    }

    private void settle() {
        if (timeoutMs > 0) {
            sleep(Constants.SETTLE_MS);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ========================================
    // Tracks
    // ========================================

    public Map<String, Object> createTrack(String type, Integer position, String name) throws BitwigApiException {
        final String operation = "createTrack";
        int before = countTracks();
        int pos = position == null ? before : position;
        if (pos < 0 || pos > before) {
            throw new BitwigApiException(ErrorCode.INVALID_RANGE, operation, "position must be 0.." + before);
        }
        switch (type) {
            case "audio" -> application.createAudioTrack(pos);
            case "instrument" -> application.createInstrumentTrack(pos);
            case "effect" -> application.createEffectTrack(pos);
            default -> throw new BitwigApiException(ErrorCode.INVALID_PARAMETER, operation,
                "type must be audio, instrument or effect");
        }
        if (!"effect".equals(type) && !waitFor(() -> countTracks() > before)) {
            throw new BitwigApiException(ErrorCode.BITWIG_TIMEOUT, operation, "Track was not created in time");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        if (!"effect".equals(type)) {
            Track created = trackBank.getItemAt(pos);
            if (!created.exists().get() || isMaster(created)) {
                throw new BitwigApiException(ErrorCode.OPERATION_FAILED, operation,
                    "Track was created but could not be located at index " + pos + "; not renaming anything");
            }
            if (name != null) {
                created.name().set(name);
                waitFor(() -> name.equals(created.name().get()));
            }
            result.put("track_index", pos);
            result.put("name", created.name().get());
        } else {
            result.put("note", "Effect tracks live outside the main track list; use list_tracks type=effect to find it");
        }
        return result;
    }

    public Map<String, Object> deleteTrack(int trackIndex) throws BitwigApiException {
        Track track = trackBank.getItemAt(trackIndex);
        if (isMaster(track)) {
            throw new BitwigApiException(ErrorCode.INVALID_PARAMETER, "deleteTrack", "Refusing to delete the master track");
        }
        String name = track.name().get();
        int before = countTracks();
        track.deleteObject();
        waitFor(() -> countTracks() < before);
        return Map.of("deleted_track", name, "track_index", trackIndex);
    }

    public Map<String, Object> duplicateTrack(int trackIndex) throws BitwigApiException {
        Track track = trackBank.getItemAt(trackIndex);
        if (isMaster(track)) {
            throw new BitwigApiException(ErrorCode.INVALID_PARAMETER, "duplicateTrack", "The master track cannot be duplicated");
        }
        int before = countTracks();
        track.duplicate();
        waitFor(() -> countTracks() > before);
        return Map.of("duplicated_track", track.name().get(), "new_track_index", trackIndex + 1);
    }

    /**
     * Applies any non-null mixer/track setting. volumeDb is matched against Bitwig's displayed dB value,
     * so it is exact regardless of the fader curve.
     */
    public Map<String, Object> setTrackProperties(int trackIndex, String name, Double volume, Double volumeDb, Double pan,
                                                  Boolean mute, Boolean solo, Boolean arm, Boolean activated,
                                                  String monitorMode, String color) throws BitwigApiException {
        final String operation = "setTrackProperties";
        Track track = trackBank.getItemAt(trackIndex);
        if (name != null) {
            track.name().set(name);
        }
        if (volume != null) {
            track.volume().value().set(volume);
        }
        if (volumeDb != null) {
            setVolumeDb(track, volumeDb);
        }
        if (pan != null) {
            track.pan().value().set((pan + 1.0) / 2.0);
        }
        if (mute != null) {
            track.mute().set(mute);
        }
        if (solo != null) {
            track.solo().set(solo);
        }
        if (arm != null) {
            track.arm().set(arm);
        }
        if (activated != null) {
            track.isActivated().set(activated);
        }
        if (monitorMode != null) {
            track.monitorMode().set(monitorMode.toUpperCase(Locale.ROOT));
        }
        if (color != null) {
            int[] rgb = parseColor(color, operation);
            track.color().set(rgb[0] / 255f, rgb[1] / 255f, rgb[2] / 255f);
        }
        settle();
        return trackMixerState(trackIndex);
    }

    private void setVolumeDb(Track track, double targetDb) {
        Parameter vol = track.volume();
        double lo = 0.0;
        double hi = 1.0;
        for (int i = 0; i < 14; i++) {
            double mid = (lo + hi) / 2;
            String before = vol.displayedValue().get();
            onHost(() -> vol.value().set(mid));
            waitFor(() -> Math.abs(vol.value().get() - mid) < 1e-6);
            waitForChange(() -> vol.displayedValue().get(), before);
            Double shown = parseDb(vol.displayedValue().get());
            if (shown == null || shown < targetDb) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        double finalValue = (lo + hi) / 2;
        onHost(() -> vol.value().set(finalValue));
        waitFor(() -> Math.abs(vol.value().get() - finalValue) < 1e-6);
    }

    /** Waits briefly for a displayed string to change; displays update after the raw value. */
    private void waitForChange(java.util.function.Supplier<String> display, String before) {
        if (timeoutMs <= 0) {
            return;
        }
        long deadline = System.currentTimeMillis() + 300;
        while (System.currentTimeMillis() < deadline && java.util.Objects.equals(display.get(), before)) {
            sleep(Constants.POLL_INTERVAL_MS);
        }
        sleep(Constants.POLL_INTERVAL_MS);
    }

    static Double parseDb(String display) {
        if (display == null) {
            return null;
        }
        String s = display.replace("dB", "").replace('−', '-').trim();
        if (s.toLowerCase(Locale.ROOT).contains("inf")) {
            return Double.NEGATIVE_INFINITY;
        }
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static int[] parseColor(String color, String operation) throws BitwigApiException {
        String hex = color.startsWith("#") ? color.substring(1) : color;
        if (hex.length() != 6) {
            throw new BitwigApiException(ErrorCode.INVALID_PARAMETER, operation, "color must be #RRGGBB");
        }
        try {
            return new int[]{
                Integer.parseInt(hex.substring(0, 2), 16),
                Integer.parseInt(hex.substring(2, 4), 16),
                Integer.parseInt(hex.substring(4, 6), 16)
            };
        } catch (NumberFormatException e) {
            throw new BitwigApiException(ErrorCode.INVALID_PARAMETER, operation, "color must be #RRGGBB");
        }
    }

    public Map<String, Object> trackMixerState(int trackIndex) {
        Track track = trackBank.getItemAt(trackIndex);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("track_index", trackIndex);
        m.put("name", track.name().get());
        m.put("volume", track.volume().value().get());
        m.put("volume_display", track.volume().displayedValue().get());
        m.put("pan", track.pan().value().get() * 2.0 - 1.0);
        m.put("pan_display", track.pan().displayedValue().get());
        m.put("mute", track.mute().get());
        m.put("solo", track.solo().get());
        m.put("arm", track.arm().get());
        m.put("activated", track.isActivated().get());
        m.put("monitor_mode", track.monitorMode().get());
        m.put("is_master", isMaster(track));
        List<Map<String, Object>> sends = new ArrayList<>();
        SendBank sendBank = sendBankOrNull(track);
        for (int i = 0; sendBank != null && i < sendBank.getSizeOfBank(); i++) {
            Send send = sendBank.getItemAt(i);
            if (!send.exists().get()) {
                continue;
            }
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("index", i);
            s.put("name", send.name().get());
            s.put("value", send.value().get());
            s.put("display", send.displayedValue().get());
            s.put("enabled", send.isEnabled().get());
            sends.add(s);
        }
        m.put("sends", sends);
        return m;
    }

    /** The track bank is created without sends, in which case Bitwig throws on sendBank(). */
    private static SendBank sendBankOrNull(Track track) {
        try {
            SendBank bank = track.sendBank();
            bank.getSizeOfBank();
            return bank;
        } catch (RuntimeException e) {
            return null;
        }
    }

    public Map<String, Object> setSend(int trackIndex, int sendIndex, Double value, Boolean enabled) throws BitwigApiException {
        Track track = trackBank.getItemAt(trackIndex);
        SendBank sendBank = sendBankOrNull(track);
        if (sendBank == null) {
            throw new BitwigApiException(ErrorCode.RESOURCE_UNAVAILABLE, "setSend", "Sends are not available");
        }
        if (sendIndex < 0 || sendIndex >= sendBank.getSizeOfBank() || !sendBank.getItemAt(sendIndex).exists().get()) {
            throw new BitwigApiException(ErrorCode.INVALID_RANGE, "setSend", "No send at index " + sendIndex);
        }
        Send send = sendBank.getItemAt(sendIndex);
        if (value != null) {
            send.value().set(value);
        }
        if (enabled != null) {
            send.isEnabled().set(enabled);
        }
        settle();
        return trackMixerState(trackIndex);
    }

    // ========================================
    // Devices
    // ========================================

    public List<String> listBitwigDeviceNames() {
        return new ArrayList<>(bitwigDevices.keySet());
    }

    private InsertionPoint insertionPoint(int trackIndex, String position, Integer afterDeviceIndex, String operation)
            throws BitwigApiException {
        Track track = trackBank.getItemAt(trackIndex);
        if (afterDeviceIndex != null) {
            return resolveDevice(trackIndex, afterDeviceIndex, operation).afterDeviceInsertionPoint();
        }
        if (position == null || "end".equals(position)) {
            return track.endOfDeviceChainInsertionPoint();
        }
        if ("start".equals(position)) {
            return track.startOfDeviceChainInsertionPoint();
        }
        throw new BitwigApiException(ErrorCode.INVALID_PARAMETER, operation, "position must be 'start' or 'end'");
    }

    public Map<String, Object> insertDevice(int trackIndex, String bitwigDevice, String clapId, String vst3Id,
                                            String position, Integer afterDeviceIndex) throws BitwigApiException {
        final String operation = "insertDevice";
        InsertionPoint ip = insertionPoint(trackIndex, position, afterDeviceIndex, operation);
        int before = countDevices(trackIndex);
        if (bitwigDevice != null) {
            UUID id = findBitwigDevice(bitwigDevice);
            if (id == null) {
                throw new BitwigApiException(ErrorCode.DEVICE_NOT_FOUND, operation,
                    "Unknown Bitwig device '" + bitwigDevice + "'. Use list_bitwig_devices.");
            }
            ip.insertBitwigDevice(id);
        } else if (clapId != null) {
            ip.insertCLAPDevice(clapId);
        } else if (vst3Id != null) {
            ip.insertVST3Device(vst3Id);
        } else {
            throw new BitwigApiException(ErrorCode.MISSING_REQUIRED_PARAMETER, operation,
                "Provide bitwig_device, clap_id or vst3_id");
        }
        boolean added = waitFor(() -> countDevices(trackIndex) > before);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inserted", added);
        result.put("devices", deviceSummaries(trackIndex));
        return result;
    }

    UUID findBitwigDevice(String name) {
        for (Map.Entry<String, UUID> e : bitwigDevices.entrySet()) {
            if (e.getKey().equalsIgnoreCase(name.trim())) {
                return e.getValue();
            }
        }
        return null;
    }

    public Map<String, Object> copyOrMoveDevice(int fromTrack, int fromDevice, int toTrack, String position,
                                                Integer afterDeviceIndex, boolean move) throws BitwigApiException {
        final String operation = move ? "moveDevice" : "copyDevice";
        Device source = resolveDevice(fromTrack, fromDevice, operation);
        InsertionPoint ip = insertionPoint(toTrack, position, afterDeviceIndex, operation);
        int before = countDevices(toTrack);
        if (move) {
            ip.moveDevices(source);
        } else {
            ip.copyDevices(source);
        }
        if (fromTrack != toTrack) {
            waitFor(() -> countDevices(toTrack) > before);
        } else {
            settle();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("target_track_devices", deviceSummaries(toTrack));
        return result;
    }

    public Map<String, Object> deleteDevice(int trackIndex, int deviceIndex) throws BitwigApiException {
        Device device = resolveDevice(trackIndex, deviceIndex, "deleteDevice");
        String name = device.name().get();
        int before = countDevices(trackIndex);
        device.deleteObject();
        waitFor(() -> countDevices(trackIndex) < before);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted_device", name);
        result.put("devices", deviceSummaries(trackIndex));
        return result;
    }

    public Map<String, Object> setDeviceEnabled(int trackIndex, int deviceIndex, boolean enabled) throws BitwigApiException {
        Device device = resolveDevice(trackIndex, deviceIndex, "setDeviceEnabled");
        device.isEnabled().set(enabled);
        waitFor(() -> device.isEnabled().get() == enabled);
        return Map.of("device", device.name().get(), "enabled", device.isEnabled().get());
    }

    public Map<String, Object> setDeviceWindowOpen(int trackIndex, int deviceIndex, boolean open) throws BitwigApiException {
        Device device = resolveDevice(trackIndex, deviceIndex, "setDeviceWindowOpen");
        device.isWindowOpen().set(open);
        return Map.of("device", device.name().get(), "window_open", open);
    }

    /** Points the private edit cursor at a device without touching the user's selection. */
    private void focusDevice(int trackIndex, int deviceIndex, String operation) throws BitwigApiException {
        Track track = trackBank.getItemAt(trackIndex);
        Device device = resolveDevice(trackIndex, deviceIndex, operation);
        String expectedName = device.name().get();
        editTrack.selectChannel(track);
        waitFor(() -> track.name().get().equals(editTrack.name().get()));
        editDevice.selectDevice(device);
        boolean ok = waitFor(() -> editDevice.exists().get()
            && editDevice.position().get() == deviceIndex
            && expectedName.equals(editDevice.name().get()));
        if (!ok) {
            throw new BitwigApiException(ErrorCode.BITWIG_TIMEOUT, operation,
                "Could not focus device " + deviceIndex + " on track " + trackIndex);
        }
        settle();
    }

    private boolean selectPage(int page) {
        if (editPage.selectedPageIndex().get() == page) {
            return true;
        }
        editPage.selectedPageIndex().set(page);
        boolean ok = waitFor(() -> editPage.selectedPageIndex().get() == page);
        settle();
        return ok;
    }

    private List<Map<String, Object>> readPage() {
        List<Map<String, Object>> params = new ArrayList<>();
        for (int i = 0; i < editPage.getParameterCount(); i++) {
            RemoteControl p = editPage.getParameter(i);
            if (!p.exists().get()) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", i);
            m.put("name", p.name().get());
            m.put("value", p.value().get());
            m.put("display", p.displayedValue().get());
            params.add(m);
        }
        return params;
    }

    /** Reads one page, or every page when page is null. */
    public Map<String, Object> getDeviceParameters(int trackIndex, int deviceIndex, Integer page) throws BitwigApiException {
        final String operation = "getDeviceParameters";
        focusDevice(trackIndex, deviceIndex, operation);
        String[] pageNames = editPage.pageNames().get();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("track_index", trackIndex);
        result.put("device_index", deviceIndex);
        result.put("device", editDevice.name().get());
        result.put("page_names", pageNames);
        List<Map<String, Object>> pages = new ArrayList<>();
        int first = page == null ? 0 : page;
        int last = page == null ? pageNames.length - 1 : page;
        if (page != null && (page < 0 || page >= Math.max(1, pageNames.length))) {
            throw new BitwigApiException(ErrorCode.INVALID_RANGE, operation, "page must be 0.." + (pageNames.length - 1));
        }
        for (int p = first; p <= Math.max(first, last); p++) {
            if (pageNames.length > 0) {
                selectPage(p);
            }
            Map<String, Object> pm = new LinkedHashMap<>();
            pm.put("page", p);
            pm.put("page_name", pageNames.length > p ? pageNames[p] : "");
            pm.put("parameters", readPage());
            pages.add(pm);
        }
        result.put("pages", pages);
        return result;
    }

    /** A single parameter assignment: by page + index, or by name (searched across all pages). */
    public record ParamAssignment(Integer page, Integer index, String name, double value) {}

    public Map<String, Object> setDeviceParameters(int trackIndex, int deviceIndex, List<ParamAssignment> assignments)
            throws BitwigApiException {
        final String operation = "setDeviceParameters";
        focusDevice(trackIndex, deviceIndex, operation);
        String[] pageNames = editPage.pageNames().get();
        int pageCount = Math.max(1, pageNames.length);
        List<Map<String, Object>> results = new ArrayList<>();
        for (ParamAssignment a : assignments) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("request", a.name() != null ? a.name() : ("page " + a.page() + " index " + a.index()));
            if (a.value() < 0.0 || a.value() > 1.0) {
                r.put("status", "error");
                r.put("message", "value must be 0.0-1.0");
                results.add(r);
                continue;
            }
            RemoteControl target = null;
            int foundPage = -1;
            if (a.name() != null) {
                for (int p = 0; p < pageCount && target == null; p++) {
                    selectPage(p);
                    for (int i = 0; i < editPage.getParameterCount(); i++) {
                        RemoteControl rc = editPage.getParameter(i);
                        if (rc.exists().get() && a.name().equalsIgnoreCase(rc.name().get())) {
                            target = rc;
                            foundPage = p;
                            break;
                        }
                    }
                }
            } else if (a.index() != null) {
                int p = a.page() == null ? 0 : a.page();
                if (p >= 0 && p < pageCount && a.index() >= 0 && a.index() < editPage.getParameterCount()) {
                    selectPage(p);
                    RemoteControl rc = editPage.getParameter(a.index());
                    if (rc.exists().get()) {
                        target = rc;
                        foundPage = p;
                    }
                }
            }
            if (target == null) {
                r.put("status", "error");
                r.put("message", "Parameter not found");
                results.add(r);
                continue;
            }
            RemoteControl t = target;
            onHost(() -> t.value().set(a.value()));
            waitFor(() -> Math.abs(t.value().get() - a.value()) < 1e-4);
            settle();
            r.put("status", "success");
            r.put("page", foundPage);
            r.put("name", t.name().get());
            r.put("value", t.value().get());
            r.put("display", t.displayedValue().get());
            results.add(r);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("device", editDevice.name().get());
        result.put("results", results);
        return result;
    }

    // ========================================
    // Clips and files
    // ========================================

    public Map<String, Object> insertFile(int trackIndex, Integer slotIndex, String path) throws BitwigApiException {
        final String operation = "insertFile";
        if (path == null || !Files.isRegularFile(Path.of(path))) {
            throw new BitwigApiException(ErrorCode.INVALID_PARAMETER, operation, "File not found: " + path);
        }
        Track track = trackBank.getItemAt(trackIndex);
        Map<String, Object> result = new LinkedHashMap<>();
        if (slotIndex != null) {
            ClipLauncherSlot slot = slotAt(track, slotIndex, operation);
            slot.replaceInsertionPoint().insertFile(path);
            boolean ok = waitFor(() -> slot.hasContent().get());
            result.put("track_index", trackIndex);
            result.put("slot_index", slotIndex);
            result.put("inserted", ok);
        } else {
            int before = countTracks();
            track.afterTrackInsertionPoint().insertFile(path);
            boolean ok = waitFor(() -> countTracks() > before);
            result.put("new_track_index", ok ? trackIndex + 1 : null);
            result.put("inserted", ok);
        }
        return result;
    }

    private ClipLauncherSlot slotAt(Track track, int slotIndex, String operation) throws BitwigApiException {
        ClipLauncherSlotBank slots = track.clipLauncherSlotBank();
        if (slotIndex < 0 || slotIndex >= slots.getSizeOfBank()) {
            throw new BitwigApiException(ErrorCode.INVALID_RANGE, operation, "slot_index must be 0.." + (slots.getSizeOfBank() - 1));
        }
        return slots.getItemAt(slotIndex);
    }

    public Map<String, Object> createClip(int trackIndex, int slotIndex, int lengthBeats) throws BitwigApiException {
        final String operation = "createClip";
        ClipLauncherSlot slot = slotAt(trackBank.getItemAt(trackIndex), slotIndex, operation);
        if (slot.hasContent().get()) {
            throw new BitwigApiException(ErrorCode.INVALID_PARAMETER, operation, "Slot already has a clip");
        }
        slot.createEmptyClip(lengthBeats);
        boolean ok = waitFor(() -> slot.hasContent().get());
        return Map.of("track_index", trackIndex, "slot_index", slotIndex, "length_beats", lengthBeats, "created", ok);
    }

    public Map<String, Object> deleteClip(int trackIndex, int slotIndex) throws BitwigApiException {
        ClipLauncherSlot slot = slotAt(trackBank.getItemAt(trackIndex), slotIndex, "deleteClip");
        slot.deleteObject();
        waitFor(() -> !slot.hasContent().get());
        return Map.of("track_index", trackIndex, "slot_index", slotIndex, "deleted", !slot.hasContent().get());
    }

    /** A MIDI note in beats from the clip start. */
    public record Note(double startBeats, int pitch, int velocity, double durationBeats) {}

    public Map<String, Object> writeNotes(int trackIndex, int slotIndex, List<Note> notes, boolean clearFirst)
            throws BitwigApiException {
        final String operation = "writeNotes";
        Track track = trackBank.getItemAt(trackIndex);
        ClipLauncherSlot slot = slotAt(track, slotIndex, operation);
        if (!slot.hasContent().get()) {
            throw new BitwigApiException(ErrorCode.CLIP_NOT_FOUND, operation, "Slot is empty; create_clip first");
        }
        editTrack.selectChannel(track);
        waitFor(() -> track.name().get().equals(editTrack.name().get()));
        slot.select();
        if (!waitFor(() -> editClip.exists().get())) {
            throw new BitwigApiException(ErrorCode.BITWIG_TIMEOUT, operation, "Could not focus the clip");
        }
        settle();
        if (clearFirst) {
            editClip.clearSteps();
        }
        int written = 0;
        for (Note n : notes) {
            int step = (int) Math.round(n.startBeats() / Constants.CLIP_STEP_BEATS);
            if (step < 0 || step >= Constants.CLIP_GRID_WIDTH || n.pitch() < 0 || n.pitch() > 127) {
                continue;
            }
            editClip.setStep(step, n.pitch(), Math.max(1, Math.min(127, n.velocity())), n.durationBeats());
            written++;
        }
        return Map.of("track_index", trackIndex, "slot_index", slotIndex, "notes_written", written);
    }

    // ========================================
    // Transport
    // ========================================

    public Map<String, Object> setTransport(Double tempoBpm, Double positionBeats, Boolean loop, Boolean metronome,
                                            Boolean record, Boolean overdub) {
        if (tempoBpm != null) {
            transport.tempo().setRaw(tempoBpm);
        }
        if (positionBeats != null) {
            transport.getPosition().set(positionBeats);
        }
        if (loop != null) {
            transport.isArrangerLoopEnabled().set(loop);
        }
        if (metronome != null) {
            transport.isMetronomeEnabled().set(metronome);
        }
        if (record != null) {
            transport.isArrangerRecordEnabled().set(record);
        }
        if (overdub != null) {
            transport.isArrangerOverdubEnabled().set(overdub);
        }
        settle();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tempo", transport.tempo().displayedValue().get());
        m.put("position_beats", transport.getPosition().get());
        m.put("loop", transport.isArrangerLoopEnabled().get());
        m.put("metronome", transport.isMetronomeEnabled().get());
        m.put("record", transport.isArrangerRecordEnabled().get());
        m.put("overdub", transport.isArrangerOverdubEnabled().get());
        return m;
    }

    // ========================================
    // Application actions
    // ========================================

    public List<Map<String, Object>> listActions(String filter) {
        List<Map<String, Object>> out = new ArrayList<>();
        String f = filter == null ? null : filter.toLowerCase(Locale.ROOT);
        for (Action a : application.getActions()) {
            String id = a.getId();
            String name = a.getName();
            String cat = a.getCategory() != null ? a.getCategory().getName() : "";
            if (f != null && !(id + " " + name + " " + cat).toLowerCase(Locale.ROOT).contains(f)) {
                continue;
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("name", name);
            m.put("category", cat);
            out.add(m);
        }
        return out;
    }

    public Map<String, Object> invokeAction(String id) throws BitwigApiException {
        Action action = application.getAction(id);
        if (action == null) {
            throw new BitwigApiException(ErrorCode.INVALID_PARAMETER, "invokeAction", "Unknown action id '" + id + "'. Use list_actions.");
        }
        action.invoke();
        return Map.of("invoked", id, "name", action.getName());
    }

    public Map<String, Object> undo(boolean redo) {
        if (redo) {
            application.redo();
        } else {
            application.undo();
        }
        settle();
        return Map.of("action", redo ? "redo" : "undo");
    }

    // ========================================
    // Device list resource
    // ========================================

    /** Loads "Name$uuid" lines (Bitwig native device UUIDs) from the bundled resource. */
    private static Map<String, UUID> loadBitwigDevices() {
        Map<String, UUID> map = new LinkedHashMap<>();
        try (InputStream in = BitwigEditFacade.class.getResourceAsStream(Constants.DEVICE_LIST_RESOURCE)) {
            if (in == null) {
                return map;
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                int sep = line.lastIndexOf('$');
                if (line.isEmpty() || line.startsWith("#") || sep < 0) {
                    continue;
                }
                map.put(line.substring(0, sep), UUID.fromString(line.substring(sep + 1)));
            }
        } catch (Exception e) {
            // Leave the map empty; insert by CLAP/VST3 id still works.
        }
        return map;
    }
}
