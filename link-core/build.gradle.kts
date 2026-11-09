plugins {
    `java-library`
    scala
}
// Same Scala as LinkCore (dev.seraphicness:LinkCore, project/Dependencies.scala). The server ships this runtime and
// Paper's plugin class loader resolves server classes first, so LinkCore's bundled copy never conflicts.
val scalaVersion = "3.10.0"
dependencies {
    api(project(":link-api"))
    api("org.scala-lang:scala3-library_3:$scalaVersion")
    runtimeOnly("org.postgresql:postgresql:42.7.5")
    runtimeOnly("org.mariadb.jdbc:mariadb-java-client:3.5.3")
    implementation(libs.lettuce)
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("software.amazon.awssdk:auth:2.31.54")
    implementation("com.google.code.gson:gson:2.13.2")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
// Mixed Java/Scala: scalac sees both, so Java and Scala classes can reference each other while files migrate.
sourceSets.main {
    java.setSrcDirs(emptyList<String>())
    scala.setSrcDirs(listOf("src/main/java", "src/main/scala"))
}
tasks.withType<ScalaCompile>().configureEach {
    scalaCompileOptions.additionalParameters = listOf("-release", "25", "-deprecation", "-feature")
}
tasks.test { useJUnitPlatform() }
tasks.register<JavaExec>("publishTemplate") {
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("io.linkmc.core.TemplateAdmin")
    if (project.hasProperty("templateFile")) {
        args(providers.gradleProperty("templateName").get(), providers.gradleProperty("templateVersion").get(), providers.gradleProperty("templateFile").get())
    }
}
