package com.winlator.cmod.inputcontrols;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.preference.PreferenceManager;

import java.util.ArrayList;
import java.util.List;

public class ControllerManager {

    @SuppressLint("StaticFieldLeak")
    private static ControllerManager instance;


    public static synchronized ControllerManager getInstance() {
        if (instance == null) {
            instance = new ControllerManager();
        }
        return instance;
    }

    private ControllerManager() {
        // Private constructor to prevent direct instantiation.
    }

    // --- Core Properties ---
    private Context context;
    private SharedPreferences preferences;
    private InputManager inputManager;

    // This list will hold all physical game controllers detected by Android.
    private final List<InputDevice> detectedDevices = new ArrayList<>();

    // This maps a player slot (0-3) to the unique identifier of the physical device.
    // e.g., key=0, value="vendor_123_product_456"
    private final SparseArray<String> slotAssignments = new SparseArray<>();

    // This tracks which of the 4 player slots are enabled by the user.
    private final boolean[] enabledSlots = new boolean[4];

    public static final String PREF_PLAYER_SLOT_PREFIX = "controller_slot_";
    public static final String PREF_ENABLED_SLOTS_PREFIX = "enabled_slot_";

    private final boolean[] vibrationEnabled = new boolean[]{ true, true, true, true };

    public static final String PREF_VIBRATE_SLOT_PREFIX = "vibrate_slot_";


    /**
     * Initializes the manager. This must be called once from the main application context.
     * @param context The application context.
     */
    public void init(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = PreferenceManager.getDefaultSharedPreferences(this.context);
        this.inputManager = (InputManager) this.context.getSystemService(Context.INPUT_SERVICE);

        // On startup, we load saved settings and scan for connected devices.
        loadAssignments();
        scanForDevices();

        // Rescan on hot-plug. Without this, a Bluetooth pad paired while a game
        // is running never enters detectedDevices, so slot lookups miss it.
        // 연결/해제 시 다시 스캔함. 이게 없으면 게임 실행 중에 붙인 블루투스
        // 패드가 detectedDevices에 안 들어가서 슬롯 조회에서 빠짐
        if (!deviceListenerRegistered && inputManager != null) {
            inputManager.registerInputDeviceListener(new InputManager.InputDeviceListener() {
                @Override public void onInputDeviceAdded(int deviceId) { scanForDevices(); }
                @Override public void onInputDeviceRemoved(int deviceId) { scanForDevices(); }
                @Override public void onInputDeviceChanged(int deviceId) { scanForDevices(); }
            }, new android.os.Handler(android.os.Looper.getMainLooper()));
            deviceListenerRegistered = true;
        }
    }

    // Registered once per process (init() is called from several activities)
    // 프로세스당 한 번만 등록함(init()은 여러 액티비티에서 호출됨)
    private boolean deviceListenerRegistered = false;

    /**
     * Slot for this device, auto-assigning it when it has none.
     * The first enabled slot with no connected device wins (P1 is enabled by
     * default, P2-P4 are not), and the choice is saved like a manual one.
     * 이 기기의 슬롯을 돌려주고, 없으면 자동 할당함.
     * 연결된 기기가 없는 활성 슬롯 중 첫 번째를 씀(기본값은 P1만 활성, P2~P4는
     * 비활성). 선택 결과는 수동 할당과 똑같이 저장됨
     *
     * @return slot index 0-3, or -1 when no enabled slot is free
     *         슬롯 번호 0~3, 비어 있는 활성 슬롯이 없으면 -1
     */
    public synchronized int getOrAutoAssignSlot(int deviceId) {
        int slot = getSlotForDeviceOrSibling(deviceId);
        if (slot != -1) return slot;

        InputDevice device = inputManager.getInputDevice(deviceId);
        if (device == null || device.isVirtual() || !isGameController(device)) return -1;

        scanForDevices();
        slot = getSlotForDeviceOrSibling(deviceId);
        if (slot != -1) return slot;

        for (int i = 0; i < 4; i++) {
            if (enabledSlots[i] && getAssignedDeviceForSlot(i) == null) {
                assignDeviceToSlot(i, device);
                Log.i("ControllerManager", "Auto-assigned " + device.getName() + " to P" + (i + 1));
                return i;
            }
        }
        return -1;
    }




