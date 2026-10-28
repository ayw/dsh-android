plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "ai.deepseek.dsh.android.core.designsystem"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
    buildFeatures { compose = true }
}

kotlin { jvmToolchain(17) }

dependencies {
    api(platform(libs.compose.bom))
    api(libs.compose.ui)
    api(libs.compose.foundation)
    api(libs.compose.material3)
    api(libs.compose.runtime)
    api(libs.compose.ui.tooling.preview)
    api(project(":core:model"))
    implementation(libs.core.ktx)
    debugApi(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}
