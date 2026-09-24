import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.gms.google-services")
    id("androidx.room")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Per-machine backend config (API endpoint + Cognito identifiers) — not
// secrets (this app client has no client secret; see
// auth/CognitoAuthManager.kt), but no reason to publish a specific
// deployment's exact infra either, so they don't belong as literals in a
// tracked file. `local.properties` is the standard Android spot for
// exactly this kind of local-only value — it's where `sdk.dir` already
// lives, and `android-app/**/local.properties` is already in .gitignore.
// See `local.properties.example` for the keys this reads and
// android-app/README.md's "Fill in the real backend values" section for
// where the real values come from. Falls back to an obvious
// REPLACE_WITH_YOUR_... placeholder when a key (or the whole file) is
// missing, so a fresh clone still builds instead of failing with a
// confusing Properties/IO error — it'll just point at nothing until you
// fill local.properties in.
val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        FileInputStream(localPropertiesFile).use { load(it) }
    }
}

fun localProperty(key: String, placeholder: String): String = localProperties.getProperty(key) ?: placeholder

android {
    namespace = "cc.gilmore.todoai"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.gilmore.todoai"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase1"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            // The API base URL is a build config field rather than hardcoded so
            // release builds can point somewhere different later without a
            // source change. Today both flavors point at the one deployed
            // API — values come from local.properties (see the top of this
            // file), not a literal here.
            buildConfigField("String", "API_BASE_URL", "\"${localProperty("api.baseUrl", "https://REPLACE_WITH_YOUR_API_URL.execute-api.us-east-1.amazonaws.com/")}\"")
            buildConfigField("String", "COGNITO_USER_POOL_ID", "\"${localProperty("cognito.userPoolId", "REPLACE_WITH_YOUR_USER_POOL_ID")}\"")
            buildConfigField("String", "COGNITO_CLIENT_ID", "\"${localProperty("cognito.clientId", "REPLACE_WITH_YOUR_CLIENT_ID")}\"")
            buildConfigField("String", "COGNITO_REGION", "\"${localProperty("cognito.region", "us-east-1")}\"")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
    }
}

// Pins the Kotlin (and therefore KSP, which compiles on the Kotlin
// compiler's target) toolchain to the same JDK 17 that compileOptions
// above targets for javac. Without this, Kotlin/KSP default to whatever
// JDK is actually running Gradle — e.g. a JDK 21 "embedded" JDK bundled
// with a newer Android Studio — which mismatches the explicit Java 17
// target and fails with "Inconsistent JVM-target compatibility detected
// for tasks 'compileDebugJavaWithJavac' (17) and 'kspDebugKotlin' (21)".
kotlin {
    jvmToolchain(17)
}

// Material3's TopAppBar, ExposedDropdownMenuBox, DatePicker/TimePicker etc.
// are all still gated behind @ExperimentalMaterial3Api (same marker for
// all of them) — used across TodoDetailScreen.kt, TodoListScreen.kt, and
// Pickers.kt. Opting in at the module level here avoids sprinkling
// @OptIn(ExperimentalMaterial3Api::class) across every composable that
// touches one of these; that's the standard fix, not a shortcut around a
// real problem — these APIs are stable in practice, just not yet
// API-frozen by the Compose team.
tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

// debug's buildConfigField block above doesn't get copied to release by
// default — mirror it so a release build isn't left with placeholder
// values silently baked in. Same localProperty(...) reads as debug, so a
// release build picks up the same local.properties values (or the same
// placeholder fallback) without a second place to edit.
android.buildTypes.getByName("release") {
    buildConfigField("String", "API_BASE_URL", "\"${localProperty("api.baseUrl", "https://REPLACE_WITH_YOUR_API_URL.execute-api.us-east-1.amazonaws.com/")}\"")
    buildConfigField("String", "COGNITO_USER_POOL_ID", "\"${localProperty("cognito.userPoolId", "REPLACE_WITH_YOUR_USER_POOL_ID")}\"")
    buildConfigField("String", "COGNITO_CLIENT_ID", "\"${localProperty("cognito.clientId", "REPLACE_WITH_YOUR_CLIENT_ID")}\"")
    buildConfigField("String", "COGNITO_REGION", "\"${localProperty("cognito.region", "us-east-1")}\"")
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.navigation:navigation-compose:2.8.4")

    // Local persistence: the offline-first cache + the outbox of pending
    // mutations made while offline. See data/local/AppDatabase.kt.
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Background sync of the outbox, and (later) periodic refresh.
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Networking. kotlinx.serialization over Moshi/Gson: no reflection,
    // no separate codegen step to go wrong, and it's already a transitive
    // dependency of the Kotlin serialization plugin applied above.
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Cognito SRP auth. This is the older, lighter-weight "AWS Mobile SDK"
    // Cognito module — not the full Amplify framework — chosen because it
    // does real SRP (USER_SRP_AUTH, matching the app client's authFlows in
    // aws-backend/infra/lib/auth-stack.ts) without pulling in Amplify's
    // broader config/plugin system for a single feature. See
    // auth/CognitoAuthManager.kt.
    implementation("com.amazonaws:aws-android-sdk-cognitoidentityprovider:2.75.1")

    // Encrypted local storage for the Cognito tokens.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Push notifications for due-date reminders (see push/TodoFcmService.kt).
    // Requires a real google-services.json — see android-app/README.md.
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")

    implementation("androidx.datastore:datastore-preferences:1.1.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
}
