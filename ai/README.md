# Cam-ON AI 서버

물건 가져오기 게임용 stateless 추론 서버. SigLIP 2 zero-shot 매칭으로
"이 프레임(ROI crop)에 무슨 물건이 있나"를 분류해서 응답한다.
**정답 여부/순위 판정은 Spring의 몫** — 여기는 인식 결과만 준다 (API 명세 참고).

## 환경 (miniforge)

```bash
conda create -n camon-siglip python=3.11
conda activate camon-siglip
pip install -r requirements.txt
# GPU 서버는 torch만 CUDA 빌드로 재설치 (서버에서 nvidia-smi로 CUDA 버전 확인 후)
# 예: pip install torch --index-url https://download.pytorch.org/whl/cu121
```

## 실시간 검증 (권장)

서버를 띄우고 브라우저에서 `http://localhost:8100/test` 접속 —
웹캠 + ROI 박스 + 제시어 선택 + 실시간 판정(왕복 지연·점수·연속 일치)이 한 화면에 나온다.
GPU 노트북이면 so400m으로 띄워서 테스트:

```bash
# PowerShell
$env:AI_MODEL_ID = "google/siglip2-so400m-patch14-384"
uvicorn app.main:app --host 0.0.0.0 --port 8100
```

## 정적 이미지 검증 (모델끼리 점수 비교용)

`samples/`에 사진을 넣고 (gitignore 됨):

```bash
python scripts/validate.py samples/*.jpg
```

## 서버 실행

```bash
uvicorn app.main:app --host 0.0.0.0 --port 8100
```

| 환경변수 | 기본값 | 설명 |
| --- | --- | --- |
| `AI_MODEL_ID` | `google/siglip2-base-patch16-224` | GPU 서버에선 `google/siglip2-so400m-patch14-384` 권장 |
| `AI_CONFIDENCE_THRESHOLD` | `0.2` | is_valid 판정 임계값 (validate.py로 튜닝) |
| `FRONTEND_BASE_URL` | (없음) | 배포 프론트 오리진 (CORS). dev 5173은 항상 허용 |

## 엔드포인트

- `GET /health` — 모델/디바이스 확인
- `POST /ai/games/{gameId}/fetch-object/detections` — multipart
  - `image`: ROI crop (JPEG 권장, 짧은 변 384px이면 충분)
  - `target`(선택): 이번 라운드 제시어 — 미션 풀에 없는 단어를 후보에 추가하는 힌트
  - 응답: `{data: {detectedValue, confidence, isValid, scores}}`
    - `detectedValue`: 가장 유력한 물건 (아무것도 인식 못 하면 null)
    - `isValid`: 임계값 통과 여부 (인식이 쓸만한가 — 정답 여부 아님)

```bash
curl -F "image=@samples/spoon1.jpg" -F "target=숟가락" \
  http://localhost:8100/ai/games/2/fetch-object/detections
```

## 구조/정책 메모

- 방/라운드 상태를 전혀 모르는 stateless 서비스. Spring과 직접 통신하지 않는다
  (판정 결과를 Spring에 전달하는 다리는 프론트 — AGENTS.md 아키텍처).
- 미션 풀은 `app/labels.py`의 mock — MySQL missions 확정 후 교체 예정.
- 첫 실행 시 HuggingFace에서 모델을 내려받는다 (base ~800MB, so400m ~4GB).
- 프론트 연동 시 전송 주기는 라운드 중 2~4fps면 충분하다.
