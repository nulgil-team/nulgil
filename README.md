# 늘길 · 타면 이미 경로가 돼 있는 차

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작의 프로토타입 저장소입니다. 늘 가는 곳의 경로를 시간대별로 준비하고, 평소보다 늦는 날만 출발 전에 짚어 주며, 순정 내비 위에서 동작하는 차량용 앱입니다.

## 바로 실행 (APK)

- 파일: `apk/nulgil-release.apk` (패키지 `kr.routine.app`, minSdk 34, release 서명)
- 대상: Pleos Connect 에뮬레이터(Android Automotive)
- 설치: `adb install -r apk/nulgil-release.apk`
- 처음 실행하면 **시연 모드**(모의 목적지·교통·안내 응답)로 열립니다. `평소 장소 등록`에서 장소와 요일·출발 시간을 고르면 바로 오늘의 경로가 준비됩니다.
- `내 일상 > 연결 시험 > 순정 내비 연결 시험`으로 순정 내비(NaviHelper)와 차량 속성을 쓰는 실제 연결 모드로 바꿀 수 있습니다. 이 모드는 차량의 기어 P·속도 0을 확인할 때만 경로 요청을 허용합니다.

## 구성

| 폴더 | 내용 |
|---|---|
| `core/` | Android에 의존하지 않는 Java 핵심 로직: 규칙·기록 기반 추천(`RoutineRecommender`), 평소 대비 예상 시간 비교(`TrafficAdvisor`), 로컬 저장(`RoutineStore`), 경로 요청 상태 관리(`DepartureController`) |
| `app/` | Android Automotive 차량 앱. 순정 내비 연동(`platform/PleosNaviGateway.java`, NaviHelper 3.0.5), 정차 확인(`platform/PublicParkedGuard.java`, GEAR_SELECTION·PERF_VEHICLE_SPEED·CarUxRestrictions), 화면(`ui/VehicleScreen.java`) |
| `bridge/`, `client/` | 웹 프로토타입이 core를 호출하는 로컬 API와 JavaScript 클라이언트 |
| `frontend/` | 웹 프로토타입(차량 화면과 휴대폰 화면 시연) |
| `checks/`, `app/checks/`, `frontend/tests/` | 자동 검사 |
| `examples/` | 로컬 API 응답 예시 |
| `docs/` | 화면 연결 계약, 날짜·시간대 처리, 활성 경로 확인, 시동 후 실행 조사 등 기술 문서 |

## 빌드 환경

- Windows, JDK 17~22 (Gradle 8.9는 Java 25에서 실행되지 않습니다)
- Android SDK Platform 35, 인터넷 연결(google, mavenCentral, `https://nexus-playground.pleos.ai/repository/maven-releases/`)
- Android: `gradlew.bat :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

## 웹 프로토타입

- Java 17 이상, Node.js 18 이상
- `powershell -ExecutionPolicy Bypass -File BUILD_BACKEND.ps1` → `START_BACKEND.cmd` → `node frontend/dev-server.mjs` → http://localhost:5173

## 자동 검사

- core: `powershell -ExecutionPolicy Bypass -File checks/check-core.ps1` (핵심 로직, 날짜·시간대, 활성 경로, 기록)
- Android 세션·화면 수치: `powershell -ExecutionPolicy Bypass -File app/checks/check-native-source.ps1`
- 웹 흐름: `frontend/tests/verify-offline.cjs`, `frontend/tests/verify-live.cjs` (puppeteer-core와 Chromium 계열 브라우저 필요)

## 구현 상태

- 구현: 추천·예상 시간 비교·로컬 저장·요청 상태 관리(core, 자동 검사 통과), 처음 등록과 오늘의 경로 화면, 이미 안내 중이면 덮어쓰지 않기, 정차 확인, 요청 후 재조회와 중복 요청 방지(Android, 에뮬레이터 실행 확인).
- 미검증: 개발 PC의 에뮬레이터에서 순정 지도 앱이 응답하지 않아 순정 내비의 장소 목록·예상 시간·경로 요청은 실기로 시험하지 못했습니다.
- 구현 목표(코드 없음): 경유지 추가, 휴대폰에서 경로 보내기와 서버, 여행 일정 연결.
- 시연 영상과 웹 프로토타입의 교통·경로·경유지·휴대폰 화면은 시연 데이터입니다.

## 자료 출처

- 글꼴: Asta Sans (SIL Open Font License, `app/src/main/assets/OFL.txt`)
- 지도: OpenStreetMap contributors
