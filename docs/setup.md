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
| `tools` | Link 런타임과 DB 연결만 사용. Upstream 플레이어 파일 저장 유지 |
| `journal` | 실험적 플레이어 데이터 SQL 저장과 입장·이동 처리 |
| `strict` | 검증 미완료로 시작 거부 |

모든 모드에서 월드는 바닐라 방식으로 저장됩니다.

## 백엔드 `link.yml`

첫 실행 시 작업 디렉터리(보통 서버 JAR을 실행한 폴더)에 `link.yml`을 자동 생성합니다. EULA 확인과 월드 초기화 전에 생성하며 기존 설정은 덮어쓰지 않습니다. 기존 파일에 `retention`·`saving` 항목이 없으면 기본값을 덧붙입니다. 수정 후 서버를 재시작하세요. [보관·정리 정책과 DB 진단](storage-retention.md)을 확인하세요.

```yaml
mode: disabled
node:
  id: node-a
  capacity: 150
database:
  jdbc-url: 'jdbc:postgresql://127.0.0.1:5432/link'
  username: 'link'
  password: ''
# 이전 빌드의 월드를 한 번 내보낼 때만 읽음
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
worlds:
  autoload: []
  capacity: 50
```

PostgreSQL 또는 MariaDB에 DB·계정을 먼저 생성하고 `database` 항목을 입력하세요. MariaDB의 URL 예시는 `jdbc:mariadb://127.0.0.1:3306/link`입니다. MariaDB는 InnoDB를 사용하며 `innodb_flush_log_at_trx_commit=1`, binlog 활성 시 `sync_binlog=1`이 필요합니다. Velocity 설정까지 준비한 뒤 `mode: journal`로 변경합니다. 비밀번호는 따옴표로 감싸고 작은따옴표 자체는 YAML 작은따옴표 문자열 안에서 두 번 적습니다. Redis 사용 시 `redis.enabled: true`로 바꿉니다.

| 항목 | 의미 |
| --- | --- |
| `worlds.autoload` | 시작 시 빈(void) 생성기로 여는 추가 월드 이름 목록. 기본 월드(`level-name`)와 네더·엔드는 Paper가 그대로 로드 |
| `worlds.capacity` | `/tpa` 도착 기준 월드당 정원. 기존 `spawn.capacity`도 읽음 |
| `node.capacity` | 백엔드당 정원 |
| `storage.*` | 이전 빌드 월드의 일회성 내보내기에만 사용 |

`spawn.template`, `spawn.version`, `spawn.max-worlds`는 제거되어 읽지 않습니다. 기존 파일에 남아 있어도 무방합니다.

YAML을 설정하면 `java -Xms512M -Xmx2G -jar Link-26.3-dev.jar --nogui`로 실행할 수 있습니다. 접속 정보가 들어간 `link.yml`은 커밋하지 않습니다.

## 월드 저장

월드는 일반 바닐라 월드입니다. Paper가 `level-name`(`level.dat`, region 파일, 네더·엔드)을 그대로 로드하고, 자동 저장과 `/save-all`이 region 파일을 씁니다. 스코어보드·지도 등 SavedData도 바닐라 파일입니다. 추가 월드는 일반 Bukkit API(`WorldCreator`)로 만들거나 `worlds.autoload`에 등록합니다. 월드 백업은 일반 서버처럼 월드 폴더를 백업합니다.

`/save-all`은 월드를 바닐라 방식으로 저장한 뒤 플레이어 데이터를 DB에 커밋하고 완료를 알립니다.

**플레이어 데이터(DB)와 월드 파일은 따로 저장됩니다.** 이전의 통합 체크포인트와 달리 강제 종료 후 상자 안 아이템과 인벤토리가 같은 시점이 아닐 수 있어 아이템 복제·유실 구간이 생길 수 있습니다.

## 이전 빌드의 월드 자동 내보내기

이전 빌드는 영구 월드(섬, `link-template-editor`, `edit-*` 작업 월드 등)를 DB 또는 SlimeVault에 저장했습니다. 이 빌드는 시작 시 DB에 `native:<node>/vanilla-export` 표시가 없으면 한 번 자동으로 내보냅니다.

