# BR-02 늘길 차량 앱 화면

2026-11-05. Claude의 웹 화면 위계를 Android 네이티브 View로 옮겼다. C 작업 checkout에서 소스 구현과 JVM 검사를 마쳤다. 사용자가 Android 빌드·실행은 SSD 연결 후 진행하기로 했으므로 APK 빌드, 기기 실행과 시각 검수는 아직 하지 않았다.

## 화면 기준과 구현

기준은 frontend 커밋 `3f2cc4763fcde1dc365b5d9ddd3c68131978a11e`의 `app.js`·`styles.css`와 BR-17·BR-16 연결 필드다. 앱 영역 1681×1210, 분할 영역 834×1210을 기준으로 삼았다. 웹의 속도계·차량 상태 바·공조·GNB는 차량 시스템 영역이므로 앱 안에 복제하지 않았다.

| 화면 키 | 구현한 내용 |
|---|---|
| home | 늘길·시연 구분 → 목적지/변경 → 예상 시간/도착 예상 → 경로 요약 → 경로 성향 → 안내 시작/넘기기 |
| picker | 현재 gateway에서 받은 목적지 목록. id와 subId를 함께 확인해 선택 |
| guide | requesting/verified/unconfirmed, 현재 활성 경로, 읽기 전용 상태 재확인 |
| settings | 실제 등록한 장소·요일·시각, 기록 동의/삭제, 테마, 연결 모드, 개발 시연 |
| onboard1/2/3 | 기록 동의 선택 → 장소 선택 → 요일·시간 등록. 동의 없이 직접 등록도 가능 |
| why/clear | 실제 비교 날짜·분 목록과 기록 삭제 확인 |

보류 유형 `NOT_ENOUGH/TIE`, 교통 `NO_COMPARE/STALE/UNKNOWN_UNITS/MISSING/CALM/LATE`, 활성 경로 `ACTIVE/INACTIVE/UNAVAILABLE`를 메시지 파싱 없이 표시한다. 저장 장소가 현재 목록에서 사라졌으면 `source=UNRESOLVED`로 분리한다.

전체 창은 목적지와 변경 버튼, ETA와 설명을 행으로 묶는다. 폭 1000 이하에서는 세로 배치로 바꾸고, 짧은 창이나 큰 글자에서는 시작 버튼도 본문과 함께 스크롤한다. 고정 크기 캔버스를 축소해 맞추지 않는다. 실제 루트 View의 크기를 기준으로 삼으며 시스템 여백을 제외한다.

주간·야간 색은 웹의 asphalt/paint/sign/warning 조합을 사용한다. 시스템 주야간 설정과 명시적 주간·야간 선택을 지원한다. 플랫폼 Force Dark에 의한 임의 재색칠은 끈다. 글자는 최소 30sp, 터치는 최소 76dp를 적용하고 참고 픽셀 값과 비교해 더 큰 값을 사용한다. 따라서 실제 차량의 밀도·글자 배율에서 참고 화면과 어느 정도 일치하는지는 기기 검수에서 확인해야 한다.

웹의 Asta Sans 파일을 변경 없이 복사하고 OFL 라이선스를 함께 포함했다. 출처는 `app/src/main/assets/README.md`에 적었다.

지도 영역은 현재 연결된 지도 데이터가 없어 경로 요약과 순정 내비 확인 안내로 구성했다. 도로 모양의 경로 띠는 출발지·목적지를 설명하는 장식이며 실제 지리 경로나 차량 위치가 아니다. 폰 전송, 경유지, 탑승 자동 시작은 구현하지 않았다.

## 모듈과 상태 수명

- `RoutineActivity`: 화면 연결·권한 요청·전경 복귀 처리. 안내 알고리즘을 포함하지 않는다.
- `RoutineApplication`: 프로세스 안의 VehicleSession을 유지한다. Activity 재생성으로 같은 이동의 요청 잠금이 풀리지 않게 한다.
- `vehicle/VehicleSession`: 기존 RoutineRecommender, TrafficAdvisor, RoutineStore, DepartureController, NaviGateway를 호출하고 화면 상태를 제공한다.
- `vehicle/VehicleUiState`: 불변 화면 상태. 등록 규칙·후보·비교 목록과 버튼 허용 여부를 포함한다.
- `ui/VehicleScreen`: 네이티브 화면 및 확인 시트. 색·치수는 VehicleVisuals, 경로 띠는 RouteSummaryView에 분리했다.

앱은 운전자가 열어 시작한다. 앱 진입·복귀·화면 변경은 안내를 자동 요청하지 않는다. 실제 어댑터의 콜백이 끝나기 전에 다른 SDK 조회를 겹쳐 보내지 않고, 오래된 콜백은 현재 제안을 덮어쓰지 못하게 한다. 확인 버튼은 정차·경로 비활성·목적지 준비 조건과 fresh precheck를 통과해야 한다.

모의 기본 화면은 빈 상태다. 예시 기록을 쓰려면 사용자가 설정에서 기록 저장에 동의한 뒤 개발 시연 상태 준비를 확인한다. 다섯 보류/교통 상태, 이미 안내 중, 조회 불가, 정차 미확인, 안내 미확인을 재현할 수 있다. 이 준비가 실제 안내를 발행하지는 않는다. 미확인 시나리오도 사용자의 안내 시작 확인 후에만 요청 1회가 발생한다.

