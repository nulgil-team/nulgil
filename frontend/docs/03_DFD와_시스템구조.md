# DFD와 시스템 구조

작성 2026-11-05, 로컬 API 연결 반영. 기준은 `codex/backend-v2`의 현재 코드다. 이 제품은 `여기 들러`의 확장판이지만 들를 곳 기능의 초대 웹, 서버, 경유지 요청은 현재 코드에 없다. 3장은 현재 구현, 8장은 들를 곳까지 포함한 목표 구조다.

## 1. 시스템 경계

앱 하나가 차량 안에서 동작하며 모든 기록은 앱 저장소에 남는다. 외부와의 접점은 순정 내비 서비스와 차량 상태뿐이다.

## 2. DFD Level 0

```mermaid
flowchart LR
  D[운전자] -- 수락, 넘기기, 성향 선택, 저장 허용 --> S((늘길 앱))
  S -- 추천, 평소 대비 시간, 안내 상태 --> D
  N[순정 내비] -- 최근 목적지, 즐겨찾기, 예상 시간, 경로 상태 --> S
  S -- 예상 조회, 안내 요청, 재조회 --> N
  V[차량 상태] -- 기어, 속도, 주행 제한 --> S
```

## 3. DFD Level 1

```mermaid
flowchart TB
  D[운전자]
  N[순정 내비]
  V[차량 상태]

  P1((P1 장소 가져오기))
  P2((P2 목적지 추천))
  P3((P3 교통 비교))
  P4((P4 정차 확인))
  P5((P5 안내 요청과 검증))
  P6((P6 기록 관리))

  DS1[(D1 평소 장소 규칙)]
  DS2[(D2 선택 이력)]
  DS3[(D3 예상 시간 이력)]
  DS4[(D4 설정: 선호, 저장 동의)]
  DS5[(D5 발행 저널)]

  N -- 최근 목적지, 즐겨찾기 --> P1
  P1 -- 현재 장소 목록 --> P2
  DS1 -- 등록 규칙 --> P2
  DS2 -- 최근 6주 선택 --> P2
  P2 -- 추천 또는 보류 사유 --> D
  D -- 목적지 확정 --> P3
  N -- 현재 예상 시간 --> P3
  DS3 -- 같은 기준 과거 값 --> P3
  P3 -- 평소 대비 차이 --> D
  P3 -- 동의 시 예상 시간 --> P6
  D -- 안내 시작, 성향 --> P4
  V -- 기어, 속도, 제한 --> P4
  P4 -- 정차 확인됨 --> P5
  P4 -- 보류 사유 --> D
  DS4 -- 기본 성향 --> P5
  P5 -- 요청 전 기록 --> DS5
  P5 -- 경로 요청, 재조회 --> N
  N -- 완료 이벤트, 목적지, 옵션 --> P5
  P5 -- 안내 상태 --> D
  P5 -- 확인된 선택 --> P6
  D -- 등록, 저장 끄기, 이력 지우기 --> P6
  P6 --> DS1
  P6 --> DS2
  P6 --> DS3
  P6 --> DS4
```

### 처리와 코드 대응

| 처리 | 현재 구현 | 상태 |
|---|---|---|
| P1 | `NaviGateway.loadDestinations` | 데모 동작. 실제 어댑터 미검증 |
| P2 | `RoutineRecommender` | core 검사 통과 |
| P3 | `NaviGateway.estimate`, `TrafficAdvisor` | core 통과. 실제 ETA는 단위 미확인으로 `minutes=-1` |
| P4 | `NaviGateway.isParked`, `PublicParkedGuard` | 실제 실행 미검증 |
| P5 | `DepartureController`, `PleosNaviGateway` | core 통과. 실제 안내 미검증 |
| P6 | `RoutineStore` | core 검사 통과 |

### 데이터 저장소

