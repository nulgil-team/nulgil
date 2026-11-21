# 늘길 · 타면 이미 경로가 돼 있는 차

**한 줄 소개:** 늘 가는 곳의 경로를 시간대별로 준비해 두고, 평소보다 늦는 날만 출발 전에 알려 주며, 가는 길에 들를 곳까지 순정 내비 경로 하나로 묶어 주는 Pleos Connect 차량용 앱입니다.

현대자동차그룹 차량용 앱 개발 공모전(Pleos Connect) 출품작 저장소입니다. 앱은 순정 내비(NaviHelper SDK) 위에서 동작하며, 경로 계산·안내·지도 표시는 순정 내비가 맡고 늘길은 목적지·들를 곳·출발 판단을 준비합니다.

| 항목 | 값 |
|---|---|
| 앱 이름 | 늘길 (패키지 `kr.routine.app`) |
| 대상 플랫폼 | Pleos Connect (Android Automotive OS, API 34) |
| 개발·시험 에뮬레이터 | **Pleos Connect Emulator v2.1.0** (`RELEASE.release_connect_v2.1.0`, Android 14 / API 34, x86_64) |
| 화면 규격 | **2560×1440, 160dpi, 가로**. 앱 창 1681×1210 기준, 분할 창 834×1210 대응 |
| 사용 SDK | NaviHelper 3.0.5 (에뮬레이터의 내비 서비스 3.0.4), Android Car API(기어·속도·UX 제한) |
| APK | `apk/nulgil-release.apk` |

## 바로 실행 (APK)

