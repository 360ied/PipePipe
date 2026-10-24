# Removing ffmpeg-kit: plan

How to delete the vendored `ffmpeg-kit` native blob and replace its two
stream-copy remux call sites with code we already own (SABR) plus framework
APIs (HLS).

- Scope: repository root (`PipePipeClient/`, `PipePipeExtractor/`,
  `.github/workflows/`).
- Audited: 2026-10-24, `main` at `15ed881`, app version `5.5.0-beta3`.
- All paths below are relative to the repository root.
- Status: **implemented in the `ffmpeg-removal-spike` worktree — pending verification/merge.**

## 1. Summary and recommendation

`ffmpeg-kit` is used in exactly two places, never for transcoding — only
`-c copy` remuxing of already-downloaded segments:

- `PipePipeClient/app/src/main/java/us/shandian/giga/get/SabrFfmpegMuxer.kt`
  (YouTube SABR downloads: fragmented-MP4 or WebM inputs)
- `PipePipeClient/app/src/main/java/us/shandian/giga/get/HlsDownloader.kt`
  (HLS downloads: MPEG-TS or fragmented-MP4 inputs, AES-128 already decrypted
  in Java)

Both jobs can be done without native code:

- **SABR → reuse the in-repo pure-Java muxers** (`Mp4FromDashWriter`,
  `WebMWriter`) that already handle all classic DASH downloads.
- **HLS → framework `MediaExtractor` + `MediaMuxer`** (zero new
  dependencies; handles TS/fMP4/WebM/MP3 input, writes MP4/WebM).

Removal deletes ~48 MB of binaries from git, ~15–19 MB of native libraries
per ABI from the APK (11–14% of APK content), and the last hand-maintained
native blob in the app. Upstream `arthenica/ffmpeg-kit` was retired January 2025 (repo archived
June 2025), so every month we keep it is unpatched FFmpeg 5.x shipped
to users. **Recommendation: remove it**, staged as
spike → SABR → HLS → delete (section 9).

## 2. Background

### 2.1 What we shipped pre-removal (`main@15ed881` snapshot)

| Artifact | Fact |
|---|---|
| `PipePipeClient/ffmpeg/ffmpeg-kit.aar` (32 MB) | 4 ABIs (arm64-v8a, armeabi-v7a, x86, x86_64); `libavcodec/format/filter/util`, `libswresample/swscale`, `libffmpegkit` |
| `PipePipeClient/ffmpeg/ffmpeg-kit.aar.bk` (16 MB) | 2 ABIs (arm64-v8a, x86_64) only; older `PT_LOAD` alignment (see 2.4) |
| `PipePipeClient/ffmpeg/build.gradle.kts` | `artifacts.add("default", file("ffmpeg-kit.aar"))` |
| `PipePipeClient/settings.gradle:37` | `include ':ffmpeg'` |
| `PipePipeClient/app/build.gradle:299-301` | commented-out Maven coordinate, `implementation project(':ffmpeg')`, `implementation 'com.arthenica:smart-exception-java:0.2.1'` (ffmpeg-kit transitive dep — goes away too) |
| `.github/workflows/build-ffmpeg.yml` | manual (`workflow_dispatch`) rebuild job from the `InfinityLoop1308/ffmpeg-kit` fork, NDK 28.2, `--lts --enable-gmp --enable-openssl --enable-android-media-codec --enable-android-zlib --disable-arm-v7a` |

Per-ABI native cost measured from the current debug APKs (uncompressed):

| ABI | ffmpeg `.so` total | Share of APK content |
|---|---|---|
| arm64-v8a | 16.5 MB | ~12% |
| armeabi-v7a | 15.2 MB | ~11% |
| x86 | 16.4 MB | ~12% |
| x86_64 | 19.5 MB | ~14% |

After removal the only remaining native library in the APK is
`libandroidx.graphics.path.so` (10 KB, transitive from Compose UI via
Maven — maintained, not vendored). Removal therefore makes the app
effectively free of hand-maintained native blobs.

### 2.2 The only two call sites

