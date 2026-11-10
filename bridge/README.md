# 로컬 화면 연결 브리지

2026-11-05. 이 브리지는 `DemoBackend`를 호출하는 Java 17 로컬 HTTP 서버다. 추천·시간대·교통 비교·안내 상태 전이는 실제 core를 실행한다. 목적지와 교통·차량·안내 응답은 DEMO 데이터이며, 실차 연결이나 지도 경로 좌표를 제공하지 않는다.

빌드·실행 스크립트와 프런트엔드 클라이언트는 프로젝트 루트의 인계 문서를 따른다. Java 진입점은 `kr.routine.bridge.BridgeMain`, 테스트 진입점은 `kr.routine.bridge.BridgeChecks`다.

## 실행 인자

```text
BridgeMain [--port 8791]
           [--origin http://localhost:5173] [--origin http://127.0.0.1:5173]
           [--zone Asia/Seoul]
           [--runtime-root bridge/runs] [--data state.properties]
```

포트 `0`은 테스트용 임시 포트다. `--origin`을 한 번이라도 지정하면 지정한 주소만 허용한다. 생략하면 위 두 주소를 허용한다. 주소에는 경로·사용자 정보·쿼리를 넣을 수 없으며 HTTP(S) `localhost`와 `127.0.0.1`만 가능하다. 브라우저 표기에 맞춰 HTTP 80/HTTPS 443 기본 포트는 생략해서 지정한다. 서버는 항상 `127.0.0.1`에 바인딩한다.

매 실행마다 새 run 폴더를 만들고 `connection.json`에 `baseUrl`, `token`, `schemaVersion`, `mode`를 저장한다. 콘솔에는 주소와 파일 경로만 출력한다. 토큰은 새 화면의 로컬 연결 설정에서 읽고, URL·소스 코드·공개 자료·로그에 넣지 않는다. 이 파일을 HTTP로 제공하는 엔드포인트는 없다. `--data`가 없으면 새 run 폴더에 상태를 저장한다.

## 요청과 응답

| 요청 | 내용 |
|---|---|
| `GET /v1/state` | 현재 `BackendSnapshot` 반환. 읽기만으로 추천·기록·안내를 실행하지 않는다. |
| `POST /v1/commands` | `application/x-www-form-urlencoded;charset=UTF-8` 본문. `operation`과 명령별 인자를 전달하며 변경 후 스냅샷을 반환한다. |
| `OPTIONS` | 브라우저 CORS 사전 요청. 토큰 없이 허용된 Origin·메서드·헤더만 검사한다. |

GET/POST에는 `X-Routine-Token` 헤더가 필요하다. 브라우저는 허용된 `Origin`에서 호출해야 하며, 네이티브 로컬 클라이언트는 Origin을 생략할 수 있다. URL 쿼리는 받지 않는다. `Host`는 실제 서버 포트를 포함한 `127.0.0.1:<port>` 또는 `localhost:<port>`여야 한다. HTTP 기본 포트 80으로 실행한 경우에만 포트를 생략한 두 주소도 받는다.

```javascript
const body = new URLSearchParams({
  operation: 'setContext',
  now: String(Date.now()),
  zoneId: 'Asia/Seoul'
});
const response = await fetch(`${baseUrl}/v1/commands`, {
  method: 'POST',
  headers: {
    'X-Routine-Token': token,
    'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
  },
  body
});
const result = await response.json();
```

본문은 최대 8 KiB다. 중복 필드, 잘못된 `%` 인코딩, 유효하지 않은 UTF-8, 알 수 없는 인자·명령을 거부한다. 실제 명령과 DTO 필드는 `core/src/main/java/kr/routine/presentation`의 계약을 따른다. 모든 응답은 `Cache-Control: no-store`, `X-Content-Type-Options: nosniff`를 포함한다.

오류는 `{ "schemaVersion": "1.0", "error": { "code": "...", "message": "..." } }`다. 화면은 `code`로 분기하고 메시지는 표시용으로 쓴다.

| HTTP | 구분 |
|---|---|
| 400 | 잘못된 본문/인자/명령/헤더, query 문자열 |
| 401 | 토큰 누락 또는 불일치 |
| 403 | 허용되지 않은 Origin/Host |
| 404 | 없는 API 경로 |
| 405 | 해당 경로가 지원하지 않는 메서드 |
| 409 | `STATE_CONFLICT`, `TRIP_LOCKED`, `NOT_READY`, `VEHICLE_NOT_PARKED`, `COLLECTION_DISABLED`, `CONSENT_NOT_SAVED`, `CLOSED` |
| 413 | 8 KiB 초과 |
| 415 | form/UTF-8 이외 본문 형식 |
| 500 | `STORAGE_ERROR`, `INTERNAL_ERROR` |

`UNCONFIRMED`는 안내 요청 결과를 확인하지 못했다는 상태다. 같은 이동에서 재전송하지 않으며 명시적인 `newDemoTrip` 이후에만 새 모의 요청을 시작한다. `canConfirm`을 확인한 뒤 사용자 동작으로 `confirm`을 호출한다.

## 검증 범위와 참고

`BridgeChecks --evidence-dir <새 결과 보관 위치>`는 실제 임시 포트 HTTP 요청으로 인증·CORS·Host·입력 오류·UTF-8·날짜 경계·추천·교통·안내 중복 방지를 확인한다. 생성되는 `state-congested.json`, `state-unconfirmed.json`, `result.txt`에는 연결 토큰이 없다. 검증 결과는 개별 실행 폴더의 `result.txt`를 기준으로 확인한다.

Oracle Java 17의 [HttpServer 공식 API](https://docs.oracle.com/en/java/javase/17/docs/api/jdk.httpserver/com/sun/net/httpserver/HttpServer.html), [HttpClient 공식 API](https://docs.oracle.com/en/java/javase/17/docs/api/java.net.http/java/net/http/HttpClient.html)를 2026-11-05 확인했다. 외부 HTTP 프레임워크나 JSON 라이브러리는 추가하지 않았다.
