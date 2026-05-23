import org.gradle.api.GradleException
import java.io.ByteArrayOutputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

fun gitOutput(vararg args: String): String? = try {
    val stdout = ByteArrayOutputStream()
    val proc = ProcessBuilder("git", *args)
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    proc.inputStream.copyTo(stdout)
    if (proc.waitFor() == 0) stdout.toString(Charsets.UTF_8).trim() else null
} catch (e: Exception) {
    null
}

val gitVersionName: String = gitOutput("describe", "--tags", "--always", "--dirty")
    ?: "0.0.1-unknown"
val gitVersionCode: Int = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 1

android {
    namespace = "io.github.tffinder.mirrocast"
    compileSdk = 35
    ndkVersion = "26.2.11394342"

    defaultConfig {
        applicationId = "io.github.tffinder.mirrocast"
        minSdk = 24
        targetSdk = 34
        versionCode = gitVersionCode
        versionName = gitVersionName

        externalNativeBuild {
            cmake {
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
                storePassword = System.getenv("KEYSTORE_PWD")
                    ?: throw GradleException("KEYSTORE_PWD env var missing")
                keyAlias = System.getenv("KEY_ALIAS")
                    ?: throw GradleException("KEY_ALIAS env var missing")
                keyPassword = System.getenv("KEY_PWD")
                    ?: throw GradleException("KEY_PWD env var missing")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (!System.getenv("KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        resources.excludes += setOf(
            "/META-INF/{AL2.0,LGPL2.1}",
            "/META-INF/DEPENDENCIES",
            "/META-INF/LICENSE*",
            "/META-INF/NOTICE*"
        )
    }

    sourceSets {
        named("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
