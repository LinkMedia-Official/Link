# 플러그인 API

[README](../README.md) · [설치](setup.md) · [설계](link-implementation.md) · [검증](validation.md)

계약 원본은 [LinkApi.java](../link-api/src/main/java/io/linkmc/api/LinkApi.java)입니다. `io.linkmc.api.Link.api()`는 Link 런타임 초기화 이후 사용합니다.

## 의존성

API는 `compileOnly`로 사용하고 플러그인 JAR에 다른 복사본을 포함하지 마세요. GitHub Packages 사용법은 [Link API 의존성](link-api.md)을 참고하세요. 빌드한 API JAR을 플러그인 프로젝트의 `libs/`에 넣을 수도 있습니다.

```kotlin
dependencies {
    compileOnly(files("libs/Link-API-dev.jar"))
}
```

또는 Link 저장소에서 `:link-api:publishToMavenLocal`을 실행하고, 플러그인 프로젝트에 `mavenLocal()`과 `compileOnly("io.linkmc:link-api:0.2.0-SNAPSHOT")`을 설정합니다. Bukkit 타입을 쓰는 코드는 별도의 호환 서버 API 의존성도 필요합니다.

## 0.2.0 변경 사항

`0.2.0-SNAPSHOT`은 호환되지 않는 변경입니다. 다음 항목이 제거됐습니다.

- `worlds()`, `editors()`, `warpGroups()`와 `Worlds`, `Editors`, `WarpGroups`, `ChannelPolicy`, `WarpGroup`, `WorldInfo`
- `network()`의 `registerChannel`, `setChannelReady`, `warp`, `warps`, `setWarp`, `deleteWarp`
- `LinkApi.warpStateId`

`requestTravel`은 명시적 목적지가 필요합니다. 월드는 일반 바닐라 월드이므로 생성·로드·언로드는 Bukkit API(`WorldCreator`, `Bukkit.createWorld`, `Bukkit.unloadWorld`)를 사용하세요.

## 서비스

| 서비스 | 주요 메서드 | 제공 위치 |
| --- | --- | --- |
| `engine()` | `journalEnabled`, `transact` | 백엔드 |
| `utilities()` | `speed`, `flight`, `heal`, `feed` | 백엔드 |
| `teleports()` | `request`, `respond`, `cancel`, `incoming`, `onlinePlayers` | 백엔드 |
| `network()` | `channels`, `reserve`, `cancel`, `requestTravel`, `travelStatus` | 백엔드·프록시 |
| `data()` | `read`, `commit`, `history` | 백엔드·프록시 |
| `sessions()` | `acquire`, `renew`, `release`, `prepare`, `accept` | 백엔드·프록시 |

프록시에는 네이티브 엔진이 없습니다. `LinkVelocity.api()`도 설치된 프록시 서비스를 제공합니다. 세션 API는 낮은 수준의 소유권 조작용이며 일반 게임 플러그인은 직접 소유권을 바꾸기보다 이동 API를 사용해야 합니다.

## 비동기 사용 규칙

반환값은 `CompletionStage`입니다. Minecraft 메인 스레드에서 `join()`이나 `get()`으로 기다리지 마세요. 의존 작업은 `thenCompose`로 연결하고, 완료 콜백이 메인 스레드라고 가정하지 마세요. 콜백에서 Bukkit/NMS 상태를 바꿔야 한다면 `engine().transact`로 다시 전달합니다.

`data().history()`는 deprecated 읽기 전용 호환 API입니다. 이전 버전에서 저장한 이벤트만 반환하고 새 커밋은 이력을 만들지 않습니다. 기존 이력은 [보관 정책](storage-retention.md)에 따라 모두 만료될 수 있습니다. 현재 상태는 `data().read()`로 조회합니다. 트랜잭션·예상 버전·작업 UUID 중복 방지 계약은 유지됩니다.

## 서버 간 이동

