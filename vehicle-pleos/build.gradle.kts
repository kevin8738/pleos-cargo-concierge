import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pleos Connect SDK adapter. Depends on the real Pleos artifacts; everything SDK-specific lives here
// so the :app mock flavor and :core never need the Pleos Maven repository.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "ai.vipseat.cargoconcierge.pleos"
    compileSdk = 34

    defaultConfig {
        minSdk = 28
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)

    // ai.pleos.playground artifacts (resolved from nexus-playground.pleos.ai)
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.fusedlocation)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)
    implementation(libs.pleos.vehicle.data)
    implementation(libs.pleos.fusedlocation.data)
}
