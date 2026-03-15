# PR: SOS Emergency Feature

> **Branch**: `feature/sos-emergency`
> **Target**: `main`
> **Title**: feat: add SOS Emergency feature with sender/receiver integration, background service, and boot resilience

---

## Summary

Add a complete SOS Emergency feature to Columba, allowing users to send emergency distress signals to pre-selected contacts over the LXMF mesh network. The feature includes a full sender-side state machine with countdown, GPS location embedding, battery level reporting, Sideband-compatible LXMF telemetry (`FIELD_TELEMETRY`), periodic updates, and a receiver-side experience with urgent notifications, visual SOS message differentiation in chat, and one-tap map navigation.

**Key capabilities:**
- Designate contacts as "SOS contacts" from Contacts or Chats screen (tag-based, persisted in DB)
- Trigger SOS with optional countdown timer (configurable 0-30s)
- Automatic GPS location and battery level embedding in SOS messages
- Sideband-compatible LXMF telemetry (`FIELD_TELEMETRY 0x02`) with `SID_LOCATION` and `SID_BATTERY`
- Periodic location/battery updates while SOS is active
- PIN-protected deactivation (optional)
- Silent auto-answer for incoming calls during SOS (optional)
- Floating SOS trigger button (optional)
- Multiple trigger modes can be active simultaneously (shake + tap + power button)
- Power button detection: 3 rapid presses trigger SOS
- Foreground service keeps accelerometer/power button detection and active SOS alive in background
- Automatic startup on device boot — no need to open the app after reboot
- Reboot resilience: active SOS state, periodic updates, and trigger detection resume automatically
- Receiver-side: urgent persistent notifications with "Open Chat" and "View on Map" actions
- Receiver-side: SOS messages displayed with red emergency styling in chat
- Receiver-side: clickable "View on Map" button for GPS-tagged SOS messages
- **Audio recording**: Records ambient audio (AAC/M4A, 16kHz) for configurable duration (15-60s) when SOS is triggered, sent as a separate LXMF message via `FIELD_AUDIO (0x07)`
- **Audio playback**: Received audio messages display an inline player (play/pause + progress bar) in the chat view

---

## Design Principles

### 1. Works Offline-First
SOS operates entirely over the LXMF/Reticulum mesh network. No internet, no cellular, no server dependency. If you have mesh connectivity to your contacts, SOS works.

### 2. Tag-Based Contact Selection
SOS contacts are managed via the existing `tags` JSON field on `ContactEntity`, using an `"sos"` tag. This avoids schema migrations and leverages the existing tag infrastructure (`getTagsList()`, `updateTags()`).

### 3. State Machine Architecture
`SosManager` implements a strict state machine (`Idle` → `Countdown` → `Sending` → `Active` → `Idle`) to prevent race conditions and ensure predictable behavior under stress.

### 4. Receiver-Side Awareness
The receiver is not just passively notified — SOS messages are visually differentiated in chat (red bubble, warning badge), and GPS coordinates are actionable (one-tap opens the map).

### 5. Non-Intrusive Settings
All SOS settings are in a dedicated collapsible card in Settings. The feature is opt-in: disabled by default, SOS contacts must be explicitly tagged.

---

## Functional Description

### Sender Side

#### SOS Contact Management
- In the **Contacts** or **Chats** screen, long-press a contact/conversation → context menu → "Mark as SOS Contact" / "Unmark as SOS Contact" (Chats shows this option only for saved contacts)
- SOS contacts display a small red warning badge overlay on their avatar (top-start corner)
- `EnrichedContact.isSosContact` computed property parses the JSON tags for the `"sos"` tag