    /**
     * Scans for all physically connected game controllers and updates the internal list.
     */
    public synchronized void scanForDevices() {
        detectedDevices.clear();
        int[] deviceIds = inputManager.getInputDeviceIds();
        for (int deviceId : deviceIds) {
            InputDevice device = inputManager.getInputDevice(deviceId);
            if (device != null
                    && !device.isVirtual()
                    && isGameController(device)) {
                detectedDevices.add(device);
            }
        }
    }

    /**
     * Loads the saved player slot assignments and enabled states from SharedPreferences.
     */
    private void loadAssignments() {
        slotAssignments.clear();
        for (int i = 0; i < 4; i++) {
            // Load which device is assigned to this slot
            String prefKey = PREF_PLAYER_SLOT_PREFIX + i;
            String deviceIdentifier = preferences.getString(prefKey, null);
            if (deviceIdentifier != null) {
                slotAssignments.put(i, deviceIdentifier);
            }

            // Load whether this slot is enabled. Default P1=true, P2-4=false.
            String enabledKey = PREF_ENABLED_SLOTS_PREFIX + i;
            // P1 forced on for the same reason as in setSlotEnabled()
            // setSlotEnabled()와 같은 이유로 P1은 강제로 켬
            enabledSlots[i] = i == 0 || preferences.getBoolean(enabledKey, false);

            String vibKey = PREF_VIBRATE_SLOT_PREFIX + i;
            vibrationEnabled[i] = preferences.getBoolean(vibKey, i == 0);
        }
    }

    /**
     * Saves the current player slot assignments and enabled states to SharedPreferences.
     */
    public void saveAssignments() {
        SharedPreferences.Editor editor = preferences.edit();
        for (int i = 0; i < 4; i++) {
            // Save the assigned device identifier
            String deviceIdentifier = slotAssignments.get(i);
            String prefKey = PREF_PLAYER_SLOT_PREFIX + i;
            if (deviceIdentifier != null) {
                editor.putString(prefKey, deviceIdentifier);
            } else {
                editor.remove(prefKey);
            }

            // Save the enabled state
            String enabledKey = PREF_ENABLED_SLOTS_PREFIX + i;
            editor.putBoolean(enabledKey, enabledSlots[i]);

            String vibKey = PREF_VIBRATE_SLOT_PREFIX + i;
            editor.putBoolean(vibKey, vibrationEnabled[i]);
        }
        editor.apply();
    }

// --- Helper & Getter Methods ---

    /**
     * Checks if a device is a gamepad or joystick.
     * @return True if the device is a game controller.
     */
    private static class Caps {
        boolean lxy, hat, trigger, rstick, anyJoyAxis, anyButtons;
    }

    private static Caps analyzeCaps(InputDevice d) {
        Caps c = new Caps();
        if (d == null) return c;

        for (InputDevice.MotionRange r : d.getMotionRanges()) {
            int src = r.getSource();
            if ((src & (InputDevice.SOURCE_JOYSTICK | InputDevice.SOURCE_GAMEPAD)) == 0) continue;

            c.anyJoyAxis = true;
            switch (r.getAxis()) {
                case MotionEvent.AXIS_X:
                case MotionEvent.AXIS_Y:           c.lxy = true; break;
                case MotionEvent.AXIS_HAT_X:
                case MotionEvent.AXIS_HAT_Y:       c.hat = true; break;
                case MotionEvent.AXIS_LTRIGGER:
                case MotionEvent.AXIS_RTRIGGER:
                case MotionEvent.AXIS_GAS:
                case MotionEvent.AXIS_BRAKE:       c.trigger = true; break;
                case MotionEvent.AXIS_RX:
                case MotionEvent.AXIS_RY:
                case MotionEvent.AXIS_RZ:          c.rstick = true; break;
            }
        }

        int[] keys = {
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
                KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
                KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_THUMBL, KeyEvent.KEYCODE_BUTTON_THUMBR,
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
                KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT
        };
        boolean[] present = d.hasKeys(keys);
        for (boolean p : present) { if (p) { c.anyButtons = true; break; } }

        return c;
    }

