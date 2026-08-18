# Mouse

An Android accessibility tool for a phone with a partly dead or ghost-touching screen. It blocks
touches in the damaged region and gives you a floating trackpad plus an on-screen cursor to drive
the phone with instead.

## How it works

Three overlay windows and one accessibility service:

| Piece | File | Job |
| --- | --- | --- |
| Blocker | `BlockerView.kt` | Transparent window over the top of the screen. `onTouchEvent` returns `true`, so every ghost touch inside it dies there instead of reaching the app below. |
| Cursor | `CursorView.kt` | Small untouchable window drawn at the virtual pointer. Its centre is the hot spot that gets clicked. |
| Trackpad | `TrackpadPanel.kt` + `res/layout/overlay_trackpad.xml` | Draggable panel, parked bottom right. Finger movement becomes relative cursor movement. |
| Input | `MouseAccessibilityService.kt` | `dispatchGesture` for tap / long press / drag, `performGlobalAction` for Back, Home and Recents. |

`OverlayService.kt` is the foreground service that owns the windows, holds the cursor position and
wires the trackpad to the accessibility service through the `MouseController` interface.

### Two details that are easy to get wrong

**Injected gestures hit our own overlays.** `dispatchGesture` goes through the normal input
pipeline, so a tap dispatched at the cursor lands on whichever window is topmost at that point -
which is the blocker. Before every gesture, `OverlayService` adds `FLAG_NOT_TOUCHABLE` to the
overlays that the gesture actually passes through, and restores them from the gesture callback
(with a timeout as a safety net, so the screen can never be left permanently unblocked). Overlays
that are not in the path stay touchable, so a finger still resting on the trackpad keeps working.

**Window opacity.** Since Android 12 the platform discards touches that pass through an untrusted
overlay above a certain opacity. The blocker and cursor windows are therefore given a window alpha
below that threshold (`0.5` and `0.75`), which is why the blocked region is drawn as an outline
with a light wash rather than a solid fill.

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
2. Set how much of the screen to block (default: the top 60%) and the cursor speed.
3. Press **Start**.

On the trackpad:

- Slide to move the cursor; lift to tap where it ended up.
- Hold to long press at the cursor.
- **Move** mode (the `Tap` / `Move` button) stops a lift from tapping, for repositioning only.
- **Drag** marks the cursor position as a start point; move and lift to drag from there.
- **Back**, **Home** and **Apps** are global actions, useful when the navigation area is dead.
- The header bar drags the panel around; `−` shrinks it to a puck, tap the puck to bring it back.

The notification carries a blocker toggle and a stop button.

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
