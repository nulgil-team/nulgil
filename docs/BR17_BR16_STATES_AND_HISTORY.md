# BR-17·BR-16 보류 상태와 비교 기록

2026-11-05. 늘길의 로컬 DEMO API에 시연 입력과 구조화된 응답을 추가했다. 차량 앱은 운전자가 여는 흐름을 유지한다. 이 작업은 실제 교통 수집이나 차량 안내 검증이 아니다.

## API 응답

기존 `schemaVersion: "1.0"`, `recommendation.status/source`, `traffic.status`는 유지한다. 아래 필드만 추가한다. 이전 서버에는 새 필드가 없을 수 있으므로 클라이언트 타입은 선택 필드로 선언했다. 화면은 한글 메시지에서 상태를 추정하지 않는다.

| 필드 | 값과 의미 |
|---|---|
| recommendation.holdType | `NONE`, `NOT_ENOUGH`, `TIE` |
| recommendation.candidates | 동률인 `{ destination, samples }` 목록. samples는 목적지별 서로 다른 선택 날짜 수다. 등록 규칙 동률은 0이다. |
| traffic.kind | `NOT_REQUESTED`, `MISSING`, `STALE`, `UNKNOWN_UNITS`, `NO_COMPARE`, `CALM`, `LATE` |
| traffic.history | 비교 후보로 채택한 `{ localDate: "YYYY-MM-DD", minutes }` 목록. 최신 날짜부터 정렬한다. |

`NO_COMPARE`에서 유효한 비교 기록이 1~2일 있으면 그 목록도 제공한다. 아직 중앙값을 사용하지 않았으므로 `baselineMinutes`와 `delayMinutes`는 null이다. `STALE`, `UNKNOWN_UNITS`, `MISSING`에는 비교 목록이 없고 사용할 수 없는 분 값도 null이다. `LATE`는 기존 지연 기준인 5분 이상이면서 중앙값의 20% 이상을 만족한 경우다.

날짜별 목록은 중앙값을 계산한 core의 같은 필터에서 생성한다. 같은 목적지·출발지·측정 기준·모의 여부, 6주 이내, 같은 요일·시간대 ±60분 조건을 만족해야 한다. 현재 현지 날짜를 제외하며, 한 날짜에는 가장 나중에 관측한 유효 기록 하나만 남긴다. 시간대는 `context.zoneId`를 사용한다.

## 재현 순서

개발용 새 저장소에서 실행한다. 아래 `clearHistory()`는 시험 기록을 지우므로 개인 기록이 들어 있는 저장소에는 그대로 실행하지 않는다. 이 준비는 개발 시연 패널에서 명시적으로 실행하며 앱 시작 시 자동 실행하지 않는다.

```javascript
await api.newDemoTrip();
await api.setContext(Date.parse("2026-11-04T08:00:00+09:00"), "Asia/Seoul");
await api.setConsent(true);
const places = (await api.getState()).destinations;

async function prepare(seed) {
  await api.newDemoTrip();
  await api.clearHistory();
  await api.setDemoScenario({ traffic: "NORMAL" });
  await api.seedDemo(seed);
  return api.recommend(true); // 기존 등록 규칙을 이번 검사에서 제외
}
```

| 재현 상태 | 준비 후 호출 | 응답 기준 |
|---|---|---|
| 기록 부족 | `prepare({ days: 2 })` | holdType=NOT_ENOUGH, samples=2, candidates=[] |
| 후보 동률 | `prepare({ days: 3, destinationIds: places.map(p => p.id) })` | holdType=TIE, 목적지별 samples=3, 확인 불가 |
| 비교 기록 부족 | `prepare({ days: 3, estimateDays: 2 })` → `refreshTraffic()` | kind=NO_COMPARE, history 2개, 중앙값·차이 null |
| 오래된 교통 | `prepare({ days: 3 })` → `setDemoScenario({ traffic: "STALE" })` → `refreshTraffic()` | kind=STALE, history=[], 예상·중앙값·차이 null |
| 단위 미확인 | `prepare({ days: 3 })` → `setDemoScenario({ traffic: "UNKNOWN_UNITS" })` → `refreshTraffic()` | kind=UNKNOWN_UNITS, history=[], 예상·중앙값·차이 null |

각 준비·추천·조회는 안내를 발행하지 않는다. `confirm` 자동 호출·재시도는 추가하지 않았다.

`seedDemo({ days, estimateDays, destinationIds })`는 선택 기록 일수와 교통 기록 일수를 별도로 받는다. 두 일수는 0~6 정수이며, estimateDays를 생략하면 days와 같다. 날짜는 현재 현지 시각에서 주 단위로 거슬러 생성한다. destinationIds는 현재 destinations 목록의 고유 id 배열이며 생략하면 첫 목적지를 사용한다. HTTP form에서는 쉼표로 구분한 문자열이다. 새 인자를 사용하면 등록 규칙을 만들지 않는다. 기존 `seedDemo()`와 `seedDemo({ signal })`는 등록 규칙과 3일씩의 이력을 만드는 동작을 유지한다. 새 인자의 취소 옵션은 `seedDemo(seed, { signal })`로 전달한다.

