# Phone-home audit

Record of every network destination the app contacts (or can contact), what
data is sent, and what triggers it. No analytics, crash-reporting, or
advertising SDKs are present in this codebase.

- Scope: `PipePipeClient/app/src/main` + `PipePipeExtractor/extractor/src`
  (release configuration; debug-only code noted separately).
- Audited: 2026-10-24, `main` at `005689f0`, app version `5.5.0-beta3`.
- To re-audit: `grep -rhoE "https?://[^\"' <>]+"` over the two trees above,
  cross-check `PipePipeClient/app/build.gradle` dependencies, and re-read the
  files cited below. Any new hardcoded host or new third-party dependency
  should land in this document.
- All code paths below are relative to the repository root.

## Summary

| Category | Status |
|---|---|
| Analytics / crash / ads SDKs (Firebase, Crashlytics, Sentry, Facebook, AppsFlyer, Adjust, Amplitude, Mixpanel, Segment, OneSignal, …) | Not present |
| ACRA crash reporter (`ch.acra:acra-core`) | Present but local-only; never transmits |
| Auto-update checker | Not present |
| Debug tooling (Stetho, LeakCanary) | `debugImplementation` only; absent from release APKs |
| WebView telemetry | Opted out via manifest meta-data |
| Location / contacts / phone-state / ad-ID access | None; permissions are `INTERNET`, `ACCESS_NETWORK_STATE`, `WAKE_LOCK`, foreground-service types, `POST_NOTIFICATIONS` |

## Endpoints contacted automatically

These fire during normal use without an explicit per-feature opt-in. All are
required for the app's stated purpose (playing YouTube/SoundCloud content).

| Destination | Trigger | Data sent |
|---|---|---|
| `youtubei.googleapis.com/youtubei/v1/`, `music.youtube.com/youtubei/…`, `www.youtube.com` (player JS, `iframe_api`), `*.googlevideo.com` (media) | Browsing, search, playback | Video IDs, queries, playback requests — inherent to the service |
| `suggestqueries.google.com/complete/search` (`extractor/.../youtube/extractors/YoutubeSuggestionExtractor.java`) | Each keystroke in search | The partial query + content-country. Remote suggestions default **ON** (`NewPipeSettings.showSearchSuggestions()` returns `true` when unset); disable under Settings → Advanced |
| SoundCloud API v2 (`extractor/.../soundcloud/…`) incl. `search/queries` suggestions (`SoundcloudSuggestionExtractor.java`) | Browsing, search, playback | Queries, track IDs, client ID |
| `jnn-pa.googleapis.com/$rpc/google.internal.waa.v1.Waa/GenerateIT` (`app/.../youtube/LocalDomPoTokenProvider.kt`) | Playback when a PoToken must be minted | BotGuard challenge response. Goes to Google; required by YouTube's anti-bot flow, not app telemetry |
| `i.ytimg.com`, `ggpht.*`, SoundCloud image CDNs (via Picasso) | Rendering lists / detail pages | Image URLs of thumbnails and avatars |
| Subscription hosts, on the user's schedule | `NotificationWorker` (WorkManager periodic) when feed notifications are enabled | Fetches subscribed channels only |

## First-party fallback: `api.pipepipe.dev`

YouTube signature/`n`-parameter decoding and player metadata are computed
**locally first**: `WebViewJavaScriptDecoder` (`app/.../WebViewJavaScriptDecoder.java`)
runs the player JS in a headless WebView (network loads blocked,
`SharedWebViewRuntime` loads only a local stub document) backed by bundled
solvers in `app/src/main/assets/ejs/`, and registers itself via
`App.onCreate` → `YoutubeApiDecoder.setLocalDecoder()`.

The remote API is a fallback only (`extractor/.../youtube/YoutubeApiDecoder.java`,
`.../youtube/YoutubeJavaScriptPlayerManager.java`):

- `GET https://api.pipepipe.dev/decoder/decode?player=<id>&sig=…&n=…`
- `GET https://api.pipepipe.dev/decoder/latest-player`

The local decoder is tried first and permanently disables itself for the
process on failure, after which the remote path is used. The remote decode
request carries the player ID plus obfuscated signature/`n` values (never
account data); the `latest-player` fetch carries no video ID. `DownloaderImpl`
special-cases `pipepipe.dev` only for timeouts. `GET
https://api.pipepipe.dev/token-status` is separate and strictly manual (the
"check token validity" button in proxy settings); `enable_proxy_key` has no
reader and currently routes nothing.

## Opt-in third parties (no traffic until the user enables them)

| Destination | Default | What is sent when enabled |
|---|---|---|
| `sponsor.ajay.app/api/skipSegments/<sha256(videoId)[0:4]>` (`extractor/.../sponsorblock/SponsorBlockExtractorHelper.java`) | Master switch ON, but all 9 categories resolve to **off** in `ServiceHelper.buildSponsorBlockApiSettings()`, and `getSegments()` returns before any request when no category is selected → no traffic out of the box | 4-hex-char hash prefix of the video ID (≈1/65k bucket; server never sees the full ID). The per-install random `userId` is sent only on segment *submit/vote*, never on fetch |
| `returnyoutubedislikeapi.com/votes?videoId=<id>` (`extractor/.../youtube/extractors/YoutubeStreamExtractor.java`) | OFF (`show_dislike_key` defaults `false`) | The exact video ID |
| `sponsor.ajay.app/api/branding/<4-char-prefix>` + `dearrow-thumb.ajay.app/api/v1/getThumbnail?videoID=<id>` (`app/.../util/dearrow/DeArrowApi.kt`) | OFF (`dearrow_enable_key` defaults `false`; `DeArrowSettings` returns `DISABLED`, `DeArrowRepository` is never queried) | Branding bucket uses a hash prefix like SponsorBlock, but the *thumbnail* URL carries the full `videoID` |

## Manual-only disclosures (explicit user action each time)

- Crash report → email to `feedback@pipepipe.dev`, GitHub issue draft, or
  copy-to-clipboard (`error/ErrorActivity.java`). The `ErrorMatcher` wiki-fetch
  block is inside `if (false)` and never executes.
- Donation link `https://ko-fi.com/pipepipe` (`res/values/donottranslate.xml`).
- YouTube login WebView (`YouTubeLoginWebViewActivity`) — user-initiated; stores
  cookies the user explicitly provides.

## Known nuances / follow-ups

- SponsorBlock defaults disagree: the category `SwitchPreference`s declare
  `defaultValue="true"` (`res/xml/sponsor_block_category_settings.xml`) while
  `ServiceHelper` reads the raw `SharedPreferences` with fallback `false` (no
  `setDefaultValues()` call exists), so the UI can display ON while the
  extractor treats the category as OFF. Behavior today is safe (no traffic) but
  the mismatch should be reconciled.
- `YoutubeJavaScriptPlayerManager` logs `YT_PERF videoId=…` to stdout — visible
  in logcat, never on the wire, but video IDs in logs are avoidable.
- `DownloaderImpl.USER_AGENT` spoofs Firefox; remote-decode calls identify as
  `PipePipe/4.9.0`. Neither attaches device identifiers.
