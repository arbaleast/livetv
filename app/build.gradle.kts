plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.livetv.android"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.livetv.android"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        // Debug 专用入口（CCTV WebView 原型）需要 BuildConfig.DEBUG 门控
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.fragment:fragment-ktx:1.8.2")
    implementation("androidx.leanback:leanback:1.0.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-effect:1.4.1")

    // 纯 JVM 单元测试（app/src/test）；不引入模拟器/仪器测试依赖
    testImplementation("junit:junit:4.13.2")
    // Android 的 android.jar 里 org.json.* 只是抛 "not mocked" 的 Stub，
    // 纯 JVM 单测必须引入真实实现，否则 ChannelJsonParser 无法被测试。
    testImplementation("org.json:json:20231013")
}
