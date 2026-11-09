# 검증 결과

## 비동기 자동 저장·멀티스레드 검증 (2026-11-03)

- PostgreSQL·MariaDB core 테스트 각각 75개 통과(전용 S3 통합 테스트 1개 제외), 서버 Link 테스트 92개 통과. 최종 linkDist 성공. 추가 NMS save-all 패치는 생성된 소스에 대한 reverse apply 검사를 통과했습니다.
- 저장 파이프라인은 호출 스레드를 막지 않는 제출, 동시에 한 체크포인트만 허용, 3개 작업 스레드의 동시 처리 상한, 모든 작업 이후 커밋, 준비 실패 후 후속 커밋 거부를 검사했습니다. 독립 청크의 S3 동시 업로드와 동일 청크 캐시, 캡처 후 원본 월드 변경에도 복사본이 유지되는 검사, 설정 추가·범위 검사도 통과했습니다. SQL 커밋이 대기 중이어도 상태 조회와 다른 플레이어 상태 복원이 대기하지 않는 회귀 테스트를 추가했고, 최종 JAR로 월드 복구·명시적 저장을 재확인했습니다.
- 실제 MariaDB + SlimeVault + Velocity + 두 백엔드에서 실행했습니다. A의 S3 요청에 테스트 프록시로 PUT당 3초 지연을 주고 4,096개 블록을 변경했습니다. 업로드 중 /list 응답은 Node/RCON 왕복 포함 약 72.4ms였고 PUT 요청 4개의 동시 처리를 확인했습니다.
- 같은 시점의 jcmd 스레드 덤프에서 Link-Save-Worker 4개는 HTTP 응답을 기다렸고, Link-Save-Commit은 병렬 작업 완료를 기다렸으며, Server thread는 다음 틱을 기다리고 있었습니다. /save-all flush 성공 메시지는 저장 완료 후 기록됐습니다.
- 이 로컬 시험에서 TPS 20.0, 최근 5초 평균 MSPT 1.2ms·최대 27.6ms를 관측했습니다. 해당 측정은 접속자 0명, A 월드 16개 및 테스트 강제 로드 청크 4개 조건이며 실제 운영 인원·WorldEdit/FAWE 부하 벤치마크가 아닙니다. 이후 유휴 백엔드 2개를 35초 관찰하여 link_operations 증가가 총 2건임을 확인했습니다.
- 명시적 저장 성공 후 A를 강제 종료하고 B를 정상 종료했습니다. lease 만료 후 재시작하여 블록 4,096개·상자 아이템·아머 스탠드·PDC만 있는 빈 청크가 복구됐습니다. 재시작 전후 실제 클라이언트 A→Velocity→B 이동에서 인벤토리·엔더 상자·체력·경험치가 유지됐습니다. 재시작 후에는 아이템을 다시 지급하지 않았습니다. 검사 기간 새 이벤트 행은 0개였습니다.
- 첫 지연 검사에서는 접속자가 없어 청크가 언로드되어 fill을 실행하지 못했습니다. 테스트 청크를 강제 로드하고 다시 검증한 뒤 해제했습니다. 첫 이동 검사에서는 목적지의 평화로움 난이도가 체력을 13에서 15로 회복시켜 테스트 플러그인의 고정값 검사가 서버를 중단했습니다. 양쪽 시험 월드를 보통 난이도로 고정한 후 같은 인벤토리·체력 검사를 통과했습니다.
- 증거: build/async-tests.log, build/async-final-tests.log, build/async-read-lock-tests.log, build/async-maria-final.log, build/yaml-firstboot/async-final-smoke.stdout.log, build/async-live-check.log, build/async-thread-dump.txt, build/async-s3-proxy.stdout.log, build/async-idle-check.log, build/async-wire2.log, build/async-restart-wire.log, build/async-db-evidence.txt 및 async-crash-recovery/async-normal-recovery 서버 로그.
- 일반 게임의 패킷은 DB 커밋을 기다리지 않습니다. 강제 종료 시 마지막 완료된 저장 이후 변경의 유실이 가능한 자동 저장 계약입니다. 저장 시점의 메인 스레드 캡처, 이동·종료의 최종 저장 대기, 기존 탑승 관계·지도 복구 한계는 남습니다. 과거 동기 저장 검사의 성능 수치를 새 구현의 성능으로 간주하지 않습니다.


## 최신 상태 저장·청크 체크포인트 검증 (2026-11-03)

- PostgreSQL·MariaDB core 테스트 각각 70개 통과(전용 S3 통합 테스트 1개 제외), 서버 Link 테스트 90개 통과. 설정 주석 변경 후 설정 테스트 10개 재확인, 최종 linkDist 빌드 성공.
- 이벤트 INSERT를 DB 제약으로 금지해도 커밋·최신 상태 복구·중복 요청 처리가 성공했습니다. 기존 이벤트 삭제, 마지막 구형 이벤트 정리 후 현재 상태 유지, 정리 실패 롤백·동시 정리를 양쪽 DB에서 검사했습니다.
- 청크 저장 테스트: PDC만 있는 빈 청크 보존, 한 청크 변경 시 다른 청크 기록 없음, 월드 설정 변경 시 청크 본문 기록 없음, 구형 전체 SRF의 원자적 전환, 필수 청크 손상 시 복구 거부, 재시작 후 불필요한 S3 객체 생성 없음.
- 실제 MariaDB + SlimeVault + Velocity + 두 백엔드에서 구형 월드 19개(A 16개, B 3개)를 청크 형식으로 전환했습니다. 구형 native 월드 본문의 비어 있지 않은 상태 조각은 0개였고, 검사 시작 이후 새 link_events 행도 0개였습니다.
- 서버 플러그인에서 4,096개 블록 변경, 상자 다이아몬드 13개, 장비를 가진 아머 스탠드, PDC만 있는 빈 청크를 저장했습니다. 빈 청크 언로드 후 확인하고, 커밋 완료 로그 뒤 A 프로세스를 강제 종료했습니다. lease 만료 후 재시작하여 모두 복구됐습니다. B는 정상 종료·재시작했습니다.
- 실제 프로토콜 클라이언트의 A→Velocity→B 이동에서 인벤토리·엔더 상자·체력·경험치를 확인했습니다. 두 백엔드 재시작 후 아이템을 다시 지급하지 않고 접속·이동하여 같은 데이터가 유지됨을 재확인했습니다.
- 증거: build/state-final-tests.log, build/state-maria-tests.log, build/state-config-build.log, build/state-wire.log, build/state-restart-wire.log, build/state-db-evidence.txt, build/yaml-firstboot/state-migration.stdout.log, build/yaml-firstboot/state-crash-recovery.stdout.log, build/tpa-backend-b/state-normal-recovery.stdout.log.
- 실제 WorldEdit/FAWE 붙여넣기 비교 벤치마크나 51명 부하 재검사는 수행하지 않았습니다. 로드된 청크 전체의 내용 캡처·비교와 동기 SQL 비용은 남습니다. 강제 종료는 커밋 완료 후 주입했고, DB 자체 전원 손실·SQL/S3 동시 손실은 이번 검사 범위가 아닙니다. 탑승 관계·서버 간 지도 등 아래의 기존 한계는 해결됐다고 간주하지 않습니다.


