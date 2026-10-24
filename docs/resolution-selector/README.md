# One pick per resolution — design

Why the player quality selector shows codec-duplicate rows today,
what "one pick per resolution" should mean now that the app is
YouTube-only, and how to implement it without touching downloads.

- Scope: `PipePipeClient/app/src/main` (player quality menu, stream
  sorting/selection, external-player dialog, download dialog) +
  `PipePipeExtractor/.../services/youtube` (resolution vocabulary:
  `ItagItem`, `YoutubeStreamExtractor`).
- Audited: 2026-10-24, `main` at `0a73160`.
- All paths below are relative to the repository root.
- Status: **design only, not implemented.** No code changes accompany
  this doc.

## 1. Summary

The player quality menu (`PlayerMenuController.buildQualityMenu()`)
adds one row per `VideoStream`: `CODEC + resolution`, with
`itemId == stream index`. The backing list
(`VideoStreamSelector.getSortedStreamVideosList()`) dedups only by
`codec + resolution + audioTrackId`, so one YouTube 1080p typically
produces 3–6 rows (`AVC 1080p`, `VP9 1080p`, `AV01 1080p HFR`,
`AVC 1080p60`, `VP9 1080p60`, …). The quality button itself shows only
`resolution`, so menu and button disagree, and the external-player
dialog (`VideoDetailFragment.showExternalPlaybackDialog()`), which
shows `resolution` only, renders literally indistinguishable
duplicates (`1080p, 1080p, 1080p`).

Proposal: dedup at the UI layer by **height + fps-class** (with
`HFR ≡ 60`), one row per bucket, auto-picking the best stream inside
each bucket with the existing comparator. Player menu + external-player
dialog adopt it; the download dialog keeps per-codec rows (there codec
+ size is real information). YouTube-only status lets us define the
bucket narrowly and delete the multi-service branches this exposes as
dead (section 4).

## 2. Current behavior (evidence)

### 2.1 Menu is 1:1 with the sorted stream list

`PipePipeClient/app/src/main/java/org/schabi/newpipe/player/PlayerMenuController.kt:178-200`:

- loops `quality.sortedVideoStreams`, one `menu.add(...)` per stream,
  label `codec.split(".")[0] + " " + resolution`, `itemId == index`.
- click path (`:439-451`) compares `selectedVideoStreamIndex ==
  menuItemIndex` and calls `setSelectedStream(availableStreams[idx])`.
- dismiss (`:475`) resets the button to codec-less `it.resolution` —
  so the button reads `1080p` while the menu row that produced it read
  `AVP9 1080p` or similar.

### 2.2 Backing list keeps one stream per codec+resolution

`PipePipeClient/app/src/main/java/org/schabi/newpipe/util/VideoStreamSelector.java:324-360`
(`getSortedStreamVideosList`): merges progressive + video-only, filters
by `advanced_formats` toggles (VP9/AV01/HEVC), then dedups via
`HashMap` keyed `codec-prefix + resolution + audioTrackId`. One codec per
resolution survives, but every codec with that resolution still gets its
own entry.

### 2.3 Selection already thinks in buckets

`VideoPlaybackResolver.java:135-142,261-263` stores the picked stream's
`resolution + codec` and re-resolves per video via
`getResolutionAndCodecIndex()` (`VideoStreamSelector.java:67-140`):
exact-variant match → same-codec same-normalized-resolution →
best-in-bucket → closest-lower. A deduped menu slots into this
unchanged: picking the bucket's representative stream stores its
`resolution + codec`, and the next video resolves sticky-codec or falls
back to best-in-bucket. No resolver protocol change needed.

### 2.4 The other two selectors

- External player
  (`PipePipeClient/app/src/main/java/org/schabi/newpipe/fragments/detail/VideoDetailFragment.java:2441-2462`):
  builds `resolutions[i] = getResolution()` with no codec — duplicates
  are indistinguishable. Same dedup helper fixes it for free.
- Downloads (`DownloadDialog` + `StreamItemAdapter`): rows show codec +
  resolution + measured size, and the choice is about file size /
  compatibility, not playback. Out of scope — do not dedup.

## 3. YouTube-only resolution vocabulary

All non-YouTube services are removed
(`PipePipeExtractor/.../services/` contains only `youtube/`), so the
bucket only needs to cover what `ItagItem` + `YoutubeStreamExtractor`
can emit:

| Source | Examples |
|---|---|
| Progressive (with audio), `ItagItem.java:31-45` | `144p, 360p, 480p, 720p, 1080p` (itag 17/18/22/37/38; v3GPP 17/36 at the low end) |
| DASH AVC video-only, `:60-70` | `144p … 1080p`, `720p60, 1080p60`, `2160p` |
| DASH VP9 video-only, `:72-85` | `144p … 1080p`, `1440p, 2160p`, `720p60, 1080p60, 1440p60, 2160p60` |
| DASH/SABR AV1, `:94-108` | `144p … 4320p HFR`, `… HFR High` (bitrate tier, not fps tier) |
| HLS, `YoutubeStreamExtractor.java:1687-1693` | `HEIGHTp` or `HEIGHTp + FRAME-RATE` (e.g. `720p60` when rate > 30) |