진행 중 조회·확인 때는 기록 동의 변경 버튼을 잠근다. 그동안 서비스로 들어온 동의 변경도 적용하지 않고 이유를 알린다. 변경을 수락한 뒤에는 후보·기록·중앙값이 즉시 비며, 기존 파일의 기록 삭제와는 구분된다. 해제 저장 실패 시 이번 실행의 비교·기록 사용과 새 안내·모드 전환을 차단한다.

화면 재생성은 세션을 교체하지 않는다. 앱 프로세스가 종료되면 Application의 메모리도 사라지므로 실제 연결의 이전 요청 복구는 기존 Pleos 어댑터의 저장 journal과 경로 재조회에 의존한다. 모의 프로세스 재시작은 새 DEMO 세션이며 이전 모의 이동을 복구했다고 표시하지 않는다. 프로세스 재생성·실제 SDK 복구는 이번에 기기 검증하지 않았다.

## C에서 실행한 검사

실행 명령:

```powershell
.\app\checks\check-native-source.ps1
.\checks\check-core.ps1
```

| 검사 | 결과 |
|---|---|
| VehicleSession JVM 검사 | 46 assertions 통과 |
| 색 대비·최소 크기·화면 전환 | 1,062 assertions 통과 |
| 공용 core/시간대/활성 경로/기록 | 45 + 25 + 36 + 18 통과 |
| native Java 구문 | 10개 파일 parse 통과 |
| Manifest·테마 XML | XML 구문 및 Application 선언 확인 |

세션 검사에는 활성/비활성/조회 불가 요청 0/1/0, pending 상태의 listener 교체, 늦은 콜백, 복귀 후 읽기, 등록 직후 추천, 사라진 장소, 동의 해제·저장 실패, 다섯 재현 상태가 포함된다. 정상 모의 교통 새로고침은 저장하지 않고, 명시적 동의 후 확인된 안내 결과에 대해서만 선택과 유효한 예상 시간을 저장한다.

색 대비 검사는 사용하는 주요 텍스트/표면 조합이 4.5:1 이상인지 계산했다. 최소 크기는 폭 320~1681, 높이 480~1210, 밀도 1~2와 글자 배율 1~2 조합에서 검사했다. 이는 숫자 규칙 검사이며 Android가 글자를 배치하거나 잘림 없이 렌더했다는 뜻이 아니다.

Java 구문 검사는 JavacTask.parse만 호출한다. Android/SDK 타입 분석, 리소스 연결, 바이트코드·APK 생성은 하지 않는다. Android 스텁으로 가짜 빌드 성공을 만들지 않았다. 로그는 로컬 evidence/br02-source-*와 checks/evidence에 보관하고 저장소에서 제외한다.

## SSD 연결 후 남은 통과 기준

1. D의 백엔드 checkout에 미커밋 변경이 없는지 확인하고 최신 비공개 브랜치를 반영한다. 02·04·05·06 및 SDK·이미지·08 브랜치는 변경하지 않는다.
2. 기존 환경을 읽고 `gradlew.bat --offline --no-daemon --max-workers=1 :app:compileDebugJavaWithJavac`로 Android 타입·리소스 연결을 확인한다. 서명·배포는 별도 승인 범위다.
3. 차량 환경에서 실제 앱 창 1681×1210과 분할 834×1210, 주간·야간·시스템 테마를 캡처한다. 긴 한글 장소명·주소·글자 확대·낮은 높이에서 잘림과 겹침을 확인한다.
4. 확인 대기 중 크기·테마 변경, 백그라운드 왕복, Activity 재생성 후 요청 횟수가 늘지 않는지 확인한다. 프로세스 종료 후 실제 어댑터의 journal 복구는 별도 시험한다.
5. 동률 후보·2일 기록·비교 부족·오래된 교통·단위 미확인, 등록한 장소 목록, 동의 해제, 저장 실패, 이미 안내 중·조회 불가 화면을 Claude에게 시각 검수받는다.
6. 외장 GPU PC가 확보되면 순정 내비 기준선과 경유지 A/B/C를 별도 수행한다.

BR-02는 **소스 구현 및 C에서 가능한 검사 완료, 차량 화면 통과 기준 미확인** 상태다. APK·실차·실시간 교통·지도·경유지 성공으로 보고하지 않는다.

## 확인한 공식 자료

2026-11-05 확인. 기존 로컬 웹 사양과 native 구현을 먼저 읽고 생명주기·창 크기·주야간·구문 검사 API만 공식 문서로 보완했다.

- [Android Application](https://developer.android.com/reference/android/app/Application): Application 수명과 Activity와의 분리
- [다중 창 지원](https://developer.android.com/develop/adaptive-apps/guides/support-multi-window-mode): 창 변경과 Activity 구성 변경
- [WindowManager](https://developer.android.com/reference/android/view/WindowManager): 전체 디스플레이와 현재 창의 구분
- [Views 주야간 테마](https://developer.android.com/develop/ui/views/theming/darktheme): uiMode와 Force Dark
- [JDK 17 JavacTask](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.compiler/com/sun/source/util/JavacTask.html): parse와 analyze/generate의 구분
