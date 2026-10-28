plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api(project(":core:model"))
    implementation(libs.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(project(":core:testing"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.serialization.json)
    testImplementation(libs.coroutines.test)
}

tasks.test {
    useJUnit()
    testLogging { events("failed") }
}
