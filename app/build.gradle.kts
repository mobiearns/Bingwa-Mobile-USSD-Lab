plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val releaseSigningEnv = listOf("BINGWA_UPLOAD_STORE_FILE","BINGWA_UPLOAD_STORE_PASSWORD","BINGWA_UPLOAD_KEY_ALIAS","BINGWA_UPLOAD_KEY_PASSWORD").all { !System.getenv(it).isNullOrBlank() }
val repositoryKeystore = rootProject.file("signing/bingwa-distribution.keystore")
val hasReleaseSigning = releaseSigningEnv || repositoryKeystore.isFile
val releaseStoreType = System.getenv("BINGWA_UPLOAD_STORE_TYPE")

android {
    namespace = "com.bingwa.mobile"
    compileSdk = 36
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "com.bingwa.mobile"
        minSdk = 21
        targetSdk = 36
        versionCode = 271
        versionName = "1.1.236"
    }
    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                if (releaseSigningEnv) {
                    storeFile = file(System.getenv("BINGWA_UPLOAD_STORE_FILE"))
                    storePassword = System.getenv("BINGWA_UPLOAD_STORE_PASSWORD")
                    if (!releaseStoreType.isNullOrBlank()) storeType = releaseStoreType
                    keyAlias = System.getenv("BINGWA_UPLOAD_KEY_ALIAS")
                    keyPassword = System.getenv("BINGWA_UPLOAD_KEY_PASSWORD")
                } else {
                    storeFile = repositoryKeystore
                    storeType = "JKS"
                    storePassword = "BingwaMobileLocal2026"
                    keyAlias = "bingwa-mobile"
                    keyPassword = "BingwaMobileLocal2026"
                }
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            check(hasReleaseSigning) { "Release signing key is missing. Configure BINGWA_UPLOAD_* or restore signing/bingwa-distribution.keystore." }
            signingConfig = signingConfigs.getByName("release")
        }
    }
    lint { abortOnError = false; checkReleaseBuilds = false }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true; compose = true }
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    testImplementation("junit:junit:4.13.2")
    constraints {
        implementation("org.bouncycastle:bcprov-jdk15on:1.76") { because("downgraded from 1.79 to resolve compatibility issues") }
        implementation("org.bouncycastle:bcprov-jdk18on:1.78.1") { because("avoids Gradle/CI classpath instrumentation failures on multi-release Java 21 classes") }
    }
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
}