## 템플릿 채널 그룹·보호·동적 탭 검증 (2026-11-03)

- PostgreSQL·MariaDB core 테스트 각각 68개 통과(전용 S3 통합 테스트 1개 제외), 버킷 Link 테스트 85개 통과, `linkDist` 성공.
- DB 검사는 발행 정책 불변성, 고정 워프 이름 충돌, 채널당 정원·전체 최대 채널, 다른 노드로 증설, 생성 범위 제한, 8개 동시 증설 시 생성 작업 하나, 자동 증설 끄기, 요청 만료·재접속 epoch, outbox 오류 시 예약/라우트/요청 전체 롤백, 보관 정리 후 작업 UUID 재사용 방지를 포함합니다.
- GUI 보호 정책 직렬화, 편집 세션에 한정된 우회, 일반 OP의 파괴·설치·상호작용 차단, 폭발 블록 목록 제거, 손상된 정책 차단, 채널 설정 범위와 동적 이름·버전·페이지를 테스트했습니다.
- MariaDB + SlimeVault + Velocity + 두 백엔드에서 실제 클라이언트로 GUI 정원 1명·최대 2개·네트워크 전체 범위 및 7개 보호 항목을 설정하고 발행했습니다. 첫 요청은 같은 버킷에 채널 생성 후 이동했고, 두 번째 플레이어 요청은 다른 버킷에 채널을 생성해 서버 간 이동했습니다. 두 채널의 입장 인원은 각각 1명으로 확인했습니다.
- 테스트 플러그인이 서버에서 Bukkit 파괴/설치 이벤트를 호출하여 편집 월드에서는 허용, 복제된 두 채널에서는 차단됨을 검증했습니다. 실제 클라이언트의 블록 파괴·설치 패킷을 전송한 검사는 아닙니다. 정상 재시작 후 기존 채널과 정책을 재사용하고 정확한 템플릿 스폰으로 이동했습니다.
- 실제 DB의 그룹·워프·템플릿 버전·작업 월드·페이지 탭 추천 10건 통과. 페이지 검사를 위해 임시 고정 워프 24개를 로컬 테스트 DB에 넣고 검사 후 삭제했습니다.
- 증거: `build/groups-final-test.log`, `build/groups-maria-verified.log`, `build/groups-completions-final.log`, `build/groups-live.log`, `build/groups-restart.log`, `build/groups-tab.log`. 런타임 이벤트 검사 로그는 `build/yaml-firstboot/groups-a.stdout.log`, `build/tpa-backend-b/groups-b.stdout.log`, `build/yaml-firstboot/groups-restart-a.stdout.log`입니다.
- 서버 생성 도중 강제 종료·운영 규모 부하·모든 보호 이벤트의 실제 게임 플레이는 이번 검사에 포함하지 않았습니다. 기존 엔진 한계와 슬롯을 보유한 서버의 복구 대기 정책은 그대로이며 운영 서버에 배포하지 않았습니다.
## 플레이어 유틸리티 검증 (2026-11-03)

- 버킷 Link 테스트 79개 통과, `linkDist` 빌드 성공. 유틸리티 신규 테스트 9개는 속도 범위·기본값·소수, 이동 종류 선택, 비행/게임 모드 검사, 접속·사망·이동 중 거부, 허기 변경, 한국어/영어 인자와 탭, 커밋 후 응답·오류 비밀값 비노출, 명시적 비행 선택 복원을 포함합니다.
- MariaDB + SlimeVault + Velocity 실제 접속에서 `/비행 켜기`, `/비행속도 3`, `/walkspeed 2`, `/회복`, `/feed`, 잘못된 `NaN`, 초기화 후 영어 속도 설정을 실행했습니다. 서버 측 검사로 비행·걷기 속도, 비행 권한/상태, 체력·허기를 확인했고 로그아웃/재접속 뒤 같은 상태를 다시 확인했습니다.
- 실제 한국어·영어 명령의 숫자 대신 초기화/종류/비행 on/off 인자 탭 추천 6건 통과. 증거: `build/utilities-final-test.log`, `build/utilities-live.log`, `build/utilities-tab.log`, `build/yaml-firstboot/utilities.stdout.log`의 `UTILITY_STATE_VERIFIED` 2건.
- 이 변경에 별도 SQL 스키마는 없습니다. 유틸리티의 서버 간 이동/강제 종료 장애 주입은 이번 검증에 포함하지 않았으며 기존 플레이어 저장·이동 경로를 사용합니다. 테스트 플러그인은 배포물에 포함하지 않았습니다.
## 템플릿 설정 GUI 검증 (2026-11-03)

