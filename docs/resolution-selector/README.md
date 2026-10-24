# One pick per resolution — design

Why the player quality selector shows codec-duplicate rows today,
what "one pick per resolution" should mean now that the app is
YouTube-only, and how to implement it without touching downloads.

- Scope: `PipePipeClient/app/src/main` (player quality menu, stream
  sorting/selection, external-player dialog, download dialog) +
  `PipePipeExtractor/.../services/youtube` (resolution vocabulary:
  `ItagItem`, `YoutubeStreamExtractor`).
- Audited: 2026-10-24, `main` at `dcd305e` (doc commits `527f7bf`, `febe228`, `dcd305e`; revalidate refs at implementation time).
- All paths below are relative to the repository root.
- Status: **implemented** on branch `one-pick-per-resolution` (commits
  `26b9101` + review follow-ups; revalidate refs against that branch).
  Rows carry `height + fps-class + codec family` (`qualityLabelOf`),
  the quality button stays height-only.

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

Proposal: dedup at the UI layer by **numeric height only**
(`1080p`, `1080p60`, `1080p HFR`, `1080p HFR High` → one `1080p`
row), auto-picking the best stream inside each bucket with the
existing comparator. Player menu + external-player dialog adopt it;
the download dialog keeps per-codec rows (there codec + size is real
information). YouTube-only status lets us define the bucket narrowly
and delete the multi-service branches this exposes as dead
(section 4).

## 2. Current behavior (evidence)

### 2.1 Menu is 1:1 with the sorted stream list

`PipePipeClient/app/src/main/java/org/schabi/newpipe/player/PlayerMenuController.kt:178-200`:

- loops `quality.sortedVideoStreams`, one `menu.add(...)` per stream,
  label `codec.split(".")[0] + " " + resolution`, `itemId == index`.
- click path (`:439-451`) compares `selectedVideoStreamIndex ==
  menuItemIndex` and calls `setSelectedStream(availableStreams[idx])`.
- dismiss (`:475`) resets the button to codec-less `it.resolution` —
  so the button reads `1080p` while the menu row that produced it read
  `VP9 1080p` or similar.

### 2.2 Backing list keeps one stream per codec+resolution

`PipePipeClient/app/src/main/java/org/schabi/newpipe/util/VideoStreamSelector.java:324-360`
(`getSortedStreamVideosList`): merges progressive + video-only, filters
by `advanced_formats` toggles (VP9/AV01/HEVC), then dedups via
`HashMap` keyed `codec-prefix + resolution + audioTrackId`. One codec per
resolution survives, but every codec with that resolution still gets its
own entry.

### 2.3 Selection already thinks in buckets

`VideoPlaybackResolver.java:261-263` stores the picked stream's
`resolution + codec` (`setSelectedStream`), consumed per video at
`:135-142` via `getResolutionAndCodecIndex()`
(`VideoStreamSelector.java:67-140`):
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

Consequence: exact-string grouping (leaves `1080p / 1080p60 /
1080p HFR / 1080p HFR High` = 4 picks for one height) is wrong, and
the current `normalizeResolutionVariantKey()` (maps `1080p HFR →
1080p` but `1080p60 → 1080p60`) preserves an fps split we have
decided to drop. The bucket is **numeric height only** —
`normalizeResolutionKey()` semantics (`1080p60 → 1080p`,
`1080p HFR → 1080p`): `1080p, 1080p60, 1080p HFR, 1080p HFR High
(+ HLS 1080p60) → one "1080p" row`. Fps and bitrate-tier stop
being user choices; both are decided by the in-bucket winner
(see 6.2–6.3).

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
  (`:500-501`) — *candidate* for removal, not proven dead. It decides
  same-codec-family/same-height ties with equal bitrate (e.g. itag 17
  `v3GPP 144p` vs itag 160 `MPEG_4 144p`), and HLS AV1 leaves
  `MediaFormat` unset (only `avc/mp4a`/`vp9` set it in
  `YoutubeStreamExtractor.java:1677-1684`), so `indexOf(null) == -1`
  participates. Keep the tiebreak or prove equal-bitrate ties
  impossible for same-codec/same-height pairs; either way handle
  `null` format explicitly.
- Audio-side, for awareness: `StreamFilters.filterUnsupportedFormats`
  `flac` branch is non-YouTube (YouTube audio is opus/mp4a, plus ec-3
  for 5.1 where the EC-3 toggle still applies). Not in the dedup path;
  listed so a later cleanup pass doesn't miss it.

## 5. Proposal (UI-layer dedup)

1. New testable helper in `VideoStreamSelector`, e.g.
   `getDistinctResolutionStreams()` / `bucketKeyOf()`:
   `LinkedHashMap<bucketKey, best VideoStream>` over the already-sorted
   list, best picked by the existing `compareVideoStreamResolution`
   (resolution → codec priority AV1 > VP9 > AVC → bitrate, with the
   6.3 fixes). Bucket key per section 3: numeric height only, i.e.
   `normalizeResolutionKey()` semantics. Insertion order follows the
   sorted list so menu order (descending) is unchanged.
