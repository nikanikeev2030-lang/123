plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "ru.arenahelper"
 compileSdk = 35
 defaultConfig {
  applicationId = "ru.arenahelper"
  minSdk = 30
  targetSdk = 35
  versionCode = 17
  versionName = "1.2.0"
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 testImplementation("junit:junit:4.13.2")
}
