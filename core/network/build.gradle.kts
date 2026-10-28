plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "ai.deepseek.dsh.android.core.network"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    api(project(":core:protocol"))
    implementation(libs.okhttp)
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.turbine)
}