- 파일: `apk/nulgil-release.apk` (패키지 `kr.routine.app`, 버전 0.7 / versionCode 9, minSdk 34, release 서명)
- 2026-11-21 빌드로, 순정 내비 실연결 시험 뒤의 수정(즐겨찾기 표시, 안내 결과 확인, 예상 시간, 순정 지도 보기, 들를 곳, 경로 성향 처리, 미확인 잠금 해제, 다음 이동 준비), 0.5의 친구 들를 곳(초대 코드), 0.6의 앱 안 지도, 0.6.1의 서버 확인 간격 조정과 친구 웹 QR, 0.6.2의 서버 요청 제한 안내, 0.7의 화면 시안 배치(오늘의 경로 띠·경유지 도착 카드·안내 시작 화면)와 폰에서 경로 보내기가 들어 있습니다. 2026-11-10의 0.2 버전과 같은 키로 서명해 기존 설치 위에 업데이트할 수 있습니다.
- 서명 키는 저장소에 없습니다. 저장소만으로는 debug 빌드를 만들 수 있고, release 빌드는 [빌드 환경](#빌드-환경)의 서명 설정이 필요합니다.
- 대상: Pleos Connect 에뮬레이터(Android Automotive). 설정은 [테스트 환경](#테스트-환경과-필요한-설정)을 따릅니다.
- 설치: `adb install -r -g apk/nulgil-release.apk` (`-g`는 차량 속도 권한 `CAR_SPEED`를 함께 허용합니다)
- 처음 실행하면 **시연 모드**(모의 목적지·교통·안내 응답)로 열립니다. `평소 장소 등록`에서 장소와 요일·출발 시간을 고르면 바로 오늘의 경로가 준비됩니다.
- `내 일상 > 연결 시험 > 순정 내비 연결 시험`으로 순정 내비(NaviHelper)와 차량 속성을 쓰는 실제 연결 모드로 바꿀 수 있습니다. 이 모드는 차량의 기어 P·속도 0을 확인할 때만 경로 요청을 허용합니다.

## 테스트 환경과 필요한 설정

시험한 환경: Windows 11, NVIDIA RTX 4070 Ti(외장 GPU), Android Studio 2026.2.2, Android Emulator 37.3, JDK 21. Pleos 공식 문서는 이 에뮬레이터에 외장 그래픽카드를 요구합니다(최소 RTX 3060 Laptop 6GB급).

1. **에뮬레이터 이미지 설치**: Android Studio `Tools > SDK Manager > SDK Update Sites`에 Pleos Connect System Image(`https://nexus-playground.pleos.ai/repository/raw-releases/connect-avd/2.x/crp-sys-img.xml`)를 추가하고, `SDK Platforms`의 API 34 아래 Pleos Connect system image를 설치합니다([공식 안내](https://document.pleos.ai/docs/connect/guide/getting-started/application-development/setup-connect-sdk-emulator)).
2. **AVD 만들기**: `Device Manager > Create device > Automotive > Pleos Connect v2.1.0`, 시스템 이미지 API 34 Pleos Connect v2.1.0. 고급 설정에서 **Default boot: Cold**(Quick boot이면 차량 속성 VHAL을 쓸 수 없음), **RAM 4GB**, **Graphics: Hardware**.
3. **실행과 설치**: 에뮬레이터를 Cold boot로 켠 뒤 `adb install -r -g <APK>`.
4. **실제 연결 모드에 필요한 것**
   - 에뮬레이터의 인터넷 연결(순정 내비가 장소 검색·경로 계산을 서버에서 합니다).
   - 순정 내비 **즐겨찾기 또는 최근 목적지**가 있어야 합니다. 늘길은 이 목록에서만 목적지와 들를 곳을 고릅니다. 순정 내비에서 장소를 검색하고 ☆를 눌러 즐겨찾기에 넣어 두세요.
   - 친구 들를 곳은 인터넷으로 늘길 초대 서버(Supabase)를 씁니다. 시연 모드에서는 서버 없이 지어낸 친구 제안 2곳으로 동작합니다.
   - 기어 P·속도 0이어야 안내 요청과 들를 곳 추가가 열립니다. 기어 등 차량 상태는 Pleos Connect Virtual Console(`adb forward tcp:8088 tcp:8088` → `http://127.0.0.1:8088/`)에서 바꿀 수 있습니다.
5. **에뮬레이터에서 알려진 차이**
   - 순정 지도는 바탕 지도를 그리지 않습니다(개발 서버의 빈 지도 스타일). 장소 검색, 경로 계산, 경로선, 회전 안내는 동작합니다.
   - 에뮬레이터 GPS 문장의 체크섬이 맞지 않아 순정 지도가 위치를 받지 못하고 기본 위치(성남 수정구)를 현재 위치로 씁니다.

실연결 시험의 상세 결과와 실측값은 [docs/05_에뮬레이터_실연결시험_20261121.md](docs/05_에뮬레이터_실연결시험_20261121.md)에 있습니다.

## 구성

| 폴더 | 내용 |
|---|---|
| `core/` | Android에 의존하지 않는 Java 핵심 로직: 규칙·기록 기반 추천(`RoutineRecommender`), 평소 대비 예상 시간 비교(`TrafficAdvisor`), 로컬 저장(`RoutineStore`), 경로 요청 상태 관리(`DepartureController`), 친구 제안 받기(`InviteInbox`) |
| `app/` | Android Automotive 차량 앱. 순정 내비 연동(`platform/PleosNaviGateway.java`, NaviHelper 3.0.5), 정차 확인(`platform/PublicParkedGuard.java`, GEAR_SELECTION·PERF_VEHICLE_SPEED·CarUxRestrictions), 초대 서버 연결(`invite/`), 화면(`ui/`: 공통 부품 `UiKit`, 친구 들를 곳 `InvitePanel`, 앱 안 지도 `RouteMapCard`·`RouteMapView`·`MapMath`) |
| `server/` | 친구 들를 곳 초대 서버 구조(Supabase 마이그레이션 SQL) |
| `invite-web/` | 친구가 휴대폰으로 여는 들를 곳 보내기 페이지(정적 파일, 디자인 수정 위치는 그 폴더의 README) |
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
- release: 저장소 밖의 서명 설정 파일(`storeFile`, `alias`, `password`)을 지정해 `gradlew.bat :app:assembleRelease -Pnulgil.signing=<signing.properties 경로>` (또는 환경 변수 `NULGIL_SIGNING`) → `app/build/outputs/apk/release/app-release.apk`. 지정하지 않으면 서명되지 않은 APK가 나옵니다. 키와 설정 파일은 커밋하지 않습니다.
- Android Studio에서 빌드할 때는 `Settings > Build Tools > Gradle > Gradle JDK`를 17~22로 지정합니다(Android Studio 2026.2에 들어 있는 JBR 25로는 Gradle 8.9가 실행되지 않습니다).

## 웹 프로토타입

- Java 17 이상, Node.js 18 이상
- `powershell -ExecutionPolicy Bypass -File BUILD_BACKEND.ps1` → `START_BACKEND.cmd` → `node frontend/dev-server.mjs` → http://localhost:5173

## 자동 검사

모든 명령은 저장소 루트에서 `powershell -ExecutionPolicy Bypass -File <스크립트>`로 실행합니다. 결과 로그는 `evidence/`, `checks/evidence/`에 새 폴더로 남고 Git에서 제외됩니다. 저장소가 OneDrive 동기화 폴더 안에 있으면 저장 파일 교체가 막혀 일부 검사(`store history capped…`, 세션의 시연 시나리오)와 Gradle 빌드 정리가 실패할 수 있습니다. 동기화되지 않는 폴더에서 실행하세요.

| 스크립트 | 확인 내용 | 필요 환경 |
|---|---|---|
| `checks/check-core.ps1` | 핵심 로직, 날짜·시간대, 활성 경로, 기록, 들를 곳, 친구 들를 곳 | JDK |
| `app/checks/check-native-source.ps1` | Android 세션·화면 수치, 지도 좌표 계산, 소스 구문 (Android 빌드는 아님) | JDK |
| `checks/verify-presentation.ps1` | 화면용 서비스 (`-JavaHome`으로 JDK 지정) | JDK |
| `BUILD_BACKEND.ps1` → `CHECK_BRIDGE.ps1` | 로컬 HTTP API | JDK |
| `BUILD_BACKEND.ps1` → `CHECK_CLIENT_INTEGRATION.ps1` | JavaScript 클라이언트와 HTTP 연동 | JDK, Node.js (`-NodePath`) |
| `BUILD_PC.ps1` → `CHECK_DESKTOP.ps1` | PC 기능 검사 화면 | JDK |
| `app/checks/check-route-status.ps1` | 순정 내비 SDK 데이터 분류 | Android 빌드 한 번 이후의 Gradle 캐시 (`GRADLE_USER_HOME`, 없으면 `~/.gradle`) |
| `frontend/tests/verify-offline.cjs`, `verify-live.cjs` | 웹 흐름과 화면 감사 | Node.js, `puppeteer-core`, Edge (live는 `BROWSER_PATH`로 다른 Chromium 계열 지정 가능) |

## 구현 상태

- 구현: 추천·예상 시간 비교·로컬 저장·요청 상태 관리(core, 자동 검사 통과), 처음 등록과 오늘의 경로 화면, 이미 안내 중이면 덮어쓰지 않기, 정차 확인, 요청 후 재조회와 중복 요청 방지(Android, 에뮬레이터 실행 확인).
- 순정 내비 실연결(2026-11-21, Pleos Connect Emulator 2.1.0 · 외장 GPU): 즐겨찾기 장소 목록, 예상 시간 조회, 경로 요청과 재조회 검증, 미확인 요청 복구, 이미 안내 중일 때 요청 차단을 확인했습니다. 실제 데이터에 맞춰 순정 어댑터를 고쳤습니다(subId 없음 허용, 경로 시작 이벤트 순서, 목적지 좌표 보정 허용). 예상 시간은 초 단위를 분으로 바꿔 홈에 표시하고, 같은 출발 위치(약 1km 격자)·순정 기본 경로 기준끼리만 평소와 비교합니다. 경로선은 순정 지도에 띄웁니다: 출발 전에는 목적지, 안내 시작 뒤에는 실제 경로 전체(`requestNavigationScreen`, `NAVI_CUSTOM_MAP` 권한). [시험 기록](docs/05_에뮬레이터_실연결시험_20261121.md)
- 들를 곳(경유지): 출발 전 최대 2곳을 목적지와 함께 요청하고, 안내 중에는 정차했을 때 1곳씩 넣습니다. 둘 다 순정 재조회로 확인하고, 같은 곳은 다시 요청하지 않습니다. 장소는 순정 즐겨찾기·최근 목적지에서 고릅니다. 에뮬레이터에서 확인했습니다.
- 친구 들를 곳(0.5): 차에서 `친구에게 들를 곳 받기`로 6자리 초대 코드를 만들면, 친구가 초대 웹(`invite-web/`)에서 코드를 넣고 장소를 검색해 보냅니다. 차는 친구 화면을 보고 있으면 15초마다, 다른 화면에서는 1분마다 확인해 목록에 띄우고(앱이 뒤에 있거나 초대가 없으면 확인하지 않음), 운전자가 정차 중에 고릅니다. 출발 전에는 들를 곳으로 담아 목적지와 함께 요청하고, 안내 중에는 바로 경로에 넣습니다. 결과(경로에 넣음, 미확인, 넘김)는 친구 화면에 표시됩니다. 친구 장소는 순정 POI가 아니어서 좌표와 이름으로 보내고, 재조회에서 이름과 경유지 수로 확인합니다. 에뮬레이터와 실제 서버로 두 경로 모두 확인했습니다. [시험 기록](docs/05_에뮬레이터_실연결시험_20261121.md#친구-들를-곳-2026-11-21)
  - 서버에는 초대 코드, 차 토큰의 해시, 친구가 보낸 장소·이름·한마디만 저장합니다. 차의 위치·목적지·경로는 보내지 않습니다. 초대는 12시간 뒤 끝나고, 한 초대에 제안은 10곳까지입니다.
  - 한계: 시제품이라 로그인 없이 초대 코드를 만들 수 있습니다. 대신 서버가 초대 만들기(10분에 60개)와 보내기(이동 하나에 10곳, 1분에 3곳)를 제한하고, 끝난 초대는 하루 뒤 지웁니다. 친구 페이지는 https://nulgil-invite.vercel.app 에 배포되어 있고, 차 화면의 QR은 이 주소에 코드를 붙여 엽니다(주소는 `app/src/main/res/values/invite.xml`의 `invite_web_url`).
- 앱 안 지도(0.6): 에뮬레이터의 순정 지도는 바탕 지도를 그리지 않아, 늘길 화면에 OpenStreetMap 지도를 따로 그립니다. 홈·안내 화면에는 현재 위치(순정이 예상 시간 조회 때 알려 준 위치)·들를 곳(번호)·목적지(도)를, 친구 들를 곳 화면에는 친구가 보낸 곳(A, B…)까지 표시합니다. SDK가 경로선 좌표를 주지 않아, 지도의 도로선은 공개 OpenStreetMap 경로 서버(OSRM)에서 받아 "예시 경로"로 표시합니다(실제 안내 경로는 순정 내비가 계산). 이때 출발·들를 곳·목적지 좌표를 약 100m 단위로 반올림해 OSRM에 보냅니다. `app/src/main/res/values/map.xml`의 `route_shape_url`을 비우면 보내지 않고 점선(들르는 순서)만 그립니다. 홈은 화면 시안의 "오늘의 경로" 배치입니다: 왼쪽에 이유·목적지·예상 시간, 오른쪽에 지도, 아래에 현재 위치 ─ 경유지(깃발) 또는 경유지 추가(+) ─ 목적지 띠. 핀이 겹치면 경로 핀은 제자리에 두고 다른 핀을 옆으로 옮겨 가는 선으로 잇습니다. 에뮬레이터에서 확인했습니다.
- 폰에서 경로 보내기(0.7): 차 내 일상 → 폰 연결의 QR을 폰으로 한 번 찍으면, 폰 페이지(`invite-web/phone.html`)에서 목적지를 검색해 "차에 경로 보내기"를 누를 수 있습니다. 차는 앱이 화면에 있을 때 1분마다(앞으로 올 때 바로) 확인해 홈에 "폰에서 보낸 경로예요"와 함께 준비하고, 운전자는 안내 시작만 누릅니다. 폰 장소는 순정 POI가 아니어서 좌표와 이름으로 요청하고, 재조회 좌표(0m)와 이름으로 확인했습니다(에뮬레이터, 2026-11-21). 폰으로 보낸 목적지는 일상 기록에 남기지 않습니다. 서버에는 보낸 장소만 최대 2시간 두고, 차의 위치·경로는 보내지 않습니다.
  - 지도 타일은 `tile.openstreetmap.org`에서 받습니다(주소와 표기는 `app/src/main/res/values/map.xml`). 타일 서버는 보고 있는 지역의 타일 번호를 알 수 있습니다. 현재 위치 자체는 보내지 않고 앱 메모리에만 둡니다. 인터넷이 없으면 지도 바탕 없이 핀만 나옵니다.
- 주행 중 차단: Virtual Console에서 기어 D로 바꾸면 Pleos 시스템이 앱 화면을 가리고, 늘길도 안내 시작·들를 곳 추가를 잠급니다. 에뮬레이터에서 확인했습니다.
- 경로 성향: 에뮬레이터의 순정 내비는 앱이 보낸 경로 옵션을 적용하지 않고 순정 설정(추천 경로)으로 안내합니다. 늘길은 목적지가 맞으면 안내를 확인으로 처리하고, 옵션을 한 번 바꿔 요청한 뒤에도 다르면 실제 성향을 화면에 알립니다.
- 안내가 끝나면 "다음 이동 준비"로 앱을 다시 켜지 않고 다음 목적지를 고를 수 있습니다. 결과 미확인 뒤 순정 안내가 끝난 경우에는 재연결 때 잠금이 풀립니다.
- 미검증: 실제 차량. 에뮬레이터의 순정 지도는 바탕 지도를 그리지 않고(빈 스타일) GPS 위치를 받지 못합니다.
- 구현 목표(코드 없음): 휴대폰에서 경로 보내기, 여행 일정 연결.
- 시연 영상과 웹 프로토타입의 교통·경로·경유지·휴대폰 화면은 시연 데이터입니다.

## 자료 출처

- 글꼴: Asta Sans (SIL Open Font License, `app/src/main/assets/OFL.txt`)
- 지도: OpenStreetMap contributors
