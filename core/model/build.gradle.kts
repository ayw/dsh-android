plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.kotlin.test.junit)
}

tasks.test {
    useJUnit()
    testLogging { events("failed") }
}
