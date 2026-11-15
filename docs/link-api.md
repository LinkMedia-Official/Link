# Link API 사용하기 (다른 플러그인 프로젝트)

`link-api`는 `dev/26.3`에 커밋이 푸시될 때마다 GitHub Packages(Maven)에 `io.linkmc:link-api:0.2.0-SNAPSHOT`으로 올라갑니다.
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
    compileOnly("io.linkmc:link-api:0.2.0-SNAPSHOT")
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
  <version>0.2.0-SNAPSHOT</version>
  <scope>provided</scope>
</dependency>
```

## 코드에서

```java
// 월드는 일반 바닐라 월드입니다. 섬 같은 월드는 Bukkit API로 만들거나 불러옵니다(서버 스레드에서 호출).
org.bukkit.World island = new org.bukkit.WorldCreator("island-" + uuid).createWorld();
```

`WorldCreator`로 만든 월드는 Paper 규칙에 따라 바닐라 파일로 저장되며, 이미 있으면 그대로 불러옵니다. 시작 시 자동으로 열 월드는 `link.yml`의 `worlds.autoload`에 등록할 수 있습니다.

## 0.2.0 API 변경

`0.2.0-SNAPSHOT`은 호환되지 않는 변경입니다. `worlds()`, `editors()`, `warpGroups()`와 워프·채널 등록 메서드가 제거됐고, `requestTravel`은 명시적 목적지가 필요합니다. 기존 `api.worlds().create(...)` 호출은 `WorldCreator`로 바꾸세요. 자세한 목록은 [플러그인 API](plugin-api.md#020-변경-사항)를 참고하세요.