fps arrives from the player response (`setFps()`,
`YoutubeStreamExtractor.java:1599-1602`) but the client never sorts or
labels by `VideoStream.getFps()` — only by the resolution string. `HFR`
itags default to `fps = 30` in `ItagItem` until overwritten, so `HFR`
vs `60` is a labeling distinction for the same fps-class, split across
codecs: AV1 says `1080p HFR`, AVC/VP9 say `1080p60`.

Consequence: neither exact-string grouping (leaves `1080p / 1080p60 /
1080p HFR / 1080p HFR High` = 4 picks for one bucket) nor the current
`normalizeResolutionVariantKey()` (maps `1080p HFR → 1080p` but
`1080p60 → 1080p60`, splitting one fps-class in two) is right. The
YouTube-only bucket is **numeric height + fps-class, `HFR ≡ 60`**:
`1080p, 1080p HFR, 1080p HFR High → "1080p"`; `1080p60 (+ HLS 1080p60)
→ "1080p60"`. `HFR High` collapses into the bucket and is decided by
the bitrate tiebreak (see 6.3).

## 4. Vestigial logic this exposes

All in `VideoStreamSelector.java` unless noted; all unreachable with
the vocabulary in section 3:

- `HIGH_RESOLUTION_LIST:26` — defined, zero references. Delete.
- `calculateResolution():419-440` — `8K / 4K / 高帧率 / 高码率 / HDR /
  杜比 / 低画質` branches never match a YouTube string. Replace with a
  `(\d+)p` parser (+ fps-class suffix) instead of extending it.
- `normalizeResolutionKey:180` HDR handling and comment (`1440p HDR →
  1440p`) — no YouTube stream emits an HDR suffix in `resolution`.
- `codecFamilyOf():193-212` `hev1/hvc1 → hevc` branch, `getCodecPriority
  hevc` rank, and the `useH265` gate in `getSortedStreamVideosList` —
  YouTube serves AVC/VP9/AV1 only; the HEVC advanced-format toggle is
  dead. (VP9 gate keyed off `MediaFormat.WEBM` rather than codec works
  but is indirect; AV1 gate keyed off `av01` codec prefix is the live
  one.)
- `VIDEO_FORMAT_QUALITY_RANKING:22` + container tiebreak
  (`:500-501`) — redundant given codec priority. For YouTube the
  container is determined by codec (VP9→WEBM, else MPEG_4; v3GPP only
  for itags 17/36), so the format rank never decides anything the codec
  rank didn't.
- Audio-side, for awareness: `StreamFilters.filterUnsupportedFormats`
  `flac` branch is non-YouTube (YouTube audio is opus/mp4a, plus ec-3
  for 5.1 where the EC-3 toggle still applies). Not in the dedup path;
  listed so a later cleanup pass doesn't miss it.

## 5. Proposal (UI-layer dedup)

1. New testable helper in `VideoStreamSelector`, e.g.
   `getDistinctResolutionStreams()` / `bucketKeyOf()`:
   `LinkedHashMap<bucketKey, best VideoStream>` over the already-sorted
   list, best picked by the existing `compareVideoStreamResolution`
   (resolution → codec priority AV1 > VP9 > AVC → bitrate). Bucket key
   per section 3, preserving fps-class (`1080p` vs `1080p60` stay
   separate). Insertion order follows the sorted list so menu order
   (descending) is unchanged.
2. `PlayerMenuController.buildQualityMenu()` builds from the deduped
   list; keep a `dedupedList ↔ stream` map because `itemId` can no
   longer double as the `sortedVideoStreams` index. Label rows with the
   plain resolution (`1080p`, `1080p60`), matching the button. Click
   maps back to the representative stream and calls the existing
   `setSelectedStream()` — resolver, persistence, and sticky-codec
   behavior unchanged.
3. `showExternalPlaybackDialog()` uses the same helper (fixes the
   identical-label bug); its `selectedVideoStreamIndex` becomes a
   bucket comparison, not a raw index.
4. Explicitly unchanged: `getSortedStreamVideosList()` ordering and
   filtering, `DownloadDialog`/`StreamItemAdapter` rows,
   `VideoPlaybackResolver` protocol, live/HLS adaptive menu
   (`POPUP_MENU_ID_LIVE_QUALITY` path).
5. `PlayerLayoutController.kt:278` button visibility (`size > 1`) must
   key off distinct-bucket count, not raw stream count — 3×1080p in
   different codecs is one bucket and should not offer a one-item menu.

