plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "ru.arenahelper"
 compileSdk = 35
 defaultConfig {
  applicationId = "ru.arenahelper"
  minSdk = 30
  targetSdk = 35
  versionCode = 16
  versionName = "1.1.5"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  ndk { abiFilters += (project.findProperty("arenaTestAbi")?.toString() ?: "arm64-v8a") }
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation("cz.adaptech.tesseract4android:tesseract4android:4.9.0")
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 testImplementation("junit:junit:4.13.2")
}
