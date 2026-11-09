# 설치와 실행

[README](../README.md) · [설계](link-implementation.md) · [API](plugin-api.md) · [검증](validation.md)

현재는 격리된 개발·검증 환경용입니다. 운영 적합성 검증은 실패 상태입니다.

## 빌드

Java 25와 Git이 필요합니다. SQL 저장소는 PostgreSQL 17 또는 MariaDB 11.4를 사용할 수 있습니다. Redis 7은 선택 사항이며 프록시는 Velocity 4.3.1을 사용합니다.

```powershell
.\gradlew.bat applyAllPatches
.\gradlew.bat linkDist
```

Linux/macOS에서는 `./gradlew`를 사용합니다. JAR은 `build/dist/`에 생성됩니다. 패치 작업과 Windows 긴 경로 설정은 [개발 가이드](../CONTRIBUTING.md)를 참고하세요.

## 실행 모드

| `-Dlink.mode=` | 동작 |
| --- | --- |
| `disabled` | 기본값. Upstream 동작 유지 |
| `tools` | 템플릿 도구 사용. Upstream 플레이어 파일 저장 유지 |
| `journal` | 실험적 네이티브 SQL 저장과 입장·이동 처리 |
| `strict` | 검증 미완료로 시작 거부 |

## 백엔드 `link.yml`

첫 실행 시 작업 디렉터리(보통 서버 JAR을 실행한 폴더)에 `link.yml`을 자동 생성합니다. EULA 확인과 월드 초기화 전에 생성하며 기존 설정은 덮어쓰지 않습니다. 기존 파일에 `retention` 항목이 없으면 기본 24시간 이벤트 보관 설정을 덧붙입니다. 수정 후 서버를 재시작하세요. [보관·정리 정책과 DB 진단](storage-retention.md)을 확인하세요.

```yaml
mode: disabled
node:
  id: node-a
  capacity: 150
database:
  jdbc-url: 'jdbc:postgresql://127.0.0.1:5432/link'
  username: 'link'
  password: ''
storage:
  worlds: database
  slimevault:
    endpoint: 'http://127.0.0.1:3900'
    region: slimevault
    bucket: worlds
    prefix: link
    access-key: ''
    secret-key: ''
redis:
  enabled: false
  uri: 'redis://127.0.0.1:6379'
spawn:
  template: lobby
  version: 1
  capacity: 50
  max-worlds: 3
```

PostgreSQL 또는 MariaDB에 DB·계정을 먼저 생성하고 `database` 항목을 입력하세요. MariaDB의 URL 예시는 `jdbc:mariadb://127.0.0.1:3306/link`입니다. MariaDB는 InnoDB를 사용하며 `innodb_flush_log_at_trx_commit=1`, binlog 활성 시 `sync_binlog=1`이 필요합니다. Velocity 설정까지 준비한 뒤 `mode: journal`로 변경합니다. 비밀번호는 따옴표로 감싸고 작은따옴표 자체는 YAML 작은따옴표 문자열 안에서 두 번 적습니다. Redis 사용 시 `redis.enabled: true`로 바꿉니다.

YAML을 설정하면 `java -Xms512M -Xmx2G -jar Link-26.3-dev.jar --nogui`로 실행할 수 있습니다. 접속 정보가 들어간 `link.yml`은 커밋하지 않습니다.

### SlimeVault에 월드 저장