- 버킷 Link 테스트 70개, PostgreSQL·MariaDB core 테스트 각각 61개 통과(전용 S3 통합 테스트 1개 제외). `linkDist` 빌드 성공.
- GUI의 스폰 PDC 직렬화, 경계 검사, 잘못된 스폰 데이터 거부, Shift/숫자키/오프핸드/버리기/더블클릭/드래그 차단, 편집 월드·권한 재검증, 설정 명령 별칭·탭 추천을 검사했습니다. 자동 스폰 증설이 템플릿의 정확한 좌표·시선을 예약에 사용하는 것도 양쪽 DB에서 확인했습니다.
- MariaDB + SlimeVault + Velocity 실제 프로토콜 클라이언트로 GUI 열기와 설정 버튼 12개를 클릭했습니다. 시간·날씨·규칙·난이도·경계·정확한 스폰 `(2.25,65,3.75,123,17)`을 서버 측 검사로 확인하고, 발행본 복제와 원본 재입장 후 동일 설정을 확인했습니다. 메뉴 아이템의 Shift/숫자키 회수 시도 후 플레이어 인벤토리·커서에 아이콘이 없는 것도 검사했습니다.
- 정상 종료 후 소유권 임대가 만료된 다음 재시작하여 원본 설정 복원과 GUI의 저장 후 나가기 버튼을 확인했습니다. 즉시 재시작 시 남은 임대 때문에 첫 부팅이 거절됐고, 임대를 강제로 수정하지 않고 만료 후 재시도했습니다. 초기 테스트 스크립트의 콘솔 TP가 기본 월드로 이동시킨 문제는 플레이어의 현재 차원에서 TP하도록 수정했습니다.
- 증거: `build/template-gui-test.log`, `build/template-gui-core.log`, `build/template-gui-final.log`, `build/gui-live-final.log`, `build/gui-restart.log`, `build/yaml-firstboot/gui-restart-ready.stdout.log`. 테스트 플러그인은 배포물에 포함하지 않았습니다. 강제 종료 장애 주입과 대규모 GUI 동시 사용 부하는 이번 검사 범위에 포함하지 않았습니다.
## DB 플레이어 티피 요청 검증 (2026-11-03)

- PostgreSQL·MariaDB core 테스트 각각 60개 통과(전용 S3 통합 테스트 1개 제외), 버킷 Link 테스트 64개 통과, `linkDist` 성공.
- 수락 전 이동 금지, 양방향 요청, 거절·취소·만료·재접속 epoch 검사, 다중 요청 선택, 동시 수락 중복 방지, outbox 오류 시 요청/이력 원자적 롤백, 보관 기간 경과 요청 정리를 검사했습니다.
- MariaDB + SlimeVault + Velocity + 두 백엔드 + 두 비 OP 프로토콜 클라이언트로 동일 서버 TPA/TPAHERE 이동(재접속 없음), 서로 다른 서버의 TPA/TPAHERE 왕복, 거절 시 제자리 유지, 취소 후 수락 거부를 확인했습니다. 테스트용 워프를 이용한 백엔드 배치에만 일시적으로 OP를 부여하고 종료 후 해제했습니다.
- 실제 한국어 요청·여기·수락·거절과 영어 요청·수락의 플레이어 이름 탭 추천 6건 통과. 최초 탭 테스트는 프록시 로그인 속도 제한에 걸려 클라이언트 접속 간격을 늘린 뒤 재검사했습니다.
- 증거: `build/tpa-release-test.log`, `build/tpa-postgres-final.log`, `build/tpa-final-test.log`, `build/tpa-live-result.log`, `build/tpa-tab-retry.log`.
- 이 검사는 요청 기능의 강제 종료 시점별 장애 주입이나 대규모 부하 검증을 포함하지 않습니다. 아래 엔진의 기존 한계는 여전히 남아 있습니다. 운영 서버에는 배포하지 않았습니다.
## DB 워프 관리 검증 (2026-11-03)

- 최종 PostgreSQL·MariaDB core 테스트 각각 54개 통과(전용 S3 통합 테스트 1개 제외), 버킷 Link 테스트 58개 통과, `linkDist` 빌드 성공.

- 버킷 명령 `/워프설정`, `/워프삭제`, `/워프목록`과 영어 별칭, 위치·시선 캡처, SQL 커밋 후 성공 응답, 권한·콘솔·편집 상태·인자 검사, 오류 메시지의 비밀값 비노출을 검사했습니다.
- DB 테스트는 공용 조회, 갱신·삭제 이력, 동시에 8회 수정한 버전 순서, 등록 및 삭제 도중 오류의 원자적 롤백, 최대 160자 이름의 이력 ID, 예약 영역 직접 쓰기 거부를 포함합니다. 워프 전용 채널의 스폰 배정 제외와 언로드·로드에 따른 입장 차단/복구도 검사합니다.
- 실제 MariaDB + SlimeVault + 갱신된 Velocity 플러그인에서 한국어 명령으로 위치 `(7.25,65,8.75)`, 시선 `(135,20)` 저장·이동 후 위치 갱신을 확인했습니다. 별도 영구 월드에 워프를 설정하고 `/스폰`과 왕복, 언로드 시 이동 거부, 로드 후 재이동을 확인했습니다.
- 정상 재시작 후 갱신된 좌표·시선과 별도 월드 워프가 유지됐고, 삭제 후 조회·이동이 거부됐습니다. 한국어/영어 설정·삭제·이동·목록 탭 완성 5건 통과. 운영 서버에는 배포하지 않았습니다.
- 증거: `build/warp-final-test.log`, `build/warp-final-maria-test.log`, `build/warp-live-test.log`, `build/warp-resume-test.log`, `build/warp-tab-wire-retry.log`. 워프 전용 월드의 서버 간 실제 이동은 이번 검사에 포함하지 않았으며 SQL 예약·기존 연결 경로를 사용합니다.

[README](../README.md) · [설치](setup.md) · [설계](link-implementation.md) · [API](plugin-api.md)

**기준: 2026-10-30, 구현 커밋 `547fae12`. 현재 운영 투입 불가입니다.** 아래는 실제 수행한 검사와 남은 한계를 구분한 기록입니다. 문서 변경 자체를 새 런타임 검증으로 간주하지 않습니다.

## 운영을 막는 확인된 문제

| 항목 | 재현 결과 |
| --- | --- |
| 처리 성능 | 51개 정지 상태 프로토콜 클라이언트에서 1분 평균 TPS 4.6 |
| 탑승 관계 | 보트는 정확히 하나 복원되지만 플레이어는 재접속 시 내려진 상태 |
| 서버 간 지도 | 지도 아이템은 A→B로 이동하지만 원래 픽셀 데이터는 B에 없음 |

전체 월드 직렬화·동기 SQL 비용 개선, 탑승 관계 정책과 복원, 네트워크 전체 지도 ID·데이터 서비스가 필요합니다. `strict`를 활성화하지 않았으며 무손실·무복제를 보장하지 않습니다.

## 자동 테스트

| 검사 | 통과 수 |
| --- | ---: |
| PostgreSQL·Redis 통합 테스트 | 29 |
| 프레임 커밋·불확실한 커밋 재시도 | 4 |
| 분할 스냅샷 무결성 | 2 |
| Netty 패킷 보류·순서·프로토콜·압축 변경 | 5 |
| **합계** | **40** |

