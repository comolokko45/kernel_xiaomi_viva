plugins {
    id("com.android.application")
}

android {
    namespace = "com.vivalinux.collector"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vivalinux.collector"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
