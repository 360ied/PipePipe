# Codec capability Detect button — design

Why `Enable advanced formats` is manual today, what a `Detect`
button would query, and how to add it without silently overriding
the user's choices.

- Scope: `PipePipeClient/app/src/main` (settings screen + a new
  detector helper + stream filtering call sites, unchanged).
- Audited: 2026-10-25, `main` (revalidate refs at implementation time).
- All paths below are relative to the repository root.
- Status: **proposal** (not implemented).

## 1. Summary

`Enable advanced formats` (`video_audio_settings.xml`) is a
`MultiSelectListPreference` with values `VP9 / AV01 / EC-3`,
default `[VP9]`. At playback/download-list time the app does a pure
preference check — `formats.contains(...)` — with no hardware query
of any kind (`VideoStreamSelector.java` for video,
`StreamFilters.java` for EC-3 audio). Tick something the device
can't decode and ExoPlayer fails at render time, which is exactly
what the preference summary warns about.

Proposal: add a `Detect supported formats` button directly under the
preference. It queries device decoders (ExoPlayer's
`MediaCodecUtil` as source of truth, framework `MediaCodecList` as
fallback), maps the result onto the three toggles, and presents the
outcome in a confirm dialog — the user reviews and applies, nothing
is overwritten silently. The filtering call sites stay untouched;
only the stored set changes.

## 2. Current behavior (evidence)

- Values + default,
  `PipePipeClient/app/src/main/res/values/settings_keys.xml:1269-1276`:
  `advanced_formats_values = [VP9, AV01, EC-3]`,
  `default_advanced_formats_values = [VP9]`.
- Preference,
  `PipePipeClient/app/src/main/res/xml/video_audio_settings.xml:36-43`:
  `MultiSelectListPreference`, `defaultValue =
  @array/default_advanced_formats_values`.
- Defaults seeding,
  `PipePipeClient/app/src/main/java/org/schabi/newpipe/settings/NewPipeSettings.java:74-75`:
  `setDefaultValues(..., R.xml.video_audio_settings, true)` on startup
  (`readAgain=true`, so new defaults backfill). Static, not per-device.
- Video gate,
  `PipePipeClient/app/src/main/java/org/schabi/newpipe/util/VideoStreamSelector.java:433-434,453-461`:
  `useWebM = formats.contains("VP9")`, `useAV1 =
  formats.contains("AV01")`; `vp9/vp09` family requires the former,
  `av01` the latter, everything else (AVC) always passes. No
  `MediaCodec*` reference anywhere on this path.
- Audio gate,
  `PipePipeClient/app/src/main/java/org/schabi/newpipe/util/StreamFilters.java:107-127`:
  `useDolbyAudio = advancedFormats.contains("EC-3")`; `ec-3` streams
  pass only when set (`flac` is hard-rejected regardless). Same story:
  preference check only.
- Player stack, `player/helper/CustomRenderersFactory.java`: only the
  framework `MediaCodecVideoRenderer` is wired up; the class comment
  notes no ExoPlayer extension (e.g. the AV1 one) is shipped. So for
  AV1 there is no bundled software fallback — the device decoder is
  the only path, which is precisely what detection must test.

## 3. Detection building blocks (both already available)

`minSdk 30`, ExoPlayer `2.18.7`
(`PipePipeClient/app/build.gradle:51,206`), so all of this is usable
without new dependencies or version guards:

| Toggle | MIME to query | Notes |
|---|---|---|
| `VP9` | `video/x-vnd.on2.vp9` | Hardware ubiquitous since ~2016; still worth checking. |
| `AV01` | `video/av01` | Framework software decoder exists from Android 10, hardware varies. Presence ≠ usable 4K. |
| `EC-3` | `audio/eac3` | Dolby Digital Plus. Presence covers decode; passthrough-to-TV is a separate question (see 5.3). |

Two query layers, in priority order:

1. **ExoPlayer `MediaCodecUtil` / `MediaCodecSelector.DEFAULT`** —
   answers "what would the actual renderer pick", consistent with
   `CustomRenderersFactory`. Preferred source of truth.
2. **Framework `android.media.MediaCodecList.getCodecInfos()`** —
   filter `!isEncoder()`, check `getSupportedTypes()`. Fallback and
   unit-test seam (inject a fake codec list; no device needed).

Useful discriminators on `MediaCodecInfo` (all available at
`minSdk 30`): `isHardwareAccelerated()`,
`getCapabilitiesForType(mime).getVideoCapabilities().areSizeAndRateSupported(w, h, fps)`.
No `Build.VERSION` guards needed for these; keep one for any
future `MediaCapabilities` use.

## 4. Proposal

1. **New helper, e.g. `util/CodecSupportDetector.java`.** Pure,
   testable, no Android-preference coupling:
   `detect(Set<String> mimes) -> Map<String, CodecSupport>` where
   `CodecSupport = { SUPPORTED_HW, SUPPORTED_SW_ONLY, UNSUPPORTED }`
   plus the backing decoder name for the dialog/debug log. Video
   entries additionally record the max tested size (1080p/2160p,
   per the decided gate in 8). Runs off the UI thread (decoder
   enumeration is slow on some devices); result posted back to the
   settings fragment.
