# BOSS Bookmarks Plugin

A dynamic plugin for BOSS that displays and manages browser bookmarks in a sidebar panel.

## Status: Phase 2 (In Progress)

This plugin is part of the BOSS dynamic plugins initiative. Full implementation is **deferred to Phase 2** because it requires window-scoped services that are currently provided via CompositionLocals in BossApp.kt.

### Required Services
- `BookmarkDataProvider` - Provides bookmark data and management operations
- `WorkspaceDataProvider` - Provides workspace state for bookmark operations
- `SplitViewOperations` - Enables opening bookmarks in split view

### Phase 2 Requirements

Before this plugin can be fully functional, BossConsole needs to support window-scoped service injection through `PluginContext`. Currently, these services are instantiated per-window in `BossApp.kt` and provided via CompositionLocals.

### Building

```bash
./gradlew buildPluginJar
```

The plugin JAR will be generated at `build/libs/boss-plugin-bookmarks-1.0.0.jar`.

### Installation

Copy the JAR to `~/.boss/plugins/`:

```bash
cp build/libs/boss-plugin-bookmarks-1.0.0.jar ~/.boss/plugins/
```

**Note**: This plugin will not work until Phase 2 is complete.

## Development

### Local Development

This project uses Gradle composite builds to depend on BossConsole's plugin-api:

```kotlin
// settings.gradle.kts
includeBuild("../../BossConsole") {
    dependencySubstitution {
        substitute(module("ai.rever.boss.plugin:plugin-api-desktop")).using(project(":plugins:plugin-api"))
    }
}
```

### Project Structure

```
boss-plugin-bookmarks/
├── build.gradle.kts
├── settings.gradle.kts
├── src/
│   └── main/
│       ├── kotlin/
│       │   └── ai/rever/boss/plugin/dynamic/bookmarks/
│       │       ├── BookmarksDynamicPlugin.kt
│       │       └── BookmarksInfo.kt
│       └── resources/
│           └── META-INF/
│               └── boss-plugin/
│                   └── plugin.json
└── README.md
```

## License

Copyright (c) 2026 Risa Labs. All rights reserved.
