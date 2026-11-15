# 최신 상태 저장과 기존 이벤트 정리

[설치](setup.md) · [검증](validation.md)

## 저장 방식

새 커밋은 `link_events`에 기록하지 않습니다. `link_state`의 최신 상태, `link_operations`의 작업 UUID·digest, `link_outbox` 알림을 한 SQL 트랜잭션에 저장합니다. 예상 버전 검사, 세션 소유권·epoch 검사, 커밋 응답 유실 시 동일 작업 재시도는 유지됩니다. 게임 패킷은 저장 완료를 기다리지 않습니다. PostgreSQL WAL이나 MariaDB InnoDB 로그를 끄는 변경이 아닙니다.

`EventStore`, `NativeJournal`, `journal` 모드 이름은 호환성을 위해 유지합니다. 이름과 달리 새 변경의 이벤트 소싱·과거 리비전 재생은 하지 않습니다. `data().history()`는 deprecated 읽기 전용 API이며 남아 있는 구버전 이벤트만 반환합니다.

## 월드와 플레이어 저장

월드는 바닐라 파일입니다. Paper가 `level-name`(`level.dat`, region 파일, 네더·엔드)을 로드하고 자동 저장과 `/save-all`이 region 파일을 씁니다. 스코어보드·지도 등 SavedData도 바닐라 파일입니다. 월드 청크는 더 이상 SQL이나 SlimeVault에 저장하지 않습니다.

플레이어 데이터(인벤토리·통계·발전 과제·세션)는 기본 30초마다 DB에 저장합니다. 서버 스레드에서 분리된 복사본을 만들고 기본 4개 작업 스레드가 직렬화하며, SQL 커밋은 전용 저장 스레드 하나가 순서대로 수행합니다. 동시에 처리하는 체크포인트는 하나입니다. `/save-all`은 월드를 저장한 뒤 플레이어 데이터를 커밋하고 성공 메시지를 보내며, 서버 이동과 정상 종료는 최종 저장 완료를 기다립니다.

**플레이어 데이터(DB)와 월드 파일은 따로 저장됩니다.** 이전의 통합 체크포인트와 달리 강제 종료 후 상자 안 아이템과 인벤토리가 같은 시점이 아닐 수 있어 복제·유실 구간이 생길 수 있습니다. 마지막 저장 이후 메모리의 변경은 강제 종료로 유실될 수 있습니다.

```yaml
saving:
  interval-seconds: 30
  workers: 4
```

기존 YAML에도 블록을 추가합니다. 주기는 1~3600초, 작업 스레드는 1~16개이며 재시작 후 적용됩니다.

## 기존 DB 전환

1. 모든 백엔드와 Velocity를 종료하고 SQL 및 참조하는 S3 객체를 함께 백업합니다.
2. 백엔드 JAR와 Velocity 플러그인을 모두 이번 버전으로 교체합니다. 구버전과 혼용하지 않습니다.
3. 첫 시작 때 DB에 `native:<node>/vanilla-export` 표시가 없으면 DB·SlimeVault의 영구 월드와 SavedData를 바닐라 파일로 한 번 내보내고 표시를 커밋합니다. 기존 `storage` 설정은 이 내보내기에만 읽습니다.
4. 이미 있는 바닐라 파일은 덮어쓰지 않으며 임시 월드는 내보내지 않습니다.

내보낸 뒤에도 DB의 월드 데이터는 삭제하지 않습니다. 이 데이터의 정리는 별도로 결정해야 하며 자동 정리 대상이 아닙니다. 절차는 [설치 안내](setup.md#이전-빌드의-월드-자동-내보내기)를 참고하세요. 운영 DB에는 이 개발 작업으로 접속하거나 데이터를 삭제하지 않았습니다.

## 자동 정리

```yaml
retention:
  enabled: true
  event-hours: 24
  interval-seconds: 60
  batch-operations: 500
  max-batches: 10
```

기존 `link.yml`에 항목이 없으면 기본 블록을 추가합니다. 백엔드 작업 스레드가 구형 이벤트를 최대 500개 작업씩 10회 정리합니다. 이제 최신 리비전의 구형 이벤트도 만료됩니다. 복구 원본인 `link_state`와 중복 요청 영수증은 삭제하지 않습니다. 새 작업은 처음부터 `events_pruned=true`로 생성하여 이벤트 정리를 위한 추가 UPDATE를 하지 않습니다.

전송 완료된 오래된 outbox, 만료된 티피 요청, 남아 있는 구형 그룹 대기 요청도 정리합니다. 미전달 outbox, 최신 상태, 소유권·이동 데이터는 보존합니다. 여러 백엔드는 행 잠금과 `SKIP LOCKED`로 함께 정리할 수 있고 실패한 정리 트랜잭션은 롤백됩니다.

## 용량에 남는 영향

기존 운영 통계는 `link_events` 15GB, `link_operations` 589MB, `link_outbox` 417MB, `link_state` 약 3.6MB였습니다. 새 이벤트 payload 증가는 중단되지만 다음 항목은 별개입니다.

- 과거 이벤트 삭제 후 SQL 파일이 즉시 줄어들지는 않습니다. 빈 페이지 재사용과 운영체제에 공간 반환은 다릅니다. PostgreSQL의 파일 회수 작업은 별도 점검 후 진행합니다.
- 작업 영수증은 중복 실행 방지를 위해 보존합니다. MariaDB 잠금 행, WAL/binlog, 최신 상태 UPDATE에 따른 공간·쓰기 비용은 남습니다.
- 미전달 outbox와 Redis 스트림은 별도 관리 대상입니다. Redis가 꺼져 있으면 미전달 알림이 누적됩니다.
- 이전 빌드가 남긴 SQL 월드 데이터와 S3 객체는 내보내기 후에도 자동 삭제하지 않습니다. 백업을 확인한 뒤 별도로 정리해야 합니다.

읽기 전용 진단: [MariaDB](../link-integration/storage-diagnostics-mariadb.sql), [PostgreSQL](../link-integration/storage-diagnostics-postgresql.sql).
