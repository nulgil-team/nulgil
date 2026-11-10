# 화면 연동 계약

`DemoBackend(Path storage, ZoneId zone)`는 Java 17 이상에서 사용할 수 있는 로컬 DEMO 서비스다. 새 화면은 `snapshot()`으로 현재 상태를 읽고 `command(operation, args)`로 변경을 요청한다. 두 메서드 모두 불변 `BackendSnapshot`을 반환한다. 생성자는 저장 파일을 읽지 못하면 `IOException`을 던진다. 명령 오류는 `BackendException.code()`와 `getMessage()`로 구분한다.

현재 시각은 자동으로 흐르지 않는다. 최초 `now`는 `DemoFixtures.NOW`, 즉 `2026-11-04T08:00:00+09:00`이며 시간대는 생성자에 전달한 값이다. `setContext`로 날짜와 시간대를 함께 바꾼다. 날짜·요일·분은 해당 순간과 시간대에서 계산한다. `snapshot()`과 교통 새로고침은 기록을 추가하지 않는다.

| 명령 | 인자 | 동작 |
|---|---|---|
| setContext | now, zoneId 필수 | epoch milliseconds와 ZoneId를 적용하고 기존 제안을 비운다. |
| selectDestination | id, subId 필수 | 현재 목적지 목록의 같은 id/subId를 선택한다. |
| setPreference | value 필수 | RECOMMENDED, SHORT_TIME, COMFORTABLE 중 하나를 저장한다. |
| setConsent | enabled 필수 | 정확히 true 또는 false. 최초 값은 false다. |
| registerRoutine | id, subId 필수 | 현재 시간대의 요일·분에 목적지를 등록한다. |
| recommend | historyOnly 선택 | 기본 false. true면 등록 규칙을 제외하고 기록으로 추천한다. |
| refreshTraffic | 없음 | 선택 목적지의 모의 교통 정보를 조회한다. 기록은 추가하지 않는다. |
| refreshActiveRoute | 없음 | 현재 경로를 다시 조회해 navigation.activeRoute에 반영한다. 조회로 발생하는 안내 요청은 0회다. |
| confirm | 없음 | 정차·경로 비활성 상태를 확인하고 안내를 한 번 요청한다. 성공 확인 후 동의가 있으면 선택과 유효한 교통 정보를 기록한다. |
| dismiss | 없음 | 아직 요청하지 않은 제안을 넘긴다. |
| clearHistory | 없음 | 선택·교통 기록을 지운다. 등록 규칙·선호·동의는 유지한다. |
| seedDemo | days, estimateDays, destinationIds 선택 | 동의가 필요하다. 인자 없는 기존 호출은 등록 규칙과 3일씩의 이력을 추가한다. 인자가 있으면 규칙 없이 모의 기록만 추가한다. days/estimateDays는 0~6 정수, 기본값 3/같은 days. destinationIds는 현재 목록의 고유 id를 쉼표로 구분하며 기본 첫 목적지다. |
| setDemoScenario | traffic, parked, failNext, activeRoute 중 하나 이상 | traffic은 NORMAL/CONGESTED/MISSING/STALE/UNKNOWN_UNITS, parked와 failNext는 true/false, activeRoute는 ACTIVE/INACTIVE/UNAVAILABLE. activeRoute를 지정하면 즉시 조회 결과를 갱신한다. 생략한 값은 유지한다. |
| newDemoTrip | 없음 | 모의 활성 안내·실패 예약·제안을 초기화한다. 날짜·시간대·주행 여부·교통 시나리오와 저장 기록은 유지한다. |

알 수 없는 인자, 누락된 필수 인자, 잘못된 enum/boolean은 거절한다. 안내 요청 후에는 성공 여부와 관계없이 목적지·시간·선호·추천을 변경하거나 같은 이동을 재요청할 수 없다. `newDemoTrip`만 새 요청을 허용한다. 앱 재실행 역시 실제 차량 상태 복원이 아닌 새 DEMO 세션이다.

