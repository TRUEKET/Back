# 트루샷 최종 판정·사진 공유 API (#49)

## 실행 준비

기존 OpenAI 및 Private Supabase Storage 설정을 사용한다. `src/main/resources/db/trueshot-judgment-schema.sql`을 서버 실행 전에 적용한다. 새 컬럼은 `trueshot_photos.hand_analysis`, `trueshot_requests.judgment_result` 두 JSONB이며 기존 데이터는 변경하지 않는다. 자동 마이그레이션은 없고 이 작업에서 실제 DB에는 적용하지 않았다.

## 2차 사진 제출

`POST /s/{token}/photo`, multipart: `n=2`, `photo=사진`, `analysis=application/json`.

```json
{"imageWidth":1000,"imageHeight":800,"hands":[{"x":300,"y":420,"gesture":"Victory","confidence":0.96}]}
```

첫 사진(n=1)에는 analysis가 필요 없다. 2차에서는 필수이며 손 없음은 빈 hands 배열을 보낸다. 사진과 좌표는 회전을 적용한 동일 canvas 기준이다. 검지 끝·신뢰도·EXIF 계약은 [손 분석 API](hand-analyze-api.md)와 같다.

접수 트랜잭션에서 파일과 분석 입력을 저장한 뒤 JUDGING으로 바꾼다. 그 다음 별도 트랜잭션에서 서버 저장 미션과 비교한다. 독립 `/hand-analyze`를 미리 호출했더라도 그 결과를 재사용하거나 클라이언트 통과 값을 받지 않고 접수한 원본을 서버에서 다시 분석한다.

```json
{"number":2,"phase":"SHARE_WAITING","accepted":true,"deadline":null,"retryReasons":[]}
```

`accepted`는 사진 접수 여부다. 미션 불일치면 FAILED, 성공하고 두 사진의 가림이 준비되면 SHARE_WAITING, 성공했으나 가림이 불확실하면 COMPLETED로 결과만 제공한다. 모델·Storage 일시 장애(503) 시에는 JUDGING으로 접수 응답을 반환한다. 클라이언트는 같은 사진을 다시 업로드하지 말고 결과 조회/판정 재시도를 사용한다.

중복 2차 제출은 409. 분석 입력 누락·잘못된 크기·좌표 등은 400이며 제출을 소비하지 않는다. 1분 마감은 서버 수신 시각으로 검사한다. 전체 30분 기한은 분석 도중에도 적용한다.

## 최종 판정 기준·저장

- 첫 사진의 기존 코드·화질 확인 완료(first_verified_at)를 요구한다. 두 번째 사진에 인증코드 재노출을 추가 요구하지 않는다.
- 서버가 찾은 확실한 예매 화면과 프론트 MediaPipe 값으로 확정된 미션을 비교한다.
- 브이 신뢰도 0.8 이상, 가리키기 근접 거리 화면 짧은 변의 10%, 위치 미션은 같은 손에서 동작과 방향 모두 만족한다.
- 손 없음·화면 없음·화면 불확실·동작/위치 불일치는 실패다. 외부 서비스 장애는 판정 실패로 저장하지 않고 JUDGING에서 재시도한다.
- 저장된 judgment_result는 첫 사진 확인 여부, 분석 상태, 미션 비교 결과, 실패 사유를 포함한다. OCR 원문과 내부 Storage 키는 포함하지 않는다.
- 가림 처리 장애가 나면 이미 계산한 결과를 저장해 두고 다음 재시도에서 화면 분석을 생략한다. 판정이 완료되기 전에는 구매자 상세에 임시 체크 결과를 노출하지 않는다.

최종 성공은 이 촬영 미션을 만족했다는 의미다. 클라이언트 손 모양·좌표를 서버가 재인식하거나 사진 두 장의 티켓 동일성·진위·소유권을 보장하는 구현은 포함하지 않는다.

## 판매자 결과·재시도

시작한 판매자 세션과 CSRF 토큰을 유지한다(GET은 CSRF 불필요).

