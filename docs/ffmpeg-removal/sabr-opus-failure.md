# SABR `.opus` download fails: WebM input vs `.opus` output

Audited: 2026-10-26, `main` at `79e69bd`.
Status: **investigated, unfixed** — every SABR audio-only `WEBMA_OPUS`
download fails closed in `SabrJavaMuxer`.
Scope: `PipePipeClient/app/src/main/java/us/shandian/giga/get/`,
`org/schabi/newpipe/download/DownloadDialog.java`.

## 1. Symptom

Downloads screen shows a pending audio mission named
`Sample_Video_Title.opus` (`<size> (paused)`,
`Error`). Tapping it raises a modal:

```text
Sample_Video_Title.opus

SABR download failed: java mux failed (output .opus disagrees
with WEBM inputs, mimes=[audio/webm; codecs="opus"])
```

The mission stays paused/errored; retry reproduces the same failure.
A second unrelated finished download behind the modal is a red herring.

## 2. Error origin

The string is built in exactly one place:

- `PipePipeClient/app/src/main/java/us/shandian/giga/get/SabrJavaMuxer.kt:229`
  inside `dispatchRemux()`, the output-suffix vs input-container
  agreement check.

`dispatchRemux()` classifies each target by case-sensitive `mimeType`
matching (same rule as `YoutubeSabrFormatTimeline`): `contains("mp4")`
→ `MP4`, `contains("webm")` → `WEBM`. Here the single audio target is
`audio/webm; codecs="opus"` → unanimous `WEBM`. The storage suffix is
`opus` (lowercased once in `remuxAndCopy()`). The family table is:

```kotlin
MP4_FAMILY  = setOf("mp4", "m4a", "m4v", "mov")
WEBM_FAMILY = setOf("webm", "weba", "mkv", "mka") // .opus excluded
```

`WEBM → opus` disagrees, so the muxer throws `MUXING` before opening
any source. The `canCopySingleInputDirectly()` fast path (`:616`)
already returns `false` for `.opus`, so there is no verbatim bypass.

## 3. Root-cause chain

1. Extractor container is WebM. `WEBMA_OPUS("webm", "audio/webm")`
   (`PipePipeExtractor/extractor/.../MediaFormat.java:46`, itags
   249/250/251) and the SABR format reports
   `audio/webm; codecs="opus"`. Nothing upstream is Ogg yet.
2. Download UI renames that format to `.opus` with `audio/ogg` mime,
   because the legacy progressive/DASH path converts it afterwards:
   `DownloadDialog.java:781` and `DirectDownloader.java:130`
   (`WEBMA_OPUS → "opus"`), plus `DownloadDialog.java:1049` /
   `DirectDownloader.java:204`
   (`psName = ALGORITHM_OGG_FROM_WEBM_DEMUXER`, i.e.
   `OggFromWebmDemuxer` → `OggFromWebMWriter`: WebM → Ogg Opus).
3. Both call sites then clear postprocessing for SABR/HLS:
   `if (containsHlsResource(...) || containsSabrStream(...)) psName = null`
   (`DownloadDialog.java:1116`, `DirectDownloader.java:260`). So SABR
   missions own muxing end to end and never get the Ogg conversion.
4. `SabrDownloader` downloads init + media segments to temp files, then
   calls `SabrJavaMuxer.remuxAndCopy()` with storage name `*.opus`.
5. The muxer fails closed per section 2. There is no Ogg branch:
   `Container.WEBM` dispatches only to `remuxWebm()` (`WebMWriter`,
   which emits WebM bytes), and writing those under `.opus` is refused.

## 4. Why `.opus` is excluded (intentional, but incomplete)

Round-4 review (`4bc4ec1`) deliberately dropped `opus` from
`WEBM_FAMILY_EXTENSIONS` so WebM bytes are never mislabeled `.opus`
(conventionally Ogg-Opus; strict players reject WebM-in-`.opus`).
That half is correct — but the other half was never ported: nothing
replaced the dropped `OggFromWebmDemuxer` postprocessing step in the
SABR path, so `.opus` went from "would be mislabeled" to "always
fails".

## 5. Blast radius

- All SABR audio-only `WEBMA_OPUS → .opus` downloads (the normal
  YouTube opus-audio case). MP4/M4A and `.webm`/`.weba` are unaffected.
- HLS has the same latent gap (`HlsFrameworkMuxer` accepts only
  `mp4/m4a/webm` + mp3-concat, no `.opus`), but YouTube-audio-via-HLS
  rarely hits it.

## 6. Fix proposal

Add an `.opus` branch to `SabrJavaMuxer`, mirroring legacy
`OggFromWebmDemuxer`, instead of widening `WEBM_FAMILY`:

- Gate: `extension == "opus" && inputs.size == 1 && target.isAudio`
  `&& mime.contains("webm")`. Multi-input, video, or mp4 inputs keep
  failing closed.
- Track pick: reuse `pickWebmTrack(wantVideo=false)` to keep the
  fake-video-cover-track protection (enumerate by `TrackKind`, never
  assume index 0).
- Remux: `OggFromWebMWriter(FileStream(input, readOnly=true),
  FileStream(output))` → `parseSource()`, `selectTrack(audioIdx)`,
  `build()`. `FileStream extends SharpStream` already satisfies the
  `canRead/canRewind` + `canWrite/canRewind` constructor checks, and
  the temp shape (init + concatenated clusters) is what `WebMReader`
  already parses on the `WebMWriter` path.
- No verbatim fallback for `.opus`: a failed Ogg build must stay a
  `MUXING` error, or the family exclusion is bypassed by the back
  door. Keep the existing interrupt taxonomy (pause, not failure).

Rejected: re-adding `opus` to `WEBM_FAMILY` (emits WebM bytes as
`.opus`); renaming SABR opus offers to `.weba` (breaks user
libraries and expectations).

## 7. Verification

- Repro: SABR audio-only `WEBMA_OPUS` mission named `*.opus` → today
  fails with the section-1 message; after the fix completes and the
  output probes as Ogg (`OggS` magic, not EBML `0x1A45DFA3`).
- Regression: existing `.webm`/`.weba`/`.m4a` SABR audio missions
  unchanged; mixed mp4+webm still `MUXING`; truncated input still
  clean-fails with no partial kept.
