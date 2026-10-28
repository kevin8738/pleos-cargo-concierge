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
        // Pleos Playground SDK (Vehicle / NaviHelper / FusedLocation / TextToSpeech / SpeechToText).
        // Scoped to the ai.pleos.playground group so other artifacts never hit this host.
        // Includes sub-groups such as ai.pleos.playground.security (universal-access, pulled in by TTS/STT).
        maven("https://nexus-playground.pleos.ai/repository/maven-releases/") {
            content { includeGroupByRegex("ai\\.pleos\\.playground(\\..*)?") }
        }
    }
}

rootProject.name = "CargoConcierge"

include(":core")
include(":app")

// The Pleos adapter is an optional module: `-PincludePleos=false` (or offline builds without the
// Pleos artifacts cached) keeps the :app mock flavor building and the :core tests green.
val includePleos = (providers.gradleProperty("includePleos").orNull ?: "true").toBoolean()
if (includePleos) {
    include(":vehicle-pleos")
}
