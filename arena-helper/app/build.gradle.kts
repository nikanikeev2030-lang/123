plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "ru.arenahelper"
 compileSdk = 35
 defaultConfig {
  applicationId = "ru.arenahelper"
  minSdk = 30
  targetSdk = 35
  versionCode = 2
  versionName = "1.0.1"
  ndk { abiFilters += "arm64-v8a" }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
 testImplementation("junit:junit:4.13.2")
}
