pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google { content { includeGroupByRegex("androidx\\..*"); includeGroupByRegex("com\\.android.*"); includeGroupByRegex("com\\.google\\.android.*") } }
        mavenCentral()
    }
}
rootProject.name = "Pawchive"
include(":app", ":core:data", ":core:media", ":core:download")