Routing: `DownloadMission.start()` sends SABR missions to `SabrDownloader`
and HLS missions to `HlsDownloader`
(`PipePipeClient/app/src/main/java/us/shandian/giga/get/DownloadMission.java`,
`hasSabrResource()`/`hasHlsResource()`). For both, `DownloadDialog` and
`DirectDownloader` deliberately clear the Java postprocessor (`psName = null`),
so these downloaders own muxing end to end. Classic DASH/progressive
downloads never touch ffmpeg — they use `Mp4FromDashMuxer`, `WebMMuxer`,
`M4aNoDash`, `OggFromWebmDemuxer` in
`PipePipeClient/app/src/main/java/us/shandian/giga/postprocessing/`.

**SABR** (`SabrFfmpegMuxer.remuxWithFfmpeg`, `executeWithArguments`):

```text
-hide_banner -nostats -loglevel fatal -y
  -i <input-0.media> [-i <input-1.media>]
  [-map 0:a? -vn] | [-map <i>:v? -map <i>:a? ...]
  -c copy [-movflags +faststart]   # faststart only for mp4/m4a/mov/m4v output
  <output>
```

Inputs are `init segment + media segments` concatenated per track by
`SabrSegmentWriter`. Containers are only fMP4 or WebM — the SABR timeline
parser (`PipePipeExtractor/.../youtube/sabr/YoutubeSabrFormatTimeline.java`)
accepts exactly `mimeType.contains("mp4")` (via `SabrMp4SegmentIndexParser`)
or `contains("webm")` (via `SabrWebmSegmentIndexParser`). A single WebM input
already bypasses ffmpeg (`canCopySingleInputDirectly`).

**HLS** (`HlsDownloader.remuxWithFfmpeg`, `FFmpegKit.execute` with a shell
string):

```text
-y -i "<input-0.media>" [-i "<input-1.media>" ...]
  [-map 0:a? -vn] | [-map <i>:v? -map <i>:a? ...]
  -c copy -movflags +faststart <output>
```

Inputs are per-resource concatenations produced by
`HlsSegmentFileTransferEngine` (init segment first, then segments in order;
AES-128-CBC already decrypted in Java; byte-ranges already resolved). Segment
containers are whatever the HLS origin serves — typically MPEG-TS or fMP4
(YouTube) — and a resource count > 1 happens when video+audio ride separate
HLS playlists plus the `transferDirectCompanionResource` path for
non-HLS companions.

Key point for the whole plan: **no call site transcodes, filters, or
re-encodes.** It is demux-then-mux sample copying, which is why native
FFmpeg is replaceable.

### 2.3 Why remove it

1. **Upstream is retired.** arthenica retired ffmpeg-kit on 2025-01-06
   (announced "Saying Goodbye to FFmpegKit"; repo archived June 2025), and
   stated already-published binaries will be removed. No new releases, no
   CVE fixes, no FFmpeg 6/7 upgrade. We ship FFmpeg 5.x-era natives with no
   patch path except rebuilding a dead fork ourselves.