    private static boolean isPointerLike(InputDevice device) {
        if (device == null) return false;
        int s = device.getSources();
        return ((s & InputDevice.SOURCE_MOUSE) == InputDevice.SOURCE_MOUSE)
                || ((s & InputDevice.SOURCE_MOUSE_RELATIVE) == InputDevice.SOURCE_MOUSE_RELATIVE)
                || ((s & InputDevice.SOURCE_TOUCHPAD) == InputDevice.SOURCE_TOUCHPAD)
                || ((s & InputDevice.SOURCE_STYLUS) == InputDevice.SOURCE_STYLUS)
                || ((s & InputDevice.SOURCE_CLASS_POINTER) == InputDevice.SOURCE_CLASS_POINTER);
    }

    public static boolean isGameController(InputDevice d) {
        if (d == null) return false;

        int s = d.getSources();
        boolean hasControllerBits =
                ((s & InputDevice.SOURCE_JOYSTICK) != 0) ||
                        ((s & InputDevice.SOURCE_GAMEPAD)  != 0);

        boolean pointer = isPointerLike(d);
        Caps c = analyzeCaps(d);

        // Pointer-like devices must have *non-button* joystick/gamepad axes beyond simple XY.
        if (pointer) {
            boolean nonButtonAxes = c.hat || c.trigger || c.rstick;
            return nonButtonAxes;                    // buttons alone are NOT enough
        }

        // Non-pointer devices: accept normal controllers with any meaningful controls.
        return hasControllerBits && (c.lxy || c.hat || c.trigger || c.rstick || c.anyButtons);
    }

