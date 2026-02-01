rootProject.name = "boss-plugin-bookmarks"

pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

// Include BossConsole for plugin-api dependency (composite build)
includeBuild("../../BossConsole") {
    dependencySubstitution {
        substitute(module("ai.rever.boss.plugin:plugin-api-desktop")).using(project(":plugins:plugin-api"))
        substitute(module("ai.rever.boss.plugin:plugin-api-browser-desktop")).using(project(":plugins:plugin-api-browser"))
        substitute(module("ai.rever.boss.plugin:plugin-scrollbar-desktop")).using(project(":plugins:plugin-scrollbar"))
        substitute(module("ai.rever.boss.plugin:plugin-logging-desktop")).using(project(":plugins:plugin-logging"))
        substitute(module("ai.rever.boss.plugin:plugin-ui-core-desktop")).using(project(":plugins:plugin-ui-core"))
    }
}
