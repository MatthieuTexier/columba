# PR: SOS Emergency Feature

> **Branch**: `feature/sos-emergency`
> **Target**: `main`
> **Title**: feat: add SOS Emergency feature with sender/receiver integration

---

## Summary

Add a complete SOS Emergency feature to Columba, allowing users to send emergency distress signals to pre-selected contacts over the LXMF mesh network. The feature includes a full sender-side state machine with countdown, GPS location embedding, periodic updates, and a receiver-side experience with urgent notifications, visual SOS message differentiation in chat, and one-tap map navigation.

**Key capabilities:**
- Designate contacts as "SOS contacts" (tag-based, persisted in DB)
- Trigger SOS with optional countdown timer (configurable 0-30s)
- Automatic GPS location embedding in SOS messages
- Periodic location updates while SOS is active
- PIN-protected deactivation (optional)
- Silent auto-answer for incoming calls during SOS (optional)
- Floating SOS trigger button (optional)
- Receiver-side: urgent persistent notifications with "Open Chat" and "View on Map" actions
- Receiver-side: SOS messages displayed with red emergency styling in chat
- Receiver-side: clickable "View on Map" button for GPS-tagged SOS messages

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
- In the **Contacts** screen, long-press a contact → context menu → "Mark as SOS Contact" / "Unmark as SOS Contact"
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

All settings are persisted in DataStore (`SettingsRepository`) as typed `Flow<T>` properties.

#### SOS Trigger Flow
1. User triggers SOS (floating button, or programmatic trigger)
2. If countdown > 0: state transitions to `Countdown`, timer ticks down, user can cancel
3. If countdown = 0 or timer expires: state transitions to `Sending`
4. `SosManager` collects all SOS-tagged contacts, builds the message:
   - Template text + GPS coordinates (if enabled): `"SOS! I need help.\nGPS: 48.85660, 2.35220 (accuracy: 10m)"`
   - Sends via `ReticulumProtocol.sendLxmfMessageWithMethod()` to each SOS contact
5. State transitions to `Active` with success/fail counts
6. Persistent notification shows "SOS Active — X contacts notified"
7. If periodic updates enabled: sends "SOS Update - GPS: lat, lng (accuracy: Xm)" at configured interval

#### SOS Deactivation
- If no PIN configured: simple deactivation returns to `Idle`
- If PIN configured: user must enter correct PIN to deactivate
- Wrong PIN: state stays `Active`, error counter increments
- Deactivation cancels periodic update job and clears the persistent notification

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

### Modified Files

| File | Changes |
|------|---------|
| `data/.../model/EnrichedContact.kt` | Added `val isSosContact: Boolean get() = getTagsList().contains("sos")` |
| `data/.../repository/ContactRepository.kt` | Added `toggleSosTag()`, `getSosContacts()`, `getSosContactsFlow()` methods |
| `app/.../repository/SettingsRepository.kt` | Added 9 SOS DataStore properties (`sosEnabled`, `sosMessageTemplate`, `sosCountdownSeconds`, `sosIncludeLocation`, `sosSilentAutoAnswer`, `sosShowFloatingButton`, `sosDeactivationPin`, `sosPeriodicUpdates`, `sosUpdateIntervalSeconds`) with getter flows and setter methods |
| `app/.../viewmodel/SettingsViewModel.kt` | Added SOS state fields, collector coroutines for all 9 SOS settings, and setter methods (`setSosEnabled`, etc.) |
| `app/.../viewmodel/ContactsViewModel.kt` | Added `toggleSosTag(destinationHash)` method |
| `app/.../notifications/NotificationHelper.kt` | Added `CHANNEL_ID_SOS`, `NOTIFICATION_ID_SOS`, `ACTION_SOS_CALL_BACK`, `ACTION_SOS_VIEW_MAP`. Added `notifySosReceived()`, `showSosActiveNotification()`, `cancelSosActiveNotification()`, `isSosMessage()`, `parseSosLocation()` |
| `app/.../service/MessageCollector.kt` | Added SOS detection branch before regular notification posting |
| `app/.../ui/screens/ContactsScreen.kt` | Added `isSos`/`onToggleSos` params to `ContactContextMenu`, SOS toggle menu item with Warning icon, red badge overlay on SOS contact avatars in `ContactListItem` |
| `app/.../ui/screens/MessagingScreen.kt` | Added SOS bubble styling (`errorContainer` color), "SOS EMERGENCY" header badge, "View on Map" button for GPS-tagged messages, `isSosMessageContent()` and `parseSosGpsLocation()` helper functions |
| `app/.../MainActivityIntentHandler.kt` | Added `ACTION_SOS_CALL_BACK` → conversation navigation, `ACTION_SOS_VIEW_MAP` → map focus navigation |
| `app/.../MainActivity.kt` | Added `PendingNavigation.SosMapFocus` sealed class variant, navigation handler for SOS map focus |
| `app/.../service/SosManager.kt` | Integrated `NotificationHelper` for sender-side persistent notification |

### SOS Message Format

```
SOS! I need help. This is an emergency.
GPS: 48.85660, 2.35220 (accuracy: 10m)
```

Periodic update:
```
SOS Update - GPS: 48.85665, 2.35225 (accuracy: 8m)
```

GPS parsing regex: `GPS:\s*(-?\d+\.?\d*),\s*(-?\d+\.?\d*)`

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

### SosManagerTest (25 tests)
Tests the core state machine:
- Initial state is `Idle`
- Trigger with countdown starts countdown, ticks correctly, transitions to Sending
- Trigger without countdown (0s) goes directly to Sending
- Cancel during countdown returns to Idle
- Deactivation without PIN succeeds
- Deactivation with correct PIN succeeds
- Deactivation with wrong PIN fails, error counter increments
- Auto-answer check during Active state
- Message template composition with GPS coordinates
- Lifecycle: re-trigger after deactivation works

**Key testing patterns:**
- `mockkObject(CallCoordinator.Companion)` for singleton companion objects
- `TestScope` + `StandardTestDispatcher` for coroutine control
- `advanceTimeBy()` for countdown tick verification
- Strict mocks (`mockk()` not `mockk(relaxed = true)`) with explicit stubs

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
- **SettingsViewModelTest**: Added stubs for all 9 SOS settings flows + `contactRepository.getSosContactsFlow()`
- **SettingsViewModelIncomingMessageLimitTest**: Same SOS stubs

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

### Scenario 3: GPS Location
1. A enables "Include Location" with GPS active
2. A triggers SOS → verify B receives message with "GPS: lat, lng"
3. B taps "View on Map" in notification → verify map centers on A's location
4. B opens chat → verify red SOS bubble with "View on Map" button

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
2. Verify B receives initial SOS message
3. Wait 60s → verify B receives "SOS Update - GPS: ..." message
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

### Scenario 11: Resilience (App Kill)
1. A triggers SOS, force-kill app
2. Relaunch → verify SOS state is reset to Idle (not stuck in Active)
3. Verify sender notification is cleared

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
- **Low risk**: Receiver-side changes are additive (new notification channel, new bubble color) — existing flows untouched when message is not SOS
