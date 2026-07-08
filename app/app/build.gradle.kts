plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.chatbar"
    compileSdk = 36
    val baseVersionCode = 12
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
        versionCode = providers.gradleProperty("CHATBAR_VERSION_CODE")
            .orElse(providers.environmentVariable("CHATBAR_VERSION_CODE"))
            .orNull
            ?.let { raw ->
                val parsed = raw.toIntOrNull()
                    ?: throw org.gradle.api.GradleException("CHATBAR_VERSION_CODE must be an integer, got '$raw'")
                if (parsed < baseVersionCode) {
                    throw org.gradle.api.GradleException("CHATBAR_VERSION_CODE must be >= $baseVersionCode, got '$raw'")
                }
                parsed
            }
            ?: baseVersionCode
        versionName = providers.gradleProperty("CHATBAR_VERSION_NAME")
            .orElse(providers.environmentVariable("CHATBAR_VERSION_NAME"))
            .orElse("1.1.5")
            .get()
        fun configValue(name: String, defaultValue: String = ""): String =
            providers.gradleProperty(name)
                .orElse(providers.environmentVariable(name))
                .orElse(defaultValue)
                .get()
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")

        val bundledSiliconFlowKey = configValue("CHATBAR_SILICONFLOW_API_KEY")
        val supabaseUrl = configValue("CHATBAR_SUPABASE_URL")
        val supabaseAnonKey = configValue("CHATBAR_SUPABASE_ANON_KEY")
        val supabaseRedirectUri = configValue("CHATBAR_SUPABASE_REDIRECT_URI", "chatbar://auth/callback")
        buildConfigField("String", "SILICONFLOW_API_KEY", "\"$bundledSiliconFlowKey\"")
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
        buildConfigField("String", "SUPABASE_REDIRECT_URI", "\"$supabaseRedirectUri\"")
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
