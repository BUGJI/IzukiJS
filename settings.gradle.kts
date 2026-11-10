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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // QuickJS 16KB 页大小兼容版（taoweiji/quickjs-android 的分支修复）与 libadb。
        maven(url = "https://jitpack.io") {
            content {
                includeGroupByRegex("com\\.github\\.hjhrq1991.*")
                includeGroupByRegex("com\\.github\\.MuntashirAkon.*")
            }
        }
    }
}

rootProject.name = "Izuki JS"
include(":app")
