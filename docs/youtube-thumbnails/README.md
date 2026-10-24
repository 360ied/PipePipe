# YouTube thumbnails broken — investigation

Why YouTube thumbnails appear broken after the `ffmpeg-kit` removal
window, and why the removal itself is not the cause.

- Scope: `PipePipeClient/app/src/main` (Picasso pipeline, Compose
  holders) + `PipePipeExtractor/extractor/src/main` (YouTube thumbnail
  extraction) + `docs/ffmpeg-removal/README.md` (removal blast radius).
- Audited: 2026-10-24, `main` at `65ecb32`, app version `5.5.0-beta3`.
- All paths below are relative to the repository root.
- Status: **root cause found and fixed in branch
  `fix-youtube-thumbnails`.** `ffmpeg` removal ruled out as causal
  (section 2). The WebP CDN switch (section 3) was a red herring: the
  real cause is `15ed881` (DeArrow removal) deleting the only Picasso
  load in every stream row (section 6). Fixed by `53be8d9` (restore
  loads) plus `af00f17` (Compose `Target` strong-ref hardening); the
  interim `sqp-rs` strip (`9aa485c`) was reverted by `28e6da6`.
  Verified on-device: gray placeholders before, real thumbnails after
  (screenshots `thumb_broken.png` vs `thumb_fixed.png` in task log).

## 1. Summary

`ffmpeg-kit` was used in exactly two places, both stream-copy remux of
already-downloaded segments (`SabrFfmpegMuxer`, `HlsDownloader`), never
image decoding. The removal diff (`4312e6f^1..4312e6f`, 31 files) touches
only download muxers, transfer engines, `FileStream`, and build files. It
does not touch `PicassoHelper`, any `*Holder`, `ComposeItemUiHelper`, the
extractor `fixThumbnailUrl` path, or any `OkHttp` image client.

Meanwhile YouTube's image CDN now returns `content-type: image/webp`
(WebP `VP8` lossy, no alpha) for the exact `hq720.jpg?sqp=…&rs=…` URLs the
extractor prefers (last thumbnail = highest resolution), while bare
`hq720.jpg` / `hqdefault.jpg` still return `image/jpeg`. That
extension/content-type mismatch coincides with upstream NewPipe reports of
missing thumbnails (`#12997` Android 7.1.1, `#13412` duplicate,
`#13186` History/Saved-Playlists) and with this repo's `minSdk 23 → 30`
bump in the same merge window, which is why `ffmpeg` got blamed.

## 2. ffmpeg removal is innocent (evidence)

- Call sites pre-removal (`main@15ed881`, see
  `docs/ffmpeg-removal/README.md` section 2.2): `SabrFfmpegMuxer.remuxWithFfmpeg`
  and `HlsDownloader.remuxWithFfmpeg`, both `ffmpeg -c copy` demux/mux.
  No transcoding, no image extraction, no thumbnail generation.
- Post-removal replacements: `SabrJavaMuxer` (`Mp4FromDashWriter` /
  `WebMWriter`) and `HlsFrameworkMuxer` (`MediaExtractor` + `MediaMuxer`).
  Neither imports image code.
- Image-pipeline grep is clean:

```text
grep -rn "thumb\|Picasso\|Glide\|Coil" \
  PipePipeClient/app/src/main/java/us/shandian/giga/  # zero hits
grep -rn "ffmpeg\|FFmpegKit" PipePipeClient/app/src/main \
  --include="*.java" --include="*.kt"
# only Mp4FromDashWriter comments + new muxer headers ("replaces ffmpeg")
```

- `app/build.gradle` delta is only `minSdk 23 → 30` plus deletion of
  `project(':ffmpeg')` and `smart-exception-java`. `picasso:2.8`,
  `okhttp:5.4.0`, `shrinkResources false`, `minifyEnabled true` (release
  only) are unchanged, so no new image-loader version or shrink rule
  arrived with the removal.
- The follow-up `65ecb32` (YouTube-only) deletes SoundCloud code and
  `place_holder_cloud.png` but leaves all `place_holder_youtube`,
  `dummy_thumbnail*`, and `ServiceHelper.getIcon()` intact. No dangling
  thumbnail drawable reference was found.

## 3. What YouTube actually serves today

Fetched 2026-10-24 with `curl` (any `User-Agent`, including
`okhttp/5.4.0`, reproduces):

