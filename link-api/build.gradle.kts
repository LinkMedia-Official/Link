plugins {
    `java-library`
    `maven-publish`
}
group = "io.linkmc"
// CI 는 LINK_API_VERSION 으로 버전을 정할 수 있다. 기본값은 커밋마다 덮어쓰는 SNAPSHOT.
version = providers.environmentVariable("LINK_API_VERSION").getOrElse("0.1.0-SNAPSHOT")
java { withSourcesJar(); withJavadocJar() }
publishing {
    publications.create<MavenPublication>("linkApi") {
        from(components["java"])
        artifactId = "link-api"
        pom {
            name = "Link API"
            description = "Plugin API for the Link transactional backend (ASP/Paper 26.3)"
            url = "https://github.com/LinkMedia-Official/Link"
            scm { url = "https://github.com/LinkMedia-Official/Link" }
        }
    }
    repositories {
        // GitHub Packages (Maven). CI 에서 GITHUB_ACTOR / GITHUB_TOKEN 으로 인증한다.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/LinkMedia-Official/Link")
            credentials {
                username = providers.environmentVariable("GITHUB_ACTOR").orNull
                password = providers.environmentVariable("GITHUB_TOKEN").orNull
            }
        }
    }
}
tasks.javadoc {
    // 공개 API 문서의 누락 태그 경고는 빌드를 막지 않는다
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}