실패·건너뜀 없이 통과했습니다. `547fae12`의 GitHub push/PR CI도 성공했습니다. 테스트에는 예상 버전 충돌, 다중 상태 롤백, 작업 재시도, 만료된 소유자 차단, 정원 경쟁, 일회용 입장권, 변경 불가능한 목적지, Redis 중복 전달, 커넥션 풀 롤백이 포함됩니다.

이동 경계 6개(체크포인트, 이전 준비, 입장권 발급, READY, CONNECTING, 도착 승인)는 SQL 상태 머신에서 장애·만료 후 회복을 검사했습니다. 여섯 번의 별도 JVM 강제 종료 시험을 뜻하지 않습니다.

## 실제 서버 검사

| 검사 | 결과 | 확인 내용 |
| --- | --- | --- |
| 강제 종료·재시작 | 통과 | 상자 다이아몬드 7개, 장비 엔티티, 월드 PDC, 전역 스코어보드 복원 |
| 상자에서 커서로 이동 후 강제 종료 | 통과 | 플레이어에게 정확히 7개 복구, 원본 상자는 비어 있음 |
| 제작창·커서 복구 | 통과 | 임시 입력 다이아몬드 5개와 커서 에메랄드 3개 복구 |
| 상자·호퍼 | 통과 | 상자 다이아몬드 11개, 호퍼와 목적지의 철 주괴 합계 16개 |
| 네이티브 메뉴 | 통과 | 좌·우 클릭, Shift 이동, 숫자키 교환, 드래그, 제작, 거래, 줍기 7개 검사 |
| 사망·리스폰 | 통과 | 네더의 별 7개 새 드롭, 기존 인벤토리 비움, 살아 있는 상태로 리스폰 |
| 월드 API | 통과 | 편집→발행→복제→저장→언로드→재로드→삭제, 템플릿 블록 유지 |
| 엔더 진주 | 통과 | 단일 백엔드 재시작 후 한 개체, 소유자, 플레이어의 청크 유지 등록 복구 |
| 보트 개체 | 통과 | 재시작 후 원본 보트 하나 유지. 탑승 관계는 별도 실패 |
| 실제 Velocity 이동 | 통과 | A→B와 같은 백엔드 워프. `(8,66,8)`, yaw 90, 아이템·엔더 상자·레벨·체력 유지 |
| 목적지 강제 종료 후 재접속 | 통과 | B 종료 후 A에서 새 epoch 획득, 다이아몬드 7개·에메랄드 3개·레벨 9·체력 13 유지 |
| PostgreSQL 중단 | 통과 | 두 백엔드 JVM이 저장 실패 후 종료. 메모리만으로 게임 지속하지 않음 |
| 고의 네이티브 예외 | 통과 | 아이템 99개·블록 변경 후 예외로 중단. 재시작 시 이전 커밋의 아이템 7개·상자 11개 복원 |
| Redis 없이 시작 | 수정 후 통과 | 기존 부팅 실패를 수정. 별도 연결 재시도로 서버 시작 가능 |
| Redis 연결 후 장애 | 통과 | lease 기간을 넘겨도 서버 응답·소유권 갱신 유지. Redis 복구 후 outbox 전달 재개 |
| 프록시 우회 접속 | 통과 | modern forwarding 없는 직접 백엔드 접속 거부 |
| 정원·연결 해제 | 통과 | 51명 전원 접속, 월드별 50+1 배정. 모두 종료 후 ADMITTED 예약 0개 |

메뉴 검사는 연결된 플레이어를 대상으로 메인 스레드에서 NMS 메서드를 호출했습니다. 그래픽 클라이언트의 실제 마우스 입력 자동화나 외부 플러그인 호환성 시험은 아닙니다. 이동 검사는 별도의 TCP 프로토콜 클라이언트가 실제 Velocity를 통과했습니다.

초기 줍기 검사는 제거된 Java 엔티티 객체의 `playerTouch`를 다시 강제 호출하는 잘못된 절차여서 실제 충돌 처리에 맞게 수정했습니다. 체력 보존 검사는 평화로움 난이도의 자동 회복을 배제하도록 일반 난이도와 고정된 음식 상태를 사용했습니다. 사망 검사는 기존 드롭과 새 드롭을 구분해 수량을 확인했습니다.

## 51명 부하 결과

Windows 11, Java 25, 백엔드 `-Xmx2G`, 로컬 Docker PostgreSQL 17, Velocity 4.3.1 환경입니다. 스폰 월드 2개와 이전 편집 월드 1개가 로드돼 있었으며, 클라이언트를 4초 간격으로 접속시킨 뒤 최종 인원을 60초 유지했습니다. 측정 중 로컬 Gradle 빌드는 실행하지 않았습니다.

| 접속 인원 | 관측된 1분 평균 TPS |
| ---: | ---: |
| 9 | 18.3 |
| 21 | 11.0 |
| 33 | 7.5 |
| 41 | 6.2 |
| 50 | 5.1 |
| 51 | 4.6 |

점진적으로 부하를 올리는 동안의 이동 평균이며 정상 상태 처리량이나 운영 권장 인원이 아닙니다. `/mspt`에는 프레임 바깥의 저장 비용이 모두 포함되지 않아 낮은 값이 나와도 실제 TPS 저하를 무시하면 안 됩니다.

## 재현

자동 통합 테스트는 Link 테이블을 초기화합니다. 반드시 폐기 가능한 DB를 사용합니다.

```powershell
$env:LINK_TEST_JDBC_URL='jdbc:postgresql://127.0.0.1:55432/link_test'
$env:LINK_TEST_DB_USER='link_test'
$env:LINK_TEST_DB_PASSWORD='link_test_local'
$env:LINK_TEST_REDIS_URI='redis://127.0.0.1:56379'
.\gradlew.bat :link-core:test
.\gradlew.bat applyAllPatches
.\gradlew.bat :link-velocity:shadowJar :link-integration:jar :aspaper-server:test --tests 'io.linkmc.server.Link*TestSuite'
.\gradlew.bat linkDist
```

라이브 검사는 [설치 안내](setup.md)에 따라 별도 서버 두 개와 루프백 프록시를 준비하고 EULA에 동의한 뒤 수행합니다. `link-integration` JAR은 배포 서버 JAR에 포함되지 않으며 `LINK_NODE_ID`가 `test-*`인 검사 서버에만 설치합니다. 백엔드 이름은 `test-a`, `test-b`, 검사 월드는 `link-spawn`을 사용합니다.