| Request URL | `content-type` | `file` result |
|---|---|---|
| `https://i.ytimg.com/vi/5laDGy4INB0/hq720.jpg?sqp=-oaymwEcCNAFEJQDSFXyq4qpAw4IARUAAIhCGAFwAcABBg==&rs=AOn4CLDtb6mb-Ig22CkO7O11fbsj1CKsuw` (extractor's pick: last thumbnail, `720x404`) | `image/webp`, `max-age=300` | `RIFF WebP, VP8, 720x404, no ALPH/ANIM` |
| Same `hq720.jpg?sqp=…` without `&rs=…` | `image/jpeg`, `max-age=300` | `JPEG 1280x720` |
| Bare `https://i.ytimg.com/vi/5laDGy4INB0/hq720.jpg` | `image/jpeg` | `JPEG 1280x720` |
| Bare `https://i.ytimg.com/vi/5laDGy4INB0/hqdefault.jpg` | `image/jpeg`, `max-age=7200` | `JPEG 480x360` |
| `https://yt3.ggpht.com/…=s68-c-k-…-no-rj` (avatars) | `image/jpeg` | `JPEG 68x68` |

Extractor source of the WebP URL
(`PipePipeExtractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeParsingHelper.java:1123-1168`
`fixThumbnailUrl`, `getThumbnailUrlFromInfoItem` picks
`thumbnails[size-1]`; same pattern in
`getImagesFromThumbnailsArray:1186`). Live `ytInitialData` for a search
confirms two `hq720.jpg?sqp=…&rs=…` entries (`360x202`, `720x404`); the
extractor keeps the `720x404` WebP variant.

`fixThumbnailUrl` only normalises `//` → `https://` and `http://` →
`https://`; it preserves `?sqp=&rs=`, so the WebP variant flows unchanged
to Picasso. Stripping the query forces the JPEG fallback (verified above).

Upstream context: `TeamNewPipe/NewPipe#12997` (0.28.0, Android 7.1.1,
list thumbnails gray), `#13412` (0.28.5, same device class, closed as
duplicate of `#12997`), `#13186` (0.28.2, History/Saved-Playlists
thumbnails missing, resolved after cache wipe + network switch). All
describe gray placeholders with playable video — the same symptom reported
here — and all pre-date or coincide with the WebP rollout, not with this
repo's `ffmpeg` merge.

## 4. Why the app pipeline is fragile to this switch

`PipePipeClient/app/src/main/java/org/schabi/newpipe/util/PicassoHelper.java:91-100`
builds its own `OkHttpClient` (15 s call timeout, `externalCacheDir/picasso`
disk cache) with `defaultBitmapConfig(RGB_565)` plus a scaling
`Transformation` (`min(600, width)`). `RGB_565` is fine for opaque `VP8`
but drops alpha and can interact badly with `VP8X`/animated storyboards.

`PipePipeClient/app/src/main/java/org/schabi/newpipe/info_list/ComposeItemUiHelper.kt:157`
`rememberPicassoBitmap(key, request)` creates its `Target` as a local
inside `DisposableEffect` and never holds a strong reference. Picasso holds
`Target` weakly (upstream `square/picasso#39`, `#1658`, `#1667`,
`SO#24180805`); GC before `onBitmapLoaded` leaves `bitmap = null` → gray
`surfaceVariant` box. RecyclerView holders (`into(ImageView)`) are not
affected the same way, so Compose lists can look broken while detail pages
work, or vice versa.

Other amplifiers, all pre-existing and unchanged by the `ffmpeg` merge:

- Signed `?sqp=&rs=` URLs cached long-term in Room (`streams.thumbnail_url`,
  `remote_playlists`, `subscriptions.avatar_url`) and in Picasso/OkHttp disk
  cache (`max-age=300` for the WebP variant) go stale faster than the old
  bare `.jpg` URLs (`max-age=7200`).
- `minSdk 23 → 30` in the same merge keeps API-30 devices (no `AVIF`,
  `ImageDecoder` behaviour differs from 31+) in the install base while
  dropping older devices where WebP failures were previously reported —
  easy to misread as "removal broke it".
- `DownloaderImpl.USER_AGENT` (Firefox) is *not* used for images; the
  Picasso client sends the default `okhttp` UA. `i.ytimg.com` serves both
  JPEG and WebP to either UA (verified), so UA blocking is ruled out, but
  the split clients make log triage confusing.

## 5. How to confirm / what to try next

1. `adb logcat | grep -i "picasso\|bitmap\|i.ytimg\|ggpht"` while opening
   fresh search (fresh `rs`) vs History/Saved-Playlists (DB-cached `rs`).
   `onBitmapFailed` vs HTTP `404 image/jpeg 120x90` placeholder
   distinguishes decode failure from stale-URL failure. No code change.
2. Force-JPEG probe (test worktree only): strip `?…` in `fixThumbnailUrl`
   or prefer `hqdefault.jpg`; if thumbnails return, the cause is the WebP
   variant, not `ffmpeg`. Cost is larger bytes (`292 KB` JPEG vs `89 KB`
   WebP for the `720` variant measured above).
3. Strong-ref probe: hoist the Compose `Target` into `remember` state and
   retest lists. Fixes intermittent Compose-only gaps if GC is the trigger.
4. Device split: API 30 vs 31+ on the same build. API-30-only failure
   points at platform WebP/`ImageDecoder` + `RGB_565`; all-API failure
   points at URL/caching.
5. Do *not* reintroduce `ffmpeg-kit` for this: it never decoded UI images
   (Picasso delegates to framework `BitmapFactory`/`ImageDecoder`), and
   upstream retired it Jan 2025. An image-loader change (Coil/Glide, or
   explicit WebP handling) is the correct layer if (2) confirms WebP.

## 6. Root cause (update): DeArrow removal deleted the image load

`git show 15ed881 -- StreamInfoItemHolder.java StreamMiniInfoItemHolder.java`
removes the only thumbnail bind in each stream row:

```java
DeArrowBinder.bind(DeArrowRow.from(item, item.getThumbnailUrl()),
        itemVideoTitleView, itemThumbnailView);
```

`DeArrowBinder.attach()` called `loadOriginal()` when DeArrow was
inactive, which ran
`PicassoHelper.loadScaledDownThumbnail(...).into(thumbnailView)`. No
replacement load was added, so `StreamInfoItemHolder`,
`StreamMiniInfoItemHolder`, feed `StreamItem.kt`, and
`LocalStatisticStreamItemHolder` set titles/metadata but never touched
`itemThumbnailView` — the layout default `dummy_thumbnail` (gray play
button) shows forever. Same window as the `ffmpeg` merge, hence the
misattribution. `VideoDetailFragment` was unaffected (loads via
`initThumbnailViews`). Channel/playlist/staff holders were unaffected
(they call `PicassoHelper` directly). The WebP `sqp-rs` variant is real
but decodes fine on `minSdk 30+`; the interim strip was reverted.

Fix (`53be8d9`): direct `PicassoHelper.loadScaledDownThumbnail(...).into(...)`
in the four row bind sites, matching the channel/staff pattern.
Hardening (`af00f17`): `ComposeItemUiHelper.rememberPicassoBitmap`
strong-holds its `Target` (Picasso keeps weak refs) and cancels on
dispose. Verified with `./scripts/check.sh` (fast gate green) and
on-device install + screenshot.

## References

- Removal plan + blast radius: `docs/ffmpeg-removal/README.md`.
- Removal merge: `4312e6f chore: merge branch 'ffmpeg-removal-spike'`
  (`git diff 4312e6f^1..4312e6f --stat`).
- YouTube-only follow-up: `65ecb32 app: keep YouTube as the only service`.
- Thumbnail extraction:
  `PipePipeExtractor/extractor/src/main/java/org/schabi/newpipe/extractor/services/youtube/YoutubeParsingHelper.java:1123-1200`,
  `extractors/YoutubeStreamExtractor.java:248-280 getThumbnailUrl/getThumbnails`,
  `:2888 getFrames` (storyboards are `i.ytimg.com/sb/…jpg?…&sigh=…` JPEG
  sprites, distinct from `an_webp/…mqdefault_6s.webp` animated previews).
- Image rendering:
  `PipePipeClient/app/src/main/java/org/schabi/newpipe/util/PicassoHelper.java`,
  `info_list/ComposeItemUiHelper.kt:157-207`,
  `player/seekbarpreview/SeekbarPreviewThumbnailHolder.java:221 loadSeekbarThumbnailPreview(url).get()`.
- Upstream: `TeamNewPipe/NewPipe#12997`, `#13412`, `#13186`;
  `TeamNewPipe/NewPipeExtractor#1417` (lockup `thumbnailViewModel`).
