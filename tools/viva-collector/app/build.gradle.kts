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
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
