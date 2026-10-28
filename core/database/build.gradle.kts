plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "ai.deepseek.dsh.android.core.database"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}

kotlin { jvmToolchain(17) }

ksp { arg("room.schemaLocation", "$projectDir/schemas") }

dependencies {
    api(project(":core:model"))
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.coroutines.core)
    ksp(libs.room.compiler)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.robolectric)
}
