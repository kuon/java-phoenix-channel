import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.dokka.gradle.DokkaTask

val projectGroup = "ch.kuon.phoenix"
// Update elixir project (mock_servver:mix.exs) version too to keep them in sync
// Also update version in README.md
val projectVersion = "0.1.7"
val projectName = "channel"

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm") version "1.3.61"

    // Documentation generation
    id("org.jetbrains.dokka") version "0.10.0"

    // Apply the java-library plugin for API and implementation separation.
    `java-library`

    signing

    // Create maven artefacts
    `maven-publish`
}

repositories {
    // Use jcenter for resolving dependencies.
    // You can declare any Maven/Ivy/file repository here.
    jcenter()
}

dependencies {
    // Align versions of all Kotlin components
    implementation(platform("org.jetbrains.kotlin:kotlin-bom"))

    // Use the Kotlin JDK 8 standard library.
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

    // Web Socket Client
    implementation("com.neovisionaries:nv-websocket-client:2.9")

    // JSON handling
    implementation("com.github.openjson:openjson:1.0.11")

    // Use the Kotlin test library.
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    // Use the Kotlin JUnit integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")

    // Concurrent Unit test
    testImplementation("net.jodah:concurrentunit:0.4.6")
}


publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
            groupId = projectGroup
            artifactId = projectName
            version = projectVersion
        }
    }
}

signing {
    useGpgCmd()
    sign(publishing.publications["mavenJava"])
}


tasks {
    compileKotlin {
        kotlinOptions.allWarningsAsErrors = true
    }
    jar {
        archiveBaseName.set(projectName)
        archiveVersion.set(projectVersion)
    }
    withType<DokkaTask> {
        outputFormat = "html"
        outputDirectory = "build/docs/"
        configuration {
            moduleName = "phoenix-channel"
            includes = listOf("README.md")
            samples = listOf("src/test/kotlin/ch/kuon/phoenix/LibraryTest.kt")
        }
    }
    withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
    }
    withType(Test::class.java) {
        testLogging.showStandardStreams = true
    }
    withType<GenerateMavenPom> {
        destination = file("$buildDir/libs/${projectName}.pom")
    }
}
