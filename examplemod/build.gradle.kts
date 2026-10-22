plugins {
    id("java-library")
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
}

dependencies {
    implementation(project(":modkit"))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// Plain jar, no dependencies bundled: the game already ships the modkit classes.
tasks.jar {
    archiveFileName.set("examplemod.jar")
}
