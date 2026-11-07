plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aventum.scanner"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aventum.scanner"
        minSdk = 26
        targetSdk = 34
        versionCode = 10
        versionName = "1.9"

        // Téléphones 64 bits uniquement (quasiment tous depuis 2017-2018) : limite la taille
        // ajoutée par le modèle d'OCR du téléphone.
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // Clé de signature fixe, fournie par les secrets GitHub (jamais stockée dans le dépôt) :
    // indispensable pour qu'une nouvelle version s'installe par-dessus l'ancienne.
    val releaseKeystore = System.getenv("SIGNING_KEYSTORE_FILE")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("SIGNING_PASSWORD")
                keyAlias = "aventum"
                keyPassword = System.getenv("SIGNING_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Retire le code et les ressources inutilisés pour alléger l'APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Sans clé fournie, signé avec la clé de debug pour rester installable.
            signingConfig = signingConfigs.getByName(if (releaseKeystore != null) "release" else "debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    // Bibliothèques natives compressées dans l'APK : téléchargement plus léger.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    // Scanner de documents Google (détection des bords, recadrage, filtres).
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0-beta1")
    // OCR du texte imprimé sur le téléphone, modèle intégré à l'APK (hors ligne, sans Play Store).
    implementation("com.google.mlkit:text-recognition:16.0.1")
}
