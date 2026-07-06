plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.chatbar"
    compileSdk = 36
    val releaseKeystorePath = providers.environmentVariable("ANDROID_KEYSTORE_PATH")
    val releaseKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS")
    val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD")
    val hasReleaseSigning = releaseKeystorePath.isPresent &&
        releaseKeystorePassword.isPresent &&
        releaseKeyAlias.isPresent &&
        releaseKeyPassword.isPresent

    defaultConfig {
        applicationId = "com.example.chatbar"
        minSdk = 26
        targetSdk = 36
        versionCode = 8
        versionName = "1.0.6"
        val bundledSiliconFlowKey = providers.gradleProperty("CHATBAR_SILICONFLOW_API_KEY")
            .orElse(providers.environmentVariable("CHATBAR_SILICONFLOW_API_KEY"))
            .orElse("")
            .get()
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
        buildConfigField("String", "SILICONFLOW_API_KEY", "\"$bundledSiliconFlowKey\"")
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystorePath.get())
                storePassword = releaseKeystorePassword.get()
                keyAlias = releaseKeyAlias.get()
                keyPassword = releaseKeyPassword.get()
            }
        }
    }

    buildTypes {
        release {
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = true
      shaders = false
    }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

// kotlin {
//     jvmToolchain(17)
// }

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation(libs.icons.lucide.cmp)
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // ObjectBox
  implementation(libs.objectbox.kotlin)

  // Network - OkHttp + SSE + Retrofit
  implementation(libs.okhttp)
  implementation(libs.okhttp.sse)
  implementation(libs.okhttp.logging)
  implementation(libs.retrofit)
  implementation(libs.retrofit.kotlinx.serialization)

  // Image loading
  implementation(libs.coil.compose)

  // DataStore for settings
  implementation(libs.datastore.preferences)

  // Markdown rendering
  implementation(libs.markwon.core)
  implementation(libs.markwon.html)
  implementation(libs.markwon.strikethrough)

  // Serialization
  implementation(libs.kotlinx.serialization.json)
  implementation(libs.kotlinx.datetime)
  implementation(libs.msgpack.core)

  // Coroutines
  implementation(libs.kotlinx.coroutines.android)
}