#### SOS Settings (Settings → SOS Emergency Card)
| Setting | Default | Description |
|---------|---------|-------------|
| Enable SOS | `false` | Master toggle for the feature |
| Message Template | `"SOS! I need help. This is an emergency."` | Customizable SOS message text |
| Countdown | `5` seconds | Delay before sending (0 = instant) |
| Include Location | `true` | Append GPS coordinates to the SOS message |
| Silent Auto-Answer | `false` | Auto-answer incoming calls during active SOS |
| Floating Button | `false` | Show a floating SOS trigger button |
| Deactivation PIN | `null` | Optional PIN required to cancel SOS |
| Periodic Updates | `false` | Send location updates while SOS is active |
| Update Interval | `120` seconds | Interval between periodic location updates |
| Trigger Modes | `{}` (empty set) | Which automatic triggers are active (multi-select): `shake`, `tap_pattern`, `power_button`. Empty = manual button only. |
| Shake Sensitivity | `2.5x` | Multiplier of gravity for shake threshold (1.0-5.0, lower = more sensitive) |
| Tap Count | `3` | Number of rapid taps required for tap pattern trigger (3-5, spike-based detection) |

All settings are persisted in DataStore (`SettingsRepository`) as typed `Flow<T>` properties.

#### SOS Trigger Modes

Trigger modes are multi-select: any combination can be active simultaneously. When no modes are selected, SOS can only be triggered manually (floating button or programmatic API). The UI uses checkboxes instead of radio buttons.

**Shake**: The device accelerometer monitors for sustained vigorous shaking. The acceleration magnitude minus gravity must exceed `shakeSensitivity * 9.81 m/s²` for a cumulative 500ms within a 1-second window. A 5-second cooldown prevents repeat triggers.

**Tap Pattern**: Uses a spike-based state machine for reliable detection. A tap is counted only when net acceleration crosses above 4.0 m/s² and returns below it within 100ms (a complete impulse event). This rejects walking steps (~150-300ms spike duration) and sustained vibrations while reliably catching brief finger taps (20-80ms). When `tapCount` taps are detected within a 2.5-second sliding window, SOS is triggered. A 150ms debounce between registered taps prevents ring-down artifacts from being double-counted. A 5-second cooldown prevents repeat triggers.

**Power Button**: Detects rapid `SCREEN_OFF` events via a `BroadcastReceiver`. 3 presses within a 2-second window trigger SOS. A 5-second cooldown prevents repeat triggers. This does not conflict with Android's built-in Emergency SOS (which requires 5 presses).

Gesture modes (shake/tap) work through `SosTriggerDetector`, a singleton `SensorEventListener` registered on `TYPE_ACCELEROMETER` with `SENSOR_DELAY_GAME` (~20ms sampling). Power button detection uses a `BroadcastReceiver` for `ACTION_SCREEN_OFF`. All listeners are registered/unregistered based on the active mode set. When shake and tap are both active, each sensor event is dispatched to both handlers independently.

#### Background Service & Boot Resilience

`SosTriggerService` is a lightweight foreground service (type `specialUse`) that keeps the main process alive whenever:
- Trigger detection is active (SOS enabled + at least one trigger mode selected), OR
- SOS is in an active state (Countdown / Sending / Active) — for periodic location updates

`BootReceiver` listens for `BOOT_COMPLETED` and starts both `ReticulumService` (mesh network) and `SosTriggerService` (main process). This triggers `ColumbaApplication.onCreate()` which calls `sosManager.restoreIfActive()` and `sosTriggerDetector.startObserving()`. If SOS was active before reboot, the state is restored, periodic updates resume (after waiting for Reticulum to become ready), and trigger detection restarts — all without user interaction.

#### SOS State Persistence

The SOS active state survives app kills and phone restarts:
- When entering `Active` state, `SosManager` persists `(sosActive=true, sentCount, failedCount)` to DataStore
- On app startup, `SosManager.restoreIfActive()` checks DataStore and restores the `Active` state, re-shows the notification, and restarts periodic updates if enabled
- On deactivation, the persisted state is cleared

This ensures that if the phone reboots during an active SOS, the emergency state is restored and the persistent notification reappears.

#### SOS Trigger Flow
1. User triggers SOS (floating button, shake, tap pattern, power button, or programmatic trigger)
2. If countdown > 0: state transitions to `Countdown`, timer ticks down, user can cancel
3. If countdown = 0 or timer expires: state transitions to `Sending`
4. `SosManager` collects all SOS-tagged contacts, builds the message:
   - Template text + GPS coordinates (if enabled) + battery level: `"SOS! I need help.\nGPS: 48.85660, 2.35220 (accuracy: 10m)\nBattery: 73%"`
   - Builds a `telemetryJson` payload with location + battery data
   - Sends via `ReticulumProtocol.sendLxmfMessageWithMethod()` to each SOS contact
   - The telemetry JSON is packed as Sideband-compatible `FIELD_TELEMETRY (0x02)` with msgpack at the Python layer
