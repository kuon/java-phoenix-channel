import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import com.jfrog.bintray.gradle.BintrayExtension
import com.jfrog.bintray.gradle.BintrayPlugin
import org.jetbrains.dokka.gradle.DokkaTask

val projectGroup = "ch.kuon.phoenix"
val projectVersion = "0.1.0"
val projectName = "channel"

plugins {
    // Apply the Kotlin JVM plugin to add support for Kotlin.
    id("org.jetbrains.kotlin.jvm") version "1.3.61"

    // Documentation generation
    id("org.jetbrains.dokka") version "0.10.0"

    // Apply the java-library plugin for API and implementation separation.
    `java-library`

    // Create maven artefacts
    `maven-publish`

    // Bintray for publication
    id("com.jfrog.bintray") version "1.8.4"
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

    // Http utils to manipulate URL properly
    implementation("org.apache.httpcomponents:httpclient:4.5.11")

    // JSON handling
    implementation("org.json:json:20190722")

    // Use the Kotlin test library.
    testImplementation("org.jetbrains.kotlin:kotlin-test")

    // Use the Kotlin JUnit integration.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit")

    // Concurrent Unit test
    testImplementation("net.jodah:concurrentunit:0.4.6")
}


publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = projectGroup
            artifactId = projectName
            version = projectVersion
        }
    }
}


bintray {
    user = System.getenv("BINTRAY_USERNAME")
    key = System.getenv("BINTRAY_API_KEY")
    publish = true
    setPublications("maven")
    pkg(delegateClosureOf<BintrayExtension.PackageConfig> {
        repo = "java"
        name = "phoenix-channel"
        userOrg = "kuon"
        websiteUrl = "https://github.com/kuon/java-phoenix-channel"
        vcsUrl = "https://github.com/kuon/java-phoenix-channel.git"
        githubRepo = "kuon/java-phoenix-channel"
        description = "Phoenix Channel Java Client written in Kotlin"
        setLabels("kotlin")
        setLicenses("MIT", "Apache-2.0")
        desc = description
        publicDownloadNumbers = true
    })
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
            moduleName ="phoenix-channel"
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
