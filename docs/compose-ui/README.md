# Compose list UI notes

Why the `Use experimental new UI` switch exists, what it actually swaps,
why it carries a performance disclaimer, and when it dates from.

- Audited: 2026-10-25, `main` at inline `8547680` + fork fixes `af00f17`, `7712cb8`.
- All paths below are relative to the repository root.
- Upstream history below refers to `InfinityLoop1308/PipePipeClient` (`dev` branch);
  local `git log --follow` for these files stops at the `8547680` submodule inline
  (2026-10-18), which squashed prior client history.

## 1. What the switch is

- `PipePipeClient/app/src/main/res/values/strings.xml:906-907`:
  `Use experimental new UI` / `Use the new Compose-based UI, still being optimized.
  Changing this will restart the app.`
- Key: `PipePipeClient/app/src/main/res/values/settings_keys.xml:1407`
  (`use_experimental_new_ui`, default `false` = opt-in).
- Declaration (this fork): `PipePipeClient/app/src/main/res/xml/advanced_settings.xml:143-145`
  (upstream added it to `appearance_settings.xml` instead).
- `PipePipeClient/app/src/main/java/org/schabi/newpipe/settings/AdvancedSettingsFragment.java:46-56`:
  writes the pref with `commit()` and calls `NavigationHelper.restartApp()`.
- Gate: `PipePipeClient/app/src/main/java/org/schabi/newpipe/util/ThemeHelper.java:336-339`
  `shouldUseExperimentalNewUi()` reads the pref (default `false`).

Short answer to "does it do anything": yes. It swaps the list-item renderer
from XML `View` holders to Compose holders. Nothing else changes.

## 2. Wiring (what flips when the pref is on)

- `PipePipeClient/app/src/main/java/org/schabi/newpipe/info_list/InfoListAdapter.java:374-376,432-433`:
  if `shouldUseExperimentalNewUi()` and item type is not `COMMENT`,
  `getItemViewType()` returns `COMPOSE_HOLDER_TYPE` (2) and
  `onCreateViewHolder()` returns `ComposeInfoItemHolder` instead of the
  stream/channel/playlist list/grid/card/mini holders.
- `PipePipeClient/app/src/main/java/org/schabi/newpipe/local/LocalItemListAdapter.java:355-356,424-426`:
  if enabled, unconditionally returns `COMPOSE_HOLDER_TYPE` and creates
  `ComposeLocalItemHolder` for all local items (bookmarks, history,
  local/remote playlists, playlist streams, statistics), bypassing ~16 legacy holders.
- Refresh: `BaseListFragment.java:860` and `BaseLocalListFragment.java:471` watch the key
  and set `LIST_MODE_UPDATE_FLAG` so lists rebind.
- Holders:
  `.../info_list/holder/ComposeInfoItemHolder.kt` (remote `InfoItem`),
  `.../local/holder/ComposeLocalItemHolder.kt` (local `LocalItem`).
  Each wraps a `ComposeView` (`MATCH_PARENT x WRAP_CONTENT`,
  `DisposeOnViewTreeLifecycleDestroyed`) and calls `setContent` on every `updateFromItem()`.
- Shared rendering: `.../info_list/ComposeItemUiHelper.kt`:
  `PipePipeComposeTheme` (Material3 `ColorScheme` derived from the current XML theme attrs),
  `buildInfoItemState()` / `buildLocalItemState()`, `CommonItem()` dispatcher
  (`ChannelListItem` / `ChannelGridItem` vs `StreamOrPlaylistListItem` / `GridItem`),
  `ThumbnailBox`, `OverlayBadge`, `DragHandle`, `RemoteImage`, `rememberPicassoBitmap`.

Not gated by this switch: `.../fragments/list/search/SearchFilterDialog.kt` is always Compose
(it has its own separate `use_old_search_filter` gate in `SearchFragment.java:300`).

## 3. Explicit limits

- Comments are excluded: `InfoListAdapter` keeps `CommentsInfoItemHolder` /
  `CommentsMiniInfoItemHolder` even when the pref is on.
- `STAFF` items are not handled: the gate is `!= COMMENT`, so `STAFF` also routes to
  `COMPOSE_HOLDER_TYPE`, but `buildInfoItemState()` has no `STAFF` branch (`else -> null`)
  and `ComposeInfoItemHolder` bails via `?: return`, leaving an empty row.
