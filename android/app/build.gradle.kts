plugins {
    id("com.android.application")
    id("kotlin-android")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

android {
    namespace = "com.example.ar_jewelry_app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = JavaVersion.VERSION_17.toString()
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.example.ar_jewelry_app"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = flutter.minSdkVersion
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

flutter {
    source = "../.."
}

dependencies {
    // CameraX pour la vue AR native (étape B.1). Rendu caméra directement en
    // Kotlin dans une PlatformView → même surface native que Filament (à venir),
    // ce qui évite le conflit de surfaces caméra↔3D côté Flutter.
    val cameraxVersion = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    // ProcessCameraProvider.getInstance() renvoie un ListenableFuture (Guava)
    // que CameraX n'expose qu'en interne → requis explicitement pour compiler.
    implementation("com.google.guava:guava:33.3.1-android")

    // Filament natif (étape B.2) : moteur 3D + chargement glTF + helpers
    // (ModelViewer, KTX1Loader). Rendu dans la MÊME vue native que la caméra.
    val filamentVersion = "1.73.0"
    implementation("com.google.android.filament:filament-android:$filamentVersion")
    implementation("com.google.android.filament:gltfio-android:$filamentVersion")
    implementation("com.google.android.filament:filament-utils-android:$filamentVersion")

    // MediaPipe Hand Landmarker (étape B.3) — même version que le plugin
    // hand_landmarker (le modèle hand_landmarker.task est déjà fusionné dans
    // l'APK par ce plugin). Déclaré ici pour l'accès au compile classpath.
    implementation("com.google.mediapipe:tasks-vision:0.10.29")
}