```java
var link = io.linkmc.api.Link.api();
var destination = new io.linkmc.api.LinkApi.Destination(
    "node-b", "world", 8, 66, 8, 90, 0);
java.util.UUID operation = java.util.UUID.randomUUID();

link.network().requestTravel(playerId, operation, destination)
    .whenComplete((reservation, error) -> {
        if (error != null) getLogger().log(java.util.logging.Level.SEVERE, "이동 요청 실패", error);
    });
```

`playerId`는 현재 접속 중인 플레이어의 UUID입니다. 목적지는 반드시 지정해야 하며 해당 노드에 로드된 월드여야 합니다. 목적지가 현재 서버면 프록시 재연결 없이 텔레포트하며, 저장 완료 후 `REQUESTED`에서 `COMPLETE`로 전환합니다. 버킷의 API는 요청을 저장한 직후 서버 스레드에 처리를 예약합니다. 요청 완료는 실제 이동 완료와 다릅니다. `travelStatus(operation)`의 상태를 확인하세요.

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

완료는 해당 변경을 포함한 플레이어 데이터 체크포인트의 DB 커밋을 뜻합니다. 월드 블록·상자 등 월드 상태는 바닐라 파일로 따로 저장되며 이 완료에 포함되지 않습니다. 일반 게임 패킷은 이 완료를 기다리지 않으며, 기본 30초 자동 저장 사이의 강제 종료에는 미저장 변경 유실이 가능합니다. 콜백 내부에서 예외가 발생하면 Java 객체를 원복하지 않고 백엔드를 중단합니다. 외부 HTTP·파일·다른 DB의 부수 효과는 포함하지 않습니다. 범용 롤백 가능한 DB 트랜잭션처럼 임의의 작업을 넣지 마세요.

## 플러그인 데이터

플러그인 자체 데이터는 `data().read/commit`으로 다룹니다. 트랜잭션에는 작업 UUID, 소유권 가드, 상태별 예상 버전과 변경 후 바이트를 전달합니다. 예상 버전 `0`은 신규 상태입니다. 재시도는 같은 UUID와 동일한 내용을 사용해야 합니다.

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

## 플레이어 유틸리티 API

`Link.api().utilities()`는 `speed(UUID, double level, MovementMode)`, `flight(UUID, Boolean enabled)`, `heal(UUID)`, `feed(UUID)`를 제공합니다. `MovementMode`는 `AUTO`, `WALK`, `FLY`이며 `flight`의 `null`은 비행 권한 토글입니다. 반환값은 `CompletionStage<Void>`로 네이티브 커밋 이후 완료됩니다. 호출하는 플러그인은 대상 UUID의 권한을 검증해야 합니다. 유효하지 않은 속도나 모드는 호출 시 거부될 수 있으므로 동기 인자 오류와 비동기 실행 오류를 모두 처리하세요. 서버 스레드에서 완료를 기다리며 `join()`하지 마세요.

### 이동 요청 재시도

network().requestTravel(player, operation, destination)는 같은 operation UUID의 재요청에 최초 예약을 반환합니다. 완료 후 또는 재접속 후 재요청해도 다시 이동하지 않습니다. 다른 플레이어나 다른 목적지로 UUID를 재사용하면 충돌입니다. 새로운 이동에는 새로운 UUID를 사용하세요. 반환되는 예약은 최초 제출 기록이며 완료 여부는 travelStatus(operation)으로 조회하세요.

같은 서버 이동은 Bukkit 텔레포트 이벤트 취소를 존중하며 취소된 요청만 FAILED 처리합니다. 이동 완료는 목적지 플레이어 상태 저장 후 확정됩니다. link_route_receipts는 중복 실행 방지를 위한 작은 영수증이며 블록 변경 이벤트 로그가 아닙니다. 현재 자동 삭제하지 않으므로 UUID 재시도 보장을 유지하려면 수동으로 삭제하지 마세요.