기본 프로토콜 클라이언트는 `127.0.0.1:25594`의 offline-auth 테스트 프록시에 `LinkWire`로 접속합니다. 이 이름의 offline UUID를 테스트 백엔드 whitelist에 등록해야 합니다. 패킷 ID를 읽으므로 먼저 패치를 적용해야 합니다.

```powershell
node link-integration/wire-client.cjs
```

검사용 콘솔 명령은 다음과 같습니다. `matrix-*`는 `MATRIX_PASS`·`MATRIX_FAIL`과 프레임 커밋 완료를 별도로 기록합니다.

| 명령 | 용도 |
| --- | --- |
| `linkprobe platform` | 발판과 일반 난이도 준비 |
| `linkprobe prepare` / `verify` | 월드·스코어보드 복구 |
| `linkprobe player-prepare` / `player-verify` | 합성 네이티브 플레이어 커서 복구 |
| `linkprobe player-transfer` / `player-arrive` | 합성 플레이어의 두 백엔드 이동 |
| `linkprobe wire-prepare` / `wire-transfer` / `wire-verify` | TCP 플레이어 상태 설정·B 이동·도착 검사 |
| `linkprobe matrix-menus` | 메뉴 조작 7개 |
| `linkprobe matrix-prepare` / `matrix-recover` | 제작창·호퍼·장비 엔티티 강제 종료 복구 |
| `linkprobe matrix-death` | 사망·리스폰 |
| `linkprobe matrix-worlds` | 공개 월드 API 생명주기 |
| `linkprobe matrix-relations-prepare` / `matrix-relations-recover` | 보트·엔더 진주 강제 종료 복구 |
| `linkprobe matrix-map-prepare` / `matrix-map-verify` | 지도 생성 후 이동 전·후 픽셀 검사 |
| `linkprobe matrix-rollback-baseline` / `matrix-abort` / `matrix-rollback-verify` | 커밋 기준 상태 생성, 고의 예외, 재시작 후 검사 |

준비와 확인 사이에 의도한 이동·강제 종료·재시작을 수행합니다. 생성되는 검사 엔티티 등은 반복 실행 시 정리하거나 새 테스트 DB·월드를 사용하세요. 강제 종료는 해당 테스트 JVM의 경로와 PID를 확인한 뒤에만 실행합니다.

부하 검사 전에는 `LinkLoad0`부터 `LinkLoad50`까지 offline UUID를 whitelist에 등록하고 테스트 백엔드 `max-players`를 51보다 크게 설정합니다. 한 백엔드의 50+1 분산을 재현할 때 다른 백엔드 채널은 배정 대상에서 제외합니다.

```powershell
node link-integration/load-clients.cjs 51 4000 60000
```

상태·개별 로그는 무시되는 `build/load-clients/`에 생성됩니다. `wire-client.cjs`는 `LINK_WIRE_NAME`, `LINK_WIRE_PORT`, `LINK_WIRE_DURATION_MS`로 테스트 이름·포트·실행 제한을 바꿀 수 있습니다. 로컬 `build/`의 시작·RCON 보조 스크립트와 비밀 설정은 저장소에 배포하지 않습니다.

## 백엔드 YAML 설정 추가 검증

`link.yml` 지원 추가 시 설정 테스트 7개와 기존 패킷 테스트 5개를 통과했습니다. 신규 파일 생성·기존 파일 보존·YAML 단독 값·환경 변수와 JVM 옵션 우선순위·잘못된 입력 거부·오류 메시지의 비밀 값 비노출을 확인했습니다. 새 작업 폴더에서 실제 JAR을 실행해 EULA 동의 전 파일 생성을 확인했고, DB 환경 변수와 `-Dlink.*` 없이 YAML만으로 `journal` 부팅을 확인했습니다. DB에서 노드 정원 34·월드 정원 17을 조회해 사용자 설정 적용도 검사했습니다. 9번째 Minecraft 패치 재적용이 성공했습니다.

## MariaDB·SlimeVault 추가 검증 (2026-10-31)

PostgreSQL 17 및 MariaDB 11.4 각각 Redis와 함께 코어 테스트 43개를 실행해 통과했습니다. MariaDB는 `ONLY_FULL_GROUP_BY`도 활성화했습니다. 트랜잭션·소유권·정원·이동 검사와 함께 리터럴 복구 prefix 조회, JVM 시간대와 무관한 임대 시각, S3 실패 시 템플릿 미발행, 리비전 참조 복원·체크섬 검사를 포함합니다. 서버 설정·패킷·명령어 테스트 19개도 통과했습니다.

SlimeVault `a346212c59910682c5440180ea8512e8050a0100`을 직접 빌드해 writer 임대와 SRF 검증을 켠 상태에서 ASP 26.3 실제 월드 파일을 저장하고 정확한 VersionId로 읽었습니다. 잘못된 SRF는 거부됐습니다. 별도 MariaDB DB와 실제 Link JAR·Velocity에서 템플릿 없는 최초 편집 월드 입장, `/spawn`, 블록 변경, 템플릿 발행, 재시작 후 스폰 전환과 블록 복구를 확인했습니다. SQL 월드 payload는 S3 리비전 참조이고 플레이어 데이터는 SQL에 남는 것도 확인했습니다. SlimeVault 중단 시 네이티브 프레임이 실패하여 백엔드가 중단됐습니다.

위 검사는 로컬 단일 SlimeVault 노드와 같은 백엔드 내 이동을 대상으로 합니다. S3를 사용하는 대규모 부하·다중 저장 노드 장애·모든 서버 간 이동 장애 조합·DB 전체 호스트 손실은 검증하지 않았습니다. 기존 운영 제한이 해결됐다는 의미가 아닙니다.

## Velocity YAML 추가 검증 (2026-11-01)

설정 파일 생성·기존 파일 보존·환경 변수 우선순위·비밀 값 비노출·잘못된 YAML 및 중복 키 거부 테스트 5개를 통과했습니다. 실제 Velocity에서 `plugins/link/config.yml` 생성을 확인하고, DB 환경 변수를 제거한 상태에서 YAML만으로 MariaDB 연결과 프록시 초기화가 완료되는 것을 확인했습니다. 최초 기본 파일의 비밀번호는 비어 있으므로 DB 설정 후 Velocity를 재시작해야 합니다.

