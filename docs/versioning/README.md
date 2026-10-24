# Tag-driven versioning proposal

How `versionName` should be resolved from git tags (mumla-oled style),
without touching `versionCode`.

- Status: proposed, not implemented.
- Scope: `PipePipeClient/app/build.gradle` (`appVersionName`,
  `universalVersionCode`), release-tagging workflow.
- Reference implementation: mumla-oled `app/build.gradle`
  (`git describe` via `providers.exec`, leading `v` stripped) and its
  `AGENTS.md` "Versioning" section.
- All paths below are relative to the repository root.

## Background

Today `appVersionName` is a hardcoded string in
`PipePipeClient/app/build.gradle` (currently `"5.5.0-beta3"`), and the
repository has no tags. Every build — release, worktree, or
three-commits-after-release — reports the same version in the About
screen, crash reports (`BuildConfig.VERSION_NAME`), and subscription
exports. Releasing means hand-editing `build.gradle`, and the audit
notes under `docs/*` must separately pin "audited at commit X, app
version Y" because the version is not derivable from git history.

Mumla-oled avoids this: `versionName` is resolved at configuration
time from `git describe --tags ... --always` with the leading `v`
stripped, so tags are the single source of truth, dev builds
self-identify (e.g. `0.26.1-5-gabc1234`), and releases are just
annotated tags. Its `versionCode`, by contrast, is a frozen constant
(`3070300`, never bumped in history) plus news-items bookkeeping —
vestigial, and not applicable here.

## Proposal

Port only the `versionName`-from-tag half. Keep `versionCode` exactly
as-is.

### 1. `PipePipeClient/app/build.gradle` (~10 lines)

Replace the hardcoded string:

```groovy
def baseVersionCode = 1110
def appVersionName = "5.5.0-beta3"
```

with mumla's resolution, verbatim (plus the `-debug` suffix decision
from below):

```groovy
def baseVersionCode = 1110
def gitDescribeRaw = providers.exec {
    // --always: never fail; falls back to a bare commit hash when no
    // tag is reachable (e.g. CI checkout without fetch-tags). The dual
    // --match accepts both v-prefixed and legacy bare tags.
    commandLine "git", "describe", "--tags", "--match", "v[0-9]*.[0-9]*.[0-9]*", "--match", "[0-9]*.[0-9]*.[0-9]*", "--always"
}.standardOutput.asText.get().trim()
// Strip the leading 'v' so versionName stays bare semver.
def appVersionName = gitDescribeRaw.replaceFirst(/^v(?=\d)/, "")
```

Everything downstream keeps working unchanged: `versionName
appVersionName`, the APK filename
(`PipePipe_${appVersionName}-${variant.name}.apk`), and all
`BuildConfig.VERSION_NAME` readers (About screen, crash reports,
subscription-export JSON).

Deliberately not ported from mumla:

- `VERSIONTAG` / `split("-")[0]`: would mangle prerelease tags
  (`5.5.0-beta3` splits to `5.5.0`). PipePipe has no `VERSIONTAG`
  consumer, so skip it.
- `versionCode 3070300` and the `XYYZZbb` / news-items bookkeeping:
  no equivalent exists here. `universalVersionCode` (`100 *
  baseVersionCode`, kept above the legacy per-ABI split codes) stays
  frozen; bump `baseVersionCode` only if store-style upgrade ordering
  ever requires it.

On the configuration-cache concern: the existing comment in
`build.gradle` warns against *eager* git probing at configuration
time. `providers.exec` is Gradle's lazy provider API — the value is
tracked as a build input, not an untracked side effect — which is why
mumla gets away with it under the same
`org.gradle.configuration-cache=true`. Verify with two consecutive
cached builds during review.

### 2. Tag convention and bootstrapping

`appVersionName` has been `"5.5.0-beta3"` since the repo's squashed
import, and there are no tags. The change therefore ships with a
genesis anchor tag — created *before* the implementation lands, so
`git describe` resolves from the start:

```sh
git tag -a v0.0.0 -m "Release v0.0.0"
```

`v0.0.0`, not `v5.5.0-beta3`: current `main` was never cut as a
release, so backdating a release tag would rewrite history. Until
the first real release tag, builds honestly report `0.0.0-N-g...`.
After that: `v5.5.0-beta4`, `v5.5.0`, and so on — annotated tags,
`v` prefix canonical, bare match kept only as fallback. Release
process becomes tag plus build. Builds from a tag yield an exact
`versionName`; worktree/dev builds self-identify as
`<tag>-N-gabc1234`.

Also included (decided, not optional): mumla's `versionNameSuffix
"-debug"` in the `debug` block. One line, and it makes
About-screen identification airtight for dev builds. It does not
affect the APK filename (which interpolates the raw variable, not
the suffixed name) unless that line is touched too — leave the
filename as-is.

### 3. `AGENTS.md` "Versioning" section

Document the mechanism alongside the implementation, mirroring
mumla's section minus the code parts:

```md
## Versioning
- **Dynamic versionName**: resolved at configuration time via
  `git describe --tags --match "v[0-9]*..." --match "[0-9]*..." --always`,
  leading `v` stripped. Never hardcode `appVersionName`.
- **Release tagging**: `git tag -a v<version> -m "Release v<version>"`
  (e.g. `v5.5.0-beta4`). The `v` prefix is canonical.
- **versionCode**: frozen at `100 * baseVersionCode` (stays above legacy
  per-ABI codes). Bump `baseVersionCode` only if store-style upgrade
  ordering ever requires it; it is not tied to tags.
```

## Verification

Per repo rules this lands as a worktree change, verified before
review:

- `./scripts/check.sh --quick`, then the default Fast Gate
  (`assembleDebug` + `lintDebug`).
- Confirm the About-screen version and APK filename from a worktree
  at the anchor tag versus a few commits later (`0.0.0` vs.
  `0.0.0-N-g...`, plus `-debug` for debug builds).
- Two back-to-back `./gradlew assembleDebug` runs to confirm the
  configuration cache stays clean (`problems=warn` surfaces any
  complaint without failing).

## Limitations (accepted, shared with mumla)

- No-tags / shallow-clone builds report a hash version. Fine for dev,
  never ships.
- A source tarball without `.git` would fail configuration (mumla
  shares this). F-Droid builds from git, so this is a non-issue
  today; a `try/catch` fallback (e.g. `"0.0.0-unknown"`) is possible
  but deferred until needed.
- `PipePipeExtractor/build.gradle`'s `version 'v5.4.0'` is a
  separate Maven-style coordinate and out of scope.

## Decisions (resolved)

- Bootstrap with a `v0.0.0` genesis tag rather than backdating
  `v5.5.0-beta3`: current `main` was never a release cut.
- Include the `-debug` `versionNameSuffix`: saving a single line is
  not worth dev builds that look exactly like releases.