1. DB의 영구 월드(catalog=persistent)를 기본 월드 폴더의 `dimensions/minecraft/<이름>/` 아래 바닐라 region·entities·poi 파일로 씁니다. 전역·월드별 SavedData 파일도 씁니다.
2. 각 월드를 빈(void) 생성기로 한 번 생성하고 스폰·난이도·월드 PDC를 적용합니다.
3. 마지막 체크포인트에 열려 있던 월드를 `link.yml`의 `worlds.autoload`에 추가합니다.
4. 내보내기 표시를 DB에 커밋합니다. 이후 시작에서는 다시 내보내지 않습니다.

이미 있는 바닐라 파일은 덮어쓰지 않습니다. DB의 원본 데이터는 삭제하지 않습니다. 임시 월드(스폰 복제본 등)는 내보내지 않습니다. 내보내기에 실패한 월드는 로그에 남고 DB 사본은 유지됩니다. SlimeVault에 월드를 저장했다면 내보내기 전까지 기존 `storage` 설정을 유지하세요. 전환 전에 SQL·S3를 백업하세요.

## 환경 변수와 기존 옵션

백엔드는 아래 환경 변수를 YAML보다 우선 적용합니다. 모드·정원은 기존 `-Dlink.*` 옵션(`link.mode`, `link.node.capacity`, `link.worlds.capacity`)이 우선합니다. 환경 변수의 비밀 값은 생성 파일에 복사하지 않습니다. Velocity 플러그인도 전용 YAML을 읽으며 같은 DB 환경 변수가 우선합니다. 백엔드 노드 이름은 `node.id` 또는 `LINK_NODE_ID`로 지정합니다.

| 변수 | 대상 | 의미 |
| --- | --- | --- |
| `LINK_JDBC_URL` | 백엔드·프록시 | 예: `jdbc:postgresql://127.0.0.1:5432/link` |
| `LINK_DB_USER` | 백엔드·프록시 | DB 계정 |
| `LINK_DB_PASSWORD` | 백엔드·프록시 | DB 비밀번호 |
| `LINK_NODE_ID` | 백엔드 | 실행 중인 다른 노드와 중복되지 않는 이름 |
| `LINK_REDIS_URI` | 백엔드, 선택 | 예: `redis://127.0.0.1:6379` |
| `LINK_S3_ACCESS_KEY`, `LINK_S3_SECRET_KEY` | 백엔드, 선택 | 일회성 내보내기의 SlimeVault 키 |

백엔드의 `node.id`(또는 `LINK_NODE_ID`)는 Velocity의 서버 등록 이름과 일치해야 합니다. 모든 백엔드와 프록시는 같은 Link DB를 사용합니다. 시작 시 스키마를 초기화하므로 DB 계정에 필요한 권한이 있어야 합니다. 자격 증명은 저장소에 커밋하지 않습니다.

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

각 백엔드는 기본 월드를 입장 채널로 등록합니다. 첫 입장 시 Velocity가 살아 있는 노드를 배정하고, 플레이어는 저장된 위치(없으면 월드 스폰, 바닐라 배치)에 입장합니다. Link 목적지는 서버 간 이동 도착에만 적용합니다.

## 백엔드 실행

Minecraft EULA를 읽고 동의한 테스트 디렉터리에 서버 JAR을 복사한 뒤 실행합니다. YAML 또는 환경 변수의 DB 설정과 forwarding 설정은 앞 단계에서 준비해야 합니다.

```powershell
java -Xms512M -Xmx2G `
  -Dlink.mode=journal `
  -Dlink.node.capacity=150 `
  -Dlink.worlds.capacity=50 `
  -jar Link-26.3-dev.jar --nogui
```

위 메모리와 정원은 실행 예시이며 검증된 운영 권장 사양이 아닙니다. DB 초기화 실패 시 시작을 중단합니다. 종료·장애 직후에는 이전 lease 만료까지 같은 노드의 시작이 거부될 수 있습니다.

