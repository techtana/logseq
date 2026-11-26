# Android voice note widget

## Problem

On Android, adding a spoken note to the journal took several steps: open
Logseq, open the capture tab or a block, then use the keyboard's voice typing
key. The in-app recorder doesn't help here. It saves an audio file and only
transcribes on iOS 26+, because `UILocal.transcribeAudio2Text` rejects on
Android. Users want a home screen widget that starts dictation in one tap and
keeps going across pauses, shows the transcript while they speak, and has
settings for where the note goes and which language it's transcribed in.

## Behavior

- A 1x1 "Voice note" widget (`VoiceNoteWidgetProvider`). A tap opens Logseq
  with `logseq://mobile/go/voice-note`.
- `mobile.deeplink` publishes `:mobile/start-voice-note`, which opens the
  recorder sheet (`mobile.components.voice-note`) and starts a session
  (`mobile.voice-note`).
- The sheet shows the destination, listening time, a level ring, the
  transcript (recognized phrases plus the phrase being spoken, dimmed), and
  Discard, Pause/Resume and Done controls.
- Each recognized phrase is written into the note's block right away. The
  first phrase creates the block at the end of today's journal, creating the
  page if needed:
  - If the parent block setting is blank, the note is a top-level block.
  - Otherwise it's the last child of the first top-level block whose title
    matches the setting. Matching is plain text, ignores case and surrounding
    whitespace, and the parent block is created when it's missing.
  Later phrases update the same block's title.
- Done (or swiping the sheet away) stops listening and saves the rest.
  Discard stops listening and deletes the note's block.
- Settings (Android only, in mobile settings, stored in localStorage as
  `:ls-mobile-voice-note`): the parent block title and the transcription
  language, either a BCP 47 tag from `voice-note/transcription-languages` or
  empty for the device default.

## Motion beside the mic

- The widget is 2x1 by default and can be resized down to 1x1. The mic has a
  slot on each side for an animated icon: sound waves, equalizer, heartbeat,
  arrows, sparkles, music notes, a GIF the user picks, or nothing.
- Each widget has its own settings (`VoiceNoteWidgetConfigActivity`): the
  motion, its sides (left, right or both) and its direction. With both sides
  the icons can be mirrored (pointing away from or toward the mic) or point
  the same way (both right or both left). With one side they point away from
  or toward the mic. A live preview builds the real widget views.
- The settings open when the widget is placed. On Android 12+ they're optional
  there (`configuration_optional`) and open again from the widget's settings
  on the home screen (`reconfigurable`). A widget that was never configured
  shows sound waves on both sides, pointing away from the mic.
- Settings are stored per widget id in the `voice_note_widget` shared
  preferences, and GIF frames as PNGs under `files/voice_note_widget/<id>/`.
  They're removed with the widget and moved to the new ids when widgets are
  restored from a backup.

## Native plugin

`VoiceNotePlugin` (`VoiceNote`) wraps `SpeechRecognizer` with
`EXTRA_PARTIAL_RESULTS`:

- `start({language})` asks for the microphone permission if needed, then
  listens until `stop` (which ends after the current phrase's final result)
  or `cancel` (which ends right away).
- Android's recognizer ends after each utterance. While a session is active it
  is started again after every result and after silence (`ERROR_NO_MATCH`,
  `ERROR_SPEECH_TIMEOUT`). `ERROR_RECOGNIZER_BUSY` and `ERROR_CLIENT` are
  retried a few times after a short delay. Other errors end the session with
  an `error` event (codes: permission, network, language, other).
- Events: `state` {listening}, `partial` {text}, `result` {text},
  `level` {level 0..1, throttled to about 12 per second}, `error` {code}.
- The screen is kept on during a session. When Logseq goes to the background
  the recognizer loses the microphone, so the session is stopped after the
  current phrase; the sheet shows it as paused.

## Decisions

- **Logseq's own UI instead of the system voice typing dialog.** The dialog
  ends after one utterance, and its result could only reach the journal after
  switching to Logseq. Dictating inside Logseq keeps one session going and
  shows the transcript as it grows.
- **The sheet reuses the native bottom sheet popup** that the in-app audio
  recorder uses.
- **Write each phrase as it's recognized.** The note survives the app being
  killed mid-dictation, and it's already on the page when the sheet closes.
  Writes are queued one after another, and each writes the latest transcript,
  so a failed write is made up by the next. If the last write fails, Done
  leaves the session open (paused) so it can be retried.
- **Partial text isn't written.** It changes several times a second.
- **Parent lookup happens again after creation.** Inserting with
  `:replace-empty-target? true` reuses an empty block's uuid, so the uuid
  returned for the new block can be stale. The note's block is inserted
  without replacing, so its returned uuid is reliable.
- **Curated language list.** Recognizer language discovery is device-dependent
  (`ACTION_GET_LANGUAGE_DETAILS`, or `SpeechRecognizer.checkRecognitionSupport`
  on API 33+). Labels are endonyms from `Intl.DisplayNames`, so they need no
  translation keys.

- **Animated icons are animated vector drawables in an indeterminate
  `ProgressBar`.** App widgets can't start an `ImageView`'s animation, but a
  `ProgressBar` starts any `Animatable` indeterminate drawable when it's
  shown, and stops it when it isn't. The animators repeat forever on the
  RenderThread. They're drawn pointing right, and a wrapper layout with
  `scaleX="-1"` mirrors them, so each icon needs one drawable and one layout.
- **A GIF plays as frames in a `ViewFlipper`.** Widgets can't decode GIFs.
  The picked GIF is sampled with `android.graphics.Movie` (deprecated, but
  the only platform decoder that can seek) into at most 30 frames, at least
  60 ms apart, scaled to fit 32x64 dp. Both sides add the same `Bitmap`
  objects, which `RemoteViews` sends once.
- **Store access stays on the main thread**, where the widget provider runs,
  so an update never reads GIF frames while they're being replaced. Writing
  30 small PNGs on Save takes a moment at most.

## Limits

- Animated icons stay still when animations are turned off on the phone
  (animator duration scale 0); the settings screen says so. GIFs still play.
- Frames are sampled at even intervals, so GIFs with uneven frame delays play
  at an average speed, and GIFs longer than about 2 seconds drop frames.
- Mirrored music notes have their flags on the wrong side.
- On Android 11 and older the settings only open when the widget is placed;
  changing them means placing the widget again.

- Phrases are joined with spaces, which reads oddly for languages written
  without them, such as Chinese and Japanese.
- Words spoken in the moment between one utterance ending and the recognizer
  restarting can be missed.
- Parent matching compares the raw block title. A block whose title is a page
  reference such as `[[Journal]]` is stored with a uuid ref and won't match a
  plain `Journal` setting.

## Tests

`src/test/mobile/voice_note_test.cljs` drives sessions through a fake plugin:
phrases written under an existing parent and then updated, a missing parent
created, a blank parent giving a top-level note, Discard deleting the note,
a failed write keeping the session open, finishing with no speech, status
changes from recognizer events, listening time, default settings, and the
deeplink.

The widget's motion icons and settings screen have no automated tests; the
Android app has no instrumentation tests. They were checked by building the
app and by playing the drawables' animations in a browser.
