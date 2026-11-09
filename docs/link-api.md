# Link API 사용하기 (다른 플러그인 프로젝트)

`link-api`는 `dev/26.3`에 커밋이 푸시될 때마다 GitHub Packages(Maven)에 `io.linkmc:link-api:0.1.0-SNAPSHOT`으로 올라갑니다.
API 구현은 Link 서버 안에 들어 있습니다. 그래서 플러그인에는 **compileOnly**로만 넣고, jar 안에 포함(shade)하지 마세요.

## 인증

GitHub Packages의 Maven 저장소는 공개 패키지라도 받을 때 인증이 필요합니다.
GitHub에서 `read:packages` 권한만 있는 토큰(PAT)을 만든 뒤, 아래 두 곳 중 하나에 넣어 두세요.

- `~/.gradle/gradle.properties`
  ```properties
  gpr.user=<GitHub 아이디>
  gpr.key=<토큰>
  ```
- 환경변수 `GITHUB_ACTOR` / `GITHUB_TOKEN`
  - GitHub Actions에서는 자동으로 들어 있습니다.
  - 다른 저장소의 워크플로에서 이 비공개 패키지를 받으려면, 그 저장소에 패키지 읽기 권한을 따로 줘야 합니다(패키지 설정의 Manage Actions access).

## Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
    maven("https://maven.pkg.github.com/LinkMedia-Official/Link") {
        credentials {
            username = providers.gradleProperty("gpr.user").orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
            password = providers.gradleProperty("gpr.key").orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
        }
    }
}

dependencies {
    compileOnly("io.linkmc:link-api:0.1.0-SNAPSHOT")
}
```

SNAPSHOT은 커밋할 때마다 덮어써집니다. 최신 버전을 바로 받으려면 `--refresh-dependencies`를 붙여 빌드하세요.

## Maven

`~/.m2/settings.xml`에 서버 `github`(아이디/토큰)를 등록한 다음:

```xml
<repositories>
  <repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/LinkMedia-Official/Link</url>
    <snapshots><enabled>true</enabled></snapshots>
  </repository>
</repositories>
<dependency>
  <groupId>io.linkmc</groupId>
  <artifactId>link-api</artifactId>
  <version>0.1.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

## 코드에서

```java
LinkApi api = io.linkmc.api.Link.api();
api.worlds().create("island-" + uuid, "island", 1, true); // 섬처럼 저장해야 하는 월드는 persistent=true
```

`persistent=false`(임시)로 만든 월드는 체크포인트에서 저장하지 않습니다. 크래시가 나면 템플릿 상태로 돌아갑니다.