| ID | 내용 | 위치 | 비고 |
|---|---|---|---|
| D1 | 장소, 요일, 시각 | 앱 저장소 | 이력 지우기 후에도 유지 |
| D2 | 확인된 선택 최대 200건 | 앱 저장소 | 운행 완료가 아님. 동의해야 저장 |
| D3 | 예상 시간 최대 200건 | 앱 저장소 | 출발 구역, 측정 기준, 모의 여부 포함 |
| D4 | 경로 선호, 저장 동의 | 앱 저장소 | 동의 기본값 꺼짐 |
| D5 | 안내 발행 기록 | 앱 저장소(실제 어댑터) | 재실행 후에도 유지, 재발행 차단 |

## 4. 시스템 구조

```mermaid
flowchart TB
  subgraph UI[화면 계층]
    W[웹 화면 frontend: 시연용]
    A[Android 앱 화면: 차후 재구현]
    PC[Swing 화면: 기능 검사용]
  end
  subgraph CORE[core: 순수 Java]
    R[RoutineRecommender]
    T[TrafficAdvisor]
    S[RoutineStore]
    C[DepartureController]
  end
  subgraph GW[NaviGateway 경계]
    DN[DemoNaviGateway]
    PN[PleosNaviGateway: 실행 미검증]
  end
  W --> CL[routine-client]
  CL --> BR[로컬 HTTP API: DEMO]
  BR --> DB[DemoBackend]
  DB --> CORE
  SN[core-snapshot.js: 오프라인 예시] -.-> W
  PC --> CORE
  A --> CORE
  C --> GW
  PN --> NAV[순정 내비 서비스]
  PN --> PG[PublicParkedGuard]
  PG --> VH[공개 차량 속성]
```

웹 화면은 로컬 API를 거쳐 실제 Java core를 호출한다. 이 API는 같은 PC의 개발·시연용이며 차량 안에서 쓰는 구조가 아니다. Android 앱은 HTTP 없이 core를 직접 쓴다. 점선은 백엔드가 없을 때만 쓰는 오프라인 예시다.

## 5. 프로토타입의 데이터 출처

| 화면 요소 | 출처 | 구분 |
|---|---|---|
| 추천 결과, 보류 사유, 표본 일수 | API `recommendation` | core 실행 |
| 평소·예상·차이 시간, 강조 여부 | API `traffic` | core 실행, 입력은 모의 |
| 시작 가능 여부, 안내 상태, 요청 횟수 | API `navigation`, `counts` | core 실행, 응답은 모의 |
| 동의, 선호, 규칙, 이력 | API 명령과 `RoutineStore` | core 실행 |
| 정차 상태 | API `vehicle` | 모의 값 |
| 출발 시 도착 시각 | 시각 + 예상 시간 표시 계산 | 화면 계산 |
| 이미 안내 중, 경로 조회 불가 | API `navigation.activeRoute` | core 실행, 입력은 모의 |
| 보류 유형, 동률 후보, 비교 날짜, 교통 상태 | API `recommendation`, `traffic` | core 실행, 입력은 모의 |
| 들를 곳, 친구 제안, 경유지, 폰 | 화면 시나리오 | 시연 데이터 |
| 목적지 주변 지도 | OpenStreetMap | 실제 지리 자료, 순정 지도 아님 |
| 예시 경로와 출발지 | 도로망 최단거리 계산 | 예시. 순정 경로 아님 |
| 시간 저장, 화면 전환 | 프로토타입 상태 | 시연 전용 |

## 6. Android로 옮길 때의 경계

```text
RoutineScreen (화면)
  └ RoutineUiState (BR-01에서 정의)
      ├ RoutineRecommender
      ├ TrafficAdvisor
      ├ RoutineStore
      └ DepartureController → NaviGateway
```

화면은 `RoutineUiState`만 그린다. 웹 화면이 쓰는 API 스냅샷 `BackendSnapshot`이 이 역할의 기준이다. 시연 화면의 상태 키(`04` 시나리오)와 같은 이름을 쓰면 디자인 검수 항목을 그대로 재사용할 수 있다.

