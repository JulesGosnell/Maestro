import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

plugins {
    id("maven-publish")
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.mavenPublish)
}

dependencies {
    testImplementation(libs.jackson.core.databind)
    testImplementation(libs.junit.jupiter.api)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.named("compileKotlin", KotlinCompilationTask::class.java) {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjdk-release=17")
    }
}

mavenPublishing {
    publishToMavenCentral(true)
    signAllPublications()
}

tasks.named<Test>("test") {
    useJUnitPlatform()
    // smithr#1184: the golden vectors the iOS port is held to; -PnearSideGoldenWrite=true regenerates them
    systemProperty("nearSide.golden.file", file("src/test/resources/near-side-golden.json").absolutePath)
    systemProperty("nearSide.golden.write", project.findProperty("nearSideGoldenWrite") ?: "false")
}
