# Codec capability Detect button — design

Why `Enable advanced formats` is manual today, what a `Detect`
button would query, and how to add it without silently overriding
the user's choices. Covers the VP9/AV01 video toggles only —
EC-3 is out of scope (decided, see 8).

- Scope: `PipePipeClient/app/src/main` (settings screen + a new
  detector helper + stream filtering call sites, unchanged).
- Audited: 2026-10-25, `main` (revalidate refs at implementation time).
- All paths below are relative to the repository root.
- Status: **implementation complete** (`feat/codec-detect`; §6 manual matrix and §10 advisory pending).

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
preference, plus a first-run auto-seed (decided, see 8). Both query
device decoders (ExoPlayer's `MediaCodecUtil` as source of truth,
framework `MediaCodecList` as fallback) and map the result onto the
VP9/AV01 toggles. The button presents the outcome in a confirm
dialog — the user reviews and applies, nothing is overwritten
silently; the first-run seed runs on every launch until `codec_seed_done`
and, only from a pristine stored set equal to the static default, writes its
VP9/AV01 result over that default. The filtering call sites stay untouched;
only the stored set changes. EC-3 is left exactly as-is.

## 2. Current behavior (evidence)

- Values + default,
  `PipePipeClient/app/src/main/res/values/settings_keys.xml:1269-1276`:
  `advanced_formats_values = [VP9, AV01, EC-3]`,
  `default_advanced_formats_values = [VP9]`.
- Preference,
  `PipePipeClient/app/src/main/res/xml/video_audio_settings.xml:36-44`:
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

Out of scope: `EC-3` (`audio/eac3`) — the detector never reads nor
writes it; the toggle keeps its static default and manual behavior
(decided, see 8).

Query vocabulary, verified against the exoplayer-core 2.18.7 AAR
in the Gradle cache — note the field-vs-method split:

1. **ExoPlayer (primary, decided — see 8).**
   `MediaCodecUtil.getDecoderInfos(mimeType, requiresSecureDecoder,
   requiresTunnelingDecoder)` — or the same-signature
   `MediaCodecSelector.DEFAULT.getDecoderInfos(...)` — throwing
   `DecoderQueryException`. Answers "what would the actual renderer
   pick", consistent with `CustomRenderersFactory`. Its
   `MediaCodecInfo` carries `hardwareAccelerated` / `softwareOnly`
   as **fields** (not methods), plus
   `isVideoSizeAndRateSupportedV21(w, h, fps)` and
   `isFormatSupported(Format)`.
2. **Framework (fallback + unit-test seam).**
   `MediaCodecList.getCodecInfos()`, filter `!isEncoder()`, check
   `getSupportedTypes()`; per-codec
   `android.media.MediaCodecInfo.isHardwareAccelerated()` (a method,
   API 29+) and
   `getCapabilitiesForType(mime).getVideoCapabilities().areSizeAndRateSupported(...)`
   (API 21+). Inject a fake codec list for tests; no device needed.

All of the above is available at `minSdk 30` without version
guards; keep one for any future `MediaCapabilities` use.

## 4. Proposal

1. **New helper, e.g. `util/CodecSupportDetector.java`.** Pure,
   testable, no Android-preference coupling:
   `detect(Set<String> mimes) -> Map<String, CodecSupport>` where
   `CodecSupport = { SUPPORTED_HW, SUPPORTED_SW_ONLY, UNSUPPORTED }`
   plus the backing decoder name for the dialog/debug log. Mapping
   (decided, see 8): only `SUPPORTED_HW` at 2160p checks the box —
   `SUPPORTED_SW_ONLY` is reported distinctly (⚠ row in the dialog)
   but maps to unchecked. Video entries record the tested size,
   fixed at 2160p30 (`areSizeAndRateSupported(3840, 2160, 30)`).
   Runs off the UI thread (decoder enumeration is slow on some
   devices); result posted back to the caller.