5. State transitions to `Active` with success/fail counts
6. Persistent notification shows "SOS Active — X contacts notified"
7. If periodic updates enabled: sends "SOS Update - GPS: lat, lng (accuracy: Xm) - Battery: X%" at configured interval with updated telemetry

#### SOS Audio Recording
- When `sosAudioEnabled` is true, audio recording starts immediately after SOS messages are sent
- Uses `MediaRecorder` with AAC codec at 16kHz mono, 24kbps → ~90KB for 30s
- Recording runs for `sosAudioDurationSeconds` (configurable 15-60s in settings)
- Audio is sent as a separate LXMF message with `FIELD_AUDIO (0x07)` in `[format, bytes]` format
- Sent asynchronously — the initial text+GPS alert goes out instantly, audio follows after recording completes
- If SOS is deactivated before recording finishes, the recording is cancelled
- Receiver sees an inline audio player (play/pause + progress bar) in the chat view

#### SOS Deactivation
- If no PIN configured: simple deactivation returns to `Idle`
- If PIN configured: user must enter correct PIN to deactivate
- Wrong PIN: state stays `Active`, error counter increments
- Deactivation cancels periodic update job and clears the persistent notification
- **Auto-deactivation**: If the user disables the SOS feature toggle while SOS is active, `SosTriggerDetector.startObserving()` automatically calls `sosManager.deactivate()` to clean up state and stop the foreground service

### Receiver Side

#### SOS Message Detection
`MessageCollector` intercepts incoming messages **after** database save but **before** notification posting:
- Checks `NotificationHelper.isSosMessage(content)`:
  - Message starts with "SOS", "URGENCE", or "EMERGENCY" (case-insensitive)
  - Or contains "SOS Update - GPS:"
- If SOS detected → parse GPS with `parseSosLocation()` → post SOS notification
- If not SOS → regular notification flow (unchanged)

#### SOS Notification (Receiver)
- **Channel**: `sos_emergency` — high priority, public lockscreen visibility
- **Vibration**: Emergency pattern `[0, 500, 200, 500, 200, 500]`
- **Title**: "SOS from {peerName}"
- **Content**: GPS coordinates if available, otherwise message preview
- **Expanded**: Full message text + formatted location
- **Persistent**: `ongoing = true`, `autoCancel = false`
- **Actions**:
  - **"Open Chat"** → `ACTION_SOS_CALL_BACK` → navigates to conversation (user can initiate voice call)
  - **"View on Map"** → `ACTION_SOS_VIEW_MAP` → navigates to `map_focus` route centered on sender's GPS coordinates (only shown when location is available)

#### SOS Chat Bubble (Receiver)
- SOS messages from others are rendered with `errorContainer` background color (red) instead of default `surfaceContainerHigh`
- A "SOS EMERGENCY" header badge with `Warning` icon appears above the message text
- If GPS coordinates are present, a red "View on Map" button appears below the message text
- Timestamp text uses `onErrorContainer` color for consistency

#### Intent Handling
- `MainActivityIntentHandler` handles `ACTION_SOS_CALL_BACK` and `ACTION_SOS_VIEW_MAP`
- `PendingNavigation.SosMapFocus(lat, lon, label)` navigates to the existing `map_focus` composable route with SOS sender's coordinates

---

## Implementation Details

### New Files

