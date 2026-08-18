# Mouse

An Android accessibility tool for a phone with a partly dead or ghost-touching screen. It blocks
touches in the damaged region and gives you a floating trackpad plus an on-screen cursor to drive
the phone with instead.

## How it works

Three overlay windows and one accessibility service:

| Piece | File | Job |
| --- | --- | --- |
| Blocker | `BlockerView.kt` | Transparent window over the top of the screen. `onTouchEvent` returns `true`, so every ghost touch inside it dies there instead of reaching the app below. |
| Cursor | `CursorView.kt` | Small untouchable window drawn at the virtual pointer. Its tip is the hot spot that gets clicked. |
| Control | `TrackpadPanel.kt` + `res/layout/overlay_trackpad.xml` | The floating control, in either shape: a full panel, or a bubble on its own. |
| Gestures | `PointerGestureDetector.kt` | Turns finger movement into tap, hold, drag and cursor movement. |
| Input | `MouseAccessibilityService.kt` | `dispatchGesture` for tap / long press / drag, `performGlobalAction` for Back, Home and Recents. |

`OverlayService.kt` is the foreground service that owns the windows, holds the cursor position and
wires the control to the accessibility service through the `MouseController` interface.

### Two shapes for the control

- **Trackpad panel** - a pad area plus Back, Home and Apps, dragged around by its header and
  collapsed to a puck with `−`. It opens away from whichever screen edge it is parked against, so
  a puck in the bottom right expands to the left and upwards.
- **Bubble only** - nothing but the puck. It follows your finger and the cursor moves with it;
  when you let go the bubble slides back to its home spot while the cursor stays where you left
  it, so the screen can be crossed in several strokes. The home spot is chosen from the settings
  screen: press "Choose where the bubble sits", drag the bubble, and let go.

### Gestures, in either shape

| Gesture | Result |
| --- | --- |
| Slide | The cursor moves. Lifting your finger does **not** tap. |
| Tap without sliding | Click where the cursor is. |
| Double tap, release | Long press at the cursor. |
| Double tap, then slide without lifting | Drag from the cursor. Slide straight away for a swipe (scrolling); rest a moment first and the drag presses and holds before moving, which is what picking an icon up needs. |

### Three details that are easy to get wrong

**Injected gestures hit our own overlays.** `dispatchGesture` goes through the normal input
pipeline, so a tap dispatched at the cursor lands on whichever window is topmost at that point -
which is the blocker. Before every gesture, `OverlayService` adds `FLAG_NOT_TOUCHABLE` to the
overlays that the gesture actually passes through, and restores them from the gesture callback
(with a timeout as a safety net, so the screen can never be left permanently unblocked). Overlays
that are not in the path stay touchable, so a finger still resting on the trackpad keeps working.

There is a second half to that: a window flag change only reaches the window manager on the next
frames, so the gesture is injected a beat after the overlays are opened rather than in the same
breath, or it would race the flag and land on the overlay it was meant to pass through.

**Window opacity.** Since Android 12 the platform discards touches that pass through an untrusted
overlay above a certain opacity. Every overlay is therefore kept below that threshold, and the
opacity slider in the app is capped at 80% for the same reason.

**The blocker is invisible in normal use.** Its dashed outline, wash and label are drawn only
while the settings screen is in front, so the blocked area can be seen while it is being adjusted
and nothing marks the screen afterwards. It still swallows touches either way.

## Permissions

- `SYSTEM_ALERT_WINDOW` - draws the three overlay windows. Granted by the user in Settings.
- `BIND_ACCESSIBILITY_SERVICE` - declared on the service so only the system can bind it. The user
  turns the service on under Settings › Accessibility › Mouse.
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE` and `POST_NOTIFICATIONS` - the overlays
  live in a foreground service so they survive backgrounding.

The accessibility service declares `canRetrieveWindowContent="false"`: it dispatches input and
nothing else, and never reads what is on screen.

## Using it

1. Open the app, grant "draw over other apps", then turn on the Mouse accessibility service.
2. Set how much of the screen to block (default: the top 60%), the cursor speed and the opacity.

3. Pick the control you want - trackpad panel or bubble only - and press **Start**.

**Back**, **Home** and **Apps** on the panel are global actions, useful when the navigation area
is dead. The notification carries a blocker toggle and a stop button.

## Building

```
./gradlew assembleDebug
```

Requires the Android SDK (compileSdk 34); minSdk is 24.

## Limitations

- `TYPE_APPLICATION_OVERLAY` windows cannot cover system UI such as the status bar shade, the
  lock screen, or permission dialogs, so ghost touches there are not blocked.
- Some apps mark their windows secure or filter obscured touches; the injected taps may be
  refused by those.
- A gesture is dispatched only after you lift your finger, so this is a point-and-click model,
  not live hover.
