# Keyboard transcript history + retry last recording

## Why
Sometimes a transcript comes back but never lands in the text field, sometimes
processing fails, and once the keyboard got stuck on "Processing" until a
series of keyboard closes and model/language switches freed it. The user wants
to recover their words in all of these cases.

## Goals
- A history button on the voice keyboard showing the last 5 transcripts; tap
  one to insert it at the cursor.
- "Retry last recording": re-transcribe the last captured audio, so a failed or
  stuck attempt is not lost.
- Fix the state bug that leaves the keyboard believing it is still processing.

## Non-goals
- History for the popup (RecognizeActivity), speech service or live subtitles.
- Editing, searching or syncing history. More than 5 entries.

## Anti-goals
- Losing a transcript because insertion failed: it is saved before insertion.
- Heavy storage writes: one audio file, replaced on each recording.

## Approaches (decided)
- Button placement: **A — icon button at the right end of the status row**
  (chosen; keyboard height and space bar unchanged) vs B — extra key in the
  bottom row (narrower space bar).
- Recording storage: **saved to a file at stop, before processing** (chosen;
  survives stuck states and keyboard restarts) vs memory only (lost on restart).

## Design
**UI.** A clock icon at the right of the status row. Tapping it swaps the
record area for a history panel of the same height (keyboard height
unchanged): header with "Retry last recording" and close (✕); below it up to
5 transcripts, newest first, each one line, ellipsized, with relative time.
Tap a transcript → insert it at the cursor (same spacing rules as a normal
dictation) and close the panel. Tapping the clock again or ✕ closes it.
Empty state: "No transcripts yet". The bottom key row stays usable.

**History storage.** Every non-empty transcript is added (newest first, max 5,
oldest dropped) the moment `onTextTranscribed` delivers it, before committing
it to the field. Stored in a small file in the app's private files dir, so it
survives keyboard/process restarts. Pure-Java store class with unit tests.

**Last recording.** When the keyboard stops a recording for transcription, the
captured audio is written to one file in the app's private files dir
(16 kHz mono 16-bit, ~1.9 MB/min), replacing the previous one. Keyboard only:
the popup recognizer that shares the Rust session code does not write it.
Discarded (canceled) recordings don't replace it.

**Retry.** Re-runs the saved audio through the normal transcription path
(same model loading, status updates, result delivery and history saving).
Disabled while recording or processing, and when no saved recording exists.
After a successful retry the text is inserted and added to history like a
normal dictation.

**Stuck-processing fix.** While the keyboard is processing, every status
starting with "Error" ends the attempt (except the "… - using built-in model"
fallback notice, which continues with the bundled model). Today only a few
specific errors do, so e.g. "Error: transcription failed unexpectedly" leaves
the keyboard thinking it is busy: mic taps and auto-record are ignored and
chip switches stay queued. The Rust worker must also always end with a final
status or text (no silent returns, e.g. when attaching the JNI thread fails).

**Strings.** New strings in all 7 locales (en, de, es, fr, it, pt, ru).

## Added after plan review (orchestrator's calls, user asleep)
- The panel covers the chip bar + record area (200dp) so 5 rows fit; keyboard
  height unchanged.
- "Clear" (two taps) wipes history and the saved recording — privacy.
- Both files are excluded from Android cloud backup / device transfer.
- Escape hatch: a mic or Retry tap while "Processing" with no transcription
  worker alive resets the keyboard to idle. Worker panics and a panicking
  model load now end with an error instead of hanging.
- Long-press "transcribe then leave" no longer leaves on the
  "… using built-in model" notice before the text arrives.

## Left to the builder
- File formats/names, JNI shape of the save and retry calls.
- Icon drawable (Material "history" style, tinted like the other keys).
- Content descriptions for accessibility.
