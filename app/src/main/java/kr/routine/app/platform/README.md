# 순정 내비 어댑터

확인일: 2026-11-04, 에뮬레이터 실연결 반영 2026-11-21. 이 폴더의 실행 환경은 Pleos Connect / Android Automotive API 34 이상이다. 2026-11-21에 Pleos Connect Emulator 2.1.0에서 장소 조회·예상 시간·경로 요청·재조회·복구를 확인했다([시험 기록](../../../../../../../../docs/05_에뮬레이터_실연결시험_20261121.md)). 실제 차량 실행은 아직 하지 않았다.

## 실측으로 바뀐 점 (2026-11-21)

- 즐겨찾기의 `poiSubId`는 `null`, `poiProvider`는 빈 문자열로 온다. `null` subId는 `""`로 받는다. `RouteInfo`에는 `""`, `TripRequest`에는 `null`을 보낸다. 이름·좌표가 모두 빈 즐겨찾기는 빈 슬롯으로 건너뛴다.
- `requestTrip`의 ETA는 초, 거리는 미터다. 순정 안내 화면(40분·22km, 36분·13km)과 일치했다. 어댑터는 분으로 반올림해 보고하고, 출발 위치는 `getCurrentLocationInfo()`를 약 1km 격자로 묶어 `originKey`로 쓴다(기준 `ETA_PLEOS_TRIP_DEFAULT_OPTION`). 위치가 1초 안에 오지 않으면 비교에서 뺀다.
- SDK 응답에는 경로선 좌표가 없다(`routePoints`는 도착지 1개). `showNativeMap`은 `requestNavigationScreen`으로 순정 지도에 목적지(`POIDetailPopup`)나 경로 전체(`OverviewRoute`)를 띄운다. `pleos.car.permission.NAVI_CUSTOM_MAP`이 필요하다. 화면 요청은 경로에 영향이 없어 실패해도 연결을 끊지 않는다.
- 경로 요청 뒤 `onRoutingCompleted`는 오지 않았다. `onRouteStarted` 직후 같은 밀리초에 `onRouteEnded`가 온다. `onRouteStarted`를 받으면 1.5초 뒤 목적지·상태를 재조회하고, 시작 직후의 종료·취소는 전환으로 본다.
- 재조회한 목적지 좌표는 도로 쪽으로 옮겨져 있다(29~283m). POI id·subId는 완전 일치를 요구하고 좌표는 500m 이내만 확인한다.
- 들를 곳: 출발 전에는 `requestRouteWithWaypoints`, 안내 중에는 경로·경유지·목적지·정차를 확인한 뒤 `addWaypoint`. 경유지 재조회 좌표는 (0, 0)으로 와서 POI id·subId로 확인한다. `addWaypoint` 뒤에는 `RouteCancelled` → 재탐색 → `WaypointChanged(ADD)`+`RouteEnded` 순서로 오며, 추가 중의 취소·종료는 재조회로 판정한다. 순정은 방문 순서를 스스로 정한다(SECOND로 보내도 FIRST에 넣음).
- 로그 태그 `RoutineNavi`에는 개수, 탈락 사유, 원시 ETA·거리, 재조회 판정만 남기고 장소 이름·좌표는 남기지 않는다.

`PleosNaviGateway`는 NaviHelper 3.0.5를 사용한다. navi-data 3.0.4의 로컬 공개 선언과 생성자 인자 순서를 확인해 작성했다. 특히 RouteInfo의 첫 두 인자는 위도, 경도다. 웹 Java 예제의 순서를 그대로 복사하지 않았다.

## 동작과 제한

