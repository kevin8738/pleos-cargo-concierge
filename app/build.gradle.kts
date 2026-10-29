import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// :vehicle-pleos is included only when the Pleos SDK can be resolved (see settings.gradle.kts).
val pleosEnabled = findProject(":vehicle-pleos") != null

// Optional release keystore (see signingConfigs below).
val keystorePropsFile = rootProject.file("keystore/keystore.properties")
val keystoreProps = Properties().apply { if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) } }

android {
    namespace = "ai.vipseat.cargoconcierge"
    compileSdk = 34

    defaultConfig {
        applicationId = "ai.vipseat.cargoconcierge"
        minSdk = 28
        targetSdk = 34
        versionCode = 4
        versionName = "1.1.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "PLEOS_MODULE_PRESENT", pleosEnabled.toString())
        // adb demo bridge (DemoBroadcastReceiver): enabled in every build of this contest prototype so judges can
        // replay the demo scripts on the release APK; must be "false" for an App Market build.
        buildConfigField("boolean", "DEMO_BRIDGE", "true")
    }

    // Compile against the AAOS car library shipped in platforms/android-34/optional/android.car.jar.
    // Used by the stock-emulator adapter (CarPropertyManager) and the UX-restrictions monitor.
    useLibrary("android.car")

    flavorDimensions += "vehicle"
    productFlavors {
        create("mock") {
            dimension = "vehicle"
            isDefault = true
            versionNameSuffix = "-mock"
            buildConfigField("String", "VEHICLE_FLAVOR", "\"mock\"")
        }
        create("pleos") {
            dimension = "vehicle"
            versionNameSuffix = "-pleos"
            buildConfigField("String", "VEHICLE_FLAVOR", "\"pleos\"")
        }
    }

    // Release signing: keystore/keystore.properties (gitignored) → storeFile / storePassword / keyAlias / keyPassword.
    // Without that file the release build is signed with the debug key, so `assembleRelease` always yields an
    // installable APK (reviewers rebuilding from source do not need our keystore).
    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (keystorePropsFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    sourceSets {
        getByName("main").java.srcDirs("src/main/kotlin")
        getByName("mock").java.srcDirs("src/mock/kotlin")
        getByName("pleos").java.srcDirs("src/pleos/kotlin")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// Without the Pleos module the pleos flavor cannot compile; disable it instead of failing the build.
androidComponents {
    beforeVariants { variant ->
        if (variant.flavorName == "pleos" && !pleosEnabled) {
            variant.enable = false
        }
    }
}

dependencies {
    implementation(project(":core"))
    if (pleosEnabled) {
        "pleosImplementation"(project(":vehicle-pleos"))
    }

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

// With product flavors AGP only creates test<Flavor>DebugUnitTest; this alias keeps the plain
// `./gradlew assembleDebug testDebugUnitTest` command working and folds in the :core JVM tests.
afterEvaluate {
    if (tasks.findByName("testDebugUnitTest") == null) {
        tasks.register("testDebugUnitTest") {
            group = "verification"
            description = "Runs :core tests plus the debug unit tests of every enabled flavor"
            dependsOn(":core:test")
            dependsOn(tasks.matching { it.name.matches(Regex("test[A-Z]\\w*DebugUnitTest")) })
        }
    }
}