주입은 기존 기록에 추가하는 동작이다. 같은 조건의 재주입은 중복 날짜를 부풀리지 않으며 일수를 줄인다고 기존 날짜를 삭제하지 않는다. 재현 상태를 정확히 만들 때는 위 prepare 절차로 시험 기록을 비운다. 잘못된 일수·목적지·중복 목적지는 HTTP 400으로 거부한다.

## 수집 동의 해제

`setConsent(false)` 응답과 이후 GET·추천·교통 새로고침에서는 `recommendation.candidates`와 `traffic.history`가 빈 배열이다. 지난 선택 기록을 새 추천에 사용하지 않고, 교통 중앙값·지연 값도 비운다. 아직 안내하지 않은 기록 기반 추천도 비운다. 목적지가 없어진 상태의 교통 조회는 NOT_READY이며, 직접 선택·등록한 장소의 현재 모의 교통 조회는 사용할 수 있다.

동의 해제는 기존 파일의 기록 삭제와 다르다. counts의 저장 개수는 남을 수 있으며, 사용자가 다시 동의한 뒤 조회하면 기존 기록을 사용할 수 있다. 삭제는 기존 `clearHistory()` 명령으로 분리한다. 동의 해제 저장에 실패한 경우에도 이번 실행에서는 목록을 숨기며 새 안내를 차단하는 기존 규칙을 유지한다.

## 검사와 화면 인계

| 검사 | 실제 결과 |
|---|---|
| core·날짜/시간대·활성 경로 회귀 | 45 + 25 + 36 통과 |
| 새 보류·기록·시드 core 검사 | 18 통과 |
| presentation 서비스 검사 | 145 assertions 통과 |
| Java 실제 HTTP 검사 | 248 assertions 통과 |
| JavaScript 클라이언트 / 실제 HTTP 연동 | 9 / 37 통과 |
| TypeScript 7.0.2 strict 타입 검사 | 통과, 잘못된 시드·시나리오의 타입 오류도 확인 |
| PC Swing 회귀 | 컴파일 및 25건 통과 |
| 웹 verify-live | Chrome 실제 연결 25개 흐름 통과, console/HTTP 오류 0 |
| 웹 레이아웃 검사 | 15상태 × 주간/야간 × 전체/분할 창 = 60조건, 자동 검사 문제 0 |

비교 목록과 중앙값이 같은 필터에서 나오는지, 날짜 중복·출발지·기준·모의 여부·요일·시간·과거/미래 필터를 확인했다. 동의 해제 직후와 GET·추천·교통 새로고침, 재동의, 잘못된 시드 입력, 저장 실패, 기존 BR-05 요청 0/1/0을 함께 검사했다. 다섯 상태를 재현하는 동안 추가 안내 요청은 0회였다. 이는 로컬 DEMO gateway 횟수다.

실제 HTTP 응답을 `examples/05-not-enough.json`부터 `11-unknown-units.json`까지 저장했다. examples의 counts.navigationRequests는 전체 검사 프로세스의 누적값이며, 각 시나리오 요청 수는 전후 차이로 검사했다. 연결 토큰과 저장 파일은 저장소에서 제외했다.

실행 명령은 `checks/check-core.ps1`, `checks/verify-presentation.ps1`, `BUILD_BACKEND.ps1`, `CHECK_BRIDGE.ps1`, `CHECK_CLIENT_INTEGRATION.ps1`, `BUILD_PC.ps1`, `CHECK_DESKTOP.ps1`이다. 타입 검사는 TypeScript가 설치된 환경에서 아래처럼 실행한다.

```text
tsc --strict --noEmit --module NodeNext --moduleResolution NodeNext --target ES2022 --lib ES2022,DOM client/checks/contract-types.mts
```

검사 로그와 브라우저 캡처는 로컬 작업 복사본의 evidence에 보관했다. 새 저장소와 새 실행 디렉터리로 검사했으며 실제 사용자 저장 기록은 사용하지 않았다.

화면 담당의 08 checkout과 브랜치는 직접 수정하지 않았다. 기준 커밋 `3f2cc4763fcde1dc365b5d9ddd3c68131978a11e`의 런타임 복사본에 연결 변경을 적용해 `verify-live`에서 검사했다. [화면 적용 안내와 패치](integration/README.md)를 제공한다. 날짜별 목록과 후보 화면의 한글·날짜·선택 일수도 캡처에서 확인했다. 이 결과는 Android 기기에서 화면이 실행됐다는 증거가 아니다.

최종 반영 전에 SSD가 연결 해제됐고 사용자가 현재 재연결할 수 없다고 확인했다. 따라서 D의 07·08 checkout은 갱신하지 않았다. C의 별도 백엔드 checkout에서 기존 비공개 저장소에 변경을 반영한다. Android SDK에 접근할 수 없어 이번 변경의 Android 컴파일은 미검증이다. 공용 Java 코드의 PC 컴파일은 통과했지만 Android 컴파일을 대신하지 않는다. SSD 재연결 후 D의 07 checkout을 최신 커밋으로 갱신하고 unsigned Java 컴파일을 확인해야 한다. 순정 내비·지도·실차·경유지 A/B/C는 이번 검사 범위에 없다.