2. **New `Preference` row** `detect_codecs_key` immediately below
   `advanced_formats_key` in `video_audio_settings.xml`
   (`title ~ "Detect supported formats"`, `summary ~ "Check which
   advanced formats this device can decode"`). Tapping it runs the
   detector and opens a confirm dialog, not a silent write.
3. **Confirm dialog (the anti-footgun).** Shows one row per video
   toggle with detected state (`VP9 ✓ hardware`,
   `AV1 ⚠ software only → off`, …) and checkboxes pre-set to the
   detected recommendation. `Apply` writes the VP9/AV01 entries of
   the set to `advanced_formats_key`, preserving any stored EC-3
   value; `Cancel` writes nothing. Re-running detection never
   auto-modifies the stored set — a user who manually forced AV1
   off (battery) or on (knows better than the probe) keeps their
   choice until they press `Apply`.
4. **First-run auto-seed (decided, see 8).** After the
   `setDefaultValues` seeding in `NewPipeSettings.initSettings` (on every
   launch until `codec_seed_done`, not only when `isFirstRun`), run the
   detector once off the UI
   thread and write its VP9/AV01 result over the static `[VP9]`
   default, preserving any EC-3 value. Three guards, all required:
   (a) the launch guard is a pristine-set check — the probe only
   starts when the stored set still equals the static default
   (an explicit user choice equal to the default is indistinguishable
   and treated as pristine; true for fresh installs, where the
   `isFirstRun` path just seeded it, and harmless for untouched
   upgrades holding the same default; a post-seed `prefs.contains(...)` check would be
   vacuous, since `setDefaultValues` guarantees the key exists).
   A customized set is never probed, only marked done, so the
   retry in (c) cannot clobber an upgraded install's manual choice;
   (b) a completion guard re-checks at write time and aborts if
   the value changed since launch, so a user fast enough to toggle
   during the probe window is never clobbered; (c) durability via
   an explicit `codec_seed_done` boolean written only on seed
   completion — the migration version stamps persist before the
   async probe returns, so a kill in between would otherwise lose
   the one-time seed forever; retry whenever the flag is absent,
   even when `isFirstRun` is false. Until the probe completes,
   readers see the static default, which is the safe subset anyway.
5. **Explicitly unchanged:** the `contains()` gates in
   `VideoStreamSelector` / `StreamFilters` (including the EC-3
audio gate), the `[VP9]` XML default, and the `MIGRATION_8_9`
   HEVC cleanup. The button + seed only produce a
   better-informed stored set.

## 5. Edge cases and trade-offs

- **Software decoder ≠ usable (the big one).** Android 10+ ships a
  software AV1 decoder, so a naive presence check reports AV1
  "supported" on devices that render 4K AV1 as a slideshow. The
  detector tiers hardware vs. software (`isHardwareAccelerated`)
  and reports `SUPPORTED_SW_ONLY` distinctly. Decided (see 8):
  SW-only maps to **unchecked** — enable only what plays smoothly.
- **Resolution gate for video: 2160p (decided, see 8).**
  `advanced_formats` exists largely to unlock 2K/4K (YouTube serves
high resolutions only as VP9/AV1), so the gate is
  `areSizeAndRateSupported(3840, 2160, 30)` per codec. A decoder
  that handles 1080p but not 2160p leaves its toggle off — safe
  direction (fewer streams offered, never a broken one). The tested
  size is recorded in the result and shown in the dialog.
- **EC-3 out of scope (decided, see 8).** The detector never reads
  nor writes the `EC-3` entry: the button dialog shows video rows
  only, and both the dialog `Apply` and the first-run seed preserve
  whatever EC-3 value is stored. Rationale: an unused feature with
  a passthrough-vs-decode ambiguity (a phone may lack an
  `audio/eac3` decoder yet pass the bitstream to a TV/soundbar)
  is not worth probing.
