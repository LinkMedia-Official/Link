plugins {
    java
    scala
    id("com.gradleup.shadow")
}
group = "io.linkmc"
version = "0.1.0-SNAPSHOT"
repositories { maven("https://repo.papermc.io/repository/maven-public/") }
dependencies {
    implementation(project(":link-core")) {
        exclude(group = "io.netty")
    }
    compileOnly("com.velocitypowered:velocity-api:4.3.1")
    // velocity-plugin.json is a static resource: Velocity's annotation processor does not run on Scala sources.
    implementation("org.yaml:snakeyaml:2.4")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
tasks.shadowJar {
    archiveBaseName.set("link-velocity")
    archiveClassifier.set("")
    mergeServiceFiles()
    relocate("io.lettuce", "io.linkmc.velocity.internal.lettuce")
    relocate("reactor", "io.linkmc.velocity.internal.reactor")
    relocate("org.reactivestreams", "io.linkmc.velocity.internal.reactivestreams")
    relocate("com.zaxxer.hikari", "io.linkmc.velocity.internal.hikari")
    relocate("org.yaml.snakeyaml", "io.linkmc.velocity.internal.snakeyaml")
    exclude("META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
}
tasks.withType<ScalaCompile>().configureEach {
    scalaCompileOptions.additionalParameters = listOf("-release", "25", "-deprecation", "-feature")
}
// Velocity gives each plugin its own class loader, so the bundled Scala 3.10.0 runtime cannot clash with other plugins.
tasks.build { dependsOn(tasks.shadowJar) }
tasks.jar { archiveClassifier.set("plain") }
tasks.test { useJUnitPlatform() }
