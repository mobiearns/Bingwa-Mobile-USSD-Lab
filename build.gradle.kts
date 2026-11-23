plugins {
    // AGP 8.10.1 supports compileSdk/targetSdk 36 and works with Gradle 8.12.
    id("com.android.application") version "8.10.1" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.20" apply false
}