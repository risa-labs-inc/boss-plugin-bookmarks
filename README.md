# BOSS Bookmarks Plugin

Bookmarks for browser pages, files and terminals, with independent Favorites, folders, and workspace navigation. Removing a favorite keeps the saved bookmark. Edit and move preserve metadata; deletion supports undo.

## Host dependency: not ready for standalone release

This branch requires the matching BossConsole host change ([PR #759](https://github.com/risa-labs-inc/BossConsole/pull/759)), including `BookmarkLibraryProvider`, `BookmarkOpeningProvider`, and `BookmarkLibraryState.unfiledCollectionIds`. SDK **1.0.73 alone does not contain these contracts**. The SDK pin is the baseline dependency, not a declaration that this plugin can run on every host using that SDK.

The exact host source revision is pinned in [.github/bookmark-host-revision](.github/bookmark-host-revision). CI builds its bookmark-types module from source. That source-built jar is compile/test-only and is never bundled in the plugin. Its existing module version is not a newly published SDK release.

Keep the plugin PR dependent/draft until the matching host is available and the contracts are published through the SDK or the production release workflow gains equivalent dependency preparation. The current shared release workflow cannot build this branch as-is: it downloads only the baseline SDK and has no custom contract preparation step. Do not merge to `main` or trigger release while that prerequisite remains unresolved. No guessed minimum host version is declared; deployment requires the actual matching host capabilities.

## Reproducible build

Requires Git, JDK 17 and network access for Gradle dependencies. From this repository:

```bash
mkdir -p build/downloaded-deps
curl -fSL -o build/downloaded-deps/boss-plugin-api.jar \
  https://github.com/risa-labs-inc/boss-plugin-api/releases/download/v1.0.73/boss-plugin-api-1.0.73.jar
git clone https://github.com/manishakuhar/BossConsole.git build/host-contract
git -C build/host-contract checkout --detach "$(cat .github/bookmark-host-revision)"
./build/host-contract/gradlew -p build/host-contract \
  :plugin-platform:plugin-bookmark-types:desktopJar
CI=true ./gradlew test buildPluginJar \
  -PbookmarkTypesJar="$PWD/build/host-contract/plugin-platform/plugin-bookmark-types/build/libs/plugin-bookmark-types-desktop-1.0.5.jar"
```

On headless Linux, run the final command under `xvfb-run -a` for Compose UI tests. CI supplies this virtual display. Reuse an existing matching local contract jar by passing its absolute path to `-PbookmarkTypesJar`. The plugin artifact is `build/libs/boss-plugin-bookmarks-2.1.10.jar`.

## Isolated trials and persistence

Launch a matching development host with `-Dboss.bookmarks.directory=/absolute/path/to/isolated-bookmarks`. Copy test data there first. Without this override the default remains `~/Documents/BOSS/bookmarks`; avoid pointing development trials at your released app's live data.

`bookmark-library.json` is the durable authority. Migration retains original legacy files and record metadata. Favorites membership and the internal unfiled folder identity are stored separately from folder display names. A user folder named “Unsorted” is still a real folder. Cross-window saves refresh automatically; conflicting external edits require explicit reload. Legacy file changes are conservatively imported as copies, not bidirectionally synchronized with older apps.

Imported terminal bookmarks can contain a saved startup command. Opening such a bookmark may execute that command in its terminal; inspect the destination and command before opening imported records.

## Validation

Run the full test suite above before using a new artifact. Store tests cover persistence failure, conflicts, migration, duplicate saves, favorites, metadata, undo and restart. Compose tests cover panel navigation, root bookmarks alongside custom folders, favorite filters, narrow navigation, and edit/delete dialogs. Passing these tests does not substitute for testing the combined plugin and host in the running application.
