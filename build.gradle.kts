plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("com.google.devtools.ksp") version "2.3.13" apply false
}

allprojects {
    group = "dev.aetherx86.boron"
    version = "0.1.0"
    repositories { mavenCentral() }
}