`storage.worlds: slimevault`로 설정하고 [SlimeVault 0.2](https://github.com/seraphicness/SlimeVault)의 endpoint·bucket·접근 키를 입력하세요. 기존 `link.yml`에는 위 `storage` 블록을 직접 추가합니다. 버킷은 미리 생성하고 해당 키에 읽기·쓰기 권한을 부여해야 합니다. `LINK_S3_ACCESS_KEY`와 `LINK_S3_SECRET_KEY` 환경 변수로 YAML의 키를 덮어쓸 수 있습니다.

월드와 템플릿의 SRF 바이트는 SlimeVault에 저장하고, 플레이어 데이터·SavedData·세션·정원·월드의 정확한 객체 리비전 참조는 SQL에 저장합니다. 따라서 S3만으로 실행할 수는 없습니다. 모든 백엔드는 같은 SQL DB와 SlimeVault endpoint·bucket·prefix를 사용합니다. Velocity에는 S3 자격 증명이 필요하지 않습니다.

업로드는 SigV4/path-style 요청으로 writer 임대를 얻은 뒤 새 `.slime` 키에 조건부 저장합니다. 성공한 VersionId와 SHA-256을 SQL에 기록하고 복구 시 그 리비전을 읽어 검증합니다. S3 오류 시 로컬 저장으로 우회하지 않습니다. SQL 실패 또는 응답 유실로 참조되지 않은 객체가 남을 수 있으며 자동 삭제하지 않습니다. SQL 이력이 참조하는 객체와 리비전을 SlimeVault에서 정리하면 복구할 수 없으므로 SQL과 S3를 함께 백업해야 합니다. S3 업로드를 기다리는 현재 구현은 틱 지연을 늘릴 수 있으며 성능 개선을 보장하지 않습니다.

기존 SQL 바이너리는 S3 모드에서도 읽을 수 있지만 DB 종류 변경과 기존 데이터의 일괄 이전은 자동으로 수행하지 않습니다. S3 참조가 생긴 뒤 저장소를 끄거나 bucket/prefix를 바꾸면 해당 데이터를 복구할 수 없습니다.

## 환경 변수와 기존 옵션

백엔드는 아래 환경 변수를 YAML보다 우선 적용합니다. 모드·스폰·정원은 기존 `-Dlink.*` 옵션이 우선합니다. 환경 변수의 비밀 값은 생성 파일에 복사하지 않습니다. Velocity 플러그인도 전용 YAML을 읽으며 같은 DB 환경 변수가 우선합니다. **오프라인 템플릿 발행 도구는 환경 변수를 사용합니다.** 백엔드의 YAML을 자동 공유하지 않습니다. 백엔드 노드 이름은 `node.id` 또는 `LINK_NODE_ID`로 지정합니다.

| 변수 | 대상 | 의미 |
| --- | --- | --- |
| `LINK_JDBC_URL` | 백엔드·프록시 | 예: `jdbc:postgresql://127.0.0.1:5432/link` |
| `LINK_DB_USER` | 백엔드·프록시 | DB 계정 |
| `LINK_DB_PASSWORD` | 백엔드·프록시 | DB 비밀번호 |
| `LINK_NODE_ID` | 백엔드 | 실행 중인 다른 노드와 중복되지 않는 이름 |
| `LINK_REDIS_URI` | 백엔드, 선택 | 예: `redis://127.0.0.1:6379` |

백엔드의 `node.id`(또는 `LINK_NODE_ID`)는 Velocity의 서버 등록 이름과 일치해야 합니다. 모든 백엔드와 프록시는 같은 Link DB를 사용합니다. 시작 시 스키마를 초기화하므로 DB 계정에 필요한 권한이 있어야 합니다. 자격 증명은 저장소에 커밋하지 않습니다.

## 첫 템플릿

`journal` 시작 시 설정한 스폰 템플릿 버전이 없으면 `link-template-editor`라는 편집 월드를 자동 생성하고, 접속자를 이 월드로 배정합니다. 9×9 기반암 발판에서 건축을 시작할 수 있으며 편집 내용은 DB에 저장되어 재시작 후 복구됩니다. 편집 상태에서는 추가 스폰 복제를 하지 않습니다. DB 장애나 템플릿 손상은 편집 월드로 우회하지 않고 부팅 오류로 처리합니다.

기본 설정이라면 건축 후 `/link publish link-template-editor lobby 1`을 실행하고 완료 메시지를 확인한 뒤 재시작하세요. 다음 부팅부터 발행한 템플릿으로 스폰을 구성하며 편집 월드는 보존됩니다. 템플릿 이름·버전을 바꿨다면 발행 명령도 `link.yml`의 `spawn.template`·`spawn.version`과 맞추세요. 템플릿 자동 전환에는 재시작이 필요합니다.

기존 SRF `.slime` 파일이 있다면 DB 환경 변수가 설정된 셸에서 미리 발행할 수도 있습니다.

```powershell
.\gradlew.bat :link-core:publishTemplate -PtemplateName=lobby -PtemplateVersion=1 -PtemplateFile=C:/maps/lobby.slime
```

템플릿 버전은 다른 내용으로 덮어쓸 수 없습니다. 수정본은 버전을 올려 발행하세요. 서버가 실행된 뒤에는 `/link editor`와 `/link publish`로 새 템플릿을 제작할 수 있습니다. 테스트용 빈 템플릿 생성기는 `link_runtime_test` DB에서만 동작하며 일반 템플릿 준비 절차를 대신하지 않습니다.

오프라인 발행 도구에서도 S3를 쓰려면 `LINK_WORLD_STORAGE=slimevault`, `LINK_S3_ENDPOINT`, `LINK_S3_BUCKET`, `LINK_S3_ACCESS_KEY`, `LINK_S3_SECRET_KEY`를 설정합니다. 선택 값은 `LINK_S3_REGION`(기본 `slimevault`), `LINK_S3_PREFIX`(기본 `link`)입니다. SQL 접속 환경 변수도 필요합니다.

## Velocity 플러그인 `plugins/link/config.yml`

플러그인 JAR을 설치하고 Velocity를 한 번 실행하면 `plugins/link/config.yml`이 생성됩니다. 첫 실행의 비밀번호는 비어 있으므로 Link 초기화 오류와 함께 설정 안내가 표시됩니다. 파일을 수정하고 Velocity를 재시작하세요. 기존 파일은 덮어쓰지 않습니다.

```yaml
database:
  jdbc-url: 'jdbc:mariadb://127.0.0.1:3306/link'
  username: 'link'
  password: 'DB비밀번호'
```

PostgreSQL은 `jdbc:postgresql://127.0.0.1:5432/link`로 지정합니다. 버킷과 같은 DB를 사용해야 합니다. `LINK_JDBC_URL`, `LINK_DB_USER`, `LINK_DB_PASSWORD`가 설정돼 있으면 YAML보다 우선하므로 YAML 변경을 적용하려면 기존 환경 변수도 확인하세요. 프록시에는 S3 설정이 필요하지 않으며 Link 명령어는 계속 버킷에서만 처리합니다.

## Velocity 연결

1. Velocity의 `plugins/`에 `Link-Velocity-dev.jar`를 설치합니다.
2. 서버 목록에 백엔드를 등록합니다. 등록 이름은 각 백엔드의 `node.id`(또는 `LINK_NODE_ID`)와 같아야 합니다.
3. 프록시의 modern forwarding과 온라인 인증을 설정합니다.
4. 백엔드 Paper 설정에서 Velocity 지원과 프록시 `online-mode`를 활성화하고 forwarding secret을 동일하게 설정합니다. `server.properties`의 백엔드 `online-mode`와 Paper의 프록시 `online-mode`는 서로 다른 설정입니다. 프록시 인증을 사용하는 백엔드는 일반적으로 `server.properties`에서 `online-mode=false`를 사용합니다.
5. 백엔드 포트에는 신뢰하는 프록시만 접근하도록 구성하고, 백엔드는 `link.yml`, 프록시는 `plugins/link/config.yml`로 DB 접속 정보를 설정합니다.

`journal`은 백엔드의 Velocity 활성화·프록시 온라인 모드 설정·비어 있지 않은 secret을 검사합니다. 직접 백엔드 접속은 거부됩니다. 로컬 부하 검사는 루프백 offline-auth 프록시를 사용했으며 Mojang 인증 검증을 대신하지 않습니다.

## 백엔드 실행

Minecraft EULA를 읽고 동의한 테스트 디렉터리에 서버 JAR을 복사한 뒤 실행합니다. YAML 또는 환경 변수의 DB 설정과 forwarding 설정은 앞 단계에서 준비해야 합니다.

```powershell
java -Xms512M -Xmx2G `
  -Dlink.mode=journal `
  -Dlink.spawn.template=lobby `
  -Dlink.spawn.version=1 `
  -Dlink.spawn.capacity=50 `
  -Dlink.node.capacity=150 `
  -Dlink.spawn.max-worlds=3 `
  -jar Link-26.3-dev.jar --nogui
```

위 메모리와 정원은 실행 예시이며 검증된 운영 권장 사양이 아닙니다. 템플릿·DB 초기화 실패 시 바닐라 월드로 대체하지 않습니다. 종료·장애 직후에는 이전 lease 만료까지 같은 노드의 시작이 거부될 수 있습니다.

## 명령어

백엔드 관리 명령에는 `link.admin` 권한이 필요하며 기본값은 OP입니다. 완료 메시지를 확인한 뒤 의존하는 다음 명령을 실행하세요.

### 템플릿 편집 흐름

| 한국어 명령 | 영어 명령 | 동작 |
| --- | --- | --- |
| `/템플릿 생성 <작업월드>` | `/template new <workspace>` | 바닥이 있는 영구 작업 월드를 만들고 입장 |
| `/템플릿 편집 <이름> <버전>` | `/template edit <name> <version>` | 발행본을 별도 영구 작업 월드로 복제하고 입장 |
| `/템플릿 입장 <작업월드>` | `/template enter <workspace>` | 저장한 작업 재개. 언로드 상태면 먼저 로드 |
| `/템플릿 저장 <이름> <새버전>` | `/template save <name> <version>` | 현재 작업을 저장하고 템플릿 발행, 편집 계속 |
| `/템플릿 나가기` | `/template exit` | 작업 월드를 저장하고 입장 전 위치·시선으로 복귀 |
| `/템플릿 저장후나가기 <이름> <새버전>` | `/template saveexit <name> <version>` | 발행 성공 후 복귀 |
| `/템플릿 목록` | `/template list` | 발행된 이름·버전 조회 |

예: `/템플릿 생성 lobby-work` → 건축 → `/템플릿 저장후나가기 lobby 1`. 수정할 때는 `/템플릿 편집 lobby 1` → 건축 → `/템플릿 저장후나가기 lobby 2`로 진행합니다. `/링크 템플릿 ...`와 `/link template ...`도 같은 명령입니다.

`생성`의 이름은 발행될 템플릿 이름이 아니라 **작업 월드 이름**입니다. 이미 `spawn` 작업 월드가 있다면 `/템플릿 입장 spawn`으로 재개하거나 `/템플릿 생성 spawn-work`처럼 다른 이름을 사용하세요. 삭제된 월드 이름은 재사용할 수 없습니다. 일반적인 이름 충돌은 오류 스택 대신 입장·새 이름 사용 안내를 표시합니다.

`나가기`는 **작업 월드 저장**이며 템플릿 발행이 아닙니다. 발행본은 덮어쓰지 않습니다. 기존 발행본을 수정할 때 새 버전을 지정하세요. 저장 실패 또는 복귀 취소 시 편집 상태를 유지합니다. 같은 플레이어의 중복 편집 요청은 거부합니다. 임시 월드는 편집 작업 월드로 사용할 수 없습니다.

작업 월드는 나간 뒤에도 유지되며 자동 삭제·언로드하지 않습니다. 입장 시 표시된 월드 이름으로 다시 들어갈 수 있습니다. `journal` 모드에서는 복귀 정보를 플레이어 PDC와 함께 저장하므로 재접속·재시작 뒤 같은 서버에서 `/템플릿 입장 <작업월드>`로 재개할 수 있습니다. 복귀 월드가 언로드됐으면 먼저 로드해야 합니다. 편집 중 Link 스폰·워프 이동은 거부되므로 먼저 `나가기`를 실행하세요. 편집 중 다른 플러그인으로 강제 이동했다면 작업 월드에 다시 입장한 뒤 발행하세요. 편집 기능은 권한 있는 관리자의 서버 내 작업을 위한 기능이며 인벤토리·게임 모드를 별도로 교체하지 않습니다.

명령어와 하위 명령어는 한국어를 지원합니다. 월드·템플릿·워프 **이름**은 기존 영문 Link 식별자 규칙을 유지합니다. 스폰 설정의 템플릿 버전 변경은 `link.yml` 수정 후 재시작해야 적용됩니다.

| 명령 | 기능 |
| --- | --- |
| `/link templates` | 템플릿 조회 |
| `/link worlds` | 로드된 관리 월드 조회 |
| `/link editor <name>` | 플레이어는 생성 후 입장, 콘솔은 월드 생성만 수행 |
| `/link publish <editor> <template> <version>` | 템플릿 발행 |
| `/link create <name> <template> <version> <persistent 또는 temporary>` | 템플릿 복제. 마지막 인수는 둘 중 하나 |
| `/link save <name>` | 체크포인트 저장 |
| `/link unload <name>` | 언로드 |
| `/link load <name>` | 저장한 월드 로드 |
| `/link delete <name>` | 삭제 표시. 이름 재사용 불가 |

예: `/link create lobby-2 lobby 1 persistent`.

`/링크`는 `/link`의 별칭입니다. 한국어 하위 명령은 각각 `템플릿목록`, `월드목록`, `에디터`, `발행`, `생성`, `저장`, `언로드`, `불러오기`, `삭제`입니다. 저장 방식은 `영구`/`임시`도 받습니다. `/스폰`, `/워프 <이름>`도 지원합니다. 영어·한국어 명령 모두 탭 자동완성을 제공하며, 편집 명령은 발행본 버전 또는 다음 발행 버전과 작업 월드를 제안합니다.

탭 자동완성은 `/link` 하위 명령어, 로드된 월드(`save`, `unload`, `publish`), 저장됐지만 언로드된 월드(`load`, `delete`), 템플릿 이름·버전과 `persistent`/`temporary`를 제안합니다. `publish`의 버전은 해당 템플릿의 다음 버전을 제안합니다. `/warp`는 등록된 워프 이름을 제안합니다. 권한이 없는 명령은 제안하지 않으며 새 월드 이름은 직접 입력합니다. DB 목록은 백그라운드에서 약 2초마다 갱신하므로 새 템플릿·워프가 바로 표시되지 않을 수 있습니다.

백엔드의 `/spawn`은 현재 서버의 입장 가능한 스폰을 우선 선택하며, 현재 서버에 여유가 없으면 다른 서버로 배정합니다. `/warp <name>`의 목적지가 현재 서버라면 해당 위치로 텔레포트합니다. 이동 요청을 SQL에 기록한 직후 서버 스레드에 처리를 예약하므로 정상 경로에서는 5초 주기 조회를 기다리지 않습니다. 같은 서버 이동은 프록시 재연결 없이 저장 완료 후 버킷에서 완료 처리합니다. 실제 이동 시간에는 SQL·월드 저장·서버 틱 처리 시간이 포함됩니다.

백엔드의 `/warp <name>`에는 `link.warp` 권한이 필요하며 기본값은 OP입니다. 두 명령은 플레이어 전용이고 `journal` 모드에서 동작합니다. Velocity 모듈은 명령어를 등록하지 않으며, 최초 접속 배정과 다른 서버로 준비한 이동 요청의 연결 전달만 담당합니다. 다른 플러그인과 명령어 이름이 겹치면 `/link:spawn`, `/link:warp <name>`으로 실행하세요. 월드 언로드는 플레이어 유무·기본 월드 보호·진행 중 입장 예약 등의 검사를 따릅니다.

### 워프 관리

이 기능을 적용할 때는 버킷과 Velocity의 Link 플러그인을 함께 업데이트하세요. 이전 버전의 접속 배정기는 새 워프 전용 채널 구분을 알지 못합니다. Velocity에 관리 명령어가 추가되는 것은 아닙니다.

| 한국어 명령 | 영어 명령 | 동작·권한 |
| --- | --- | --- |
| `/워프설정 <이름>` | `/setwarp <name>` | 현재 서버·월드·좌표·시선을 저장. 플레이어, `link.admin` |
| `/워프삭제 <이름>` | `/delwarp <name>` | 등록 삭제. 플레이어/콘솔, `link.admin` |
| `/워프목록 [페이지]` | `/warps [page]` | DB 목록을 10개씩 조회. 플레이어/콘솔, `link.warp` |
| `/워프 <이름>` | `/warp <name>` | 목적지 이동. 플레이어, `link.warp` |

모두 버킷의 `journal` 모드에서 사용합니다. 두 권한의 기본값은 OP입니다. 같은 이름으로 설정하면 위치를 갱신합니다. 위치는 명령 실행 순간의 값을 사용하며 SQL 커밋 후에만 성공을 알립니다. 설정·삭제·이동 명령은 기존 워프 이름을 탭으로 제안합니다. 새 이름은 직접 입력합니다.

워프 원본은 공용 SQL의 `link_warps`입니다. 설정·수정·삭제 결과는 `LinkApi.warpStateId(이름)` 최신 상태와 outbox에 함께 커밋됩니다. YAML이나 Redis가 워프 원본이 아니며, Redis 없이도 DB를 공유하는 모든 백엔드가 같은 워프를 조회합니다. 새 이벤트 이력은 기록하지 않고 현재 워프 상태를 보존합니다.

스폰 이외의 로드된 월드에서 설정하면 `spawn.capacity` 정원의 워프 전용 입장 채널을 DB에 함께 등록합니다. 이 채널은 신규 접속이나 `/스폰`의 배정 후보가 아닙니다. 워프 목적지 서버의 `node.id`는 Velocity 서버 이름과 일치해야 하며 해당 서버·월드가 준비되어 있어야 이동합니다. 월드 언로드 시 입장을 차단하고 로드·재시작 복구 후 다시 활성화합니다. 워프 삭제는 월드를 삭제하지 않으며 삭제 전에 시작된 이동 요청은 완료될 수 있습니다.

템플릿 편집 세션에서는 워프 설정을 거부합니다. 편집을 마치고 실제 사용할 월드에서 설정하세요. 워프를 유지할 월드는 영구 월드를 사용하고 직접 삭제하지 마세요. 워프 이름은 네트워크 전체에서 공유됩니다.

`link-integration`은 일반 서버에 설치하지 마세요. 월드·아이템을 변경하거나 의도적으로 서버를 중단하는 검사 명령이 포함됩니다.

### 플레이어 티피 요청

버킷 `journal` 모드의 플레이어 명령입니다. `link.tpa` 권한은 기본적으로 모든 플레이어에게 허용됩니다. Velocity에는 명령어를 추가하지 않습니다.

| 한국어 명령 | 영어 명령 | 동작 |
| --- | --- | --- |
| `/티피요청 <플레이어>` | `/tpa <player>` | 상대가 수락하면 내가 상대에게 이동 |
| `/티피여기 <플레이어>` | `/tpahere <player>` | 상대가 수락하면 상대가 내게 이동 |
| `/티피수락 [요청자]` | `/tpaccept [requester]` | 받은 요청 수락 |
| `/티피거절 [요청자]` | `/tpdeny [requester]` | 받은 요청 거절 |
| `/티피취소` | `/tpacancel` | 내가 보낸 대기 요청 취소 |

요청은 60초 동안 유효하며 한 사람은 진행 중 요청 하나만 보낼 수 있습니다. 재요청 간격은 5초, 받는 사람의 대기 요청은 최대 10개입니다. 받은 요청이 여러 개라면 수락·거절할 요청자 이름을 지정해야 합니다. 요청·여기 명령은 네트워크의 접속자, 수락·거절 명령은 받은 요청자를 탭으로 제안합니다(약 1초 캐시). 이름 대소문자는 구분하지 않습니다. 다른 플러그인과 이름이 겹치면 `/link:tpa`처럼 네임스페이스를 사용하세요.

수락 후 목적지 플레이어가 있는 버킷의 서버 스레드에서 위치와 시선을 확정합니다. 같은 버킷에서는 재접속 없이 이동하고, 다른 버킷에서는 기존 DB 예약·체크포인트·Velocity 연결 경로를 사용합니다. 목적지 정원과 월드 준비 상태 검사를 적용합니다. 템플릿 편집 중에는 사용할 수 없습니다. 로그아웃·재접속 또는 Link 이동으로 세션 epoch가 바뀐 요청은 실행을 거부합니다.

공용 SQL의 `link_player_names`에 이름/UUID, `link_teleport_requests`에 방향·양측 세션 epoch·만료 시각·상태·확정 목적지를 저장합니다. 상태 변경과 `tpa:<요청자 UUID>` 현재 상태·outbox를 같은 트랜잭션으로 커밋합니다. 시간 만료는 저장된 시각으로 판정하며 별도 만료 이벤트를 생성하지 않습니다. 보관 정책이 활성화되면 만료 시각이 보관 기간보다 오래된 요청 행도 정리합니다. 요청별 새 현재 상태를 영구 누적하지 않고 요청자별 최신 상태를 유지합니다. SQL이 원본이며 Redis는 알림 용도입니다.

### 템플릿 설정 GUI

`/템플릿 생성 <작업월드>` 또는 `/템플릿 편집 <이름> <버전>`으로 입장한 뒤 `/템플릿 설정`을 실행하세요. 영어는 `/template settings` 또는 `/template gui`입니다. `journal` 모드와 `link.admin` 권한이 필요하며 `설정`도 탭으로 제안합니다.

메뉴에서 다음 설정을 바꿀 수 있습니다.

- 현재 위치와 시선을 스폰으로 지정하고, 지정한 스폰으로 이동하여 확인
- 아침·정오·저녁·밤, 시간 흐름 켜기/끄기
- 맑음·비·천둥, 날씨 변화 켜기/끄기
- 난이도, PVP, 사망 시 인벤토리 유지, 몹 자연 생성
- 월드 경계 크기(좌클릭 +16, 우클릭 -16)와 현재 위치를 경계 중심으로 지정
- 작업 월드 저장, 저장 후 편집 종료

설정은 변경할 때마다 작업 월드 저장 경로로 반영됩니다. **새 버전 발행은 `/템플릿 저장 <이름> <새버전>`으로 진행합니다.** 발행한 버전으로 새로 복제하는 월드는 설정을 이어받고, 이미 생성된 월드는 변경하지 않습니다. 운영 스폰에 적용할 때는 사용할 템플릿 버전을 지정하고 기존 월드의 교체 여부를 별도로 결정하세요. 서버 재시작만으로 기존 저장 월드를 새 템플릿으로 덮어쓰지 않습니다.

정확한 스폰 좌표·시선은 월드 PDC `link:template-spawn`으로 템플릿에 포함됩니다. 일반 Bukkit 월드 스폰은 블록 좌표이며 Link 편집 입장·스폰 채널 배정은 이 정확한 좌표를 우선 사용합니다. 자동 증설 스폰 월드도 해당 템플릿 스폰을 사용합니다. 시간·날씨·게임 규칙·경계는 네이티브 월드 데이터와 함께 저장됩니다. 기존 SQL/SlimeVault 저장 설정을 따르며 별도 로컬 설정 파일을 만들지 않습니다.

경계 크기·중심 변경으로 지정된 스폰이 경계 밖으로 나가는 것은 거부합니다. 스폰 지점의 바닥·주변 건축물 등 실제 이동 안전성은 관리자가 확인하세요. 메뉴에는 복사·드래그·숫자키 이동을 막은 표시용 아이템만 사용합니다.

### 플레이어 유틸리티

`journal` 모드의 버킷에서 사용합니다. 플레이어 본인에게 적용되며 기본 권한은 OP입니다. 한국어·영어 명령과 인자의 탭 추천을 제공합니다. 이름이 다른 플러그인과 겹치면 `/link:speed`처럼 실행하세요.

| 한국어 | 영어 | 기능·권한 |
| --- | --- | --- |
| `/속도 <0~10 또는 초기화> [걷기 또는 비행]` | `/speed <0..10 or reset> [walk or fly]` | 종류 생략 시 현재 비행 여부로 선택. `link.speed` |
| `/비행속도 <값>` | `/flyspeed <value>` | 비행 속도. `link.speed` |
| `/걷기속도 <값>`, `/이동속도 <값>` | `/walkspeed <value>` | 지상 이동 속도. `link.speed` |
| `/비행 [켜기 또는 끄기]` | `/fly [on or off]` | 생략하면 비행 권한 토글, 켜면 즉시 비행. `link.fly` |
| `/회복` | `/heal` | 현재 최대 체력으로 회복하고 불 끄기. `link.heal` |
| `/배고픔`, `/허기` | `/feed` | 허기·포만감 채우기, 소모도 초기화. `link.feed` |

예: `/비행 켜기` → `/비행속도 3`. `/속도 초기화 비행` 또는 `/flyspeed reset`으로 기본값을 복원합니다. 속도 값은 배수가 아닌 단계이며 소수도 가능합니다. `0`은 해당 이동 속도 정지, `1`은 기본값(Bukkit 걷기 0.2 / 비행 0.1), `10`은 Bukkit 최대값 1.0입니다. 비행 속도를 바꾸는 명령 자체는 비행 권한을 켜지 않습니다.

접속 중이고 살아 있으며 서버 이동 중이 아닌 플레이어만 변경합니다. 관전자 모드의 비행 변경과 크리에이티브 비행 권한 끄기는 거부합니다. 공중에서 비행을 끄면 이후 낙하 피해를 받을 수 있습니다. 회복 명령은 상태 효과를 지우거나 허기를 채우지 않습니다.

변경은 서버 스레드의 네이티브 프레임에서 처리하며 DB 커밋 후 성공 메시지를 보냅니다. 속도·체력·허기는 기존 플레이어 데이터에 저장됩니다. Link 비행 선택은 플레이어 PDC `link:utility-flight`에도 저장해 재접속 시 서바이벌/어드벤처 모드에서 복원합니다. 다른 플러그인이나 게임 모드 변경은 이후 값을 다시 변경할 수 있습니다. 별도 YAML이나 Redis를 원본으로 사용하지 않습니다.

### 템플릿 채널 그룹 워프

템플릿 편집 월드에서 `/템플릿 설정` → **채널 설정**을 엽니다.

| 설정 | 동작 |
| --- | --- |
| 채널당 정원 | 1~1,000명. 좌/우 클릭으로 ±1, Shift 좌/우 클릭으로 ±10 |
| 그룹 최대 채널 | 1~100개. 서버별이 아닌 그룹 전체 생성 한도 |
| 자동 증설 | 켜면 여유 채널이 없을 때 한도 안에서 추가 생성. 끄면 최초 채널 1개만 생성 |
| 생성 범위 | 그룹을 연결한 서버만 또는 DB를 공유하는 네트워크 전체의 활성 Link 버킷 |

기본값은 정원 50명, 최대 3개, 자동 증설 켜짐, 그룹 연결 서버만입니다. 버킷 자체 정원도 함께 검사하므로 채널 정원을 크게 지정해도 서버 정원을 초과하지 않습니다. 새 채널은 템플릿의 정확한 스폰으로 입장하며 영구 저장됩니다. 비어 있다고 자동 삭제하거나 채굴한 광산을 초기화하지 않습니다.

사용 예:

```text
/템플릿 편집 mine 1
/템플릿 설정
# 채널·보호 설정 변경 후
/템플릿 저장후나가기 mine 2
/워프그룹 mines mine 2
/워프 mines
```

| 한국어 | 영어 | 기능 |
| --- | --- | --- |
| `/워프그룹 <워프이름> <템플릿> <버전>` | `/warpgroup <name> <template> <version>` | 발행한 버전의 설정으로 그룹 연결. `link.admin`, 플레이어/콘솔 |
| `/워프그룹 목록` | `/warpgroup list` | 그룹과 정원·한도 조회 |
| `/워프그룹 채널 <그룹>` | `/warpgroup channels <group>` | 준비된 채널의 서버·입장/예약 인원 조회 |
| `/워프 <이름>` | `/warp <name>` | 고정 워프 또는 그룹 워프 이동. `link.warp` |
| `/워프삭제 <이름>` | `/delwarp <name>` | 연결 해제. 기존 월드·진행 중 이동은 강제 삭제/중단하지 않음 |

그룹 워프는 여유 있는 채널 중 현재 버킷을 우선하고, 없으면 다른 준비된 채널을 선택합니다. 자리가 없으면 DB에 생성 작업을 배정하고 해당 버킷이 월드를 생성합니다. 같은 버킷이면 직접 이동하고 다른 버킷이면 기존 체크포인트·Velocity 입장 경로를 사용합니다. 새 JVM이나 서버 프로세스를 실행하는 기능은 아닙니다. 네트워크 전체 모드에서는 참여하는 모든 버킷과 Velocity 모듈을 이 빌드로 맞추고, DB·SlimeVault 접근과 `node.id`/Velocity 서버 이름을 일치시켜야 합니다.

요청은 DB에서 최대 60초 대기합니다. 정원·한도 부족, 목적지 서버 중단 또는 생성 실패로 그 안에 배정하지 못하면 실패를 알립니다. 로그아웃·재접속·다른 Link 이동으로 원본 세션 epoch가 바뀌면 실행을 거부합니다. 생성 중 채널도 최대 개수에 포함하고, 그룹마다 미완료 생성 작업 하나만 배정합니다. DB 잠금으로 여러 버킷의 동시 증설을 조정합니다. 원래 서버가 중단된 채널을 다른 서버로 강제로 옮기거나 슬롯을 임의 반환하지 않으며, 원래 버킷 복구 후 저장된 월드를 다시 사용합니다.

발행한 템플릿 버전과 연결된 그룹 설정은 고정됩니다. 설정을 바꾸려면 새 버전을 발행하고 새 그룹 이름으로 연결하세요. 기존 그룹을 덮어써 서로 다른 설정의 채널이 섞이게 하지 않습니다. 삭제한 그룹 이름은 재사용하지 않습니다. 이미 생성된 월드의 개수는 언로드만으로 줄지 않습니다. 고정 위치 워프는 기존대로 사용할 수 있지만 같은 이름을 그룹 워프와 공유할 수 없습니다.

채널 정책은 월드 PDC `link:template-channels`와 발행 버전의 `link_template_channels`에, 그룹은 `link_warp_groups`, 생성 작업은 `link_group_worlds`, 대기 요청은 `link_group_requests`에 저장합니다. 정책과 템플릿 바이트는 동일한 서버 스레드 시점에 캡처하고 SQL에서 함께 발행합니다. 요청 배정은 예약·라우트·최신 상태를 한 SQL 트랜잭션으로 커밋합니다. 오래된 대기 요청 행은 기존 보관 기간 정리를 따릅니다.

### 템플릿 보호 설정

`/템플릿 설정` → **보호 설정**에서 다음 항목을 각각 허용/차단할 수 있습니다. 기본값은 모두 허용입니다.

- 블록 파괴: 플레이어 파괴와 양동이로 퍼 담기
- 블록 설치: 플레이어 설치와 양동이 비우기
- 블록 상호작용: 문·버튼·레버 등 클릭 및 압력판 등의 물리 상호작용
- 상자 등 보관함 사용: 위치가 있는 블록/엔티티 인벤토리 열기
- 폭발의 블록 파괴: 폭발 대상 블록 목록을 제거하며 엔티티 피해 설정과는 별개
- 엔티티의 블록 변경: 엔더맨·몹 등의 `EntityChangeBlockEvent` 경로
- 불 생성·번짐·연소: 점화, 불에 의한 확산과 블록 연소

설정은 월드 PDC `link:template-protection`에 저장되어 발행·복제·재시작 시 유지됩니다. 해당 작업 월드의 활성 편집 세션과 `link.admin` 권한을 가진 편집자는 건축할 수 있습니다. 일반 생성 월드에서는 OP라도 보호 설정을 따릅니다. 별도 `link.protection.bypass` 권한은 기본 허용되지 않으며 필요할 때 권한 플러그인으로 부여합니다. 서버/플러그인이 직접 블록을 변경하거나 일반 Bukkit 이벤트를 우회하는 작업까지 차단하는 기능은 아닙니다. 피스톤·유체 흐름 등 위 이벤트에 포함되지 않은 환경 변화는 별도 제한을 추가하지 않았습니다.

### 실제 데이터 기반 탭 추천

템플릿·발행 버전·편집 작업 월드·워프·그룹 이름은 공용 DB와 로드된 월드 목록으로 약 2초마다 갱신합니다. 플레이어와 받은 티피 요청자는 약 1초마다 갱신합니다. `/워프목록`의 페이지도 실제 워프 수로 계산합니다. 고정 워프 설정에는 고정 워프 이름, 그룹 채널 조회에는 그룹 이름을 추천하며, 임시 월드는 편집 입장·저장 후보에서 제외합니다. 권한이 없는 명령은 추천하지 않습니다. 새 이름을 만드는 자리와 아직 발행하지 않은 이름은 직접 입력합니다. 인자가 없는 명령은 추가 인자를 제안하지 않습니다.

## 비동기 자동 저장

월드 변경과 플레이어 데이터는 메모리에서 처리한 뒤 기본 30초마다 저장합니다. 생성된 월드도 마지막 저장 상태로 복구하며 재시작 때 템플릿으로 초기화하지 않습니다. 기존 설정 파일에는 다음 블록을 추가합니다.

```yaml
saving:
  interval-seconds: 30
  workers: 4
```

설정 변경 후 재시작합니다. 주기는 1~3600초, 스레드는 1~16개입니다. 서버 스레드는 상태를 캡처하고, 작업 스레드는 직렬화·압축·S3 업로드를 병렬 처리합니다. SQL 커밋은 순서대로 처리하며 저장이 느릴 때 체크포인트를 무한히 쌓지 않습니다.

`/save-all`과 `/save-all flush`는 저장을 요청하고 완료 후 성공을 알립니다. Link 월드 저장·템플릿 발행 명령도 유지됩니다. 로그아웃은 저장 후 소유권을 해제하며 서버 이동과 정상 종료는 최종 저장을 기다립니다. 일반 게임 패킷은 저장을 기다리지 않으므로 강제 종료하면 마지막 완료된 저장 이후 변경이 유실될 수 있습니다. API 완료·저장 계약은 [설계 문서](link-implementation.md)를 참고하세요.

## Same-node restart and forced ownership takeover

Starting a backend immediately acquires its configured node ID, even when a previous process still holds an unexpired lease. Link increments the node epoch in `link_sessions` instead of deleting ownership rows. All backend database transactions validate this epoch under a shared startup lock; takeover uses an exclusive lock and waits for existing transactions to finish. Normal transactions still run concurrently. Old-epoch writes, renewals and shutdown cleanup are rejected. The old backend stops when it detects ownership loss.

Normal shutdown stops teleport/group scheduling first, drains world work and the final checkpoint, stops lease renewal, and releases node/player ownership. A failed final save does not explicitly release the lease, but a replacement can still take over immediately. Startup also invalidates old player sessions, pending routes/tickets and reservations belonging to that node; players reconnect using the latest durable state.

Use a different node ID for each concurrently running backend. Reusing an ID deliberately replaces its previous owner. Forced takeover recovers the last completed save; changes still only in the old process's memory are not transferred. No manual session-row deletion or lease-expiry wait is needed. Database outages or lock timeouts still fail startup safely.

## Lease renewal during long checkpoints

Lease renewal uses an atomic conditional expiry update and does not acquire the session advisory lock held by checkpoints. Checkpoint validation retains the advisory lock that serializes ownership changes, but no longer holds a session row lock: renewal may extend expiry concurrently, while takeover/release/transfer still wait for the checkpoint. Renewal requires the same node, epoch, ACTIVE state and an unexpired DB-clock lease; it cannot revive an expired or replaced owner.

Transient SQL connection errors, lock timeouts and deadlocks are retried at most twice with short backoff. Exhausted failures still fence the backend. The existing 5-second lock timeout remains unchanged. Network discovery heartbeat failures are logged separately after a successful ownership renewal.

The NMS shutdown hook stops teleport and group workers before plugin disable. Late off-thread tasks during shutdown are discarded; normal off-thread native mutations remain rejected.

## Large checkpoints and PostgreSQL lock capacity

Link no longer acquires one advisory lock per state key. New keys are inserted with primary-key conflict detection; existing keys are updated only when the stored version matches the expected version. Prepared statements are reused, and keys are written in sorted order. The entire checkpoint, operation receipt and outbox entry still commit or roll back together. Node/session fencing and operation deduplication remain enabled. Row-count growth no longer creates matching growth in aggregate advisory locks.

For this upgrade, stop all Link backends sharing the database before replacing their JARs, then restart them. Replace the bundled Velocity module as well. Do not mix old unconditional state writers with the new conditional writers during a rolling upgrade. Existing state/schema and link.yml require no migration. Increasing PostgreSQL max_locks_per_transaction is not required for the per-key checkpoint issue.

## Save progress and deadlines

Saving now reports phase, completed/total work, elapsed time and time since the last completed work item every 10 seconds while pending. Startup, transfer and shutdown waits use the same progress monitor as ordinary asynchronous autosaves. Completion of a chunk job or a conditional SQL state write counts as progress; merely polling does not.

New defaults, automatically added to an existing saving section in link.yml:

```yaml
saving:
  interval-seconds: 30
  workers: 4
  stall-timeout-seconds: 120
  total-timeout-seconds: 1800
```

A progressing save can run beyond the old fixed 120-second limit. Stalled work still fails after the configured stall timeout; total elapsed time remains bounded. Both use a monotonic clock. An actual storage error still fails immediately. The startup wait remains a readiness barrier: players should not enter before the initial checkpoint completes.

On restart, comparing an unchanged serialized chunk to a persisted SlimeVault reference uses its embedded SHA-256 digest instead of downloading the chunk a second time. World restoration still downloads and verifies the content checksum. This reduces redundant startup S3 traffic without dropping the initial checkpoint.


## 이동 소유권 수정 빌드 업그레이드

공유 DB를 사용하는 모든 백엔드와 Link Velocity 플러그인을 함께 교체합니다. 먼저 플레이어 접속을 막고 서버를 정상 종료하여 저장을 마친 뒤 교체하세요. 시작 시 PostgreSQL/MariaDB에 link_route_receipts 테이블이 자동 생성됩니다. 기존 플레이어/월드 데이터나 세션 행을 수동으로 삭제할 필요는 없습니다.

같은 백엔드의 스폰/워프는 기존 플레이어 소유권을 유지합니다. 서버 간 이동은 출발 서버의 저장 후 전달하며, 대상 서버가 수락할 때만 소유권 세대가 증가합니다. 빠른 재접속은 이전 연결의 저장/해제를 최대 15초 기다립니다. 그보다 오래 걸리면 접속을 거절하고 재시도를 안내하며, 저장 중인 인벤토리 소유권을 강제로 빼앗지 않습니다.

BetonQuest 등 플러그인의 접속 전 OfflinePlayer 조회는 마지막으로 DB에 저장된 플레이어 데이터를 읽습니다. 이 읽기에는 로그인 소유권이 필요하지 않지만, 실제 로그인과 저장에는 기존 소유권 검증이 적용됩니다.
