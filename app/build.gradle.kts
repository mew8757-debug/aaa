plugins {
    id("com.android.application")
}

android {
    namespace = "kr.co.seongsamgukji.launcher"
    compileSdk = 35

    defaultConfig {
        applicationId = "kr.co.seongsamgukji.launcher"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
}