2. **New `Preference` row** `detect_codecs_key` immediately below
   `advanced_formats_key` in `video_audio_settings.xml`
   (`title ~ "Detect supported formats"`, `summary ~ "Check which
   advanced formats this device can decode"`). Tapping it runs the
   detector and opens a confirm dialog, not a silent write.
3. **Confirm dialog (the anti-footgun).** Shows one row per toggle
   with detected state (`VP9 ✓ hardware`, `AV1 ⚠ software only`,
   `EC-3 ✗ missing`, …) and checkboxes pre-set to the detected
   recommendation. `Apply` writes the set to
   `advanced_formats_key`; `Cancel` writes nothing. Re-running
   detection never auto-modifies the stored set — a user who
   manually forced AV1 off (battery) or on (knows better than the
   probe) keeps their choice until they press `Apply`.
4. **Explicitly unchanged:** the `contains()` gates in
   `VideoStreamSelector` / `StreamFilters`, the `[VP9]` XML default,
   `NewPipeSettings` seeding, and the `MIGRATION_8_9` HEVC cleanup.
   The button only produces a better-informed stored set.

## 5. Edge cases and trade-offs

- **Software decoder ≠ usable (the big one).** Android 10+ ships a
  software AV1 decoder, so a naive presence check reports AV1
  "supported" on devices that render 4K AV1 as a slideshow. The
  detector must tier hardware vs. software (`isHardwareAccelerated`)
  and report `SUPPORTED_SW_ONLY` distinctly; whether SW-only maps
  to checked or unchecked is a decision (see 8).
- **Resolution gate for video.** `advanced_formats` exists largely to
  unlock 2K/4K (YouTube serves high resolutions only as VP9/AV1).
  A decoder that handles 1080p VP9 but not 2160p VP9 argues for
  different gates per height; simplest coherent rule is one gate per
  codec at 2160p (`areSizeAndRateSupported(3840, 2160, 30)`), with
  1080p as the fallback question. Decide one threshold, record the
  tested size in the result.
- **EC-3 passthrough vs. decode.** A phone may lack an `audio/eac3`
  decoder yet pass the bitstream to a TV/soundbar over HDMI/ARC —
  or list a decoder that OEM-broke. Presence is the best cheap
  signal; document that the EC-3 row means "decoder present", not
  "surround verified end-to-end".
- **Lying/broken decoders.** The app already carries MediaCodec
  workarounds (`CustomMediaCodecVideoRenderer` forces the
  setOutputSurface workaround; ExoPlayer async-queueing toggle in
  settings). Detection can false-positive on the same class of
  device — another reason the result goes through a confirm dialog
  instead of auto-applying.
- **Performance of the probe.** `MediaCodecList` enumeration can
  take hundreds of ms; run on a background thread with a small
  progress indicator on the preference row, cache nothing
  (decoders don't change without an OS update; re-probe per tap).
- **No silent first-run seeding (proposed).** Tempting, but it
  fights `setDefaultValues(readAgain=true)` ordering and removes
  the user's chance to learn what the toggles mean. Button-only
  keeps migration story trivial: no new `PipePipeMigrations`
  entry, no version bump.

## 6. Verification

- Unit tests (no device): fake codec list → mapping
  `VP9-HW + AV1-SW-only + no-EAC3` yields `{VP9: HW, AV01:
  SW_ONLY, EC-3: UNSUPPORTED}`; empty list → all unsupported;
  ExoPlayer-vs-framework disagreement prefers ExoPlayer.
- Manual: on a VP9-only device, tap `Detect` → dialog proposes
  `[VP9]` only → `Apply` → stored set equals proposal; on an AV1
  device → proposes `[VP9, AV01]`; re-run after manually toggling
  does not change the stored set until `Apply`.
- Gate: `./scripts/check.sh` fast gate from the feature worktree
  (this doc itself is standalone `docs/` and needs no gate).

## References

- Toggles + default: `.../res/values/settings_keys.xml:1269-1276`,
  `.../res/xml/video_audio_settings.xml:36-43`.
- Seeding: `.../settings/NewPipeSettings.java:62-84`.
- Gates: `.../util/VideoStreamSelector.java:424-461`,
  `.../util/StreamFilters.java:103-127`.
- Renderer (no bundled AV1 extension):
  `.../player/helper/CustomRenderersFactory.java`.
- Build constraints: `PipePipeClient/app/build.gradle:47-53,206`
  (`minSdk 30`, ExoPlayer `2.18.7`).

## 8. Decisions (open)

- **SW-only video counts as supported?** Options: (a) unchecked —
  safe, matches "enable only what plays smoothly"; (b) checked with
  ⚠ label — user's call. Leaning (a) for AV1 given 4K software
  decode cost.
- **Resolution gate height:** 1080p (lenient, more devices light up)
  vs. 2160p (strict, matches the 2K/4K motivation). Leaning 2160p
  for VP9/AV1 with the tested size shown in the dialog.
- **EC-3 row semantics:** decoder presence only (cheap, honest) vs.
  attempting passthrough detection (expensive, fragile). Leaning
  presence-only with dialog wording to match.
- **ExoPlayer vs. framework as primary:** leaning ExoPlayer
  (`MediaCodecUtil`) primary, framework fallback — renderer
  consistency wins.
- **Button-only vs. first-run auto-seed:** leaning button-only (no
  migration, no silent overwrite); revisit if user testing shows
  nobody finds the button.