| File | Description |
|------|-------------|
| `app/.../service/SosManager.kt` | Core state machine: `Idle` → `Countdown` → `Sending` → `Active`. Manages GPS, message sending, periodic updates, PIN deactivation. Injected with `CoroutineScope`, `ContactRepository`, `SettingsRepository`, `ReticulumProtocol`, `NotificationHelper`. |
| `app/.../viewmodel/SosViewModel.kt` | Thin ViewModel wrapping `SosManager`. Exposes `state: StateFlow<SosState>`, `trigger()`, `cancel()`, `deactivate(pin)`. Used by UI screens. |
| `app/.../ui/screens/settings/cards/SosEmergencyCard.kt` | Composable settings card with all SOS configuration options. Collapsible card with toggle switches, text fields, sliders. |
| `app/.../service/SosTriggerDetector.kt` | Multi-mode SOS trigger detection. Supports shake, spike-based tap pattern (`SensorEventListener`), and power button (`BroadcastReceiver` for `SCREEN_OFF`). Multiple modes can be active simultaneously. Singleton with `start()`/`stop()`/`reloadSettings()`/`startObserving()` API. Manages `SosTriggerService` lifecycle and auto-deactivates SOS when the feature toggle is disabled. |
| `app/.../service/SosTriggerService.kt` | Lightweight foreground service (`specialUse`) with persistent notification. Keeps the main process alive for accelerometer detection and active SOS periodic updates. Started/stopped by `SosTriggerDetector.startObserving()`. |
| `app/.../receiver/BootReceiver.kt` | `BOOT_COMPLETED` broadcast receiver. Starts `ReticulumService` and `SosTriggerService` on device boot so mesh networking and SOS detection/state resume automatically without user interaction. |
| `app/.../ui/components/SosOverlay.kt` | Composable for SOS UI states: countdown dialog, sending dialog, active banner (placed in Scaffold `bottomBar` above NavigationBar), and deactivation dialog with optional PIN input. |
| `app/.../ui/components/AudioMessagePlayer.kt` | Composable audio player for LXMF FIELD_AUDIO messages. Play/pause button, linear progress bar, duration display. Uses `MediaPlayer` with temp file. |
| `app/.../service/SosAudioRecorder.kt` | Singleton audio recorder using `MediaRecorder` with AAC codec (16kHz, 24kbps, mono). Start/stop/cancel API, returns audio bytes for LXMF transmission. |

### Modified Files

