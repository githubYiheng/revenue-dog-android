pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "revenue-dog-android"

include(":purchases")
include(":api-tester")
include(":detekt-rules")
// 最小手测 app（形态照 RC `examples/purchase-tester` 裁剪）。
// 它同时是 **consumer ProGuard 规则的端到端门禁**：`:example:assembleRelease` 开着
// `minifyEnabled true`，规则漏了就在这里当场暴露（设计 §7 最后一行）。
include(":example")