- Only list rows are recomposed. Fragments, player, settings, app chrome stay `View`/XML.
  "New UI" = new list-item renderer, not a full Compose rewrite.
- Grid/list/card and drag-handle modes are still honored via
  `ThemeHelper.isGrid(itemViewMode)` / `itemMode == CARD` / `showDragHandle`.

## 4. Performance disclaimer ("still being optimized")

The string is inherited from upstream (blame stops at the `8547680` inline);
no benchmark is checked in. It is honest for three structural reasons:

1. **Interop cost.** Every row creates a `ComposeView` and runs a `Composition` on bind,
   vs. plain XML inflation + view binding. RecyclerView + Compose interop is heavier
   on scroll, before any image work happens.
2. **Image bridge.** Legacy rows use `Picasso.into(ImageView)` (recycled, auto-cancelled).
   Compose rows use custom `rememberPicassoBitmap` (`Target -> Bitmap -> asImageBitmap()`
   into Compose `Image`) with manual `DisposableEffect` cancel. Two correctness fixes
   in this fork show the immaturity: `af00f17` (Picasso holds `Target` weakly, so an
   un-held `Target` was GC'd → permanent gray box) and `7712cb8` (cancel targeted
   `Picasso.get()` while requests use the helper's own instance = no-op cancel;
   failures now render `dummy_thumbnail` instead of a gray box). Even fixed, it is an
   extra copy plus per-item effect management.
3. **Bind-time DB hit.** `buildInfoItemState()` does
   `historyRecordManager.loadStreamState(item).blockingGet()` on the calling thread —
   same as legacy `StreamInfoItemHolder.java:99-100` — so the existing main-thread DB read
   now compounds with composition cost during fast scroll.
4. **Binary cost.** Per `docs/apk-size-analysis/README.md`, `androidx.compose.*` is
   1,827 input classes / 99,308 method entries (largest per-package total) plus
   `libandroidx.graphics.path.so` (~7-11 KB per ABI, 37,392 B total). Enabling the rows does not add
   the dependency (it ships either way), but it is the reason the disclaimer and the
   size audit point at the same subsystem.

Practical read: parity is functional, not tuned. Leave the switch off for smoothness.

## 5. Dating

- Birth: upstream `InfinityLoop1308/PipePipeClient@9268963`
  `UI: make new ui opt in, add a switch to use back the old ui`,
  author `2026-05-22T07:53:58Z`, committer `2026-05-23T05:58:36Z`.
  `ComposeItemUiHelper.kt`, `ComposeInfoItemHolder.kt`, `ComposeLocalItemHolder.kt`
  are all `added` in that commit, together with the pref key/strings and the switch.
- Context: upstream `v5.0.0` (`2026-05-02`) advertised "slightly improved UI";
  upstream issue `InfinityLoop1308/PipePipe#2306` (`[Feature request] Old New UI`,
  opened `2026-05-19`, closed `2026-05-23`) brackets the opt-in commit.
- This fork: visible only from `8547680 Inline PipePipeClient and PipePipeExtractor
  submodules` (`2026-10-18`); the two thumbnail follow-ups are `af00f17` and `7712cb8`
  (both `2026-10-24`).

## References

- Switch + gate: `values/strings.xml:906-907`, `values/settings_keys.xml:1407`,
  `xml/advanced_settings.xml:143-145`, `settings/AdvancedSettingsFragment.java:46-56`,
  `util/ThemeHelper.java:336-339`.
- Adapters: `info_list/InfoListAdapter.java:374-376,432-433`,
  `local/LocalItemListAdapter.java:355-356,424-426`,
  `fragments/list/BaseListFragment.java:860`, `local/BaseLocalListFragment.java:471`.
- Compose: `info_list/ComposeItemUiHelper.kt`, `info_list/holder/ComposeInfoItemHolder.kt`,
  `local/holder/ComposeLocalItemHolder.kt`.
- Upstream: `PipePipeClient@9268963` (birth/opt-in), `PipePipe#2306` (issue),
  `PipePipeClient@v5.0.0` release notes.
- Size: `docs/apk-size-analysis/README.md` §3 (Compose census), §4 (graphics.path `.so`), §6 #7.
- Thumbnails: `docs/youtube-thumbnails/README.md` §4-6, `af00f17`, `7712cb8`.
