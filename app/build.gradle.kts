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

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.navigation.fragment.ktx)
    implementation(libs.androidx.navigation.ui.ktx)
    implementation(libs.material)

    // 📌 เพิ่ม OkHttp สำหรับเชื่อมต่อ WebSocket สื่อสารกับคอมพิวเตอร์ (YOLOv8)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}