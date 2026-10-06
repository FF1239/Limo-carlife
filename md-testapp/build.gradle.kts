plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.diplay.carlife.md"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.diplay.carlife.md"
        minSdk = 28
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources.excludes += "META-INF/*"
    }
}

dependencies {
    implementation(project(":core-carlife"))
    implementation(libs.androidx.core.ktx)
}
