plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "jp.androplus.fakecheck"
    compileSdk = 35

    defaultConfig {
        applicationId = "jp.androplus.fakecheck"
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    // CI のビルドごとに署名が変わると上書きインストールできないため、固定鍵があればそれで署名する。
    // 鍵は GitHub Secrets から渡す (README 参照)。無い場合はビルド環境の debug 鍵になる。
    val keystorePath = System.getenv("SIGNING_KEYSTORE")
    val fixedSigning = if (keystorePath != null && file(keystorePath).exists()) {
        signingConfigs.create("fixed") {
            storeFile = file(keystorePath)
            storePassword = System.getenv("SIGNING_STORE_PASSWORD")
            keyAlias = System.getenv("SIGNING_KEY_ALIAS")
            keyPassword = System.getenv("SIGNING_KEY_PASSWORD") ?: System.getenv("SIGNING_STORE_PASSWORD")
        }
    } else {
        signingConfigs.getByName("debug")
    }

    buildTypes {
        debug {
            signingConfig = fixedSigning
        }
        release {
            isMinifyEnabled = false
            signingConfig = fixedSigning
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