## 6. Edge cases and trade-offs

- **Manual codec override goes away.** Users forcing AVC (battery /
  old-device compat) or AV1 (data saving) lose per-playback control;
  the remaining lever is the coarse `advanced_formats` toggles. This is
  the main cost, and the reason dedup stays out of downloads.
- **60 fps stays user-visible.** Bucket preserves fps-class, so
  `1080p` vs `1080p60` remain two rows. Merging them (pure-height
  buckets) would silently force 60 fps on everyone; not proposed.
- **Bitrate tiebreak becomes the picker.** Same-height same-codec
  duplicates (AVC `480p` itag 135 vs 212; VP9 `480p` 244/245/246;
  AV1 `… HFR` vs `… HFR High`) differ only by bitrate, and the current
  tiebreak (`:496-498`, `return -A + B`) prefers the *lower* bitrate.
  Confirm intent (quality vs data-saving) before wiring it as the
  bucket winner — it reads like a sign bug.
- **Multi-audio HLS.** `getSortedStreamVideosList` output is already
  pre-filtered by preferred audio track (`VideoPlaybackResolver`
  audio-track block; `StreamFilters.filterVideoStreamsByPreferredLanguage`
  + `collapseAudioTrackVariants`). Dedup must run *after* that filter,
  on the `videos` list the menu actually receives, or dubbed-audio
  variants collapse incorrectly.
- **Live streams unaffected.** `buildLiveQualityMenu()` (manifest
  variants + Adaptive row) is a separate path and keeps its rows.
- **Empty/unknown resolutions.** `RESOLUTION_UNKNOWN ("")` must still
  yield exactly one fallback row, never zero; keep the existing
  closest-lower-then-lowest fallback in `getDefaultResolutionIndex()`
  for buckets with no match.

## 7. Cleanup bundled with the change

Delete `HIGH_RESOLUTION_LIST`; replace `calculateResolution`'s
non-YouTube branches with the section-3 parser; fix `HFR ≡ 60` in the
variant/bucket key; remove the HEVC branch + gate (or the whole
`VIDEO_FORMAT_QUALITY_RANKING` tiebreak if review agrees it is dead);
resolve the bitrate-tiebreak sign with a named comment + unit test.
Each is independently revertable but they all touch the same comparator,
so one pass avoids three re-reviews of `compareVideoStreamResolution`.

## 8. Verification

- Unit tests (no device needed): bucketing of a representative YouTube
  set (`1080p` AVC + `1080p` VP9 + `1080p HFR` AV1 + `1080p HFR High`
  AV1 + `1080p60` AVC + `1080p60` VP9 + `720p` AVC → buckets
  `{1080p: 1 row, best codec}`, `{1080p60: 1 row}`, `{720p: 1 row}`);
  `HFR ≡ 60`-class separation (`1080p` vs `1080p60` stay split);
  unknown-resolution fallback; sticky-codec re-resolution via
  `getResolutionAndCodecIndex` unchanged.
- Manual: open a video with VP9 + AV1 enabled and check the menu shows
  each height once (with/without its `60` twin); disable AV1/VP9 in
  `advanced_formats` and confirm the bucket winner downgrades to the
  remaining codec; check external-player dialog shows no duplicate
  labels; check downloads still list per-codec rows with sizes.
- Gate: `./scripts/check.sh` fast gate from the feature worktree
  (this doc itself is standalone `docs/` and needs no gate).

## References

- Menu + click/dismiss:
  `PipePipeClient/app/src/main/java/org/schabi/newpipe/player/PlayerMenuController.kt:178-200,439-451,475`,
  button gating:
  `.../player/PlayerLayoutController.kt:278`.
- Sorting/selection:
  `.../util/VideoStreamSelector.java:22-26,67-140,144-170,180-212,243-310,324-360,419-501`.
- Resolver: `.../player/resolver/VideoPlaybackResolver.java:135-142,261-263`,
  `.../player/resolver/PlayerQualityResolver.kt:20-34`.
- External dialog:
  `.../fragments/detail/VideoDetailFragment.java:1956-1963,2441-2462`.
- Downloads (out of scope):
  `.../download/DownloadDialog.java:130-180`,
  `.../util/StreamItemAdapter.java`.
- Vocabulary: `PipePipeExtractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/ItagItem.java:31-108,150-182`,
  `.../extractors/YoutubeStreamExtractor.java:940-1075,1585-1602,1660-1740`.

## Decisions (resolved)

- Bitrate tiebreak: **prefer higher bitrate** inside a bucket.
  The current `return -A + B` reads inverted (lower wins); flip the
  sign with a named comment + unit test (`HFR` vs `HFR High`).
- HEVC cleanup: **remove fully** — `useH265` gate, `hevc` codec
  branch, and the HEVC toggle entry. YouTube serves no HEVC, so the
  preference is dead.
