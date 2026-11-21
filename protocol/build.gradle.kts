plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.bcprov)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}

tasks.test {
    systemProperty("otprelay.vectors", rootProject.file("vectors/test-vectors.json").absolutePath)
    inputs.file(rootProject.file("vectors/test-vectors.json"))
}