2. **48 MB of binaries in git history**, no LFS, every clone pays for them.
3. **~15–19 MB per ABI of APK content** for a stream-copy job.
4. **Last vendored native blob.** Everything else native comes from Maven
   (Compose's path library). Deletion simplifies F-Droid/reproducible-build
   reasoning: no more "where did this `.so` come from" question.
5. **Small, contained blast radius.** Two files import ffmpeg-kit; no JNI of
   our own; no app-start initialization. The replacement surface is two
   private `remuxWithFfmpeg` methods plus error mapping.

### 2.4 Honest caveats (read before citing urgency)

- **16 KB page compliance appears already handled.** ELF inspection of every
  `.so` in both AARs: the `.bk` has `PT_LOAD align=0x1000` (4 KB — would
  fail on 16 KB-page devices, which Google has required support for since
  Nov 2025), but the **current `.aar` is uniformly `0x4000` (16 KB)**,
  consistent with an NDK r27+ rebuild (the workflow pins NDK 28.2). So
  "the app will crash on 16 KB devices" is **not** a live argument — the
  urgency is maintenance/security debt, not an active crash.
- **The rebuild workflow could not reproduce the committed AAR as committed.**
  `.github/workflows/build-ffmpeg.yml` passed `--disable-arm-v7a`, yet the
  committed `.aar` contained `armeabi-v7a` and `x86` outputs. Either the AAR
  was built with different options or by a different run; neither was
  recorded. The AAR was provenance-unknown even though a build script
  existed (both deleted by this branch).
- **ffmpeg is more forgiving than its replacements.** `-c copy` tolerates
  timestamp gaps/overlaps and odd brand combos that `MediaMuxer` (strictly
  increasing PTS per track) and `Mp4FromDashWriter` (requires ≥ 8 samples
  per track, see 6.1) reject. The QA matrix (section 8) exists mostly for
  this reason.

## 3. Goal and non-goals

**Goal:** download paths that today shell out to ffmpeg produce
byte-playable, seekable, faststart-equivalent outputs using only code in the
repo plus Android framework APIs, with pause/resume checkpoints behaving as
today; then delete the `:ffmpeg` module, both AARs, the rebuild workflow,
and the `smart-exception-java` dependency.

**Non-goals:**

- No new muxing features (no transcoding, no bitrate changes, no subtitle
  burn-in, no loudness normalization). Anything needing re-encode stays
  unsupported, exactly as today (`-c copy` would already fail it).
- No playback-path changes (ExoPlayer stays 2.18.7; no Media3 migration as
  part of this work).
- No checkpoint-schema migration: `HlsDownloadCheckpoint` /
  `SabrDownloadCheckpoint` classes stay as-is; only the bytes written after
  the remux step change producer (see 5.3).
- No HLS-live-download support (already rejected: "Live HLS playlists are
  not supported yet") and no inline-manifest support (already rejected) —
  keep rejecting them.

## 4. Pre-removal inventory (`main@15ed881` snapshot, for reference)

| Path | Role |
|---|---|
| `PipePipeClient/app/src/main/java/us/shandian/giga/get/HlsDownloader.kt:212-237` | HLS ffmpeg call; `psState` 1 during, 0 on failure; `IOException("HLS ffmpeg remux failed: …")` |
| `PipePipeClient/app/src/main/java/us/shandian/giga/get/SabrFfmpegMuxer.kt:29-73` | SABR ffmpeg call; throws `SabrDownloadException(Reason.MUXING, …)` with ffmpeg output appended |
| `…/get/SabrDownloader.kt:104` | `SabrFfmpegMuxer(mission).remuxAndCopy(targets, workDir)` → `completeMission` |
| `…/get/SabrSegmentWriter.kt` | builds the temp inputs (init first, in-sequence media, gap-tolerant pending map) |
| `…/get/SabrDownloadFormatResolver.kt` | picks formats by itag; **no container-consistency check** (mixed mp4+webm pairs possible — must handle, see 5.1) |
| `…/hls/transfer/HlsSegmentFileTransferEngine.kt` | builds the temp inputs (init + ordered segments, decrypt, byte-ranges) |
| `…/hls/manifest/HlsPlaylistParser.kt` | ExoPlayer-based playlist parse; records init segment, AES-128 keys, discontinuities |
| `…/get/HlsDownloadStreamHelper.java` | manifest/audio fallbacks (`MediaFormat.MPEG_4` video, `M4A` audio) |
| `…/get/MissionRecoveryInfo.kt` | `isHls`/`isSabr`/`itag`/`kind` — everything the new muxers need to pick a strategy |
| `org/schabi/newpipe/streams/Mp4FromDashWriter.java` | `Mp4FromDashWriter(sources…)` → `parseSources()` → `selectTracks(…)` → `build(out)`; `setMainBrand` for M4A |
| `org/schabi/newpipe/streams/WebMWriter.java` + `WebMReader.java` | same-shape API (`parseSources`/`getTracksFromSource`/`selectTracks`/`build`) |
| `…/postprocessing/Mp4FromDashMuxer.java`, `WebMMuxer.java`, `M4aNoDash.java` | copy-paste templates for 2-track MP4, N-track WebM (note its "fake video cover track" quirk), and single-track M4A (note its `test()` brand gate on `dash`/`iso5`) |
| `org/schabi/newpipe/streams/io/SharpStream.java`, `us/shandian/giga/io/FileStream.java` | bridge: Java muxers speak `SharpStream`, downloaders hold `java.io.File` — wrap with the file-backed stream, mirroring how `Postprocessing` feeds them |

## 5. Design

### 5.1 SABR: reuse the in-repo Java muxers

SABR temp files are init + fragments — the same shape DASH feeds the Java
muxers. New `SabrJavaMuxer` (same call shape as `SabrFfmpegMuxer`:
`remuxAndCopy(inputs, targets, workDir)`) dispatches on the two formats'
`mimeType` (already carried on `YoutubeSabrInfo.Format`):

| Audio container | Video container | Strategy |
|---|---|---|
| mp4 | mp4 | `Mp4FromDashWriter` over both inputs, `selectTracks(0, 0)` (== today's `-map i:v? -map i:a?` for the well-formed case). Model on `Mp4FromDashMuxer`. |
| webm | webm | `WebMWriter` over both inputs. Model on `WebMMuxer`, but select the **video** track from the video source (WebMMuxer picks audio-per-source for its DASH case) and keep its fake-video-cover-track handling in mind; verify with `getTracksFromSource`. |
| single input, any | — | keep today's fast paths: direct copy when the single track can be copied verbatim (extend `canCopySingleInputDirectly` reasoning to mp4: concatenated init+fragments are already playable fragmented MP4 — but confirm players/seek behavior, else run the single-track muxer), and `M4aNoDash`-style (`setMainBrand("M4A ")`) single-track mux for audio-only missions (`kind == 'a'`). |
| **mixed** (mp4↔webm) | — | decide in the spike (section 9.1): option A — constrain pairing where the format is chosen (prefer same-container audio for the picked video; needs a `SabrDownloadFormatResolver`/UI-adjacent change); option B — `MediaExtractor`→`MediaMuxer` MP4 fallback for mixed pairs only. Do not silently produce unplayable files: mixed input that cannot be muxed must raise `Reason.MUXING`, never a corrupt output. |

Preserve today's observable behavior: `mission.psState = 1` + `writeThisToFile()`
before muxing; `psState = 0` + `SabrDownloadException(Reason.MUXING, …)` on
failure (keep appending the underlying error text — it is the only
diagnostic these failures have); `+faststart` equivalence comes free with
`Mp4FromDashWriter` (it reserves and backpatches `moov` ahead of `mdat`,
same as today's flag); keep `supportsFastStart`-style gating by output
extension; delete the temp `output.*` after copy as today.

### 5.2 HLS: framework `MediaExtractor` → `MediaMuxer`

`minSdk` is 30 (raised from 23 after implementation; WebM output since
API 26 is therefore always available); `MediaMuxer` needs no new
dependency, no native code. Per temp input file:

1. `MediaExtractor.setDataSource(path)`; iterate tracks, read
   `MediaFormat` (`MIME`, `DURATION`, codec-specific-data).
2. Mirror today's `-map` semantics: for `kind == 'a'` single-input missions
   take audio tracks only (`-map 0:a? -vn`); otherwise keep every audio
   track plus the first video track across inputs (a superset of
   `-map i:v? -map i:a?` that preserves alternate dubs while never playing
   two videos at once).
3. `MediaMuxer.addTrack()` per selected track, `start()`, then per input,
   per track: `seekTo`/`selectTrack`, loop `readSampleData` → `writeSampleData`
   with `BufferInfo` (offset/size/`presentationTimeUs`/flags). **Rewrite PTS
   if needed:** `MediaMuxer` requires non-decreasing PTS per track while
   ffmpeg tolerated overlaps — offset each appended input/track so timestamps
   stay monotonic (track a per-track `lastPts` and shift; drop or clamp
   backwards samples rather than aborting when the source is merely sloppy,
   and count such corrections in debug logs).
4. `stop()`/`release()` in `finally`; on any failure delete the partial
   output, reset `psState = 0`, throw `IOException` with the same message
   shape as today so error-reporting buckets don't churn.

Container decisions:

- Output container follows today's `outputExtension()` (storage filename).
  `mp4` → `MUXER_OUTPUT_MPEG_4`; `m4a` (audio-only) → `MUXER_OUTPUT_MPEG_4`;
  `webm` → `MUXER_OUTPUT_WEBM` (always available at `minSdk` 30 — HLS outputs
  are MP4-family in practice via the manifest/audio fallbacks, so this is a
  backstop). `mov`/`m4v`/anything else fails closed with `IOException`
  (MediaMuxer always writes MP4 brands, so those suffixes would mislabel).
  The `mp4` default applies only to blank/non-alphanumeric names via
  `downloadOutputExtension`.
- **MP3 fast path:** if all selected tracks are MP3 and the output extension
  is `mp3` (SoundCloud-style audio HLS), skip the muxer entirely and
  concatenate inputs — remuxing MP3→MP4 via `MediaMuxer` is unsupported and
  pointless when container already matches. This mirrors the existing
  single-WebM direct-copy precedent.
- Encrypted segments need no handling here (decrypted pre-mux); byte-ranges
  are already resolved; discontinuity tags stay accepted-but-ignored exactly
  as today (parser records `hasDiscontinuity`; downloader never acted on it).

### 5.3 Checkpoint and error compatibility

- Do **not** change `HlsDownloadCheckpoint` (`remuxStarted`,
  `remuxOutputPath`) or `SabrDownloadCheckpoint` (`VERSION`, `resources`):
  in-flight missions upgrading mid-download keep working, and a mission
  paused mid-remux reuses-or-redoes the output under today's work-dir +
  path-prefix validation.
- Keep the `psState` contract both downloaders rely on (`1` = muxing,
  `2` = done, `0` = failed) and the `DownloadMission.ERROR_*` buckets;
  map every new failure mode onto the existing `SabrDownloadException.Reason`
  / `IOException` shapes rather than inventing codes.
- Keep the user-visible strings stable ("…ffmpeg remux failed" wording
  should change to name the new muxer, but keep the `session.returnCode`-style
  detail — append the underlying exception message instead).

### 5.4 Deletion checklist (execute last, section 9.4)

- [x] Delete `PipePipeClient/ffmpeg/` (AAR, `.bk`, `build.gradle.kts`,
      empty `build/`).
- [x] `PipePipeClient/settings.gradle`: remove `include ':ffmpeg'`.
- [x] `PipePipeClient/app/build.gradle`: remove commented Maven line +
      `project(':ffmpeg')` + `smart-exception-java`.
- [x] Delete or repurpose `.github/workflows/build-ffmpeg.yml` (it builds
      something nothing consumes afterward).
- [x] Remove the two `com.arthenica.ffmpegkit` imports and both
      `remuxWithFfmpeg` methods (replaced by `SabrJavaMuxer` +
      `HlsFrameworkMuxer`; verified no source references remain).
- [x] Verify `proguard-rules.pro` needs no change (no arthenica keep rules)
      and the manifest needs none.
- [x] Verify APKs: no `libav*`/`libsw*`/`libffmpegkit*` under `lib/` per ABI;
      only `libandroidx.graphics.path.so` remains (arm64 debug ~51 MB).
- [x] Re-run `./scripts/check.sh` (Fast Gate) from a clean worktree.

## 6. Edge cases and risks

| # | Risk | Handling |
|---|---|---|
| 6.1 | Very short clips: `Mp4FromDashWriter` warns it needs ≥ 8 samples per track | Spike must include a < 8-sample fixture; if it fails, fall back to direct/fragmented copy for that case and document the threshold |
| 6.2 | Mixed-container SABR pairs (resolver picks by itag, no container check) | Section 5.1 mixed row: constrain pairing or MediaMuxer fallback; never emit corrupt output; `Reason.MUXING` otherwise |
| 6.3 | VP9/Opus-in-MP4 through framework `MediaMuxer` | Device-dependent — verify on API 30/33/34+ emulators in the spike; if unsupported, route those pairs to the Java muxers (preferred) |
| 6.4 | Non-monotonic PTS in TS sources | Rewrite/shift per 5.2(3); log corrections in debug builds; failure only if samples are unorderable |
| 6.5 | Multi-GB outputs (`co64`) | `Mp4FromDashWriter` already handles `THRESHOLD_FOR_CO64`; `MediaMuxer` handles large files natively — include a large-output case in QA, don't assume |
| 6.6 | 60fps / B-frames | `MediaMuxer` supports B-frames since N MR1; Java writer uses 6-samples-per-chunk packing — verify A/V sync on a 60fps fixture |
| 6.7 | Pause/resume across the mux step | Checkpoint classes unchanged; remux-output reuse validation unchanged; test pause-during-remux explicitly (pause during postprocessing is a no-op by design — keep that) |
| 6.8 | WebM "fake video cover track" | `WebMMuxer` precedent: enumerate via `getTracksFromSource`, pick by `TrackKind`, don't assume index 0 |
| 6.9 | Single-track outputs' brands | audio-only MP4 must keep the `M4A ` main brand (`M4aNoDash` precedent), else players mislabel |
| 6.10 | Unknown/future output extensions | fail closed with `IOException` (`mp4` default applies only to blank/non-alphanumeric names); never crash, never mislabel |
| 6.11 | `M4aNoDash.test()`-style skip | if a single input is already a plain (non-fragmented) file of the right brand, skip muxing as `M4aNoDash.test()` does for `dash`/`iso5` brands |

## 7. Fixtures to capture BEFORE deleting anything

ffmpeg is the oracle: every fixture below gets muxed once with the current
build (ground truth) and again with the replacement. Capture and check in
(or otherwise preserve) the **temp inputs**, not just final outputs:

- SABR: mp4 video + mp4 audio; webm video + webm audio; mixed mp4+webm;
  audio-only mp4; audio-only webm; video-only; shortest available clip
  (< 8 samples if obtainable); 60fps video.
- HLS: TS segments (YouTube-style, video+audio in one playlist);
  fMP4+init-segment playlist; AES-128 encrypted playlist; video+audio split
  across two resources; audio-only (incl. an MP3-segment set if SoundCloud
  still serves one); playlist with `#EXT-X-DISCONTINUITY`.
- Record per fixture: container/codec of each input (ffprobe), output size,
  duration, and whether `moov` precedes `mdat` (faststart check).

## 8. QA matrix

For each fixture in section 7, verify replacement output vs ffmpeg output:

| Check | How |
|---|---|
| Plays to end, correct duration (±1s) | ExoPlayer in-app + VLC external |
| A/V sync | spot-check speech/lip-sync content, not just test tones |
| Seekable (forward/backward, near end) | in-app player + VLC |
| `moov` before `mdat` for MP4 outputs | `qt-faststart`-style atom dump or equivalent parser check |
| M4A outputs carry `M4A ` brand | atom dump |
| Byte-size within ±5% of ffmpeg output (stream-copy should be near-identical modulo headers) | `ls -l` |
| Pause → resume → complete across the mux step | manual, both downloaders |
| In-flight upgrade: old-version mission files resume under new build | install old, start SABR + HLS downloads, upgrade, resume |
| API-level sweep: 30 (minSdk), 31/33, 34+ | emulators; 6.3 lives or dies here |
| Error paths: corrupt truncated input → clean failure, no partial file kept, correct error bucket | unit/instrumented test with truncated fixture |

## 9. Work breakdown (rough estimates)

Per `AGENTS.md`: each code step in its own worktree off `main`
(`./scripts/worktree.py add <branch>`), verified with `./scripts/check.sh`
(Fast Gate minimum; Full Gate before review). Docs land directly on `main`.

### 9.1 Spike: prove the replacements parse real inputs (0.5–1 day)

- Feed captured SABR mp4/webm temp files through `Mp4DashReader.parse()` /
  `WebMReader` (`parseSources` + `getTracksFromSource`) in a scratch
  instrumented test. Success = tracks enumerated with sane sample tables.
- Feed HLS TS + fMP4 fixtures through `MediaExtractor` on API 30/33/34+
  emulators; record which track MIME types appear and whether VP9/Opus
  survive `MediaMuxer.addTrack` per API level (decides 6.3 and the 5.1 mixed
  row).
- Go/no-go: if `Mp4DashReader` rejects SABR inits (e.g. missing `moov`
  flavor) or `MediaExtractor` rejects our TS variant, stop and revise —
  do not start 9.2/9.3.

### 9.2 SABR Java muxer (2–3 days)

- Add `SabrJavaMuxer` mirroring `SabrFfmpegMuxer`'s public shape
  (`remuxAndCopy(inputs, targets, workDir)`), dispatch table per 5.1.
- Rewire `SabrDownloader.kt:104`. Keep messages, `Reason.MUXING`, `psState`.
- Unit tests with fixtures: mp4+mp4, webm+webm, single-audio (M4A brand
  assertion), mixed-container policy, truncated-input failure.
- Commit atomically; `./scripts/check.sh` green.

### 9.3 HLS framework remux (3–5 days)

- Replace `HlsDownloader.remuxWithFfmpeg` with the 5.2 extractor→muxer
  pipeline + MP3 concat fast path. Keep method signature, checkpoint calls
  (`markRemuxOutput`), progress/`psState` behavior, `outputExtension()`.
- Tests with fixtures: TS single, fMP4+init, encrypted, dual-resource,
  audio-only, discontinuity playlist, unknown-extension rejection.
- Commit atomically; `./scripts/check.sh` green.

### 9.4 Delete ffmpeg (0.5 day)

- Execute the 5.4 checklist. Confirm APK `lib/` contents per ABI and total
  size delta; record both in the commit message.
- Commit atomically; full `./scripts/check.sh` (default Fast Gate) + a
  manual download smoke test per downloader before review.

### 9.5 Soak and docs (overlaps 9.4)

- Beta soak: at least one beta with SABR+HLS download smoke reports before
  any stable cherry-pick; watch `SabrDownloadException(MUXING)` and HLS
  `IOException` buckets for regressions vs the ffmpeg baseline.
- Update this document's Status line and any user-facing changelog if
  behavior visibly changes (it shouldn't — same containers, same names).

Total: roughly **1.5–3 weeks** including review cycles; the QA matrix
(section 8), not the code, is the long pole.

## 10. Alternatives considered (and rejected)

| Alternative | Verdict |
|---|---|
| Keep ffmpeg, fix the workflow to reproduce the committed AAR | Preserves provenance but keeps a retired, CVE-frozen FFmpeg, 48 MB in git, and 15+ MB/ABI in the APK forever. Worth doing **only** as a stopgap if 9.1 fails. |
| Adopt FFmpegKitNext (community source-only successor) | Restores a build path but re-adds the same native size/complexity for a stream-copy job. Overkill. |
| Media3 Transformer / `media3-muxer` for HLS | Maintained and pure-Java/Kotlin, but a new third-party dependency + API integration for what the framework already does; revisit if `MediaExtractor`/`MediaMuxer` hit device-specific walls in 9.1. |
| Third-party TS→MP4 remux libs | Same dependency cost as Media3 with less maintenance confidence. The framework path is strictly better. |
| Do nothing | Default if this plan is rejected: accept shipping unpatched FFmpeg 5.x from a retired upstream indefinitely, plus the blob's weight. Not recommended. |

## 11. Appendix

### A. Exact ffmpeg invocations being replaced

HLS (`HlsDownloader.kt`, shell string via `FFmpegKit.execute`):

```text
-y -i "<abs input-0>" [-i "<abs input-N>"]
  (-map 0:a? -vn) | (-map 0:v? -map 0:a? [-map 1:v? -map 1:a? ...])
  -c copy -movflags +faststart "<abs output>"
```

SABR (`SabrFfmpegMuxer.kt`, argv via `executeWithArguments`):

```text
-hide_banner -nostats -loglevel fatal -y
  -i <abs input-0> [-i <abs input-1>]
  (-map 0:a? -vn) | (-map 0:v? -map 0:a? [-map 1:v? -map 1:a? ...])
  -c copy [-movflags +faststart] <abs output>
```

### B. Size and ELF evidence (measured 2026-10-24)

- `ffmpeg-kit.aar`: 31.9 MB on disk, 67.8 MB uncompressed; per-ABI
  `.so` totals 15–19 MB (arm64-v8a 16.5, armeabi-v7a 15.2, x86 16.4,
  x86_64 19.5).
- `ffmpeg-kit.aar.bk`: 16.4 MB; arm64-v8a + x86_64 only.
- `PT_LOAD p_align`: current `.aar` = `0x4000` on every segment (16 KB —
  consistent with an NDK r27+ rebuild); `.bk` = `0x1000` (4 KB — stock
  6.0-era alignment, not 16 KB-safe).
- Current debug APKs (`PipePipe_5.5.0-beta3-*-debug.apk`): ~66–67 MB on
  disk; uncompressed content ~138–142 MB, of which ffmpeg natives are
  11–14%.

### C. Provenance note

A rebuild path existed (`.github/workflows/build-ffmpeg.yml` → fork
`InfinityLoop1308/ffmpeg-kit`, NDK 28.2) but **as committed it could not
reproduce the committed AAR**: the workflow passed `--disable-arm-v7a`
while the AAR shipped `armeabi-v7a` + `x86` (both deleted by this branch).
