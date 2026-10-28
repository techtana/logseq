# Android: blank gap above the keyboard after opening settings

- Date: 2026-10-27
- Platform: Android (Pixel 11 Pro, Android 17, Android System WebView 156)
- Fix: `fix(android): keep editor visible above the keyboard` (e76a166f22)
- Follow-up: `enhance(mobile): edit sync server URL inline in settings` (9d187e1aa7)

## Symptom

On the Journals tab (local and synced graphs), tapping a block opened the
keyboard with a large white gap between the edited text and the keyboard. The
gap could be dragged down to reveal more text. It never happened right after
launching the app, only after visiting another screen, e.g. settings, and
coming back.

## Root cause

Three things handled the keyboard at once, and one of them kept stale state.

1. **The native host already resizes the WebView.** The
   `@capacitor-community/safe-area` plugin pads the activity's decor view by
   the IME height, so the WebView ends at the keyboard top. With the keyboard
   open, `window.innerHeight` drops from 828 to 431 CSS px.
2. **WebView M139+ also resizes its visual viewport.** WebView shrinks the
   visual viewport for insets and for any part of itself that sits below its
   window. That's fine for a single WebView, but this app moves one WebView
   between windows:
   `NativeBottomSheetPlugin` puts it into a `BottomSheetDialog` for settings,
   where part of it hangs below the sheet, so the visual viewport shrinks to
   489.5 px. When the WebView moves back to the main window, nothing passes
   it fresh insets, so the shrink stays ("ghost padding" in the Android docs).
   On the next tap, WebView squeezes the cursor into a ~92 px visual viewport
   and pans the page up 308 px. That pan is the blank gap.
3. **The web layer padded for the keyboard again.** `:mobile/keyboard-will-show`
   set `--ls-native-kb-height` to the keyboard height and the scroll
   containers added it as `padding-bottom` (430 px). That's right for iOS,
   where the keyboard overlays the WebView (`Keyboard.resize: None`), but on
   Android the WebView was already resized, so the padding overflowed the
   visible area.

"Works on first launch" came from item 2: before the WebView had visited
another window there was no stale shrink.

### Evidence (measured over CDP on the device)

| Step (keyboard open, block focused) | Before | After |
|---|---|---|
| First launch | vv 431, no pan | vv 431, no pan |
| Sheet closed, keyboard closed | vv **489.5** on an 828 page | vv 828.6 |
| Tap block after settings | vv **91.8**, pan **308.6**, editor under the header | vv 430.9, no pan, editor visible |
| Keyboard padding | 430 px | 32 px |

(vv = `visualViewport.height`, pan = `visualViewport.offsetTop`, CSS px.)

## Fix

- `ComposeHost.kt`: the WebView's container clears system bar, display cutout
  and IME insets before they reach the WebView, and hands the WebView fresh
  cleared insets every time it's attached (`OnHierarchyChangeListener`).
  Passing cleared, non-consumed insets is the documented way to reset
  WebView's visual viewport. The host already keeps the WebView clear of
  these (NavHost system bar padding, SafeArea IME padding).
- `NativeBottomSheetPlugin.kt`: the sheet window uses `SOFT_INPUT_ADJUST_RESIZE`.
  The default pan can't help in the sheet: the focused view is the whole
  WebView, which is taller than the area above the keyboard, so Android
  doesn't pan and inputs (Sync Server URL, Login) ended up behind the keyboard.
- `events.cljs`: set `--ls-native-kb-height` on iOS only.
- `util.cljc`: on Android, `scroll-editor-cursor` uses `visualViewport.height`
  instead of iOS's `screen.height - keyboard-height`.

## Decisions

- **One owner for the keyboard on Android: the native host.** The WebView is
  resized above the keyboard natively; WebView's own inset handling and the
  web keyboard padding are switched off for the main window. The alternative
  (let the keyboard overlay the WebView like iOS) would fight SafeArea and the
  native editor toolbar, which already measure against a resized content root.
- **Clear insets for the WebView instead of consuming them.** Returning
  `WindowInsetsCompat.CONSUMED` stops propagation, so WebView never learns
  about later changes and keeps old state. Zeroed, non-consumed insets keep
  the update flowing and tell WebView there's nothing to adjust.
- **Sheet: resize mode only, no measured padding.** A version that padded
  the sheet container by its measured overlap with the keyboard shrank the
  WebView to 296 px, because it measured while the sheet was still collapsed.
  Removed. Known gap: the expanded sheet still hangs ~55 CSS px below the
  visible area (this predates the fix), so a centered dialog's bottom edge
  can sit just under the keyboard.
- **Sync Server URL is an inline field, not a dialog inside the sheet.**
  Saving or resetting blurs the field (hiding the keyboard), then asks for
  confirmation; Cancel restores the saved value. Desktop and mobile share
  `settings/<set-sync-server-url!`.
- **Left `WebViewKeyboardRecovery` in place.** It was probably a workaround
  for this same stale state, but removing it wasn't verified on device.

## Lessons

- **Reparenting a WebView between windows carries WebView-internal state.**
  Since M139 that includes the visual viewport size. Any code that moves
  the WebView (navigation in `ComposeHost`, the settings sheet) must hand it
  fresh insets afterwards.
- **Count who handles the keyboard.** On Android check the native layer
  (SafeArea/SystemBars plugins, decor padding), WebView itself (M139+), and the
  web CSS. Each one alone is fine; two or more stack.
- **`window.innerHeight` vs `visualViewport.height` tells you which layer
  moved.** A resized layout viewport means the native host resized the
  WebView; a smaller visual viewport means WebView is compensating itself.
- **promesa swallows falsy rejections after a `p/then`.** `shui/dialog-confirm!`
  cancels by rejecting with `false`. promesa's own promise implementation
  treats a falsy reason as success when forwarding through `p/then`, so a
  `p/catch` chained after `p/then` never runs. Attach the cancel handler
  directly to the dialog promise.
- **Test on the device, not in theory.** The first explanation (IME insets
  counted twice) didn't match first-launch measurements; the reproduction
  showed the stale shrink came from the settings sheet. A variant build
  without the native change also showed the Sync Server URL issue was
  already there, which avoided chasing a false regression.

## How it was debugged

- Debuggable build + `adb forward tcp:9333 localabstract:webview_devtools_remote_<pid>`,
  then `Runtime.evaluate` over the CDP WebSocket to read `innerHeight`,
  `visualViewport`, scroll container geometry, and React hook state.
- `adb exec-out screencap`, `uiautomator dump` for coordinates, `input tap`
  to drive the flow, `dumpsys window` for soft input modes
  (`adjust=pan` on the sheet vs `adjust=resize` on the main window).
- Logcat (`Capacitor/KeyboardPlugin`, `InsetsController`) showed the Keyboard
  plugin fires no events for the sheet's dialog window.

## Build notes for this machine

- Both installed JDKs are Java 25; `master`'s Gradle 8.14.3 wrapper can't run
  on it. Gradle 8.14.5 can.
- A worktree needs `node_modules` (a junction to the main checkout works),
  the generated `android/capacitor-cordova-android-plugins/`, and
  `android/local.properties` before Gradle can configure. Remove the junction
  afterwards so a worktree cleanup can't recurse into the main `node_modules`.
- Web bundle: `pnpm release-mobile`, delete `static/mobile/**/*.map`, then
  `pnpm exec cap copy android`.
