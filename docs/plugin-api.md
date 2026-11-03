# 플러그인 API

[README](../README.md) · [설치](setup.md) · [설계](link-implementation.md) · [검증](validation.md)

계약 원본은 [LinkApi.java](../link-api/src/main/java/io/linkmc/api/LinkApi.java)입니다. `io.linkmc.api.Link.api()`는 Link 런타임 초기화 이후 사용합니다.

## 의존성

API는 `compileOnly`로 사용하고 플러그인 JAR에 다른 복사본을 포함하지 마세요. 공개 Maven 저장소 배포를 가정하지 않고, 빌드한 API JAR을 플러그인 프로젝트의 `libs/`에 넣을 수 있습니다.

```kotlin
dependencies {
    compileOnly(files("libs/Link-API-dev.jar"))
}
```

또는 Link 저장소에서 `:link-api:publishToMavenLocal`을 실행하고, 플러그인 프로젝트에 `mavenLocal()`과 `compileOnly("io.linkmc:link-api:0.1.0-SNAPSHOT")`을 설정합니다. Bukkit 타입을 쓰는 코드는 별도의 호환 서버 API 의존성도 필요합니다.

## 서비스

| 서비스 | 주요 메서드 | 제공 위치 |
| --- | --- | --- |
| `worlds()` | `templates`, `createEditor`, `publish`, `create`, `load`, `save`, `unload`, `delete`, `loaded` | 백엔드 |
| `editors()` | `create`, `edit`, `enter`, `save`, `exit` | 백엔드 |
| `engine()` | `journalEnabled`, `transact` | 백엔드 |
| `network()` | `channels`, `registerChannel`, `setChannelReady`, `reserve`, `cancel`, `warp`, `warps`, `setWarp`, `deleteWarp`, `requestTravel`, `travelStatus` | 백엔드·프록시 |
| `data()` | `read`, `commit`, `history` | 백엔드·프록시 |
| `sessions()` | `acquire`, `renew`, `release`, `prepare`, `accept` | 백엔드·프록시 |

프록시에는 월드와 네이티브 엔진이 없습니다. `LinkVelocity.api()`도 설치된 프록시 서비스를 제공합니다. 세션 API는 낮은 수준의 소유권 조작용이며 일반 게임 플러그인은 직접 소유권을 바꾸기보다 이동 API를 사용해야 합니다.

## 비동기 사용 규칙

반환값은 `CompletionStage`입니다. Minecraft 메인 스레드에서 `join()`이나 `get()`으로 기다리지 마세요. 의존 작업은 `thenCompose`로 연결하고, 완료 콜백이 메인 스레드라고 가정하지 마세요. 콜백에서 Bukkit/NMS 상태를 바꿔야 한다면 `engine().transact`로 다시 전달합니다.

`data().history()`는 [이벤트 보관 정책](storage-retention.md)의 영향을 받습니다. 기본 24시간이 지난 과거 이벤트는 정리될 수 있고 최신 상태를 보존하는 트랜잭션만 예외로 남습니다. 반환 버전이 연속이거나 최초 버전부터 존재한다고 가정하지 마세요. 현재 상태와 작업 UUID 중복 방지 기록은 보존합니다.

다음은 백엔드 플러그인 메서드 내부에서 사용하는 예제입니다.

```java
var link = io.linkmc.api.Link.api();
link.worlds().createEditor("lobby-editor")
    .thenCompose(world -> link.engine().transact(() -> {
        org.bukkit.Bukkit.getWorld(world.name())
            .getBlockAt(0, 64, 0).setType(org.bukkit.Material.BEDROCK);
    }))
    .thenCompose(ignored -> link.worlds().publish("lobby-editor", "lobby", 2))
    .thenCompose(ignored -> link.worlds().create("lobby-copy", "lobby", 2, true))
    .whenComplete((world, error) -> {
        if (error != null) {
            getLogger().log(java.util.logging.Level.SEVERE, "월드 생성 실패", error);
        } else {
            getLogger().info("생성 완료: " + world.name());
        }
    });
```

이름·버전은 예시입니다. 재실행 전에 기존 월드·템플릿과의 충돌을 확인하세요. 월드를 직접 채널로 등록한다면 로드 완료 후 등록하고 언로드 전에 `setChannelReady(id, false)`로 입장을 막아야 합니다.

## 이동과 워프

`network().warps()`는 공용 DB의 워프 이름 목록을, `deleteWarp(name)`은 삭제 여부를 반환합니다. `setWarp(name, destination)`은 위치만 등록하고 기존 입장 채널을 사용합니다. `setWarp(name, destination, capacity)`는 필요하면 워프 전용 입장 채널을 같은 SQL 트랜잭션에 추가합니다. 호출자는 해당 노드와 월드가 실제로 로드됐는지 확인해야 합니다. 워프 전용 채널은 스폰 자동 배정에 포함되지 않습니다. 설정·삭제는 같은 이름별로 직렬화하며 `LinkApi.warpStateId(name)` 이벤트와 DB 조회용 상태를 원자적으로 변경합니다. 일반 `data().commit()`으로 `warp:` 예약 영역을 수정할 수 없습니다. 삭제는 이후 조회에 적용되며 삭제 전에 목적지를 조회한 이동 요청이나 이미 발급된 예약은 자동 취소하지 않습니다.

