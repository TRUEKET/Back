# MediaPipe 손 인식 연동

React에서 MediaPipe를 실행하고 Spring Boot는 사진의 예매 화면을 LLM으로 찾은 뒤 손 위치를 계산한다. 별도 Python 서버·모델 학습은 필요 없다. 이 저장소에는 React 소스가 없어 프론트 구현은 아래 계약으로 연결한다.

참고: [팀 지정 샘플](https://github.com/google-ai-edge/mediapipe-samples-web/blob/main/src/tasks/gesture-recognizer.ts), [공식 Web 가이드](https://developers.google.com/edge/mediapipe/solutions/vision/gesture_recognizer?tab=Web).

## 요청

`POST /hand-analyze`, `multipart/form-data`

| part | Content-Type | 내용 |
| --- | --- | --- |
| photo | image/jpeg 또는 image/png | 실제 이미지. 최대 5MiB, 양 변 최소 320px, 최대 4천만 픽셀 |
| analysis | application/json | 아래 JSON |
| token (선택) | 텍스트 form field | 트루샷 판매자 링크 토큰. 전달하면 서버 저장 미션과 비교 |

```json
{
  "imageWidth": 1000,
  "imageHeight": 800,
  "hands": [{"x": 300, "y": 420, "gesture": "Victory", "confidence": 0.96}]
}
```

- `x,y`는 업로드 파일의 왼쪽 위 원점 기준 픽셀 좌표다. CSS 표시 크기를 사용하지 않는다. 범위는 `[0,imageWidth]`, `[0,imageHeight]`다.
- 기준점은 **검지 끝 landmark 8**이다. 현재 요청은 손당 기준점 한 개이므로 다섯 손가락 끝 전체는 전송하지 않는다. 주먹·엄지도 같은 기준점을 사용한다.
- MediaPipe `landmarks[i][8].x * imageWidth`, `.y * imageHeight`로 변환한다. 같은 인덱스의 `gestures[i][0].categoryName`, `.score`를 전달한다.
- `gesture`: `None`, `Closed_Fist`, `Open_Palm`, `Pointing_Up`, `Thumb_Down`, `Thumb_Up`, `Victory`, `ILoveYou` 중 하나. 미분류는 `None`, score가 없으면 0을 쓴다.
- `confidence`는 유한한 0~1 숫자. 손 없음은 `hands:[]`. 여러 손은 누락 없이 전달하며 API는 최대 10개를 받는다. MediaPipe의 `numHands`도 설정해야 한다(아래 예제는 2).
- width/height와 x/y/confidence 및 gesture는 모두 필수다. 이미지 크기가 파일과 다르면 400이다.

## 회전·이미지 일치

파일을 브라우저에서 올바른 방향으로 디코딩한 뒤 canvas에 그리고 JPEG/PNG로 재인코딩한다. **이 canvas를 MediaPipe에 넣고, 같은 canvas에서 만든 Blob을 업로드**한다. EXIF가 남은 JPEG/PNG는 서버에서 400으로 거절한다(orientation=1이어도 동일). 원본 HEIC 파일 대신 canvas 변환 결과를 보낸다. 브라우저가 HEIC를 디코딩하지 못하면 JPEG/PNG 재선택을 안내한다.

서버는 회전·좌우 반전을 추가하지 않는다. CSS `scaleX(-1)`을 좌표에 적용하지 않는다. 화면 표시도 canvas와 같은 방향을 유지한다. EXIF·가로세로 크기만으로 프론트가 서로 다른 파일을 보냈는지까지 검증할 수는 없다.

## 프론트 연결 예제

`recognizer`는 `@mediapipe/tasks-vision`의 `GestureRecognizer.createFromOptions`로 `runningMode:"IMAGE", numHands:2` 설정 후 재사용한다. WASM과 `.task` 모델 파일을 제공해야 하며 패키지와 WASM 버전을 맞춰 고정한다. 업로드 사진 분석은 `recognize(canvas)`를 사용한다. UI 멈춤이 문제가 되면 Worker에서 실행한다.

```ts
async function analyzePhoto(file: File, recognizer: GestureRecognizer, token?: string) {
  const bitmap = await createImageBitmap(file, { imageOrientation: "from-image" });
  const canvas = document.createElement("canvas");
  canvas.width = bitmap.width;
  canvas.height = bitmap.height;
  const context = canvas.getContext("2d");
  if (!context) { bitmap.close(); throw new Error("사진을 처리할 수 없어요."); }
  context.fillStyle = "white";
  context.fillRect(0, 0, canvas.width, canvas.height);
  context.drawImage(bitmap, 0, 0);
  bitmap.close();
  const result = recognizer.recognize(canvas);
  const analysis = {
    imageWidth: canvas.width,
    imageHeight: canvas.height,
    hands: result.landmarks.map((landmarks, i) => ({
      x: landmarks[8].x * canvas.width,
      y: landmarks[8].y * canvas.height,
      gesture: result.gestures[i]?.[0]?.categoryName || "None",
      confidence: result.gestures[i]?.[0]?.score ?? 0,
    })),
  };
  const blob = await new Promise<Blob>((resolve, reject) =>
    canvas.toBlob(value => value ? resolve(value) : reject(new Error("사진 변환 실패")),
      "image/jpeg", 0.9));
  // 큰 사진은 canvas를 먼저 축소한 후 그 canvas에서 인식과 업로드를 모두 실행한다.
  if (blob.size > 5 * 1024 * 1024) throw new Error("사진은 5MB 이하로 올려 주세요.");
  const csrfResponse = await fetch("/auth/csrf", { credentials: "include" });
  if (!csrfResponse.ok) throw new Error("요청을 준비하지 못했어요.");
  const csrf = await csrfResponse.json();
  const body = new FormData();
  body.append("photo", blob, "photo.jpg");
  if (token) body.append("token", token);
  body.append("analysis", new Blob([JSON.stringify(analysis)], { type: "application/json" }));
  const response = await fetch("/hand-analyze", {
    method: "POST", credentials: "include", headers: { [csrf.headerName]: csrf.token }, body,
  }); // multipart Content-Type은 직접 지정하지 않는다(boundary 자동 생성).
  if (!response.ok) throw new Error(`사진 분석 실패 (${response.status})`);
  return response.json();
}
```

별도 프론트 origin을 쓰면 API base URL과 배포 CORS·쿠키 설정(#32)을 맞춘다. 비로그인 판매자도 호출할 수 있으며 세션 CSRF 토큰은 필요하다.

## 응답

```json
{
  "status": "ANALYZED",
  "imageWidth": 1000,
  "imageHeight": 800,
  "screen": {"x":400,"y":100,"width":300,"height":600,"text":"공연 A"},
  "hands": [{"x":300,"y":420,"gesture":"Victory","confidence":0.96,"position":"LEFT","verticalPosition":"OVERLAP","nearScreen":false,"missionMatched":true}],
  "missionCheck": {"mission":{"type":"GESTURE_POSITION","gesture":"Victory","position":"LEFT"},"status":"MATCHED","matchedHandIndexes":[0]}
}
```

위 예시는 token에 저장된 미션이 브이+왼쪽인 경우다. token이 없으면 mission은 null, missionCheck.status는 NOT_REQUESTED이며 손별 missionMatched도 null이다.

`screen`은 휴대폰 외곽이 아닌 예매 화면의 축 정렬 사각형이며 원본 업로드 픽셀 좌표다. 서버가 모델 입력을 축소해도 응답은 원본 좌표로 환산한다. `text`는 화면에서 읽힌 텍스트(최대 2000자)이며 누락·오인식 가능하다.

| status | 의미 |
| --- | --- |
| ANALYZED | 확실한 예매 화면을 찾고 각 손 위치 계산 |
| NO_HANDS | 손 없음. LLM 호출 생략 |
| SCREEN_NOT_FOUND | 예매 화면을 찾지 못함 |
| SCREEN_UNCERTAIN | 여러 화면·불확실한 경계 등으로 비교 불가 |

`ANALYZED` 외에는 `screen:null`, 손이 있다면 각 `position:UNKNOWN`이다. 왼쪽/오른쪽은 사람의 왼손/오른손 구분이 아니다.

- `x < screen.x`: LEFT
- `x > screen.x + screen.width`: RIGHT
- 그 사이와 양쪽 경계: OVERLAP (가로축 투영 기준이며 y는 위치 분류에 사용하지 않는다)

세로축은 `verticalPosition`으로 별도 반환한다. 화면 위는 ABOVE, 아래는 BELOW, 화면 내부와 경계는 OVERLAP이다. 화면이 없으면 두 축 모두 UNKNOWN이다. 대각선의 손은 가로·세로 관계를 각각 유지하므로 왼쪽이면서 위쪽일 수 있다.

## 미션 조건 비교

- GESTURE: Victory + confidence ≥ 0.8. 위치 제약은 없다.
- POINT_SCREEN: 검지 끝과 화면 사각형 사이의 최소 유클리드 거리가 화면 짧은 변의 10% 이하면 일치한다. 내부는 거리 0이다. Pointing_Up 분류를 강제하지 않으며, 손가락이 실제로 펴졌는지·방향 벡터는 현재 입력값만으로 검사하지 않는다.
- GESTURE_POSITION: 같은 손에서 브이 조건과 LEFT/RIGHT/ABOVE/BELOW 조건을 모두 만족해야 한다. 화면 경계는 해당 방향으로 인정하지 않는다.

손별 `nearScreen`과 `missionMatched`, 전체 `missionCheck`를 반환한다. 하나 이상의 손이 같은 손에서 조건을 만족하면 MATCHED다. 손 없음이나 모든 손 불일치는 NOT_MATCHED, 화면 미탐지·불확실은 UNDETERMINED다. `matchedHandIndexes`는 요청 hands 배열에서 일치한 인덱스 목록이다. 화면이 없으면 손별 nearScreen/missionMatched는 null이다.

미션은 클라이언트 JSON에서 받지 않고 token으로 서버에서 읽는다. 시작한 판매자 세션과 SECOND_WAITING 단계가 필요하다. 두 번째 사진 업로드 전에 호출하며, 마감/요청 만료는 410, 기존 임시 미션·잘못된 단계는 409, 다른 브라우저는 403이다. 예전 요청은 새로 생성해야 한다.

오류: 400 잘못된 이미지·JSON·좌표·크기·EXIF, 403 CSRF 누락, 413 5MiB 초과, 415 잘못된 part Content-Type, 503 모델 설정 누락·시간 초과·서비스 오류·잘못된 모델 출력. 업무 오류는 `{"message":"..."}`이며 Spring 기본 400/403/415 응답 본문은 다를 수 있다.

## 실행과 남은 연결

기존 `openai.api-key`, `openai.model` 등 트루샷 OpenAI 설정을 재사용한다. 사진·텍스트·손 결과를 DB/Storage에 저장하지 않으며 응답은 `Cache-Control: no-store`다. 이미지는 화면 인식을 위해 모델 공급자로 전송된다.

실행 전에 `src/main/resources/db/trueshot-second-mission-schema.sql`로 미션 종류·위치 컬럼을 추가한다. 실제 DB 적용은 수행하지 않았다. 기존 `POST /s/{token}/photo`는 변경하지 않았다. 클라이언트 MediaPipe 값은 변경 가능한 입력이므로 이 조건 비교만으로 최종 인증 상태를 바꾸지 않는다. 최종 결과 저장·공유·원본 삭제 연결은 후속 작업이다.

자동 테스트는 모델을 대체해 계약·오류·계산을 검증한다. 실제 사진 LLM 위치 정확도와 React/모바일 회전 동작은 실제 연동 후 검증해야 한다.