Snapshot은 `schemaVersion=1.0`, `mode=DEMO`를 포함한다. `recommendation.destination`이 null이면 확인 버튼을 표시하지 말고 `navigation.canConfirm`을 최종 기준으로 삼는다. 교통 값과 기준값이 없으면 null이며, 0분으로 표시하면 안 된다. 교통의 source 및 차량의 source는 DEMO다. `liveNavigation`, `mapGeometry`, `etaPerPreference`는 모두 false다. 경로 선호를 바꿔도 선호별 ETA를 계산하지 않는다. `counts.navigationRequests`는 현재 프로세스가 보낸 모의 요청 횟수이며 재시작 시 초기화된다.

`notice`는 저장 오류 등 안내 결과와 별개의 문제를 담는다. 저장 실패가 있어도 모의 안내 성공을 실패로 바꾸거나 자동 재요청하면 안 된다. 수집 해제 저장이 실패하면 `CONSENT_NOT_SAVED`를 반환하고 이번 실행의 수집과 새 안내 요청을 막는다. 저장 실패이므로 디스크에는 이전 동의값이 남을 수 있다. 재시작 전 저장 문제를 해결하고 해제 상태를 확인해야 한다. 기존 파일을 외부에서 수정한 경우 서비스를 자동 재시작해서 덮어쓰지 않는다.

기록의 목적지는 현재 목록의 id/subId와 대조한다. 확인되지 않은 저장 목적지는 `UNRESOLVED`이며 안내할 수 없다. 교통 비교와 추천 판단은 기존 `TrafficAdvisor`와 `RoutineRecommender`를 호출한다.

### 현재 경로와 이번 이동의 요청 결과

서비스를 열 때 현재 경로를 한 번 조회한다. 상태 화면을 다시 그릴 때는 GET /v1/state로 마지막 조회 결과를 읽는다. 최신 경로가 필요하면 refreshActiveRoute를 호출한다.

| navigation.activeRoute | 안내 발행 전 화면 | confirm 결과 |
|---|---|---|
| ACTIVE | ALREADY_GUIDING · 이미 안내 중 | HTTP 409 ALREADY_GUIDING, 요청 0회 |
| INACTIVE | 목적지 선택·정차 등 조건을 모두 만족하면 canConfirm=true | 사용자가 확인한 뒤 비활성을 다시 확인하면 요청 1회 |
| UNAVAILABLE | ROUTE_UNAVAILABLE · 현재 경로 확인 보류 | HTTP 409 ROUTE_UNAVAILABLE, 요청 0회 |

안내 발행 후에는 navigation.state로 이번 요청의 결과(VERIFIED 또는 UNCONFIRMED)를 유지한다. 이후 경로 재조회는 navigation.activeRoute만 갱신한다. 같은 이동에서 confirm을 반복하면 HTTP 409 TRIP_LOCKED이며 추가 발행은 0회다. counts.navigationRequests는 프로세스 누적값이므로 시나리오별 발행 횟수는 확인 전후 차이로 측정한다.

서비스 회귀 검사는 `checks/verify-presentation.ps1`로 실행한다. 매 실행은 새로운 evidence 폴더에 컴파일 로그, 검사 로그, 테스트용 저장 파일을 남긴다.

BR-17·BR-16의 추천 보류는 `recommendation.holdType/candidates`, 교통 상태와 날짜별 비교 기록은 `traffic.kind/history`로 제공한다. 기존 status는 유지한다. 수집 동의를 해제하면 두 목록은 즉시 빈 배열이고, 과거 기록에 기반한 추천과 중앙값 계산도 중지한다. 상세 코드와 재현 순서는 [보류 상태와 비교 기록](../../../../../../../docs/BR17_BR16_STATES_AND_HISTORY.md)에 있다.