| File | Changes |
|------|---------|
| `data/.../model/EnrichedContact.kt` | Added `val isSosContact: Boolean get() = getTagsList().contains("sos")` |
| `data/.../repository/ContactRepository.kt` | Added `toggleSosTag()`, `getSosContacts()`, `getSosContactsFlow()` methods |
| `app/.../repository/SettingsRepository.kt` | Added 12 SOS DataStore properties: 9 original settings + 3 state persistence (`sosActive`, `sosActiveSentCount`, `sosActiveFailedCount`) + trigger mode settings (`sosTriggerModes` as `Set<String>`, with legacy migration from single-mode string, `sosShakeSensitivity`, `sosTapCount`) with getter flows and setter methods |
| `app/.../viewmodel/SettingsViewModel.kt` | Added SOS state fields (`sosTriggerModes: Set<String>`), collector coroutines for all SOS settings, and setter methods (`setSosEnabled`, `toggleSosTriggerMode`, etc.) |
| `app/.../viewmodel/ContactsViewModel.kt` | Added `toggleSosTag(destinationHash)` method |
| `app/.../viewmodel/ChatsViewModel.kt` | Added `isSosContact(peerHash)` flow, `toggleSosTag(peerHash)` for SOS contact management from Chats screen |
| `app/.../notifications/NotificationHelper.kt` | Added `CHANNEL_ID_SOS`, `NOTIFICATION_ID_SOS`, `ACTION_SOS_CALL_BACK`, `ACTION_SOS_VIEW_MAP`. Added `notifySosReceived()`, `showSosActiveNotification()`, `cancelSosActiveNotification()`, `isSosMessage()`, `parseSosLocation()` |
| `app/.../service/MessageCollector.kt` | Added SOS detection branch before regular notification posting |
| `app/.../ui/screens/ContactsScreen.kt` | Added `isSos`/`onToggleSos` params to `ContactContextMenu`, SOS toggle menu item with Warning icon, red badge overlay on SOS contact avatars in `ContactListItem` |
| `app/.../ui/screens/ChatsScreen.kt` | Added `isSos`/`onToggleSos` params to `ConversationContextMenu`, SOS toggle menu item (visible only for saved contacts) |
| `data/.../repository/ContactRepository.kt` | Added `isSosContactFlow(destinationHash)` for observing SOS status of individual contacts |
| `app/.../ui/screens/MessagingScreen.kt` | Added SOS bubble styling (`errorContainer` color), "SOS EMERGENCY" header badge, "View on Map" button for GPS-tagged messages, `isSosMessageContent()` and `parseSosGpsLocation()` helper functions |
| `app/.../MainActivityIntentHandler.kt` | Added `ACTION_SOS_CALL_BACK` → conversation navigation, `ACTION_SOS_VIEW_MAP` → map focus navigation |
| `app/.../MainActivity.kt` | Added `PendingNavigation.SosMapFocus` sealed class variant, navigation handler for SOS map focus. SosOverlay placed in Scaffold `bottomBar` slot (above NavigationBar) for reliable visibility across all screens. |
| `app/.../service/SosManager.kt` | Added state persistence to DataStore on Active transition, `restoreIfActive()` for startup recovery, clear on deactivation. `startPeriodicUpdates()` waits for Reticulum service to bind and become ready before sending (reboot resilience). Added `getBatteryLevel()`, `isBatteryCharging()`, `buildTelemetryJson()` for battery + location telemetry. |
| `reticulum/.../protocol/ReticulumProtocol.kt` | Added `telemetryJson: String? = null` parameter to `sendLxmfMessageWithMethod()` interface |
| `app/.../protocol/ServiceReticulumProtocol.kt` | Added `telemetryJson: String?` parameter, passes to AIDL service call |
| `reticulum/.../protocol/MockReticulumProtocol.kt` | Added `telemetryJson: String?` parameter to override |
| `app/.../aidl/IReticulumService.aidl` | Added `String telemetryJson` to `sendLxmfMessageWithMethod` AIDL signature |
| `app/.../binder/ReticulumServiceBinder.kt` | Added `telemetryJson: String?` parameter, passes to Python wrapper call |
| `python/reticulum_wrapper.py` | Added `SID_BATTERY = 0x04`, extended `pack_location_telemetry()` / `unpack_location_telemetry()` with battery fields, added `telemetry_json` parameter to `send_lxmf_message_with_method()` with JSON→msgpack→`FIELD_TELEMETRY` packing |
| `app/.../ColumbaApplication.kt` | Added `SosManager` and `SosTriggerDetector` injection; calls `sosTriggerDetector.startObserving()` and `sosManager.restoreIfActive()` in `onCreate()` (main process only). |
| `app/.../IncomingCallActivity.kt` | Added `shouldAutoAnswer()` check via `ColumbaApplication.sosManager` for silent auto-answer during active SOS. |
| `AndroidManifest.xml` | Added `RECEIVE_BOOT_COMPLETED`, `FOREGROUND_SERVICE_SPECIAL_USE` permissions. Declared `SosTriggerService` (foreground, `specialUse`) and `BootReceiver` (`BOOT_COMPLETED`). |

### SOS Message Format

```
SOS! I need help. This is an emergency.
GPS: 48.85660, 2.35220 (accuracy: 10m)
Battery: 73%
```

Periodic update:
```
SOS Update - GPS: 48.85665, 2.35225 (accuracy: 8m) - Battery: 68%
```

GPS parsing regex: `GPS:\s*(-?\d+\.?\d*),\s*(-?\d+\.?\d*)`

### LXMF Telemetry Protocol (FIELD_TELEMETRY)

SOS messages carry structured telemetry as `FIELD_TELEMETRY (0x02)` in the LXMF message fields, using the standard Sideband/Reticulum msgpack format:

| Sensor ID | Constant | Data Format |
|-----------|----------|-------------|
| `0x01` | `SID_TIME` | `[timestamp_seconds]` |
| `0x02` | `SID_LOCATION` | `[latitude, longitude, altitude, speed, bearing, accuracy]` |
| `0x04` | `SID_BATTERY` | `[charge_percent, is_charging, temperature_celsius]` |

**Data flow through the protocol stack:**
1. `SosManager` builds a `telemetryJson` JSON string with location + battery data
2. Passed through `ReticulumProtocol` → `ServiceReticulumProtocol` → AIDL IPC → `ReticulumServiceBinder`
3. `reticulum_wrapper.py` parses the JSON, calls `pack_location_telemetry()` to produce msgpack bytes
4. Packed telemetry attached as `FIELD_TELEMETRY` on the LXMF message before sending