- **Accepted limitations (stated, not solved).** The 2160p30 gate
  probes size/rate only — 10-bit/VP9-P2/AV1-Main10
  profiles, and HDR are not probed, matching the current
  family-level gate granularity; a decoder passing 2160p30 SDR is
  assumed good enough. 60 fps is probed separately as an advisory
  hint, never as a gate (see §10). `allowBackup="true"`
  (`AndroidManifest.xml:53`) means a cloud restore suppresses the
  seed on a new device and can carry a stale set — accepted, the
  Detect button is the remedy. New button/dialog strings need
  locale resources like any other string. Mid-session, early
  readers see the static default until the seed lands; playback
  menus rebuild per video, so the window is self-healing.
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
- **First-run seed ordering and races.** The seed launches after
  `setDefaultValues`, overwriting the static `[VP9]` default it
  just seeded — but only from a pristine stored set, so the
  `readAgain=true` backfill on later runs and upgraded installs'
  manual choices can never be clobbered. Two races are narrowed to a
  single-`apply()` window (best-effort, see §9 and §4c), not hoped away:
  a toggle made during the async probe window aborts the write
  (completion guard, see 4b), and a kill before the write leaves
  `codec_seed_done` unset so the next launch retries (see 4c).
  No new `PipePipeMigrations` entry needed: the flag is a plain
  pref key on the first-run path, not a versioned migration.

## 6. Verification

- Unit tests (no device): fake codec list → mapping
  `VP9-HW + AV1-SW-only` yields `{VP9: HW → checked, AV01:
  SW_ONLY → unchecked}`; empty list → all unchecked but stored
  EC-3 preserved; ExoPlayer-vs-framework disagreement prefers
  ExoPlayer.
- Manual: on a VP9-only device, tap `Detect` → dialog proposes
  `[VP9]` only → `Apply` → stored VP9/AV01 entries equal the
  proposal and EC-3 is unchanged; on an AV1
  device → proposes `[VP9, AV01]`; re-run after manually toggling
  does not change the stored set until `Apply`; fresh install on
  an AV1 device seeds `[VP9, AV01]` without opening settings;
  fresh install preserves the EC-3 default (off) in both cases.
- Gate: `./scripts/check.sh` fast gate from the feature worktree
  (this doc itself is standalone `docs/` and needs no gate).

## 7. References

- Toggles + default: `.../res/values/settings_keys.xml:1269-1276`,
  `.../res/xml/video_audio_settings.xml:36-44`.
- Seeding + seed guards:
  `.../settings/NewPipeSettings.java:97` (call site),
  `.../settings/NewPipeSettings.java:127-187` (guards),
  `.../settings/NewPipeSettings.java:196-213` (pure helpers)
  (pristine-set launch check, not the `isFirstRun`-from-prefs-emptiness
  derivation — a post-seed `prefs.contains(...)` check would be
  vacuous, since `setDefaultValues` guarantees the key exists).
- Gates: `.../util/VideoStreamSelector.java:424-461`,
  `.../util/StreamFilters.java:103-127`.
- Renderer (no bundled AV1 extension):
  `.../player/helper/CustomRenderersFactory.java`.
- Build constraints: `PipePipeClient/app/build.gradle:47-53,206`
  (`minSdk 30`, ExoPlayer `2.18.7`).

## 8. Decisions (resolved 2026-10-25)

- **SW-only video counts as supported? No.** SW-only maps to
  unchecked — enable only what plays smoothly. The dialog still
  shows the ⚠ row so the user can override manually.
- **Resolution gate height: 2160p.**
  `areSizeAndRateSupported(3840, 2160, 30)` per codec; tested size
  recorded and shown in the dialog.
- **2160p60: advisory, not a gate (decided 2026-10-25).**
  Probed alongside 2160p30 and surfaced as a dialog hint
  (`4K60 may stutter`); the checkbox follows the 2160p30 result
  only. Gating on 60 would hide 4K30/1080p60-capable formats that
  play fine.
