# MediaPipe 2차 사진 제출 연동

React에서 MediaPipe로 손 좌표·동작을 추출하고 사진과 결과를 2차 사진 제출 API로 함께 보낸다. Spring Boot는 같은 사진에서 예매 화면을 LLM으로 찾고 서버 저장 미션과 비교해 최종 결과를 저장한다. 별도 Python 서버·모델 학습은 필요 없다.

기존 독립 손 분석 API는 제거했다. 사진 제출 전에 별도 서버 분석을 호출하지 않는다. 파일명은 기존 문서 링크를 유지하기 위해 그대로 사용한다.

## 요청

`POST /s/{token}/photo`, `multipart/form-data`

| part | Content-Type | 내용 |
| --- | --- | --- |
| n | 텍스트 form field | `2` |
| photo | image/jpeg 또는 image/png | MediaPipe로 분석한 동일 이미지. 최대 5MiB, 양 변 최소 320px, 최대 4천만 픽셀 |
| analysis | application/json | 아래 JSON |

```json
{
  "imageWidth": 1000,
  "imageHeight": 800,
  "hands": [{"x": 300, "y": 420, "gesture": "Victory", "confidence": 0.96}]
}
```

- token은 판매자 링크의 경로 값이다. 별도 multipart token이나 클라이언트의 미션·통과 여부는 받지 않는다.
- 첫 사진 `n=1`에는 analysis가 필요 없다. 2차에서는 필수이며 손이 없으면 `hands:[]`로 보낸다.
- `x,y`는 업로드 이미지 왼쪽 위 원점 기준 **검지 끝 landmark 8의 픽셀 좌표**다. CSS 표시 크기를 사용하지 않는다.
- MediaPipe의 `landmarks[i][8].x * imageWidth`, `.y * imageHeight`로 변환한다. 같은 인덱스의 `gestures[i][0].categoryName`, `.score`를 전달한다.
- 허용 동작은 `None`, `Closed_Fist`, `Open_Palm`, `Pointing_Up`, `Thumb_Down`, `Thumb_Up`, `Victory`, `ILoveYou`다. 미분류는 None, score가 없으면 0이다.
- confidence는 유한한 0~1 숫자다. 좌표 범위는 `[0,imageWidth]`, `[0,imageHeight]`이고 여러 손은 누락 없이 최대 10개까지 보낸다. MediaPipe의 numHands도 설정한다(아래 예제는 인식기 초기화 시 2로 설정).
- 이미지 크기·손 좌표·동작·신뢰도는 모두 필수이며 잘못된 입력은 제출을 소비하지 않는다.

## 회전·이미지 일치

촬영한 이미지를 올바른 방향으로 디코딩한 뒤 canvas에 그리고 JPEG/PNG로 재인코딩한다. **같은 canvas를 MediaPipe에 넣고 그 canvas의 Blob을 전송한다.** 원본 파일의 EXIF가 남으면 orientation=1이어도 서버에서 400으로 거절한다. CSS 좌우 반전을 좌표에만 적용하지 않으며 화면 표시와 실제 이미지 방향도 일치시킨다.

사진을 줄일 때는 먼저 canvas를 축소한 뒤 그 canvas에서 인식과 파일 생성을 모두 진행한다. EXIF와 크기 검사만으로 서로 다른 파일을 보냈는지까지 검증할 수는 없다. 카메라 촬영·갤러리 선택 UI는 프론트에서 제어한다.

## 프론트 연결 예제

인식기는 `@mediapipe/tasks-vision`의 GestureRecognizer를 `runningMode:"IMAGE", numHands:2`로 초기화해 재사용한다. WASM·모델 파일을 제공하고 패키지와 WASM 버전을 고정한다. recognize는 동기 함수이므로 UI 멈춤이 문제가 되면 Worker에서 실행한다.

