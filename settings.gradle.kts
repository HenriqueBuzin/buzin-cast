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
val oldProbe = providers.gradleProperty("buzinFireOs3Probe").orNull == "true"
val oldApp = providers.gradleProperty("buzinFireOsLegacy").orNull == "true"
if (oldProbe || oldApp) include(":fireos3-runtime")
if (oldProbe) {
    include(":fireos3-probe")
}
if (oldApp) include(":fireos-legacy")