2. `PlayerMenuController.buildQualityMenu()` builds from the deduped
   list; keep a `dedupedList ↔ stream` map because `itemId` can no
   longer double as the `sortedVideoStreams` index. Label rows with
   `qualityLabelOf()` (`720p60 · AVC`: height + fps-class + codec
   family of the representative); the quality button stays height-only.
   Click maps back to the representative stream and calls the existing
   `setSelectedStream()` — resolver, persistence, and sticky-codec
   behavior unchanged.
3. `showExternalPlaybackDialog()` uses the same bucket helper on its
   own input list (fixes the identical-label bug) — note that list
   differs from the player's: the fragment builds with
   `preferVideoOnlyStreams=false` and no blacklist/audio-track
   filtering (`VideoDetailFragment.java:1956-1963`), while the
   resolver uses `preferVideoOnlyStreams=true` + blacklist +
   inline audio-track filtering (`VideoPlaybackResolver.java:101-127`).
   The dialog therefore filters by preferred audio language first
   (`StreamFilters.filterVideoStreamsByPreferredLanguage`, as in
   downloads), drops video-only streams shadowed by a muxed stream at
   the same height (`dropVideoOnlyWhenMuxedExists` — external players
   need audio), then dedups; rows use `qualityLabelOf()` too (same-codec
   pairs always collapse to one, so the codec suffix disambiguates every
   surviving same-height pair); map its pre-selection by bucket, not by
   raw index, across the two lists.
4. Explicitly unchanged: `getSortedStreamVideosList()` ordering and
   filtering, `DownloadDialog`/`StreamItemAdapter` rows,
   `VideoPlaybackResolver` protocol, live/HLS adaptive menu
   (`POPUP_MENU_ID_LIVE_QUALITY` path).
5. VOD button visibility must key off distinct-bucket count, not raw
   stream count — 3×1080p in different codecs is one bucket and should
   not offer a one-item menu. Note `:278` (`size > 1`) is the
   live-only branch; the VOD branch (`VIDEO_STREAM, POST_LIVE_STREAM`,
   ~`PlayerLayoutController.kt:291-298`) currently shows the button
   whenever streams are non-empty with no count check — that branch is
   the one that needs the `distinctBuckets.size <= 1` hide rule.

## 6. Edge cases and trade-offs

- **Manual codec override goes away.** Users forcing AVC (battery /
  old-device compat) or AV1 (data saving) lose per-playback control;
  the remaining lever is the coarse `advanced_formats` toggles. This is
  the main cost, and the reason dedup stays out of downloads.
- **HFR and 30fps merge (decided).** Each height is one row, so
  picking `1080p` silently takes the 60fps/HFR encode whenever one
  exists. This is the strictest reading of "one pick per
  resolution" and the shortest menu, at the cost of the fps choice:
  no filmic-30fps option, ~1.5–2× bitrate/battery vs 30fps, and
  users who preferred the smaller 30fps stream lose that lever
  (same class of cost as the codec override above).
- **Bitrate tiebreak becomes the picker (decided: higher wins).**
  Same-height same-codec duplicates (AVC `480p` itag 135 vs 212;
  VP9 `480p` 244/245/246; AV1 `… HFR` vs `… HFR High`) differ only
  by bitrate, and the current tiebreak (`:496-498`, `return -A + B`)
  prefers the *lower* bitrate — reads like a sign bug, flip it with
  a named comment + unit test. Note the tiebreak now also decides
  fps: fix `calculateResolution` so `HFR` scores above plain `1080p`
  (today `1080p60 → 1081` outranks `1080p` but `1080p HFR → 1080`
  ties it, leaving the fps outcome to codec/bitrate accident).
- **Multi-audio HLS.** The player list is pre-filtered by the
  resolver's inline `audioTrack`/`defaultTrackId` block
  (`VideoPlaybackResolver.java:101-127`) — *not* by
  `StreamFilters.filterVideoStreamsByPreferredLanguage` /
  `collapseAudioTrackVariants`, which run only on the download path.
  Dedup must run *after* that inline filter, on the `videos` list the
  menu actually receives. Key buckets as `height + audioTrackId`
  (null-safe) so dubbed-audio variants can't collapse across tracks
  when the filter leaves more than one track in play. The external
  dialog has no resolver filter, so it filters by preferred language
  first and additionally drops video-only streams shadowed by a muxed
  stream at the same height (identical labels, and silent video, otherwise).
- **Plain default targets resolve fps-blind.** Settings offer plain heights only
  (`720p`, …), so a fresh-start default must match by resolution, not by variant —
  otherwise variant-first matching leaves only the 30fps streams in the bucket and
  autoplay picks 30fps while the row itself plays the HFR encode. Explicit variant
  targets (sticky `1080p60`) keep variant-first matching.
