import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.register

plugins {
    java
    idea // Gradle's Scala plugin wires IDEA Scala support through the root ideaProject task (aspaper-server applies scala + idea)
    id("io.papermc.paperweight.patcher")
}

paperweight {
    upstreams.paper {
        ref = providers.gradleProperty("paperRef")

        // Setup file patches for build scripts
        patchFile {
            path = "paper-api/build.gradle.kts"
            outputFile = file("aspaper-api/build.gradle.kts")
            patchFile = file("aspaper-api/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-server/build.gradle.kts"
            outputFile = file("aspaper-server/build.gradle.kts")
            patchFile = file("aspaper-server/build.gradle.kts.patch")
        }
        patchFile {
            path = "paper-checkstyle/build.gradle.kts"
            outputFile = file("aspaper-checkstyle/build.gradle.kts")
            patchFile = file("aspaper-checkstyle/build.gradle.kts.patch")
        }
        patchDir("paperCheckstyle") {
            upstreamPath = "paper-checkstyle"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("aspaper-checkstyle/paper-patches")
            outputDir = file("paper-checkstyle")
        }
        patchDir("paperApi") {
            upstreamPath = "paper-api"
            excludes = setOf("build.gradle.kts")
            patchesDir = file("aspaper-api/paper-patches")
            outputDir = file("paper-api")
        }
        patchDir("paperCheckstyleConfig") {
            upstreamPath = ".checkstyle"
            patchesDir = file("aspaper-checkstyle/config-patches")
            outputDir = file(".checkstyle")
        }
    }
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "maven-publish")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(JAVA_VERSION)
        }
    }

    repositories {
        mavenCentral()
        maven(PAPER_MAVEN_PUBLIC_URL)
    }

    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
    tasks.withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
        options.release = JAVA_VERSION
        options.isFork = true
    }
    tasks.withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }
    tasks.withType<ProcessResources> {
        filteringCharset = Charsets.UTF_8.name()
    }
    tasks.withType<Test> {
        testLogging {
            showStackTraces = true
            exceptionFormat = TestExceptionFormat.FULL
            events(TestLogEvent.STANDARD_OUT)
        }
    }
}

tasks.register<DefaultTask>("createMojmapPaperclipJar") {
    description = "Fallback to the old mojmap naming for our CI... Yes this was the easiest fix I could come up with."
    dependsOn(":aspaper-server:createPaperclipJar")
}

tasks.register<Copy>("linkDist") {
    description = "Assemble experimental Link server, Velocity module and plugin API."
    dependsOn(":aspaper-server:createPaperclipJar", ":link-velocity:shadowJar", ":link-api:jar")
    into(layout.buildDirectory.dir("dist"))
    from(project(":aspaper-server").layout.buildDirectory.dir("libs")) {
        include("aspaper-paperclip-*.jar")
        rename { "Link-26.3-dev.jar" }
    }
    from(project(":link-velocity").layout.buildDirectory.dir("libs")) {
        include("link-velocity-*.jar")
        exclude("*-plain.jar")
        rename { "Link-Velocity-dev.jar" }
    }
    from(project(":link-api").layout.buildDirectory.dir("libs")) {
        include("link-api-*.jar")
        exclude("*-sources.jar", "*-javadoc.jar")
        rename { "Link-API-dev.jar" }
    }
}
