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

rootProject.name = "Orion"

include(":app")
// Liquid Glass 组件库（来源：https://github.com/Kyant0/AndroidLiquidGlass ，Apache-2.0，
// 已按单平台（Android）源码整理，见 backdrop/LICENSE.txt）
include(":backdrop")