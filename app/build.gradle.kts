plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "org.example.otppager"
    compileSdk = 35
    defaultConfig {
        applicationId = "org.example.otppager"
        minSdk = 26
        targetSdk = 35
        versionCode = 16
        versionName = "0.16.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val upgradeVerification = providers.gradleProperty("upgradeVerification").isPresent
    if (upgradeVerification) {
        testBuildType = "release"
        defaultConfig.versionCode = providers.gradleProperty("verificationVersionCode").get().toInt()
    }
    val signingStore = providers.environmentVariable("OTP_SIGNING_STORE_FILE").orNull
    val signingPassword = providers.environmentVariable("OTP_SIGNING_STORE_PASSWORD").orNull
    val requireSigning = providers.gradleProperty("requireReleaseSigning").isPresent || upgradeVerification
    if (requireSigning) {
        require(!signingStore.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
            "Release signing is required. Configure the owner-controlled key; debug fallback is forbidden."
        }
    }
    if (!signingStore.isNullOrBlank() && !signingPassword.isNullOrBlank()) {
        val ownerRelease = signingConfigs.create("ownerRelease") {
            storeFile = file(signingStore)
            storePassword = signingPassword
            keyAlias = "otp-pager"
            keyPassword = signingPassword
            storeType = "PKCS12"
            enableV1Signing = true
            enableV2Signing = true
        }
        buildTypes.getByName("release").signingConfig = ownerRelease
    }
    buildTypes.getByName("release").isDebuggable = false
    sourceSets["test"].resources.srcDir("../test-vectors")
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}


dependencies {
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    // The scanner's published metadata does not reliably package these runtime helpers.
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.fragment:fragment:1.6.2")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