- **Live streams unaffected.** `buildLiveQualityMenu()` (manifest
  variants + Adaptive row) is a separate path and keeps its rows.
- **Empty/unknown resolutions.** `RESOLUTION_UNKNOWN ("")` must still
  yield exactly one fallback row, never zero; keep the existing
  closest-lower-then-lowest fallback in `getDefaultResolutionIndex()`
  for buckets with no match. Hardening required: the default-index
  path calls `normalize…(stream.getResolution())` directly
  (`VideoStreamSelector.java:264,273,296,302`) with no null guard
  (unlike `getResolutionAndCodecIndex`'s `Objects.toString`), and
  `""` throws inside `calculateResolution`, caught as `return 1`
  (first-is-greater) — so unknown currently sorts as *greatest* and
  `Collections.max` can crown it. Null-guard the helper inputs and
  rank unknown below every numeric height.
- **Null/empty codec labels.** HLS leaves `codec` unset for some variants
  (`YoutubeStreamExtractor.java:1677-1684` only sets it for
  `avc/mp4a`/`vp9`). Row labels therefore go through
  `qualityLabelOf()`: `height + fps-class + codec family`
  (e.g. `720p60 · AVC`, `1080p · VP9`), falling back to the raw string
  for unknown resolutions and omitting the codec when it is missing —
  never a blank or `" 1080p"`-style label. Only the popup rows use the
  full label; the quality button stays height-only.

## 7. Cleanup bundled with the change

Delete `HIGH_RESOLUTION_LIST`; replace `calculateResolution`'s
non-YouTube branches with the section-3 height parser and score `60` /
`HFR` above plain height so the in-bucket winner prefers HFR
(`1080p60 → 1081` today; give `1080p HFR` the same rank instead of
tying `1080`); collapse the bucket/variant key to height only
(`normalizeResolutionKey` semantics); remove the HEVC branch + gate;
keep the `VIDEO_FORMAT_QUALITY_RANKING` tiebreak unless equal-bitrate
ties are proven impossible (handle `null` format explicitly either
way); flip the bitrate-tiebreak sign with a named comment + unit test;
null-guard helper inputs and rank unknown below numeric heights.
Each is independently revertable but they all touch the same comparator,
so one pass avoids three re-reviews of `compareVideoStreamResolution`.

## 8. Verification

- Unit tests (no device needed): bucketing of a representative YouTube
  set (`1080p` AVC + `1080p` VP9 + `1080p HFR` AV1 + `1080p HFR High`
  AV1 + `1080p60` AVC + `1080p60` VP9 + `720p` AVC → `{1080p: 1 row,
  HFR High AV1 wins}`, `{720p: 1 row}`); unknown-resolution fallback;
  sticky-codec re-resolution via `getResolutionAndCodecIndex` unchanged.
- Manual: open a video with VP9 + AV1 enabled and check the menu shows
  each height exactly once (no `60` twins); picking `1080p` plays the
  HFR encode; disable AV1/VP9 in `advanced_formats` and confirm the
  bucket winner downgrades to the remaining codec; check
external-player dialog shows no duplicate labels; check downloads
  still list per-codec rows with sizes.
- Gate: `./scripts/check.sh` fast gate from the feature worktree
  (this doc itself is standalone `docs/` and needs no gate).

## References

- Menu + click/dismiss:
  `PipePipeClient/app/src/main/java/org/schabi/newpipe/player/PlayerMenuController.kt:177-204,433-479,481-494`,
  button gating: live `:279-299`, VOD `.../player/PlayerLayoutController.kt:300-317`.
- Sorting/selection:
  `.../util/VideoStreamSelector.java:25-33,148-180,198-240,246-320,343-395,421-560,580-640`.
- Resolver: `.../player/resolver/VideoPlaybackResolver.java:101-127` (inline audio-track filter), `:135-142` (consume), `:261-263` (store),
  `.../player/resolver/PlayerQualityResolver.kt:20-34`.
- External dialog:
  `.../fragments/detail/VideoDetailFragment.java:1956-1963,2441-2462`.
- Downloads (out of scope):
  `.../download/DownloadDialog.java:160-180` (`newInstance(context, info)`),
  `.../util/StreamItemAdapter.java` (codec + resolution + size rows).
- Vocabulary: `PipePipeExtractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/ItagItem.java:31-108,150-182`,
  `.../extractors/YoutubeStreamExtractor.java:940-1075,1585-1602,1660-1740`.

## Decisions (resolved)

- Bitrate tiebreak: **prefer higher bitrate** inside a bucket.
  The current `return -A + B` reads inverted (lower wins); flip the
  sign with a named comment + unit test (`HFR` vs `HFR High`).
- HEVC cleanup: **remove fully** — `useH265` gate, `hevc` codec
  branch, and the HEVC toggle entry. YouTube serves no HEVC, so the
  preference is dead.
- Fps merge: **height-only buckets** — `1080p / 1080p60 / 1080p HFR /
  1080p HFR High` collapse to one row, winner preferably HFR.
