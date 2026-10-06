import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val javacvVersion = "1.5.12"
val ffmpegVersion = "7.1.1-1.5.12"
val javacppVersion = "1.5.12"

dependencies {
    implementation(project(":core-carlife"))
    // javacv classes only (FFmpegFrameGrabber, Java2DFrameConverter) without the
    // all-platform native transitives; the Windows x64 natives are pinned below.
    implementation("org.bytedeco:javacv:$javacvVersion") { isTransitive = false }
    implementation("org.bytedeco:javacpp:$javacppVersion")
    implementation("org.bytedeco:javacpp:$javacppVersion:windows-x86_64")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion")
    implementation("org.bytedeco:ffmpeg:$ffmpegVersion:windows-x86_64")

    testImplementation(libs.junit)
}

application {
    mainClass.set("com.diplay.carlife.desktop.MainKt")
    applicationDefaultJvmArgs = listOf("-Xmx512m", "--enable-native-access=ALL-UNNAMED")
}