편집 입장은 `link.editors().create(playerId, "lobby-work")` 또는 `edit(playerId, "lobby", 1)`로 요청합니다. `save(playerId, "lobby", 2, true)`는 작업 저장·발행 후 복귀하고, 마지막 인수가 `false`면 편집을 계속합니다. `exit(playerId)`는 작업 월드만 저장하고 복귀합니다. `enter(playerId, workspace)`는 저장된 작업을 재개합니다. 이 API가 서버 스레드 이동과 편집 세션을 관리하므로 별도 텔레포트가 필요하지 않습니다. API 자체는 권한 검사를 하지 않으므로 호출 플러그인이 권한을 확인해야 합니다. 작업 월드는 영구 보관되며 명시적으로 정리해야 합니다. 편집 세션이 있으면 Link 이동 요청은 `FAILED`로 끝나므로 먼저 `exit`를 완료하세요.

```java
var link = io.linkmc.api.Link.api();
var destination = new io.linkmc.api.LinkApi.Destination(
    "node-b", "link-spawn", 8, 66, 8, 90, 0);
java.util.UUID operation = java.util.UUID.randomUUID();

link.network().setWarp("shop", destination)
    .thenCompose(ignored -> link.network().requestTravel(playerId, operation, destination))
    .whenComplete((reservation, error) -> {
        if (error != null) getLogger().log(java.util.logging.Level.SEVERE, "이동 요청 실패", error);
    });
```

`playerId`는 현재 접속 중인 플레이어의 UUID입니다. 목적지를 `null`로 전달하면 현재 서버의 입장 가능한 스폰을 우선 배정합니다. 목적지가 현재 서버면 프록시 재연결 없이 텔레포트하며, 저장 완료 후 `REQUESTED`에서 `COMPLETE`로 전환합니다. 버킷의 API는 요청을 저장한 직후 서버 스레드에 처리를 예약합니다. 요청 완료는 실제 이동 완료와 다릅니다. `travelStatus(operation)`의 상태를 확인하세요.

| 상태 | 의미 |
| --- | --- |
| `REQUESTED` | 원본 서버의 체크포인트 처리 대기 |
| `READY` | 원본 처리가 커밋되어 프록시 이동 가능 |
| `CONNECTING` | 프록시가 요청을 선점하고 연결 처리 중 |
| `COMPLETE` | 이동 완료 기록 |
| `FAILED` | 실패 기록 |
| `MISSING` | 해당 요청 없음 |

동일 요청 재시도에는 같은 작업 ID와 같은 목적지를 사용합니다. 이미 완료한 이동을 새 이동으로 요청할 때는 새 ID를 발급합니다.

## 네이티브 트랜잭션

```java
var playerId = player.getUniqueId(); // 안전한 호출 위치에서 확보한 UUID
io.linkmc.api.Link.api().engine().transact(() -> {
    var online = org.bukkit.Bukkit.getPlayer(playerId);
    if (online != null) {
        online.getInventory().setItem(0,
            new org.bukkit.inventory.ItemStack(org.bukkit.Material.DIAMOND, 7));
    }
});
```

완료는 포함된 프레임의 커밋을 뜻합니다. 콜백 내부에서 예외가 발생하면 Java 객체를 원복하지 않고 백엔드를 중단합니다. 외부 HTTP·파일·다른 DB의 부수 효과는 포함하지 않습니다. 범용 롤백 가능한 DB 트랜잭션처럼 임의의 작업을 넣지 마세요.

## 플러그인 데이터

플러그인 자체 데이터는 `data().read/commit/history`로 다룹니다. 트랜잭션에는 작업 UUID, 소유권 가드, 상태별 예상 버전과 변경 후 바이트를 전달합니다. 예상 버전 `0`은 신규 상태입니다. 재시도는 같은 UUID와 동일한 내용을 사용해야 합니다.

`player:`와 `native:` 네임스페이스는 일반 데이터 API 쓰기가 거부됩니다. 네이티브 상태 변경은 엔진 API와 지원되는 Bukkit/NMS 경로를 사용하세요. 과거 이벤트의 인코딩 스키마를 읽을 수 있도록 플러그인 자체 코덱을 관리해야 합니다.

임의 가상 인벤토리, 비동기 Bukkit/NMS 변경, 모든 플러그인 조합의 호환성은 보장하지 않습니다. 검증된 범위는 [검증 문서](validation.md)를 참고하세요.

## 동의 기반 플레이어 이동

`Link.api().teleports()`는 다음 비동기 API를 제공합니다.

- `request(requesterUUID, recipientName, here)`: `here=false`이면 요청자가 상대에게, `true`이면 상대가 요청자에게 이동하도록 요청합니다.
- `respond(recipientUUID, requesterName, accept)`: 수락/거절. 이름이 `null`이면 유일한 대기 요청을 선택하고 여러 개면 거부합니다.
- `cancel(requesterUUID)`: 아직 수락되지 않은 요청 취소.
- `incoming(recipientUUID)`, `onlinePlayers()`: SQL의 대기 요청 및 활성 접속자 조회.

`TeleportRequest`의 ID는 이동 작업 UUID와 같습니다. `DISPATCHED`는 이동 제출을 의미하며 도착 완료는 `network().travelStatus(id)`로 확인하세요. 수락 이후 위치 확정·이동 제출은 목적지 버킷이 수행합니다. 요청 만료, 세션 epoch, 정원 검사가 적용됩니다. 명령 권한은 버킷 명령에서 검사하므로 API를 호출하는 플러그인은 호출자의 권한과 UUID를 직접 검증해야 합니다. 비동기 완료 콜백에서 Bukkit 객체를 직접 변경하지 마세요.

`tpa:`는 내부 상태 영역이므로 일반 `data().commit` 쓰기를 거부합니다. 요청 변경은 반드시 `teleports()`를 사용하세요.