This ensures interoperability with Sideband and other Reticulum clients that read `FIELD_TELEMETRY`.

### SOS Detection Logic

```kotlin
fun isSosMessage(content: String): Boolean {
    val upper = content.uppercase().trimStart()
    return upper.startsWith("SOS") ||
        upper.startsWith("URGENCE") ||
        upper.startsWith("EMERGENCY") ||
        upper.contains("SOS Update - GPS:")
}
```

Supports French ("URGENCE") and English ("EMERGENCY", "SOS") prefixes. Detection is case-insensitive.

---

## Unit Tests

### SosManagerTest (27 tests)
Tests the core state machine, telemetry, and persistence:
- Initial state is `Idle`
- Trigger with countdown starts countdown, ticks correctly, transitions to Sending
- Trigger without countdown (0s) goes directly to Sending
- Cancel during countdown returns to Idle
- Deactivation without PIN succeeds
- Deactivation with correct PIN succeeds
- Deactivation with wrong PIN fails, error counter increments
- Auto-answer check during Active state
- Message includes GPS when location available and setting enabled
- Message excludes GPS when setting disabled
- Message includes battery level when available
- Message includes both GPS and battery when both available
- State persistence: persists active state, clears on deactivation, correct failure counts
- Restore state: restores from DataStore, shows notification, starts periodic updates
- Failed identity does not send but persists state
- Lifecycle: re-trigger after deactivation works

**Key testing patterns:**
- `StandardTestDispatcher` with `advanceTimeBy()` / `advanceUntilIdle()` for coroutine control
- Strict mocks (`mockk()` not `mockk(relaxed = true)`) with explicit stubs for `Location` (`hasAltitude()`, `hasSpeed()`, `hasBearing()`) and `BatteryManager`
- `advanceTimeBy(500)` (not `advanceUntilIdle()`) for tests involving `startPeriodicUpdates()` infinite loop

### SosTriggerDetectorTest (30 tests)
Tests spike-based tap detection, shake detection, power button detection, and multi-mode enum:
- Single tap does not trigger, 3/5 taps trigger correctly
- Tap window expiry resets count
- Walking step rejection (>100ms spike duration)
- Deduplication (<150ms interval between taps)
- Cooldown prevents repeat triggers
- Threshold sensitivity levels
- Shake duration, window, gap, and cooldown logic
- Power button: 3 rapid presses trigger SOS, 2 presses don't, spread-out presses don't
- `fromKey()` returns null for unknown keys, correct mode for valid keys
- `fromKeys()` converts set of strings, ignores unknown keys

### BootReceiverTest (4 tests)
Tests boot-time service startup:
- Ignores non-BOOT_COMPLETED actions
- Starts ReticulumService and SosTriggerService on boot
- Verifies ACTION_START intent

### SosViewModelTest (7 tests)
Tests ViewModel delegation:
- State flow propagation from SosManager
- `trigger()`, `cancel()`, `deactivate()` delegation
- UI state composition

### ContactRepositorySosTest (13 tests)
Tests tag-based SOS contact management:
- `getSosContacts()` filters by `"sos"` tag
- `toggleSosTag()` adds `"sos"` to empty tags
- `toggleSosTag()` adds `"sos"` alongside existing tags
- `toggleSosTag()` removes `"sos"` when already present
- `toggleSosTag()` clears tags to null when `"sos"` was the only tag
- `isSosContact` computed property on EnrichedContact