## 미검증·미구현 범위

정품 클라이언트의 Mojang 인증, 전체 GUI 입력, 임의 플러그인 가상 메뉴, 비동기 변경, 대형 맵의 장시간 혼합 부하, 모든 네트워크 분리·이동 중 장애 조합은 인증하지 않았습니다. 네트워크 전역 지도 저장, 유휴 스폰 정리, 영속 대기열, 스키마 이전·가져오기·백업 복구 도구도 미완료입니다. 따라서 이 문서를 “모든 상황 검증 완료”로 해석하면 안 됩니다.
## 같은 서버 이동 검증 (2026-11-02)

- 현재 서버 스폰 우선 배정과 정원 초과 시 다른 서버 배정, SQL 기록 후 즉시 처리 예약, 예약 실패 시 주기 조회 복구를 회귀 테스트했습니다.
- PostgreSQL과 MariaDB에서 core 테스트 각각 45개 통과, SlimeVault 전용 통합 테스트 1개는 이번 실행에서 제외했습니다. 버킷 Link 테스트 26개와 `linkDist` 빌드가 통과했습니다.
- MariaDB + SlimeVault 테스트 버킷에 Velocity를 통해 접속한 프로토콜 클라이언트로 `/warp tab-shop` → `(3,65,3)`, `/spawn` → `(0,65,0)`을 확인했습니다. 두 이동 모두 재접속 없이 같은 플레이 세션에서 완료됐습니다.
- 이 테스트의 요청부터 위치 패킷 수신까지 시간은 워프 673ms, 스폰 224ms였습니다. 운영 환경 지연 시간을 보장하는 수치는 아닙니다.

## 템플릿 편집 흐름·한국어 명령 검증 (2026-11-02)

- 버킷 Link 테스트 46개 통과: 편집 세션 15개, 템플릿 명령 4개, 자동완성 8개, 기존 설정·패킷·이동 19개. 저장 실패, 발행 실패, 복귀 취소, 복귀 월드 부재, 접속 종료, 중복 요청, 임시 월드, 손상된 세션, 인자·권한을 검사했습니다. `linkDist` 빌드 성공.
- MariaDB + SlimeVault + Velocity 환경에서 한국어 명령으로 작업 월드 생성·입장, 에메랄드 블록 건축, 버전 1 발행·복귀, 발행본 복제 편집, 금 블록 변경, 동일 버전 발행 거부, 버전 2 발행·복귀를 확인했습니다. 버전 1을 다시 복제해 에메랄드 블록이 보존된 것도 확인했습니다.
- 편집 중 `/스폰` 요청은 거부되고 편집이 유지됩니다. 존재하지 않는 월드의 `/링크 저장`은 오류를 반환하고 서버는 계속 동작했습니다.
- 편집 중 접속 종료 후 작업 재개·복귀를 확인했습니다. 별도로 정상 서버 종료·재시작 뒤 영구 작업 월드의 블록과 플레이어 PDC의 원래 복귀 좌표 `(12.5,65,12.5)`가 보존되는 것을 확인했습니다. 강제 종료·DB 장애의 모든 조합을 검증했다는 의미는 아닙니다.
- 실제 프로토콜 자동완성 6건 통과: `/템플릿 편`, 편집 버전, `/링크 템플릿` 발행 버전, `/링크 저`, `영구`, `/워프` 이름.
- 로컬 증거: `build/template-final-test.log`, `build/template-flow-retry.log`, `build/template-resume-test.log`, `build/korean-tab-wire.log`. 테스트 보조 프로그램의 좌표 기대값과 IPC 종료 처리, 재시작 실행 스크립트를 수정한 뒤 해당 검증을 완료했습니다.

## 이벤트 보관 정책 검증 (2026-11-02)

- PostgreSQL·MariaDB에서 core 테스트 각각 49개 통과, SlimeVault 전용 통합 테스트 1개는 이번 단위 테스트 실행에서 제외했습니다. 버킷 Link 테스트 48개와 `linkDist` 빌드가 통과했습니다.
- 보관 기한·배치 제한, 최신 상태·트랜잭션 전체 보존, 미전달 outbox 보존, 전송 완료 알림 정리, 이력 삭제 후 같은 작업 UUID 재시도 및 다른 내용 재사용 거부를 검사했습니다. 두 정리 작업의 동시 실행과 삭제 후 오류 발생 시 롤백도 검증했습니다.
- 실제 MariaDB + SlimeVault 테스트 버킷에서 기본 60초 스케줄러가 과거 작업 3,540건의 이벤트 25,069행을 정리했습니다. 플레이어 현재 상태와 발행된 템플릿의 버전·payload SHA-256은 정리 전후 동일했습니다. 사용자 별도 운영 DB는 접속·변경하지 않았습니다.
- 정리 후 버킷을 정상 종료·재시작해 월드 복구와 기동 완료, RCON 명령 응답을 확인했습니다(`build/yaml-firstboot/retention-restart.stdout.log`).
- 증거: `build/retention-maria-test.log`, `build/retention-postgres-test.log`, `build/yaml-firstboot/retention-live.stdout.log`, `build/retention-live-before.tsv`, `build/retention-live-after.tsv`. 운영 DB 파일 크기의 물리적 축소나 16GB 부하에서의 정리 처리량은 검증하지 않았습니다.

## 2026-11-04 node ownership takeover

- PostgreSQL 17 and MariaDB 11.4: 80 core tests passed per database (one optional S3 integration test skipped); 92 Link server tests passed.
- New coverage: immediate takeover of an active node; cancellation of old moving sessions; preservation of another node's session; stale renew/release rejection; full backend transaction fencing; parallel shared-lock transactions; takeover waiting for active transactions; bound shutdown release; concurrent startup epochs.
- Local MariaDB/S3 runtime: started backend A as test-a (epoch 24), then a second backend on another port with the same ID (epoch 25). The old backend rejected SQL work and stopped on ownership loss. The replacement started and restored the previously saved chest. The old shutdown did not release epoch 25.
- Graceful replacement shutdown immediately persisted RELEASED at epoch 25. Restart without a lease-expiry sleep acquired epoch 26 and reached ready state. No production database was accessed.
- Forced replacement recovers completed checkpoints only, not unsaved memory. Same-ID instances are replacements, not independent servers.
- Evidence: build/ownership-tests.log, build/ownership-maria.log, build/ownership-pg-final.log; build/yaml-firstboot/ownership-first.stdout.log; build/tpa-backend-b/ownership-takeover.stdout.log and ownership-restart.stdout.log.

