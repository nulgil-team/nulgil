# 늘길 화면

타면 이미 오늘의 경로가 돼 있는 차량용 앱의 화면이다. 2026-11-06 기준으로 BR-05, BR-16, BR-17까지 포함한 로컬 API에 연결했다. 원본 디자인 작업은 `D:\00_HYUNDAI_CONTEST\06_APP_DESIGN_20261104`에 보존한다.

**실제 차량 앱이 아니다.** 로컬 API는 DEMO다. 교통과 안내 응답은 모의 값이고, 순정 내비·지도·폰·서버 전달은 연결돼 있지 않다.

## 실행

처음 한 번만 저장소 루트에서 `BUILD_BACKEND.ps1`을 실행한다. 이후 `frontend\START_DEMO.cmd`를 더블클릭한다. 백엔드, 화면 서버, 브라우저가 차례로 열린다.

수동 실행:

```powershell
.\START_BACKEND.ps1
node frontend\dev-server.mjs
```

화면 주소는 `http://localhost:5173`이다.

| 주소 | 내용 |
|---|---|
| `/` | 시연 패널 포함 |
| `/?present` | 차량 화면만. 영상 녹화용 |
| `/?present&phone` | 폰과 차량 화면 |
| `/?offline` | 백엔드 없이 오프라인 예시 |

첫 접속은 상태를 읽기만 한다. 동의, 예시 이력, 안내 요청을 자동 실행하지 않는다. 시연 데이터가 필요하면 시연 패널의 상태 버튼을 누른다. 패널 버튼은 시연 기록을 지우고 예시 이력으로 다시 준비한다.

## 데이터 출처

| 화면 요소 | 출처 |
|---|---|
| 추천, 보류 사유, 표본 일수 | 로컬 API `recommendation`, Java core 실행 |
| 예상·평소·차이 시간, 강조 여부 | 로컬 API `traffic`, 입력은 모의 값 |
| 안내 시작 가능 여부 | `navigation.canConfirm` |
| 이미 안내 중, 경로 조회 불가 | `navigation.activeRoute`, 입력은 모의 값 |
| 보류 유형, 동률 후보 | `recommendation.holdType`, `candidates` |
| 교통 상태, 비교 날짜 목록 | `traffic.kind`, `traffic.history` |
| 안내 상태 | `navigation.state`, VERIFIED도 모의 응답 확인 |
| 정차 상태 | `vehicle.state`, 모의 값 |
| 경로 성향, 저장 동의, 등록, 이력 | 로컬 API, 기기 내 저장 |
| 들를 곳, 친구 제안, 경유지, 폰 화면 | 화면 시연 데이터. API 없음 |
| 지도, 경로선 | OpenStreetMap, 예시 경로 |

화면은 추천·교통 비교·정차·중복 방지를 계산하지 않는다. `data/core-snapshot.js`는 이전 core 실행 결과로, 백엔드가 없을 때만 쓴다.

## 지키는 규칙

- 시작 버튼은 `navigation.canConfirm`이 참일 때만 열린다.
- `confirm`은 사용자 확인 후 한 번만 보내고 자동 재전송하지 않는다.
- 응답을 잃으면 잠근 채 `getState`로 재조회한다. 재조회도 실패하면 잠금을 유지하고 `다시 연결`을 표시한다.
- null 값은 정보 없음으로 표시하고 0으로 바꾸지 않는다.
- 폰에서 보낸 경로의 자동 시작은 폰 입력을 사용자 확인으로 본다. 차량 앱이 열린 뒤 요청 직전에 현재 경로를 다시 읽고, `canConfirm`이 참일 때만 `confirm`을 한 번 보낸다. 폰 전달 자체는 시연이다. 시동만으로 앱이 열리는지는 미검증이다.
- 개발 서버는 `frontend/`와 공용 클라이언트만 제공한다. 연결 토큰은 파일에 쓰지 않고 메모리에서 전달한다.

## 검사

`puppeteer-core`와 Edge가 필요하다.

```powershell
node frontend\tests\verify-live.cjs <출력 폴더> <시험용 저장 파일>
node frontend\tests\verify-offline.cjs <출력 폴더> --shots=key
```

`verify-live`는 저장 파일을 일부러 변경한다. 반드시 시험용 저장 경로로 띄운 백엔드에서, 새 저장 파일로 실행한다. 최신 결과는 `D:\00_HYUNDAI_CONTEST\03_TEST_RESULTS\20261106_frontend_api_05`이다.

| 검사 | 결과 |
|---|---|
| 실제 API 연결 흐름 | 31건 통과 |
| API 연결 상태의 화면 감사 | 주간·야간 × 전체·분할, 문제 0건 |
| 오프라인 흐름 | 22건 통과 |
| 오프라인 화면 감사 | 172개 조합, 문제 0건 |
| 콘솔 오류 | 0건 |

주요 시험: 안내 버튼 6회 연타에 요청 1회, 새로고침 후 재요청 없음, 화면 44·29·15분과 API 값 일치, 저장 실패와 연결 끊김 구분, 이미 안내 중과 조회 불가에서 요청 0회, 자동 시작 대기 중 안내가 시작되면 요청 0회, 개발 서버가 `.git`·`runs`·저장소 파일을 제공하지 않음.

미검증: 실제 차량, Android 화면, 터치 조작감, 다른 표시 유형. Headless Edge 2560×1440 기준이다.

## 문서

`docs/`의 기획, 사용자 흐름, DFD, 백엔드 요청이 현재 기준이다. 저장소 공통 규칙은 `../docs/06_FRONTEND_HANDOFF.md`와 `../docs/05_Git_작업규칙.md`를 따른다.