    /**
     * Creates a stable, unique identifier string for a given device.
     * This is used for saving and loading assignments.
     * @param device The InputDevice.
     * @return A unique identifier string.
     */
    public static String getDeviceIdentifier(InputDevice device) {
        if (device == null) return null;
        // The descriptor is the most reliable unique ID for a device.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            return device.getDescriptor();
        }
        // Fallback for older Android versions
        return "vendor_" + device.getVendorId() + "_product_" + device.getProductId();
    }

    /**
     * Returns the list of all detected physical game controllers.
     */
    public List<InputDevice> getDetectedDevices() {
        return detectedDevices;
    }

    /**
     * Returns the number of player slots the user has enabled.
     */
    public int getEnabledPlayerCount() {
        int count = 0;
        for (boolean enabled : enabledSlots) {
            if (enabled) {
                count++;
            }
        }
        return count;
    }

    /**
     * Assigns a physical device to a specific player slot.
     * This method handles un-assigning the device from any other slot it might have been in.
     * @param slotIndex The player slot to assign to (0-3).
     * @param device The physical InputDevice to assign.
     */
    public void assignDeviceToSlot(int slotIndex, InputDevice device) {
        if (slotIndex < 0 || slotIndex >= 4) return;

        String newDeviceIdentifier = getDeviceIdentifier(device);
        if (newDeviceIdentifier == null) return;

        // First, remove the new device from any slot it might already be in.
        for (int i = 0; i < 4; i++) {
            if (newDeviceIdentifier.equals(slotAssignments.get(i))) {
                slotAssignments.remove(i);
            }
        }

        // Assign the new device to the target slot.
        slotAssignments.put(slotIndex, newDeviceIdentifier);
        saveAssignments(); // Persist the change immediately.
    }

    public boolean hasEnabledUnassignedSlot() {
        for (int i = 0; i < 4; i++) {
            if (enabledSlots[i] && getAssignedDeviceForSlot(i) == null) {
                return true;
            }
        }
        return false;
    }


    /**
     * Clears any device assignment for the given player slot.
     * @param slotIndex The player slot to un-assign (0-3).
     */
    public void unassignSlot(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= 4) return;
        slotAssignments.remove(slotIndex);
        saveAssignments();
    }

    /**
     * Finds which player slot a given device is assigned to.
     * @param deviceId The ID of the physical device.
     * @return The player slot index (0-3), or -1 if the device is not assigned.
     */
    public int getSlotForDevice(int deviceId) {
        InputDevice device = inputManager.getInputDevice(deviceId);
        String deviceIdentifier = getDeviceIdentifier(device);
        if (deviceIdentifier == null) return -1;

        // Correctly loop through the sparse array to find the key for our value.
        for (int i = 0; i < slotAssignments.size(); i++) {
            int key = slotAssignments.keyAt(i);
            String value = slotAssignments.valueAt(i);
            if (deviceIdentifier.equals(value)) {
                return key; // Return the key (the slot index), not the internal index!
            }
        }

        return -1; // Not found
    }


    /**
     * Gets the InputDevice object that is currently assigned to a specific player slot.
     * @param slotIndex The player slot (0-3).
     * @return The assigned InputDevice, or null if no device is assigned or if the device is not currently connected.
     */
    public synchronized InputDevice getAssignedDeviceForSlot(int slotIndex) {
        String assignedIdentifier = slotAssignments.get(slotIndex);
        if (assignedIdentifier == null) return null;

        // Search our current list of connected devices for one that matches the saved identifier.
        for (InputDevice device : detectedDevices) {
            if (assignedIdentifier.equals(getDeviceIdentifier(device))) {
                return device; // Found it.
            }
        }

        return null; // The assigned device is not currently connected.
    }

    /**
     * Sets whether a player slot is enabled ("Connected").
     * @param slotIndex The player slot (0-3).
     * @param isEnabled The new enabled state.
     */
    public void setSlotEnabled(int slotIndex, boolean isEnabled) {
        if (slotIndex < 0 || slotIndex >= 4) return;
        // P1 stays enabled. The launcher creates one virtual SDL pad per enabled
        // slot (EVSHIM_MAX_PLAYERS), so a disabled P1 meant the game saw no pad at
        // all - neither the screen buttons nor a Bluetooth pad.
        // P1은 항상 켜둠. 런처는 활성 슬롯 수만큼 SDL 가상 패드를 만들기 때문에
        // (EVSHIM_MAX_PLAYERS) P1이 꺼지면 게임에 패드가 하나도 안 보였음 -
        // 화면 버튼도 블루투스 패드도 안 됨
        if (slotIndex == 0) isEnabled = true;
        enabledSlots[slotIndex] = isEnabled;
        saveAssignments();
    }

    public boolean isSlotEnabled(int slotIndex) {
        if (slotIndex < 0 || slotIndex >= 4) return false;
        return enabledSlots[slotIndex];
    }

    // ControllerManager.java

    private static String sanitizeName(String name) {
        if (name == null) return "";
        // Collapse suffixes often used for sensor/touch subdevices.
        return name.replaceAll("(?i)(\\s*-?\\s*(touch|touchpad|sensor|motion).*)$", "")
                .trim();
    }

    public static String makePhysicalGroupKey(InputDevice d) {
        if (d == null) return null;
        // Vendor/Product are the anchor; include a sanitized name to reduce
        // false grouping across very different devices from same vendor.
        return d.getVendorId() + ":" + d.getProductId() + ":" + sanitizeName(d.getName());
    }

    public int getSlotForDeviceOrSibling(int deviceId) {
        InputDevice d = inputManager.getInputDevice(deviceId);
        if (d == null) return -1;

        // 1) Exact descriptor match first (current behavior)
        int slot = getSlotForDevice(deviceId);
        if (slot != -1) return slot;

        // 2) Group match against already-assigned devices
        String g = makePhysicalGroupKey(d);
        for (int i = 0; i < 4; i++) {
            InputDevice assigned = getAssignedDeviceForSlot(i);
            if (assigned != null) {
                if (g.equals(makePhysicalGroupKey(assigned))) {
                    return i;
                }
            }
        }
        return -1;
    }

    public boolean isVibrationEnabled(int slot) {
        return (slot >= 0 && slot < 4) && vibrationEnabled[slot];
    }
    public void setVibrationEnabled(int slot, boolean enabled) {
        if (slot < 0 || slot >= 4) return;
        vibrationEnabled[slot] = enabled;
        saveAssignments();
    }



}