```ts
async function submitSecondPhoto(token: string, capturedPhoto: Blob, recognizer: GestureRecognizer) {
  const bitmap = await createImageBitmap(capturedPhoto, { imageOrientation: "from-image" });
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
  if (blob.size > 5 * 1024 * 1024) throw new Error("사진은 5MB 이하로 올려 주세요.");
  const csrfResponse = await fetch("/auth/csrf", { credentials: "include" });
  if (!csrfResponse.ok) throw new Error("요청을 준비하지 못했어요.");
  const csrf = await csrfResponse.json();
  const body = new FormData();
  body.append("n", "2");
  body.append("photo", blob, "photo.jpg");
  body.append("analysis", new Blob([JSON.stringify(analysis)], { type: "application/json" }));
  const response = await fetch(`/s/${encodeURIComponent(token)}/photo`, {
    method: "POST", credentials: "include", headers: { [csrf.headerName]: csrf.token }, body,
  }); // multipart Content-Type은 boundary가 자동 생성되도록 직접 지정하지 않는다.
  if (!response.ok) throw new Error(`사진 제출 실패 (${response.status})`);
  return response.json();
}
```

시작한 판매자 세션과 CSRF 토큰이 필요하다. 별도 프론트 origin이면 API base URL과 배포 CORS·쿠키 설정을 맞춘다.

## 응답·후속 처리

```json
{"number":2,"phase":"SHARE_WAITING","accepted":true,"deadline":null,"retryReasons":[]}
```

accepted는 접수 성공이며 최종 인증 성공 여부와 구분한다. phase는 정상 판정·가림 완료 시 SHARE_WAITING, 불일치 시 FAILED, 가림이 불확실해 결과만 제공하면 COMPLETED, 외부 서비스 일시 장애 시 JUDGING일 수 있다.

`GET /s/{token}/result`로 판매자 결과를 조회한다. 제출 응답에는 해당 사진의 checks·failReasons, 결과 조회에는 두 사진의 checks·failReasons가 포함된다. JUDGING이면 사진을 다시 업로드하지 않고 결과를 조회한다. 서버가 같은 원본으로 기본 10초 간격으로 자동 재시도하며, 접수 후 최대 기본 60초를 넘기면 분석 시간 초과로 종료한다. 수동 재시도 API는 제공하지 않는다. 구매자는 로그인한 소유자 권한으로 `GET /trueshot/{id}`에서 최종 결과와 체크 내용을 확인한다. 내부 분석의 화면·손 좌표와 OCR 원문을 별도 응답으로 직접 반환하지 않는다.

## 미션 비교

- GESTURE: Victory + confidence ≥ 0.8.
- POINT_SCREEN: 검지 끝과 화면 사각형의 최소 유클리드 거리가 화면 짧은 변의 10% 이내. 내부는 거리 0이다. Pointing_Up 분류를 강제하거나 손가락 방향 벡터를 검사하지 않는다.
- GESTURE_POSITION: 같은 손에서 브이 조건과 LEFT/RIGHT/ABOVE/BELOW 조건을 모두 만족해야 한다. 화면 경계는 지정 방향으로 인정하지 않는다.

손 없음·화면 없음·화면 불확실은 통과시키지 않는다. 같은 화면 탐지 호출에서 2차 사진의 화질도 확인하며, 미션이 일치해도 흐린 사진은 실패 처리한다. 미션은 경로 token에 해당하는 서버 저장값을 사용한다. 손 모양은 프론트가 인식한 값을 사용하며 서버에서 다시 인식하지 않는다.

오류는 400 잘못된 사진·analysis·좌표·EXIF, 403 판매자 세션·CSRF, 409 중복 제출·단계 불일치, 410 제한 시간 초과, 413 용량 초과, 415 part Content-Type 오류다. 접수 후 외부 서비스 장애는 JUDGING에서 재시도한다.

최종 판정 저장·가림·공유·원본 정리·재시도는 [최종 판정·공유 명세](trueshot-finalization-api.md)를 따른다. 실제 React·모바일 회전·사진 정확도는 실연동에서 검증한다.

참고: [팀 지정 샘플](https://github.com/google-ai-edge/mediapipe-samples-web/blob/main/src/tasks/gesture-recognizer.ts), [공식 Web 가이드](https://developers.google.com/edge/mediapipe/solutions/vision/gesture_recognizer?tab=Web).

JUDGING 동안 GET result의 pollAfterMillis(기본 2000)에 맞춰 조회하고, 다른 phase가 되면 로딩을 종료한다. judgmentDeadline은 판정 마감, failureCode=ANALYSIS_TIMEOUT은 분석 시간 초과다. JUDGING 제출 응답의 deadline에도 판정 마감을 제공한다. [대기 시간 설정과 처리 기준](trueshot-finalization-api.md#판정-대기-시간프론트-로딩-종료)을 참고한다.