**Key testing patterns:**
- `match { tags -> ... }` and `isNull()` matchers for nullable String parameters (MockK `capture()` doesn't support nullable types)
- Verifying JSON tag manipulation correctness

### Fixed Existing Tests
- **ContactsScreenTest**: Updated 7 `ContactContextMenu` call sites with new `isSos`/`onToggleSos` params
- **MessageCollectorTest**: Added `isSosMessage` stub (`every { notificationHelper.isSosMessage(any()) } returns false`)
- **SettingsViewModelTest**: Added stubs for all SOS settings flows (`sosTriggerModes` as `Set<String>`) + `contactRepository.getSosContactsFlow()`
- **SettingsViewModelIncomingMessageLimitTest**: Same SOS stubs
- **ChatsScreenTest**: Added `isSosContact` mock stub for `ChatsViewModel`
- **MessagingViewModelTest**: Updated all positional `any()` matchers (10→11) for new `telemetryJson` parameter

---

## Real-World Test Plan (3 Phones)

**Setup**: Phone A = SOS sender, Phone B + C = SOS contacts/receivers. All connected via LXMF mesh (LoRa or BLE).

### Scenario 1: Countdown + Cancel
1. A configures 10s countdown, tags B and C as SOS
2. A triggers SOS → verify countdown UI appears
3. A cancels at 5s → verify no message sent to B or C

### Scenario 2: Instant Send (0s countdown)
1. A sets countdown to 0s
2. A triggers SOS → verify B and C receive immediately
3. Verify urgent notification on B and C with vibration pattern

### Scenario 3: GPS Location + Battery Telemetry
1. A enables "Include Location" with GPS active
2. A triggers SOS → verify B receives message with "GPS: lat, lng" and "Battery: X%"
3. B taps "View on Map" in notification → verify map centers on A's location
4. B opens chat → verify red SOS bubble with "View on Map" button
5. Verify LXMF message carries `FIELD_TELEMETRY` with `SID_LOCATION` and `SID_BATTERY` (Sideband-compatible)

### Scenario 4: PIN Deactivation
1. A sets deactivation PIN "1234"
2. A triggers SOS (contacts notified)
3. A tries to deactivate with wrong PIN "0000" → verify rejection
4. A deactivates with correct PIN "1234" → verify deactivation + notification cleared

### Scenario 5: Auto-Answer
1. A enables "Silent Auto-Answer", triggers SOS
2. B calls A → verify A auto-answers without user interaction
3. Verify voice call established

### Scenario 6: Periodic Updates
1. A enables periodic updates (60s interval), triggers SOS
2. Verify B receives initial SOS message with GPS + battery
3. Wait 60s → verify B receives "SOS Update - GPS: ... - Battery: X%" message with updated telemetry
4. A deactivates → verify no more updates sent

### Scenario 7: No GPS Available
1. A enables "Include Location" but GPS is off/unavailable
2. A triggers SOS → verify message sent without GPS coordinates
3. Verify B notification shows message text (not coordinates)
4. Verify no "View on Map" button in chat bubble

### Scenario 8: Mesh Transport (Multi-Hop)
1. A and C are not directly reachable (2+ hops via B as relay)
2. A triggers SOS → verify C eventually receives SOS (may take longer)
3. Verify notification and chat styling work identically

### Scenario 9: Floating Button
1. A enables floating SOS button
2. Verify button appears on chat screens
3. Tap button → verify SOS trigger flow starts

### Scenario 10: Custom Template
1. A customizes message template: "AU SECOURS! Accident de randonnée."
2. A triggers SOS → verify B receives custom text (not default)

### Scenario 11: Resilience (App Kill / Phone Restart)
1. A triggers SOS, force-kill app
2. Relaunch → verify SOS state is restored to Active (not reset to Idle)
3. Verify sender notification reappears with correct sent/failed counts
4. Verify periodic updates resume if they were enabled
5. A deactivates → verify state clears and does not restore on next relaunch

### Scenario 11b: Full Reboot Resilience
1. A triggers SOS with periodic updates (60s interval), verify message sent
2. Reboot phone (do NOT open Columba manually)
3. After boot completes, verify "SOS Monitoring Active" notification appears
4. Wait for periodic update interval → verify contacts receive "SOS Update" message
5. Open Columba → verify SOS Active state in UI with correct sent/failed counts
6. Deactivate SOS → verify service notification disappears

### Scenario 12: Repeated Cycle
1. A triggers SOS → deactivate → trigger again
2. Verify second SOS sends correctly to all contacts
3. Verify notifications update correctly

### Scenario 13: SOS Badge Visibility
1. Tag contact B as SOS → verify red badge on B's avatar in contact list
2. Untag B → verify badge disappears

### Scenario 14: Receiver Chat Experience
1. B receives SOS from A
2. Open conversation → verify red bubble, "SOS EMERGENCY" badge, "View on Map" button
3. Tap "View on Map" → verify map opens at correct location
4. Receive regular message from A → verify normal bubble styling (not red)

### Scenario 15: Shake Trigger
1. A enables SOS, activates "Shake" trigger mode (checkbox), sensitivity 2.5x
2. Shake phone vigorously for ~1 second → verify SOS countdown starts
3. Let countdown complete → verify messages sent normally
4. Verify gentle movements (walking, pocket) do NOT trigger SOS

### Scenario 16: Tap Pattern Trigger (Spike-Based)
1. A enables SOS, activates "Tap Pattern" trigger mode (checkbox), 3 taps
2. Tap back of phone 3 times at natural pace (~2s) → verify SOS countdown starts
3. Verify 2 taps do NOT trigger SOS
4. Set to 5 taps → verify 3 taps no longer trigger, 5 taps do
5. Walk around normally → verify walking does NOT trigger SOS (steps >100ms are rejected)
6. Place phone on table firmly → verify single impact does NOT trigger SOS

### Scenario 17: Trigger Mode Cooldown
1. A enables shake trigger, triggers SOS, deactivates
2. Immediately shake again → verify 5-second cooldown prevents re-trigger
3. Wait 5+ seconds, shake → verify trigger fires

### Scenario 18: Power Button Trigger
1. A enables SOS, activates "Power button" trigger mode (checkbox)
2. Press power button 3 times rapidly (~1.5s) → verify SOS countdown starts
3. Verify 2 presses do NOT trigger SOS
4. Verify presses spread over >2 seconds do NOT trigger SOS
5. Verify does not conflict with Android's built-in Emergency SOS (5 presses)

### Scenario 19: Multi-Mode Triggers
1. A enables SOS, activates both "Shake" and "Power button" trigger modes
2. Shake phone → verify SOS triggers
3. Deactivate, then press power button 3x → verify SOS triggers
4. Enable all 3 modes simultaneously → verify each can trigger independently

### Scenario 20: Background Service Lifecycle
1. Enable SOS with any trigger mode → verify "SOS Monitoring Active" notification appears
2. Navigate away from app → verify notification persists (service keeps process alive)
3. Uncheck all trigger modes → verify notification disappears
4. Re-enable a trigger mode → notification reappears
5. Disable SOS entirely → notification disappears
6. Trigger SOS (Active state), uncheck all modes → verify notification stays (SOS is active)
7. Deactivate SOS → notification disappears

### Regression Checklist
- [ ] Regular messages still display correctly (no false SOS detection)
- [ ] Contact pin/unpin still works
- [ ] Contact removal still works
- [ ] Existing notifications (messages, announces, BLE) unaffected
- [ ] Settings screen loads without crash (SOS card present)
- [ ] App startup time not degraded

---

## Screenshots

_To be added after implementation._

---

## Risk Assessment

- **Low risk**: Tag-based SOS contact selection uses existing DB infrastructure (no migration)
- **Low risk**: SOS detection regex is conservative (requires "GPS:" prefix)
- **Medium risk**: Periodic GPS updates may impact battery — mitigated by configurable interval (default 120s) and opt-in setting
- **Low risk**: Battery/telemetry data uses standard Sideband `FIELD_TELEMETRY` format — interoperable with existing Reticulum ecosystem clients
- **Low risk**: Receiver-side changes are additive (new notification channel, new bubble color) — existing flows untouched when message is not SOS
- **Low risk**: Accelerometer listener uses `SENSOR_DELAY_GAME` (~20ms) for reliable tap detection; unregistered when no gesture mode is active. Power button receiver registered only when power button mode is active. Foreground service notification only shown when detection is active
- **Low risk**: State persistence uses existing DataStore infrastructure; 3 boolean/int keys with negligible storage overhead
- **Low risk**: Boot receiver starts services automatically — if SOS is disabled, services stop within seconds after DataStore is read
- **Medium risk**: Shake/tap false positives — mitigated by configurable sensitivity, cumulative duration threshold (shake), spike-based state machine with duration filter (tap rejects walking/sustained motion), and always requiring the countdown phase
- **Low risk**: Power button trigger uses 3 presses (vs Android's 5-press Emergency SOS) — no conflict. SCREEN_OFF events are reliable across Android versions
