plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.pcoverlaycontrol"

    // ปรับเป็น 37 เพื่อรองรับ androidx.core:1.19.0+
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.pcoverlaycontrol"

        // minSdk 27 = Android 8.1 (ตรงตามระบบหุ่นยนต์ AutoXing)
        minSdk = 27

        // targetSdk 34 เพื่อรองรับ Overlay WindowManager API
        targetSdk = 34

        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)

    // HTTP + WebSocket สำหรับคุยกับ Raspberry Pi 5
    implementation(libs.okhttp)
}