pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "BuzinCast"
include(":app")
include(":dlna-domain")
include(":receiver-core")
if (providers.gradleProperty("buzinFireOs3Probe").orNull == "true") {
    include(":fireos3-probe")
}