## 명령어

명령어와 하위 명령어는 한국어·영어를 지원하며 탭 자동완성을 제공합니다. 다른 플러그인과 이름이 겹치면 `/link:tpa`처럼 네임스페이스를 사용하세요. Velocity 모듈은 명령어를 등록하지 않으며 최초 접속 배정과 다른 서버로 준비한 이동 요청의 연결 전달만 담당합니다.

`/link`, `/템플릿`, `/스폰`, `/워프`, `/워프설정`, `/워프그룹` 등은 제거됐습니다.

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

요청은 60초 동안 유효하며 한 사람은 진행 중 요청 하나만 보낼 수 있습니다. 재요청 간격은 5초, 받는 사람의 대기 요청은 최대 10개입니다. 받은 요청이 여러 개라면 수락·거절할 요청자 이름을 지정해야 합니다. 요청·여기 명령은 네트워크의 접속자, 수락·거절 명령은 받은 요청자를 탭으로 제안합니다(약 1초 캐시). 이름 대소문자는 구분하지 않습니다.

수락 후 목적지 플레이어가 있는 버킷의 서버 스레드에서 위치와 시선을 확정합니다. 같은 버킷에서는 재접속 없이 이동하고, 다른 버킷에서는 기존 DB 예약·체크포인트·Velocity 연결 경로를 사용합니다. 목적지 정원(`worlds.capacity`)과 월드 준비 상태 검사를 적용합니다. 로그아웃·재접속 또는 Link 이동으로 세션 epoch가 바뀐 요청은 실행을 거부합니다.

공용 SQL의 `link_player_names`에 이름/UUID, `link_teleport_requests`에 방향·양측 세션 epoch·만료 시각·상태·확정 목적지를 저장합니다. 상태 변경과 `tpa:<요청자 UUID>` 현재 상태·outbox를 같은 트랜잭션으로 커밋합니다. 시간 만료는 저장된 시각으로 판정하며 별도 만료 이벤트를 생성하지 않습니다. 보관 정책이 활성화되면 만료 시각이 보관 기간보다 오래된 요청 행도 정리합니다. 요청별 새 현재 상태를 영구 누적하지 않고 요청자별 최신 상태를 유지합니다. SQL이 원본이며 Redis는 알림 용도입니다.

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

## 플레이어 데이터 자동 저장

플레이어 데이터는 메모리에서 처리한 뒤 기본 30초마다 DB에 저장합니다. 월드는 바닐라 자동 저장을 따릅니다. 기존 설정 파일에는 다음 블록을 추가합니다.

```yaml
saving:
  interval-seconds: 30
  workers: 4
```

설정 변경 후 재시작합니다. 주기는 1~3600초, 스레드는 1~16개입니다. 서버 스레드는 상태를 캡처하고, 작업 스레드가 직렬화를 처리합니다. SQL 커밋은 순서대로 처리하며 저장이 느릴 때 체크포인트를 무한히 쌓지 않습니다.

로그아웃은 저장 후 소유권을 해제하며 서버 이동과 정상 종료는 최종 저장을 기다립니다. 일반 게임 패킷은 저장을 기다리지 않으므로 강제 종료하면 마지막 완료된 저장 이후 변경이 유실될 수 있습니다. API 완료·저장 계약은 [설계 문서](link-implementation.md)를 참고하세요.

## Same-node restart and forced ownership takeover

Starting a backend immediately acquires its configured node ID, even when a previous process still holds an unexpired lease. Link increments the node epoch in `link_sessions` instead of deleting ownership rows. All backend database transactions validate this epoch under a shared startup lock; takeover uses an exclusive lock and waits for existing transactions to finish. Normal transactions still run concurrently. Old-epoch writes, renewals and shutdown cleanup are rejected. The old backend stops when it detects ownership loss.

Normal shutdown stops teleport scheduling first, drains the final player checkpoint, stops lease renewal, and releases node/player ownership. A failed final save does not explicitly release the lease, but a replacement can still take over immediately. Startup also invalidates old player sessions, pending routes/tickets and reservations belonging to that node; players reconnect using the latest durable state.

