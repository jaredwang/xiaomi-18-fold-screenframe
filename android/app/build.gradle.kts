import java.time.LocalDate
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * Release signing key. CI (the "Release APK" workflow) passes it through
 * SIGNING_* environment variables; locally, the properties file written by
 * scripts/setup-release-signing.sh is used if present. Without either,
 * release builds fall back to the debug key.
 */
val releaseSigning: Map<String, String>? = System.getenv("SIGNING_KEYSTORE_PATH")?.let { path ->
    mapOf(
        "storeFile" to path,
        "storePassword" to System.getenv("SIGNING_STORE_PASSWORD").orEmpty(),
        "keyAlias" to System.getenv("SIGNING_KEY_ALIAS").orEmpty(),
        "keyPassword" to System.getenv("SIGNING_KEY_PASSWORD").orEmpty(),
    )
} ?: File(System.getProperty("user.home"), ".android/fold18-screenframe-release.properties")
    .takeIf { it.isFile }
    ?.let { file -> Properties().apply { file.inputStream().use(::load) } }
    ?.let { props -> props.stringPropertyNames().associateWith { props.getProperty(it) } }

/** Date of the last commit (yyyy-MM-dd); today when building outside a git checkout. */
val commitDate: String = runCatching {
    providers.exec { commandLine("git", "log", "-1", "--format=%cs") }
        .standardOutput.asText.get().trim()
}.getOrNull()?.takeIf { it.isNotEmpty() } ?: LocalDate.now().toString()

android {
    namespace = "com.veltrix.fold.frame"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.veltrix.fold.frame"
        minSdk = 33
        targetSdk = 35
        // CI sets these from the release tag and the workflow run number.
        versionCode = System.getenv("VERSION_CODE")?.toInt() ?: 1
        versionName = System.getenv("VERSION_NAME") ?: "1.0.3"
        // Shown in About as the update date: the date of the commit being built.
        buildConfigField("String", "BUILD_DATE", "\"$commitDate\"")
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        releaseSigning?.let { s ->
            create("release") {
                storeFile = file(s.getValue("storeFile"))
                storePassword = s.getValue("storePassword")
                keyAlias = s.getValue("keyAlias")
                keyPassword = s.getValue("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
