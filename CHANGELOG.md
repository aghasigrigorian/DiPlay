# Unreleased

- Open DiPlay when the selected iPhone joins the car's Bluetooth (opt-in, *Open when your iPhone connects*). Head units usually wake from sleep instead of rebooting, so the boot trigger alone never fired on an ordinary drive; reported on a Song Plus (DiLink 4.0).
- *Open after the car starts* also reacts to quick boot.
- Android 10+ only lets DiPlay open itself from the background with *Display over other apps*; the settings page offers the permission, and without it DiPlay posts a notification to tap. Repeated triggers within 30 s count once; nothing happens while a CarPlay session runs.
- Decision logic is covered by unit tests. Not yet validated on the DiLink 5.1 development car.
- Phone calls: new *Car Bluetooth address* setting. iOS hands calls to CarPlay and drops the car's hands-free link only when it recognises the car's Bluetooth address in what DiPlay advertises; firmware that hides the address from apps got a synthetic one, and the car's own call screen then appeared next to CarPlay's (reported on a Song Plus, DiLink 4.0; likely the cause of the garbled call audio in #1). The address is now also read from the kernel where readable, the advertised value is written to the diagnostic log, and the home screen warns while the car's phone link stays connected during a session.
- Calls through CarPlay no longer feed the car speakers back to the far end: the platform echo canceller and noise suppressor are attached to the call microphone where the firmware offers them, and the head unit is switched to in-communication mode for the duration of the call. Reported after routing calls to CarPlay on a Song Plus (the car's *Call Audio* Bluetooth toggle off): the other party heard themselves.
- The app now carries its own echo canceller (vendored speexdsp, BSD) for head units whose firmware offers none: the far end's voice, as written to the car speakers, is subtracted from the microphone before it goes to the iPhone. *Settings → Phone calls → Echo cancellation*: Automatic (head unit first, app if missing), Always in the app, Off. The settings page also shows which microphone and which cancellers the last call used.
- The app canceller lines its reference up with what the player still holds in its buffer, runs speexdsp's residual-echo suppressor and denoiser after the adaptive filter, takes the raw microphone in *Always in the app* mode, and reports its reference lead and coverage on the settings page after the call. During the call it measures the real echo delay (speakers → cabin → microphone) by correlation and moves the reference to match, with a 1 s filter window and stronger residual suppression; the measured delay is shown after the call. The reference now follows the far end across sample-rate changes (resampled onto one timeline) and re-anchors after long silences; the settings page lists which streams fed it.
- Call volume: the car's knob usually moves only the media stream, so drivers turned it up for calls and got blasted when the music came back. *Remember call volume separately* (on by default) behaves like the car's own phone: during a call the media volume is set to the remembered call level, whatever the knob is turned to during the call is remembered for the next one, and the music returns at its previous level when the call ends. The car's call stream also runs at its maximum during a call and is put back afterwards.
- Echo test without a call (*Settings → Phone calls → Run echo test*): plays a recorded voice through the speakers on the call route, captures the microphone, runs the app canceller as during a call, reports what it removed, and lets you play back the raw and the cleaned recording. The clip is a synthetic voice generated with a text-to-speech engine.

# 0.2.0 — BYD navigation and connection improvements

- Standalone windshield HUD arrows, distance and street names on the verified DiLink5.1 firmware; no ADB, root or computer helper.
- Retain contributor cluster/SOME-IP navigation, route parsing, BYD CarPlay icon and display-size presets.
- Fix Car hotspot startup by using scoped IPv6 when available and binding discovery/probing to the AP interface. Physically confirmed on the development car.
- Drain asynchronously decoded audio during packet gaps and rebuild the music buffer after starvation. Wi-Fi Direct is much better in the user retest; occasional audio cutouts remain for a later version.
- Preserve bounded music-buffer choices, USB read improvements and decoder recovery; fix USB request/close races and keep vendor output outside phone callbacks.
- Save audio/video/receive timing and discovery diagnostics without road names or protocol payloads.
- HUD cleanup on normal end/disconnect/off/stale input; interrupted sessions recover on the next app launch. Force-stop may leave guidance visible until reopening.
- Thanks to @romanchukg-cloud and @georgiyrr for PR #3 and vehicle testing.

# 0.1.0 release restored — 2026-09-25

- Rebuilt and signed the APK locally with explicitly supplied runtime authentication assets.
- Restored release downloads; no app behavior or version-code change from 0.1.0.
- Accessory identity remains in the APK only. No credential files enter Git or the source archive.
- Retained generated test identities and public-source credential checks.
- Source/CI builds omit runtime identity assets by default; local packaging requires an explicit external directory.

# Source reset — 2026-09-25

- Withdrew the 0.1.0 APK and removed its release tag.
- Reset the public branch after preserving restricted local incident records.
- Removed static synthetic test private keys; generate test identities at runtime.
- Removed automatic private-asset packaging and disabled the old release build script.
- Added a build guard rejecting credential asset files.
- Replaced the download site with a five-language suspension notice.

The APK was subsequently rebuilt and restored as described above. Existing copies cannot be recalled by a Git history reset.
