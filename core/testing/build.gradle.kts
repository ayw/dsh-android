plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.test { useJUnit() }

dependencies {
    api(project(":core:model"))
    api(project(":core:domain"))
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.test)
    implementation(libs.junit)
    implementation(libs.kotlin.test.junit)
    implementation(libs.turbine)
}