- 최근 목적지·즐겨찾기를 모두 받은 뒤 유효한 장소만 노출한다. 빈 식별자, 빈 주소, 잘못된 좌표, 서로 충돌하는 장소 메타데이터는 제외한다. 표시 좌표와 POI 제공자도 순정에서 받은 값을 유지한다.
- 조회·요청은 한 번에 하나다. 시간 초과나 오류가 나면 해당 연결을 폐기해 늦은 응답이 다음 요청으로 해석되지 않게 한다. 자동 재시도하지 않는다.
- (2026-11-04 기록, 11-21에 해소) `requestTrip`의 ETA 숫자는 당시 단위를 확정하지 못했다. 그래서 그때의 실제 어댑터는 `Estimate.minutes`를 -1로 두고, 원시값과 교통 구간 수를 미검증 정보로 전달한다. 모의 데모의 분 단위 예측과 혼합하지 않는다. 출발 위치와 경로 옵션도 확인되지 않아 과거 대비 지연 계산에 사용하지 않는다.
- 경로 요청은 공개 차량 속성으로 정차를 확인하고, 현재 내비 상태가 `SAFE_GUIDING`이며 `isRouting=false`일 때만 발행한다. `SAFE_GUIDING` 조건의 실제 이미지 동작은 아직 검증해야 한다.
- 발행 직전에 SharedPreferences에 미확인 요청을 동기 저장한다. 저장에 실패하면 요청하지 않는다. 실제 요청 후 경로 시작(또는 완료) 이벤트와 목적지·좌표·옵션 재조회가 일치해야 검증 완료로 처리한다. 결과가 불확실하면 기록을 남기고 재발행을 차단한다.
- 재연결 시 남은 미확인 요청은 현재 순정 상태를 읽어서만 확인한다. 현재 상태가 저장한 목표와 다르면 계속 차단된다. 운영자가 순정 화면을 확인한 뒤 해제하는 별도 제품 흐름은 아직 없다.
- 기존 경로 취소, 경로 대체를 위한 선행 취소, 숨은 API 접근은 구현하지 않았다. 실제 지도의 올바른 경로와 접근 도로 표시는 별도 확인이 필요하다.

## 정차 확인

`PublicParkedGuard`는 공개 `CarPropertyManager`로 기어와 속도를 읽고 `CarUxRestrictionsManager`를 함께 확인한다. 기어 P, 최신 속도 0, 조작 제한 해제를 모두 확인해야 한다. 권한 거부, 연결 끊김, 속성 미지원, 값 오류, 2초 넘은 속도·조회 결과는 차단한다.

속성 조회는 UI 스레드 밖에서 수행한다. 기어는 변경 시 갱신되는 속성이므로 오래된 기어 이벤트 시각만으로 차단하지는 않으며, 최신 동기 조회가 정상 완료돼야 한다. 속도는 연속 속성이므로 이벤트 시각도 2초 이내여야 한다. 실제 장비의 갱신 주기와 권한 처리는 아직 검증하지 않았다.

필요한 권한은 `android.car.permission.CAR_POWERTRAIN`(normal), `android.car.permission.CAR_SPEED`(dangerous)다. 시스템 전용 `CarDrivingStateManager`는 사용하지 않는다. 앱 UI에서도 차량 UX 제한을 따라야 하며, 이 어댑터가 UI 전체 제한을 대신하지 않는다.

## 근거

- 로컬 `현대차_공개API부터_재탐색_20261102/정적분석`의 RouteInfo, RouteStateInfo, TripRequest, RecentDestinationInfo, BookmarkInfo, NaviHelperEventListener 공개 선언.
- 설치된 Android API 35 `optional/android.car.jar`의 공개 클래스와 메서드 선언.
- [Pleos 경로 옵션](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/routes): 경로 옵션과 콜백.
- [Pleos 목적지](https://document.pleos.ai/docs/pleos-only/connect-sdk-pleos/NaviHelper/destinations): 목적지 요청과 완료 확인.
- [Android 차량 상태·UX 제한](https://source.android.com/docs/automotive/driver_distraction/consume): CarDrivingStateManager의 시스템 API 제약과 UX 제한.
- [차량 속성](https://developer.android.com/reference/android/car/VehiclePropertyIds): 속도 값·형식·권한.
- [CarPropertyValue](https://developer.android.com/reference/android/car/hardware/CarPropertyValue): 상태·이벤트 시각.
- [Car 권한](https://developer.android.com/reference/android/car/Car): 공개 차량 권한.

태그: 순정 내비, 공개 SDK, 정차 판정, 미확인 요청, 재조회, 실행 미검증.
