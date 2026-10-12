pluginManagement {
    repositories {
        google { content {
            includeGroupByRegex("com\\.android.*")
            includeGroupByRegex("com\\.google\\.testing\\.platform.*")
        } }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content {
            includeGroupByRegex("com\\.android.*")
            includeGroupByRegex("androidx.*")
            includeGroupByRegex("com\\.google\\.testing\\.platform.*")
        } }
        mavenCentral()
    }
}
rootProject.name = "AiUsage"
include(":app")
include(":core:ui")
