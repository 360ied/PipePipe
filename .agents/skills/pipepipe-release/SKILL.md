---
name: pipepipe-release
description: >-
  End-to-end release process for the NanoPipe Android client: bump the
  semver version via git tag, write dry, user-centric GitHub release notes,
  build the signed release APK, and publish the pre-release with the
  APK attached. Use when the user asks to cut, create, or publish a release.
---

# NanoPipe Release Process

Prerequisites: `gh` CLI authenticated (`gh auth status`), Nix dev shell for
Gradle builds, clean working tree on `main` with the changes to release
already merged, and release signing configured (`PipePipeClient/keystore.properties`,
gitignored, or the `KEY_PATH` / `KEY_STORE_PASSWORD` / `KEY_ALIAS` /
`KEY_PASSWORD` env vars — without it the APK builds unsigned). Never commit
key material.

## 1. Determine the version

- Latest tag: `git tag -l | sort -V | tail -1`. Versions are plain `vX.Y.Z`
  semver with a leading `v` and no prerelease suffixes (e.g. `v0.1.0`).
  `<version>` below always means the full v-prefixed tag.
- Scope of changes: `git log <last-tag>..main --oneline`.
- **Bump type (major/minor/patch) must be explicitly stated by the user.**
  Never infer it from the commit log. If the user did not specify one, ask
  before proceeding. You may quote a recommendation (e.g. "the log shows
  features, so minor by convention") but the user makes the call.
- Flag anything in the log beyond the user's described core/secondary
  changes before tagging.

## 2. Review and categorize changes

- Read full commit messages: `git log <last-tag>..main --format='--- %h %s%n%b'`.
- Categorize changes into:
  - **User-Facing**: New playback features, UI screens and settings,
    subscription and library behaviors, bug fixes, visual polish, and
    streaming or playback stability improvements the user can observe.
  - **Developer/Internal**: Test harnesses, extractor refactors with no
    observable behavior change, CI/build tweaks, lint fixes, dev scripts,
    and pure internal code refactorings.
- Developer and internal changes must **not** be included in release highlights.

## 3. Tag and push

```bash
git tag -a <version> -m "Release <version>"
git push origin main <version>
```

Tag before building so the tag-derived `versionName` (AGENTS.md
"Versioning") resolves to the exact release version in the APK.

## 4. Write release notes

Write notes to `plans/release-<version>.md` (the `plans/` directory is
gitignored).

### Core Principles

1. **Non-Technical End-User Focus**:
   Release notes are read by end users downloading the APK to their Android
   device. Notes must describe **what the user experiences** (what they see,
   hear, or can do differently), NOT how it was implemented in code.
   - Lead with the practical result from the user's viewpoint.
   - Never include implementation jargon: no extractor internals, player
     engine details, class/variable names, Android resource IDs,
     URL patterns, or architectural patterns.

2. **Strictly Dry and Factual Tone**:
   Prose must be plain, factual, and understated. Never write marketing copy,
   promotional fluff, or exaggerated claims that upplay changes.
   - **Banned marketing buzzwords and puffery**: Avoid words like *seamlessly*,
     *pure*, *dramatically*, *flawlessly*, *essential*, *modernized*,
     *maximum fidelity*, *hardened*, *streamlined*, *take full advantage of*,
     *overhaul*, or *delightful*.
   - **Factual, neutral statements**: State what was added, changed, or fixed
     plainly without editorializing or self-congratulation.

### Phrasing: Do vs. Don't

| Don't (Jargon or Marketing Puffery) | Do (Dry, User-Centric, Factual) |
| --- | --- |
| "Migrated background playback to a Media3 session with a dedicated playback thread and ring-buffered audio pipeline..." *(technical jargon)* | **Background Playback**: Fixed playback stopping when the screen turns off on some devices. |
| "Users on high-bandwidth networks can now take full advantage of up to 4K streams for maximum viewing fidelity..." *(marketing spin)* | **Video Quality**: Added a 4K option to the quality selector where the source provides it. |
| "Subscriptions now sync seamlessly across automatic reconnection attempts..." *(marketing puffery)* | **Subscriptions**: Feed updates now resume automatically after connection drops. |
| "Cleared the pending ExoPlayer buffer queue on pause transition." *(implementation detail)* | **Pause Behavior**: Audio now stops immediately on pause instead of playing a short remainder. |
| "Refactored the service descriptor registry to decouple extraction strategies per endpoint." *(developer jargon)* | Omit — no observable behavior change. If it fixes visible breakage: **Playback Fixes**: Fixed videos from [service] failing to load. |
| "Prevented concurrent modification exceptions in the playlist snapshot list." *(developer jargon)* | **Playlist Stability**: Fixed an intermittent crash when editing large playlists. |

### What to Include vs. Exclude in Highlights

| Category | Include in `## Highlights`? | Guidance |
| --- | --- | --- |
| **New Features & UI** | **Yes** | Describe new buttons, screens, settings, and behaviors. |
| **User-Observable Bug Fixes** | **Yes** | Describe the resolved crash, UI glitch, or broken behavior. |
| **Playback / Streaming Improvements** | **Yes** | Describe the visible result or stability improvement. |
| **Test Suites & Harnesses** | **No** | Completely omit (e.g. extractor test migrations, runner speedups). |
| **Developer Tooling & Scripts** | **No** | Completely omit (`check.sh`, `commit.py`, Nix expressions). |
| **Pure Code Refactoring** | **No** | Omit if there is no observable behavior change for the user. |
| **Internal Identifiers** | **No** | No file paths, class names, method names, or resource IDs. |

### Release Notes Structure

- **Title**: passed via `gh release create --title "NanoPipe <version>"`
  (e.g. `NanoPipe v0.1.0`; the title carries the `v` like the tag). Do NOT include an
  `# NanoPipe <version>` H1 heading in the notes file — GitHub renders
  `--title` above the body, so an H1 duplicates it. The notes file MUST start
  at `## Highlights`.
- **`## Highlights`**:
  - Group by feature or theme using `### Plain-English Feature Title`.
  - Under each heading, use bullet points formatted as:
    `* **Feature/Benefit Summary**: 1–2 plain-language sentences explaining what changed and why it helps the user.`
  - Order by importance (primary feature/fix first, secondary items after).
- **Universal APK Trailer** (must be the exact concluding paragraph):

  ```markdown
  Single universal release APK that works on all supported devices; there are no separate per-architecture downloads.
  ```

## 5. Build the release APK

```bash
cd PipePipeClient && nix develop --command ./gradlew assembleRelease --stacktrace -DskipFormatKtlint
```

Output: `PipePipeClient/app/build/outputs/apk/release/NanoPipe_<version>-release.apk`
(the filename interpolates the tag-derived `versionName`, so it is exact
when built at the tag; signed with the release config when signing is
configured).

## 6. Publish the release

New releases are always published as pre-releases (`--prerelease`) rather than
being marked as latest:

```bash
gh release create <version> PipePipeClient/app/build/outputs/apk/release/NanoPipe_<version>-release.apk \
  --target main --title "NanoPipe <version>" \
  --prerelease \
  --notes-file plans/release-<version>.md
```

## 7. Verify

- `gh release view <version> --json assets,body,isPrerelease` — APK attached,
  title correct, `isPrerelease` is `true`, body starts at `## Highlights`
  (no `# NanoPipe` H1 — that would duplicate `--title`), universal-APK note
  present at the end of the body, and notes free of developer jargon or
  test-only sections.
- Check the release list (`gh release list`) so the new entry shows type
  `Pre-release` and matches the naming of previous releases.
