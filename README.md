# 늘길 · 타면 이미 경로가 돼 있는 차

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작의 프로토타입 저장소입니다. 늘 가는 곳의 경로를 시간대별로 준비하고, 평소보다 늦는 날만 출발 전에 짚어 주며, 순정 내비 위에서 동작하는 차량용 앱입니다.

## 바로 실행 (APK)

- 파일: `apk/nulgil-release.apk` (패키지 `kr.routine.app`, minSdk 34, release 서명). 서명 키는 저장소에 없으므로 이 APK는 저장소만으로 다시 만들 수 없습니다. 저장소에서는 아래 debug 빌드를 사용합니다.
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

`docs/`와 `frontend/docs/`의 문서는 작성일(2026-11-04~06) 당시의 작업 기록입니다. 그 뒤의 구현 상태는 이 README의 [구현 상태](#구현-상태)가 기준입니다. 문서에 나오는 `evidence/`, `dist/` 폴더와 커밋 번호는 이전 작업 폴더의 것이라 이 저장소에는 없습니다. 같은 결과는 [자동 검사](#자동-검사) 명령으로 새로 만들 수 있습니다.

## 빌드 환경

- Windows, JDK 17~22 (Gradle 8.9는 Java 25에서 실행되지 않습니다)
- PowerShell 스크립트는 `JAVA_HOME`의 JDK를 쓰고, 없으면 `C:\Program Files\Java\jdk-22`를 찾습니다. 다른 JDK는 `-JdkPath <JDK 폴더>`로 지정합니다.
- Android SDK Platform 35, 인터넷 연결(google, mavenCentral, `https://nexus-playground.pleos.ai/repository/maven-releases/`)
- Android: `gradlew.bat :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

## 웹 프로토타입

- Java 17 이상, Node.js 18 이상
- `powershell -ExecutionPolicy Bypass -File BUILD_BACKEND.ps1` → `START_BACKEND.cmd` → `node frontend/dev-server.mjs` → http://localhost:5173

## 자동 검사

모든 명령은 저장소 루트에서 `powershell -ExecutionPolicy Bypass -File <스크립트>`로 실행합니다. 결과 로그는 `evidence/`, `checks/evidence/`에 새 폴더로 남고 Git에서 제외됩니다.

| 스크립트 | 확인 내용 | 필요 환경 |
|---|---|---|
| `checks/check-core.ps1` | 핵심 로직, 날짜·시간대, 활성 경로, 기록 | JDK |
| `app/checks/check-native-source.ps1` | Android 세션·화면 수치, 소스 구문 (Android 빌드는 아님) | JDK |
| `checks/verify-presentation.ps1` | 화면용 서비스 (`-JavaHome`으로 JDK 지정) | JDK |
| `BUILD_BACKEND.ps1` → `CHECK_BRIDGE.ps1` | 로컬 HTTP API | JDK |
| `BUILD_BACKEND.ps1` → `CHECK_CLIENT_INTEGRATION.ps1` | JavaScript 클라이언트와 HTTP 연동 | JDK, Node.js (`-NodePath`) |
| `BUILD_PC.ps1` → `CHECK_DESKTOP.ps1` | PC 기능 검사 화면 | JDK |
| `app/checks/check-route-status.ps1` | 순정 내비 SDK 데이터 분류 | Android 빌드 한 번 이후의 Gradle 캐시 (`GRADLE_USER_HOME`, 없으면 `~/.gradle`) |
| `frontend/tests/verify-offline.cjs`, `verify-live.cjs` | 웹 흐름과 화면 감사 | Node.js, `puppeteer-core`, Edge (live는 `BROWSER_PATH`로 다른 Chromium 계열 지정 가능) |

## 구현 상태

- 구현: 추천·예상 시간 비교·로컬 저장·요청 상태 관리(core, 자동 검사 통과), 처음 등록과 오늘의 경로 화면, 이미 안내 중이면 덮어쓰지 않기, 정차 확인, 요청 후 재조회와 중복 요청 방지(Android, 에뮬레이터 실행 확인).
- 순정 내비 실연결(2026-11-21, Pleos Connect Emulator 2.1.0 · 외장 GPU): 즐겨찾기 장소 목록, 예상 시간 조회, 경로 요청과 재조회 검증, 미확인 요청 복구, 이미 안내 중일 때 요청 차단을 확인했습니다. 실제 데이터에 맞춰 순정 어댑터를 고쳤습니다(subId 없음 허용, 경로 시작 이벤트 순서, 목적지 좌표 보정 허용). 예상 시간은 초 단위를 분으로 바꿔 홈에 표시하고, 같은 출발 위치(약 1km 격자)·순정 기본 경로 기준끼리만 평소와 비교합니다. 경로선은 순정 지도에 띄웁니다: 출발 전에는 목적지, 안내 시작 뒤에는 실제 경로 전체(`requestNavigationScreen`, `NAVI_CUSTOM_MAP` 권한). [시험 기록](docs/05_에뮬레이터_실연결시험_20261121.md)
- 미검증: 실제 차량, 주행 중 차단(Virtual Console로 재현 가능), 경로 옵션별 재조회, 경유지 A/B/C. 에뮬레이터의 순정 지도는 바탕 지도를 그리지 않고(빈 스타일) GPS 위치를 받지 못합니다.
- 구현 목표(코드 없음): 경유지 추가, 휴대폰에서 경로 보내기와 서버, 여행 일정 연결.
- 시연 영상과 웹 프로토타입의 교통·경로·경유지·휴대폰 화면은 시연 데이터입니다.

## 자료 출처

- 글꼴: Asta Sans (SIL Open Font License, `app/src/main/assets/OFL.txt`)
- 지도: OpenStreetMap contributors
