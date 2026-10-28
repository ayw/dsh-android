plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ai.deepseek.dsh.android.core.data"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    testOptions { unitTests { isIncludeAndroidResources = true } }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":core:protocol"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)
    implementation(libs.work.runtime)
    implementation(libs.paging.runtime)
    implementation(libs.core.ktx)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.work.runtime)
    testImplementation(libs.okhttp.mockwebserver)
}