## 2026-11-04 lease lock-contention follow-up

- Checkpoint validation keeps the session advisory lock but no longer locks the session row. Renewal takes only a short row lock and checks node/epoch/state/DB-clock expiry after acquiring it.
- Transient SQL failures retry twice; expired/replaced ownership still fails closed. This avoids treating one recoverable lock timeout as immediate ownership loss without weakening takeover fencing.
- Added regression tests for renewal during an active checkpoint guard, recovery after a 5-second row-lock timeout, rejection of already expired leases and rejection when expiry occurs while waiting for the row lock.
- NMS patch 0011 stops teleport/group scheduling before plugin disable; late off-thread tasks during shutdown are discarded. The patch reverse-apply check passed.
- Final verification: PostgreSQL 17 and MariaDB 11.4 each passed 84 core tests (one optional S3 test skipped). The 92 Link server tests passed. Final core expiry-wait changes were retested on both databases and included in the rebuilt distribution.
- Live MariaDB/S3 backend test: held the node session row FOR UPDATE for 12 seconds; the server continued responding to RCON and retained epoch 27 ACTIVE with a valid lease after the lock was released. Explicit save followed immediately by shutdown completed; epoch 27 became RELEASED. No teleport polling/thread warning or final checkpoint failure appeared in this run. This is a local zero-player test, not a reproduction of the production plugin set.
- Evidence: build/lease-contention-build.log, build/lease-contention-final-pg.log, build/lease-contention-final-maria.log, build/lease-contention-live-db.txt, build/yaml-firstboot/lease-contention-live.stdout.log.

## 2026-11-04 large checkpoint lock exhaustion

Removed per-state-key advisory locks and replaced read-then-unconditional-upsert with primary-key protected inserts and expected-version conditional updates. All writes, operation receipt and outbox remain atomic. SQL statements are reused and writes are sorted. This also removes the per-key lock and version-read round trips.

PostgreSQL test configuration: max_locks_per_transaction=64, max_connections=100. Regression test inserts and updates 65,536 keys in single checkpoints, asserts exactly one advisory lock for the unguarded commit, checks duplicate-operation replay, and checks complete rollback after a late version conflict. Independent concurrent writers test both initial insertion and updates without session guards that would otherwise serialize them.

Upgrade requires stopping all backends sharing the DB before replacing their JARs; old unconditional writers must not overlap with new conditional writers. No schema/data migration or link.yml change is required.

Verification: 86 core tests passed on each of PostgreSQL 17 and MariaDB 11.4; one optional S3 integration test skipped per run. The maximum-size checkpoint and concurrent unguarded writer regressions passed on both databases. A local MariaDB/S3 backend loaded existing worlds, restored the saved chest, completed save-all and shut down cleanly with no checkpoint failure. This smoke test used zero players and does not measure production plugin performance. Evidence: build/bounded-locks-pg.log, build/bounded-locks-maria.log and build/yaml-firstboot/bounded-locks-live.stdout.log.

## 2026-11-05 progressing saves beyond 120 seconds

- Replaced the fixed 120-second future wait with monotonic stall and total deadlines, shared by tick polling and synchronous startup/shutdown barriers. New link.yml defaults: stall-timeout-seconds=120, total-timeout-seconds=1800; existing saving sections receive missing keys automatically.
- Added 10-second phase/completion/elapsed/idle logs; chunk completion and successful SQL state writes advance progress. Storage exceptions still fail immediately. Unchanged restored SlimeVault chunks compare embedded SHA-256 digests without a second download; restore still verifies downloaded content.
- PostgreSQL and MariaDB: 91 core tests passed each, one optional S3 integration test skipped. Server: 93 Link tests passed. Includes 65,536-state commits, virtual-clock 300-second progress, stall/absolute deadlines, error propagation, checksum comparison without GET and existing-YAML migration.
- Live local MariaDB/S3 test: forced 64 chunks and placed 16,384 stone blocks. Test proxy delayed each PUT by 3 seconds. Logs showed elapsed=130, 140, 150 and 160 seconds with increasing completed counts; RCON still responded. Delay was then removed and the explicit save completed at about 170 seconds. Restart verified stone at both ends of the region; test blocks and force-load flags were cleaned up, and final shutdown saved successfully. The next startup checkpoint completed in 2 seconds; total startup was about 19 seconds. Zero players, no production plugin performance claim.
- Docker was initially stopped; the first DB test attempt failed to connect. After Docker startup, final PostgreSQL/MariaDB runs passed. Evidence: build/save-progress-pg-final.log, build/save-progress-maria.log, build/yaml-firstboot/save-progress-live.stdout.log, build/yaml-firstboot/save-progress-restart.stdout.log and build/progress-proxy.stdout.log.


## 2026-11-07 travel ownership and pre-login reads