- **EC-3: out of scope.** Detector never reads nor writes the
  `EC-3` entry; dialog `Apply` and first-run seed both preserve
  the stored value. Unused feature, not worth probing.
- **ExoPlayer vs. framework as primary: ExoPlayer.**
  `MediaCodecUtil` / `MediaCodecSelector.DEFAULT` primary
  (renderer consistency), framework `MediaCodecList` fallback and
  unit-test seam.
- **Button-only vs. first-run auto-seed: both.** Button for
  re-detection plus a one-time seed on first run (guarded so
  manual choices are never overwritten afterwards).

## 9. Implementation notes (`feat/codec-detect`)

Deviations from §4, recorded so the design stays truthful:

- **Seed launch guard is a pristine-set check, not the
  `isFirstRun` flag.** `maybeSeedCodecSupport` probes only when
  the stored set still equals the static default — an explicit choice
  equal to the default is indistinguishable and treated as pristine —
  (fresh installs
  after seeding, or untouched upgrades holding the same default);
  anything else is marked done without probing. A pure
  `isFirstRun` guard would clobber an upgraded install's
  customized set on the kill-retry path from §4.3(c), where
  `isFirstRun` is already false but the flag is still absent.
  An absent formats key (backup-restore, partial clear) defers
  without marking done, so the next launch retries. The completion
  guard is best-effort — `SharedPreferences` offers no
  compare-and-set, so a write racing the final `apply()` itself
  can still win; `shouldStartProbe` / `shouldWriteSeed` capture the
  pure decisions and are JVM-tested (`NewPipeSettingsSeedTest`).
- **Detector API is fixed to the two video toggles.** Implemented
  as `detectSync()` / `detectSync(exoQuerier, frameworkQuerier)`
  rather than `detect(Set<String> mimes)`: only VP9/AV01 exist
  and EC-3 is out of scope, so a mime parameter buys generality
  with no caller. The `DecoderQuerier` functional interface is
  the inject-a-fake-codec-list seam from §3; the detector itself
  is synchronous, threading lives at the call sites (RxJava
  `Single`/`Completable` on `Schedulers.io()`, project
  convention).
- **Dialog rows are plain text, no ✓/⚠ glyphs.** Rows read
  e.g. `VP9: hardware decoding, tested 2160p30` /
  `AV1: software decoding only, left off (tested 2160p30)` /
  `…: no decoder, left off`. Same three tiers and EC-3
  preservation as §4.3, translator-friendly wording.
- **Dialog `Apply` retires the seed.** An explicit `Apply` sets
  `codec_seed_done` alongside the format write, so a late probe can
  never overwrite the user's choice; the reverse order is already
  covered by the completion guard. Either order resolves in the
  user's favor.
- **Verification status.** Unit tests, `lintDebug`, and
  `assembleDebug` pass (`./scripts/check.sh --full` from the
  worktree, 17/17 tests green). The §6 manual matrix
  (VP9-only vs. AV1 device dialogs, fresh-install seed) has not
  been run — no device in this environment — and should precede
  merge.

## 10. Follow-up: 2160p60 advisory (proposed, not implemented)

Gate on 30, report 60 as advisory:

- Probe `areSizeAndRateSupported(3840, 2160, 60)` alongside the
  2160p30 gate in both queriers (microseconds against
  already-enumerated caps); carry a `supports2160p60` flag into
  `DetectionResult`.
- Mapping unchanged: `SUPPORTED_HW` at 2160p30 checks the box. When
  the same decoder fails 2160p60, suffix the HW dialog row, e.g.
  `AV1: hardware decoding, tested 2160p30 (4K60 may stutter)`.
- Phrasing stays a hint, never a verdict: declared caps are
  conservative on some SoCs and optimistic on lying ones, and
  bitrate/10-bit/HDR can still break a blessed stream. Never a
  second gate.
- Tests: advisory flag set/unset mapping, suffix present/absent,
  gate behavior unchanged (a 4K60-fail with 4K30 HW still enables).
