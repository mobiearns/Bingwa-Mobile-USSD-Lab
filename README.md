# Bingwa Mobile

## Requirements

- JDK 17
- Android Gradle Plugin 8.10.1
- Gradle Wrapper 8.12
- Android SDK Platform 36 (Build Tools 35.0.0)

## Build and test

    ./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug

The debug APK is generated at:

`app/build/outputs/apk/debug/app-debug.apk`