## 7. 계획 요소

아래는 현재 구현이 아니라 제안이다. 들를 곳은 8장에 따로 정리한다.

| 요소 | 조건 |
|---|---|
| 앱 자동 시작, 시동 시 제안 | 공개 API 확인 후 |
| Gleo 음성 연계 | Gleo Interface 가이드 검토 후 |
| 휴대폰 사전 알림 | 차량 앱 검증 뒤 별도 판단 |
| 출발 시각 제안 | 예측 정확도 근거 확보 후 |

## 8. 들를 곳을 포함한 목표 구조

이 장은 구현 전 목표다. 들를 곳의 원래 설계는 `현대차_출품진행_20261104/01_시스템설계.md`에 있다. 프로토타입은 이 흐름을 예시 데이터로 보여 준다.

제품의 중심은 차량 앱이다. 폰은 탑승 전에 미리 보내는 빠른 길이며 없어도 제품이 완성된다. 안내 요청은 차량 앱이 정차를 확인한 뒤 보낸다. 폰에서 보낸 경우 그 입력을 운전자 확인으로 보고 탑승 시 자동 요청하되, 잠깐 취소할 수 있다.

```mermaid
flowchart LR
  U[운전자 폰: 시연 화면, 합격 후 모바일 웹] -- 차에 경로 보내기 --> SV[경로 전달 서버: 미구현]
  F[친구 폰 웹] -- 들를 곳 --> SV
  SV -- 오늘의 경로, 친구 제안 --> U
  SV -- 준비된 경로 --> C[차량 앱]
  C -- 받음, 안내 결과 --> SV
  C -- 안내 요청 --> N[순정 내비]
  C -- 동의한 일상 요약 --> SV
```

폰이 오늘의 경로를 보여 주려면 추천 결과와 교통 정보가 폰까지 와야 한다. 현재 추천과 기록은 차량 앱 안에만 있고 교통 조회는 순정 내비를 거치므로, 차가 꺼져 있을 때 폰이 무엇을 보여 줄 수 있는지는 미확인이다(BR-08). 시동 후 앱 자동 실행 가능 여부도 미확인이다(BR-15).

```mermaid
flowchart TB
  F[친구] -- 지도 링크 --> W[초대 웹: 미구현]
  W --> P7((P7 링크 해석: 미구현))
  P7 -- 좌표 확정 핀 --> DS6[(D6 이동별 제안: 서버, 미구현)]
  P7 -- 보류 사유 --> F
  DS6 -- 대기 제안 --> P8((P8 제안 수신))
  P8 -- 들를 곳 카드 --> D[운전자]
  D -- 경로에 넣기, 넘기기 --> P9((P9 경유지 요청과 검증))
  P4((P4 정차 확인)) --> P9
  P9 -- 출발 전: 목적지와 함께 요청 --> N[순정 내비]
  P9 -- 안내 중: addWaypoint --> N
  N -- 목적지, 경유지 재조회 --> P9
  P9 -- 결과 --> D
  P9 -- 상태 --> DS6
```

| 항목 | 현재 |
|---|---|
| `addWaypoint`, `getWaypointInfo` | SDK 선언 확인. 외부 좌표 등록은 시험 A/B/C 미실행 |
| 출발 전 경유지 포함 요청 | SDK 문서 확인. core의 `NaviGateway.start`에는 경유지 인자가 없음 |
| 초대 웹, 제안 저장소 | 미구현. 차량 위치와 숙소 주소를 서버로 보내지 않는 설계 유지 |
| 제안 상태 | 설계만 있음: PENDING, REQUESTED, ADDED, UNCONFIRMED, FAILED, DISMISSED, EXPIRED |

프로토타입에서 들를 곳 관련 상태는 core 실행 결과가 아니라 화면 시나리오다.