Use a different node ID for each concurrently running backend. Reusing an ID deliberately replaces its previous owner. Forced takeover recovers the last completed save; changes still only in the old process's memory are not transferred. No manual session-row deletion or lease-expiry wait is needed. Database outages or lock timeouts still fail startup safely.

## Lease renewal during long checkpoints

Lease renewal uses an atomic conditional expiry update and does not acquire the session advisory lock held by checkpoints. Checkpoint validation retains the advisory lock that serializes ownership changes, but no longer holds a session row lock: renewal may extend expiry concurrently, while takeover/release/transfer still wait for the checkpoint. Renewal requires the same node, epoch, ACTIVE state and an unexpired DB-clock lease; it cannot revive an expired or replaced owner.

Transient SQL connection errors, lock timeouts and deadlocks are retried at most twice with short backoff. Exhausted failures still fence the backend. The existing 5-second lock timeout remains unchanged. Network discovery heartbeat failures are logged separately after a successful ownership renewal.

The NMS shutdown hook stops teleport workers before plugin disable. Late off-thread tasks during shutdown are discarded; normal off-thread native mutations remain rejected.

## Large checkpoints and PostgreSQL lock capacity

Link no longer acquires one advisory lock per state key. New keys are inserted with primary-key conflict detection; existing keys are updated only when the stored version matches the expected version. Prepared statements are reused, and keys are written in sorted order. The entire checkpoint, operation receipt and outbox entry still commit or roll back together. Node/session fencing and operation deduplication remain enabled. Row-count growth no longer creates matching growth in aggregate advisory locks.

For this upgrade, stop all Link backends sharing the database before replacing their JARs, then restart them. Replace the bundled Velocity module as well. Do not mix old unconditional state writers with the new conditional writers during a rolling upgrade. Increasing PostgreSQL max_locks_per_transaction is not required for the per-key checkpoint issue.

## Save progress and deadlines

Saving reports phase, completed/total work, elapsed time and time since the last completed work item every 10 seconds while pending. Startup, transfer and shutdown waits use the same progress monitor as ordinary asynchronous autosaves. Completion of a work item or a conditional SQL state write counts as progress; merely polling does not.

New defaults, automatically added to an existing saving section in link.yml:

```yaml
saving:
  interval-seconds: 30
  workers: 4
  stall-timeout-seconds: 120
  total-timeout-seconds: 1800
```

A progressing save can run beyond the old fixed 120-second limit. Stalled work still fails after the configured stall timeout; total elapsed time remains bounded. Both use a monotonic clock. An actual storage error still fails immediately. The startup wait remains a readiness barrier: players should not enter before the initial checkpoint completes.

## 이동 소유권 수정 빌드 업그레이드

공유 DB를 사용하는 모든 백엔드와 Link Velocity 플러그인을 함께 교체합니다. 먼저 플레이어 접속을 막고 서버를 정상 종료하여 저장을 마친 뒤 교체하세요. 시작 시 PostgreSQL/MariaDB에 link_route_receipts 테이블이 자동 생성됩니다. 기존 플레이어 데이터나 세션 행을 수동으로 삭제할 필요는 없습니다.

같은 백엔드의 티피는 기존 플레이어 소유권을 유지합니다. 서버 간 이동은 출발 서버의 저장 후 전달하며, 대상 서버가 수락할 때만 소유권 세대가 증가합니다. 빠른 재접속은 이전 연결의 저장/해제를 최대 15초 기다립니다. 그보다 오래 걸리면 접속을 거절하고 재시도를 안내하며, 저장 중인 인벤토리 소유권을 강제로 빼앗지 않습니다.

BetonQuest 등 플러그인의 접속 전 OfflinePlayer 조회는 마지막으로 DB에 저장된 플레이어 데이터를 읽습니다. 이 읽기에는 로그인 소유권이 필요하지 않지만, 실제 로그인과 저장에는 기존 소유권 검증이 적용됩니다.