- Same-backend routes retain ACTIVE ownership/epoch, use Bukkit teleport cancellation, and acknowledge admission after the player checkpoint. Durable route receipts claim each operation once and replay the original reservation even after completion/reconnect. Remote transfer preparation and arrival issuance share one SQL transaction; READY follows the source removal checkpoint. Connected frozen sources renew their MOVING lease while saving; an accepted destination epoch cannot be renewed/released by the old source.
- Disconnect removes the old backend connection/cache before publishing RELEASED. Velocity waits up to 15 seconds for the previous session, associates pending cleanup with the actual proxy connection, and never steals active inventory. Expired crashed-source ownership reaps pinned reservations before reconnect allocation.
- OfflinePlayer NBT reads use the latest committed DB snapshot without claiming a session. Actual PlayerList login still uses the ownership-gated path. Patch 0012 applies to the NMS PlayerDataStorage read path.
- Final PostgreSQL 17 and MariaDB 11.4 runs: 96 core tests passed per database; one optional S3 test skipped. Server: 98 Link tests passed. New cases cover local epoch preservation, duplicate receipt replay after reconnect, eight concurrent claims, atomic remote rollback, accepted-destination protection, pending-transfer disconnect cancellation, MOVING renewal and expired-source reservation cleanup.
- Actual local MariaDB/S3 backends test-a/test-b plus Velocity 4.3.1 and protocol client: cancelled local teleport isolated to FAILED; same-world local travel; same-backend cross-world travel; /spawn; A-to-B transfer; eight submissions sharing one UUID plus replay after completion; three immediate disconnect/reconnect cycles. Verified seven diamonds, three ender-chest emeralds, level 9 and health 13. Local epoch stayed 64; remote acceptance advanced it to 65; reconnects advanced to 66, 67 and 68. Final session RELEASED, zero reservations and zero pending routes. AsyncPlayerPreLoginEvent probe called OfflinePlayer.getName/getFirstPlayed/getLastPlayed successfully on both backends and repeated login.
- Scope: the exact Bukkit pre-login access pattern behind the BetonQuest error was reproduced; the user's BetonQuest JAR/quest pack was not installed, so full quest behavior is not claimed. No production DB was accessed and this is not a production load benchmark.
- Test fixture corrections: an initial cross-world run entered a peaceful test world and regenerated health to 14, causing the strict test assertion to abort that disposable backend. All test worlds were then set to NORMAL. A second run hit Velocity's default login-rate limit on repeated logins; the final run kept each connection open at least 3.2 seconds, then reconnected immediately after disconnect, without changing proxy rate limits. Final run had no Link ownership/checkpoint/pre-login failures. Windows OSHI performance-counter and upstream version-fetch warnings are unrelated environment warnings.
- Evidence: build/travel-final-pg.log, build/travel-final-maria.log, build/travel-final-build.log, build/travel-wire-pass.log, build/yaml-firstboot/travel-a2.stdout.log, build/tpa-backend-b/travel-b2.stdout.log. Backend SHA-256 67183E84142C701BDF06F5B85E6AEE6CD0B3BCD6C921296D4A7675A8A816E257 matched both tested backend copies and the distribution.

## 2026-11-10 서버 멈춤 차단 + Scala 3.10.0 전환

**정지 정책 변경(선택적 정지).** 게임/플러그인 예외는 일반 Paper와 같이 처리합니다(패킷 → 해당 플레이어 연결 해제, 작업 → 로그). DB·S3 저장 실패와 120초 이상 진행 없는 저장은 서버를 멈추지 않고 5초마다 재시도합니다. 결과를 알 수 없는 커밋은 같은 작업 ID로 먼저 재전송합니다. 노드 lease는 DB 장애 중에도 남은 유효 시간(30초 중 25초)까지 재시도합니다. 정지하는 경우는 소유권 상실(다른 인스턴스 인계, lease 만료, 저널 버전 충돌)뿐입니다.

**메인 스레드 대기 제거.** 서버 간 이동은 다음 체크포인트에서 캡처·동결 후 커밋 완료 시 이어서 진행합니다. 접속 도착 claim·플레이어 데이터 조회(PrepareSpawnTask, 패치 0013), 라우트 status/claim/prepareRemote, 세션 해제·READY·로컬 이동 확인·플레이어 등록 SQL은 별도 스레드에서 실행합니다. 월드 로드는 저장 완료를 틱 차단 없이 기다리고 다운로드·디코딩은 별도 스레드에서 합니다. Velocity 재접속 대기는 작업 스레드를 붙잡지 않습니다. 메인 스레드를 50ms 넘게 붙잡는 SQL은 호출 위치와 함께 경고합니다.

**증분 청크 캡처(패치 0014).** `ChunkAccess.markUnsaved`의 Link 전용 변경 카운터, 인접 청크, 직전 변경 청크, 엔티티·블록 엔티티·예약 틱·PDC·POI가 있는 청크, 로드/언로드, 20라운드 순환 전수 검사로 다시 캡처할 청크를 고릅니다. 저장 실패 후에는 다음 캡처가 전체 캡처입니다.

**Scala 전환.** link-core·link-velocity·`io.linkmc.server`를 Scala 3.10.0(Java 25, LinkCore와 동일 런타임)으로 1:1 변환했습니다. 패치에서 호출하는 `NativeJournal`·`LinkRuntime`은 Java 진입점으로 남고 `LinkServerBridge`(ServiceLoader)로 Scala 구현을 호출합니다. `LinkConfiguration`·`LinkPacketGate`·`ConflictException`·`link-api`는 Java입니다. 서버 JAR은 `scala-library`·`scala3-library_3` 3.10.0을 포함하며 LinkCore가 포함한 런타임과 같은 버전입니다.

**검증.** PostgreSQL 17·MariaDB 11.4 core 테스트 각 98개(S3 전용 1개 제외), Velocity 5개, 서버 Link 93개 통과. 실제 서버(Scala 빌드): 1,296개 강제 로드 청크·블록 레이어·armor stand 300개·상자 40개 상태에서 블록·상자·엔티티 변경 후 강제 종료 → 재시작 시 모두 복구(armor stand 301/301). PostgreSQL 컨테이너 20초 일시 정지 중 TPS 20.0 유지·서버 정지 없음, 장애 중 변경이 복구 후 저장되고 강제 종료 뒤에도 유지. 캡처 시간: 6,368청크 107~196ms → 증분 31~52ms(Java 빌드 측정), 200명 합성 플레이어 캡처 44~55ms. LinkCore 0.1.0-SNAPSHOT JAR을 같은 서버에 설치해 Scala 클래스 충돌(LinkageError 등) 없이 로드됨을 확인(활성화는 LinkCore DB 미설정으로 실패). 실제 200명 클라이언트 부하, 다중 백엔드 Velocity 이동, 25초를 넘는 DB 장애(소유권 보호로 정지)는 이번 검사 범위가 아닙니다.

## 2026-11-10 임시 월드 체크포인트 제외

- 템플릿에서 만든 임시 월드(`link-spawn`, `spawn-*`, `/link create … temporary`)는 체크포인트에서 청크/월드 데이터를 캡처하지 않는다. 크래시 후에는 템플릿에서 다시 만든다. 그 월드에 있던 플레이어 데이터는 계속 저장된다.
- 영구 월드(`/link create … persistent`, API `persistent=true`, 템플릿 편집 월드)와 Link가 모르는 월드는 기존대로 변경 청크를 캡처한다.
- 실서버 강제 종료 테스트: `island-test`(영구) 블록 유지, `link-template-editor`(편집) 블록 유지, `link-spawn`(임시) 블록은 템플릿 상태로 복귀. 캡처는 2 worlds / 2~50청크 / 2~3ms.
- 서버 테스트 93개 통과.
