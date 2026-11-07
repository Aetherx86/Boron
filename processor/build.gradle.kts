import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    `maven-publish`
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    withSourcesJar()
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_21) }
}

dependencies {
    implementation("com.google.devtools.ksp:symbol-processing-api:2.3.13")
    implementation("com.squareup:kotlinpoet:2.4.0")
    implementation("com.squareup:kotlinpoet-ksp:2.4.0")
    testImplementation(kotlin("test"))
    testImplementation(project(":annotations"))
    testImplementation("dev.zacsweers.kctfork:ksp:0.14.0")
}

tasks.test {
    useJUnitPlatform()
    maxHeapSize = "2g"
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "boron-processor"
            from(components["java"])
        }
    }
    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/Aetherx86/Boron")
            credentials {
                username = System.getenv("GITHUB_ACTOR")
                password = System.getenv("GITHUB_TOKEN")
            }
        }
    }
}
