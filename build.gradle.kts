plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
val serverUrl = (project.findProperty("SERVER_URL") as String?) ?: "https://support.example.com"
android {
    namespace = "com.livehelp"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.livehelp"
        minSdk = 29; targetSdk = 34; versionCode = 1; versionName = "1.0"
        buildConfigField("String", "SERVER_URL", "\"$serverUrl\"")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.github.webrtc-sdk:android:125.6422.07")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
