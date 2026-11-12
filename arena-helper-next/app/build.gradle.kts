plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
 namespace = "ru.arenahelper"
 compileSdk = 35
 buildFeatures { buildConfig = true }
 defaultConfig {
  applicationId = "ru.arenahelper"
  minSdk = 30
  targetSdk = 35
  versionCode = 29
  versionName = "1.3.1"
  val licenseUrl = providers.gradleProperty("LICENSE_URL").orElse("").get()
  require(licenseUrl.isEmpty() || Regex("https://[A-Za-z0-9.-]+(?::[0-9]+)?/v1/activate").matches(licenseUrl)) { "LICENSE_URL must be an HTTPS activation URL" }
  buildConfigField("String", "LICENSE_URL", "\"$licenseUrl\"")
  testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
 }
 signingConfigs {
  create("commercial") {
   val path = System.getenv("ARENA_KEYSTORE")
   if (!path.isNullOrBlank()) {
    storeFile = file(path)
    storePassword = System.getenv("ARENA_STORE_PASSWORD")
    keyAlias = System.getenv("ARENA_KEY_ALIAS")
    keyPassword = System.getenv("ARENA_KEY_PASSWORD")
   }
  }
 }
 buildTypes { getByName("release") { signingConfig = signingConfigs.getByName("commercial") } }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 androidTestImplementation("androidx.test:runner:1.6.2")
 androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
 androidTestImplementation("androidx.test.ext:junit:1.2.1")
 testImplementation("junit:junit:4.13.2")
}

tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
 doFirst {
 require(!System.getenv("ARENA_KEYSTORE").isNullOrBlank()) { "Configure permanent release signing key" }
 require(providers.gradleProperty("LICENSE_URL").orNull?.startsWith("https://") == true) { "Configure LICENSE_URL before a commercial release" } }
}