- `GET /s/{token}/result`: 현재 상태·공유 가능 여부 조회
- `POST /s/{token}/judge`: 접수한 동일 사진으로 판정 재시도. 완료된 판정은 반복하지 않는다.

```json
{"status":"PASSED","phase":"SHARE_WAITING","judgedAt":"2026-11-15T10:00:00Z","photosSharedAt":null,"canShare":true,"message":"사진 확인을 완료했어요."}
```

판매자 응답에는 상세 실패 사유·내부 체크 결과를 포함하지 않는다. JUDGING인 요청은 기존 1분 주기 작업에서도 자동 재시도한다. 요청 행 잠금으로 중복 실행을 직렬화한다. 두 번째 사진 접수 전 판정은 409다.

## 가림 미리보기·사진 공유

`GET /s/{token}/preview/1`, `GET /s/{token}/preview/2`에서 가린 JPEG만 반환한다. 첫 사진 가림 단계에서는 1번만, SHARE_WAITING에서는 두 사진을 볼 수 있다. 원본 다운로드 경로는 제공하지 않는다.

`POST /s/{token}/share`, JSON:

```json
{"sharePhotos":true,"boxes":[{"x":0.2,"y":0.3,"width":0.1,"height":0.1}]}
```

boxes는 두 번째 사진에 추가할 가림 영역이며 0~1 비율, 최대 300개다. 추가 영역이 없으면 빈 배열이다. 기존 자동 가림을 지울 수 없고 가린 사진 위에만 덧칠한다. 두 사진이 모두 READY일 때만 공유할 수 있다.

공유 동의 후 photos_shared_at을 기록하고 COMPLETED로 전환한다. `sharePhotos:false, boxes:[]`면 공유 없이 COMPLETED로 전환한다. 완료 후 재전송은 기존 결정을 유지하며 파일·공유 시각을 변경하지 않는다. 추가 가림 저장 실패 시 동의를 기록하지 않으므로 재시도할 수 있다.

공유 기한은 기존 요청의 expires_at(생성+30분)이다. 기한이 지나면 PASSED 결과는 유지하고 사진 공유는 차단한다. 최초 늦은 공유 시도는 410, 이후 완료된 요청 재전송은 기존 결과를 반환한다.

## 구매자 조회

로그인한 요청 소유자만 접근한다.

- `GET /trueshot/{id}`: judgedAt, judgmentResult, photosSharedAt, photos를 기존 응답에 추가한다.
- `GET /trueshot/{id}/photos/1`, `/photos/2`: 공유에 동의한 가림 JPEG를 반환한다.

photos 예시:

```json
[{"number":1,"url":"/trueshot/123/photos/1"},{"number":2,"url":"/trueshot/123/photos/2"}]
```

공유 전·공유 거절·실패·가림 미완료·보관 기한 만료 상태에서는 사진 목록이 비고 다운로드는 404다. 다른 구매자도 404, 비로그인은 401이다. 모든 사진 및 결과 응답은 Cache-Control: no-store다. URL은 인증이 필요한 서버 경로이며 공개 Storage URL이 아니다.

## 원본 정리·남은 검증

최종 성공/실패 결과를 확정한 뒤 원본 두 장의 삭제를 즉시 시도한다. 삭제 오류가 나도 결과는 유지한다. 파일 키와 삭제 시각은 삭제 성공 때만 갱신하고, 기존 주기 작업에서 PASSED/FAILED 원본을 재시도한다. 가림/판정에 필요한 원본을 처리 도중 지우지 않는다.

자동 테스트는 H2와 대체 모델/Storage로 상태·권한·동의·만료·일시 장애·삭제 재시도·동시 판정을 검증한다. 실제 React·모바일 회전·LLM 화면 좌표·가림 정확도 및 실제 Storage 통합은 실연동에서 검증해야 한다. 7일 보관 만료 전체 삭제는 후속 이슈 범위다.
