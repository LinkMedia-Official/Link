# Link

**Minecraft 26.3 · 바닐라 월드 · PostgreSQL/MariaDB · Redis · Velocity**

Link는 AdvancedSlimePaper `dev/26.3`을 기반으로 개발하는 패치 방식의 서버 포크입니다. 월드는 일반 바닐라 월드 파일로 저장하고, 플레이어 데이터(인벤토리·통계·발전 과제·세션)는 PostgreSQL 또는 MariaDB에 저장하며, Velocity로 여러 백엔드 사이의 첫 입장과 서버 간 이동을 조정합니다.

> **개발 단계이며 현재 운영 투입 불가입니다.** 51명 부하에서 TPS 저하, 재접속 시 탑승 관계 유실, 서버 간 지도 픽셀 누락을 확인했습니다. 플레이어 데이터(DB)와 월드 파일은 따로 저장되므로 강제 종료 후 상자와 인벤토리의 시점이 다를 수 있습니다. `strict` 모드는 실행을 거부하며 `journal`은 격리된 테스트 환경용입니다. [검증 결과](docs/validation.md)를 먼저 확인하세요.

## 구현 범위

| 영역 | 현재 동작 |
| --- | --- |
| 월드 | Paper가 `level-name` 월드(네더·엔드 포함)를 바닐라 방식으로 로드·저장. 스코어보드·지도 등 SavedData도 바닐라 파일 |
| 첫 입장 | 각 백엔드가 기본 월드를 입장 채널로 등록. Velocity가 살아 있는 노드로 배정하고 저장된 위치(바닐라 배치)에 입장 |
| 플레이어 티피 | `/티피요청`, `/티피여기`, 수락·거절·취소. SQL 요청 저장, 한국어/영어 탭 완성, 서버 간 이동 |
| 서버 이동 | 원본 체크포인트 → 소유권 동결 → 입장권 → Velocity 연결 → 목적지 소유권 획득 및 예약 좌표 입장 |
| 유틸리티 | 이동·비행 속도, 비행 토글, 회복·허기. 한국어/영어 명령과 탭 추천 |
| 플레이어 저장 | 메모리에서 게임 진행, 기본 30초 자동 저장. 버전·재시도 중복·소유권 epoch 검사 후 SQL에 순서대로 커밋 |
| Redis | SQL outbox 알림 전달. 장애 시 기록을 유지하고 별도 작업에서 재시도 |
| 플러그인 API | 티피·이동, 데이터 조회·커밋, 세션, 네이티브 트랜잭션, 유틸리티 |

워프, `/spawn`, 스폰 채널, 템플릿(명령·GUI·보호·발행), `/link` 관리 명령, Link 관리 Slime 월드와 청크 체크포인트는 제거됐습니다. 월드 생성·로드는 일반 Bukkit API(`WorldCreator`)를 사용합니다.

이전 빌드가 DB에 저장한 영구 월드는 첫 시작 때 한 번 자동으로 바닐라 파일로 내보냅니다. 자세한 내용은 [설치 안내](docs/setup.md#이전-빌드의-월드-자동-내보내기)를 확인하세요.

새 이벤트 이력은 기록하지 않습니다. 기존 이벤트는 기본 24시간 보관 후 정리하며 최신 상태·중복 실행 방지 기록은 유지합니다. [DB 증가 원인과 정리 정책](docs/storage-retention.md)을 확인하세요.

## 문서

| 문서 | 내용 |
| --- | --- |
| [설치와 실행](docs/setup.md) | 빌드, DB·Velocity 설정, 모드, 월드 내보내기, 명령어 |
| [설계와 저장 경계](docs/link-implementation.md) | 네이티브 프레임, 최신 상태 저장, 이동, 장애 동작 |
| [플러그인 API](docs/plugin-api.md) | 의존성 설정, 비동기 예제, 서비스별 계약 |
| [Link API 의존성](docs/link-api.md) | GitHub Packages에서 API 받기 |
| [검증 결과](docs/validation.md) | 통과·실패·미검증 항목, 재현 방법, 부하 수치 |
| [개발 가이드](CONTRIBUTING.md) | 패치 관리, 테스트, 이슈·PR 작성 |

## 빌드

Java 25와 Git이 필요합니다. 저장소 루트에서 실행합니다.

```powershell
.\gradlew.bat applyAllPatches
.\gradlew.bat linkDist
```

Linux/macOS에서는 `./gradlew`를 사용합니다. 결과는 `build/dist/`에 생성됩니다.

| 파일 | 용도 |
| --- | --- |
| `Link-26.3-dev.jar` | Link 백엔드 서버 |
| `Link-Velocity-dev.jar` | Velocity의 `plugins/`에 설치하는 라우팅 모듈 |
| `Link-API-dev.jar` | 플러그인 컴파일용 API. 서버에 설치하는 플러그인이 아님 |

첫 실행 시 서버 작업 폴더에 `link.yml`이 생성되며 기본 모드는 `disabled`입니다. [실행 안내](docs/setup.md)에 따라 YAML의 DB·노드 설정과 modern forwarding을 준비하고 `mode: journal`을 설정해야 합니다. 기존 환경 변수와 `-Dlink.*` 옵션도 지원합니다. Upstream 버전 문자열의 `stable`은 Link의 운영 안정성을 뜻하지 않습니다.

## 검증 상태

2026-11-15 바닐라 월드 전환 후 서버 Link 단위 테스트 33개가 통과했습니다. 단일 백엔드 실제 서버에서 이전 DB의 월드 2개 내보내기와 재시작 후 유지를 확인했습니다(서버 간 이동은 미확인). 이전 부하 검사에서는 51명 접속 시 1분 평균 TPS가 4.6까지 하락했습니다. 테스트 성공이 모든 게임플레이와 플러그인 조합의 무손실을 보장하지는 않습니다.

## Upstream과 라이선스

Link는 [AdvancedSlimePaper](https://github.com/InfernalSuite/AdvancedSlimePaper)와 Paper의 작업을 기반으로 합니다. 기존 `aspaper-*` 모듈명과 패키지는 패치 호환성을 위해 남아 있습니다. 원본 저작권·라이선스 고지를 유지하며 [LICENSE](LICENSE)와 [기여자](CREDITS.md)를 참고하세요.

### 이동 및 접속 소유권

같은 서버의 티피는 플레이어 세션을 유지하고, 서버 간 이동만 저장 완료 후 소유권을 전달합니다. 이동 UUID별 DB 영수증으로 중복 실행을 막으며 빠른 재접속은 이전 연결의 저장과 해제를 기다립니다. 접속 전 OfflinePlayer 조회는 마지막 저장 데이터를 읽으며 소유권을 가져오지 않습니다. 업데이트 시 백엔드와 Link Velocity 플러그인을 함께 교체하세요. 자세한 동작과 테스트 범위는 docs/setup.md, docs/validation.md를 참고하세요.
