// 손 선별(handSelection.ts) 실측 확인용 dev 페이지. `npm run dev` 후 /gesture-check.html 로 연다.
//
// 왜 별도 페이지인가: 실제 인식 루프는 대기방(LiveKit 방 + 백엔드)에 들어가야 돌기 때문에,
// MIN_HAND_DEPTH 같은 상수를 카메라로 재보려면 MySQL/Redis/백엔드까지 다 띄워야 했다. 이 페이지는
// getUserMedia + MediaPipe만 쓰므로 프론트 dev 서버 하나로 끝난다.
//
// 인식 루프(useHandGestureRecognition)와 같은 모듈·같은 순서를 쓴다: 반전 프레임에서 detect →
// 픽셀 좌표 계산 → describeHandCandidate → selectComboHands → 선별된 손만 분류기에 넣는다.
// 선별에서 버려진 손도 빨강으로 그려서 "무엇이 왜 빠졌는지"가 눈에 보이게 했다.
import { DrawingUtils, HandLandmarker } from '@mediapipe/tasks-vision';
import { createHandLandmarker } from './src/features/gesture/lib/handLandmarker';
import { calcLandmarkList, preProcessLandmark, combineTwoHandLandmarks } from './src/features/gesture/lib/landmarkPreprocessing';
import { describeHandCandidate, selectComboHands, MIN_HAND_DEPTH } from './src/features/gesture/lib/handSelection';
import { classifyKeyPoint } from './src/features/gesture/lib/keypointClassifier';
import { measureHandProximity, handProximityFactor, handGapLimitFor } from './src/features/gesture/lib/handProximity';

const mirror = document.getElementById('mirror') as HTMLCanvasElement;
const skeleton = document.getElementById('skeleton') as HTMLCanvasElement;
const readout = document.getElementById('readout') as HTMLDivElement;
const mirrorCtx = mirror.getContext('2d')!;
const skeletonCtx = skeleton.getContext('2d')!;
const drawingUtils = new DrawingUtils(skeletonCtx);

// 훅과 같은 좌우 라벨 보정 (이유는 useHandGestureRecognition.ts 주석 참고)
const correctHandedness = (label: 'Left' | 'Right') => (label === 'Left' ? 'Right' : 'Left');

async function main() {
  const video = document.createElement('video');
  video.autoplay = true;
  video.playsInline = true;
  video.muted = true;

  readout.textContent = '카메라를 여는 중…';
  const stream = await navigator.mediaDevices.getUserMedia({ video: { width: 960, height: 540 } });
  video.srcObject = stream;
  await video.play();

  readout.textContent = '인식 모델을 내려받는 중… (첫 실행은 8MB 다운로드라 조금 걸린다)';
  const landmarker = await createHandLandmarker();

  let engaged = false;

  const loop = () => {
    requestAnimationFrame(loop);
    if (video.readyState < 2 || video.videoWidth === 0) return;

    const width = video.videoWidth;
    const height = video.videoHeight;
    for (const canvas of [mirror, skeleton]) {
      if (canvas.width !== width || canvas.height !== height) {
        canvas.width = width;
        canvas.height = height;
      }
    }

    // 학습 파이프라인과 맞추기 위해 반전 프레임에서 detect (훅과 동일)
    mirrorCtx.save();
    mirrorCtx.scale(-1, 1);
    mirrorCtx.drawImage(video, -width, 0, width, height);
    mirrorCtx.restore();

    const now = performance.now();
    const detection = landmarker.detectForVideo(mirror, now);

    const candidates = detection.landmarks
      .map((landmarks, i) => {
        const raw = (detection.handedness[i]?.[0]?.categoryName ?? 'Right') as 'Left' | 'Right';
        const pixels = calcLandmarkList(landmarks, width, height);
        return describeHandCandidate(correctHandedness(raw), landmarks, pixels, width, height);
      })
      .filter((c): c is NonNullable<typeof c> => c !== null);

    const selection = selectComboHands(candidates, engaged);
    engaged = selection.hands.length > 0;
    const selected = new Set(selection.hands);

    skeletonCtx.clearRect(0, 0, width, height);
    // 버린 손을 먼저 그려서 겹칠 때 선별 통과한 손이 위에 오게 한다
    for (const candidate of candidates) {
      const kept = selected.has(candidate);
      drawingUtils.drawConnectors(candidate.landmarks, HandLandmarker.HAND_CONNECTIONS, {
        color: kept ? '#00e676' : '#ff5252',
        lineWidth: kept ? 3 : 2,
      });
      drawingUtils.drawLandmarks(candidate.landmarks, {
        color: kept ? '#ffffff' : '#ff8a80',
        radius: 2,
      });
    }

    // 선별된 손만 분류기에 넣는다 (훅과 동일)
    const preprocessed: Partial<Record<'Left' | 'Right', number[]>> = {};
    const pixels: Partial<Record<'Left' | 'Right', ReturnType<typeof calcLandmarkList>>> = {};
    for (const candidate of selection.hands) {
      pixels[candidate.handedness] = candidate.pixels;
      preprocessed[candidate.handedness] = preProcessLandmark(candidate.pixels);
    }

    let comboLine = '조합 판정: 양손이 다 잡혀야 나옴';
    if (preprocessed.Left && preprocessed.Right && pixels.Left && pixels.Right) {
      const { label, confidence } = classifyKeyPoint(
        combineTwoHandLandmarks(preprocessed.Left, preprocessed.Right),
      );
      const proximity = measureHandProximity(pixels.Left, pixels.Right);
      const factor = proximity ? handProximityFactor(label, proximity) : 1;
      comboLine =
        `조합 판정: ${factor > 0 ? label : `${label} (손 거리 초과로 기각)`} ` +
        `${(confidence * factor * 100).toFixed(0)}% · 손 거리 ` +
        `${proximity ? proximity.gap.toFixed(2) : '-'} / 허용 ${handGapLimitFor(label).toFixed(2)}`;
    }

    const lines = [
      `depth 하한 ${MIN_HAND_DEPTH.toFixed(3)}   (잡고 있는 중엔 ${(MIN_HAND_DEPTH * 0.85).toFixed(3)}까지 유지)`,
      `가장 가까운 손 depth ${selection.nearestDepth?.toFixed(3) ?? '-'}   ` +
        `후보 ${candidates.length}개 · 선별 ${selection.hands.length}개 · 너무 멀어서 버림 ${selection.farHandCount}개`,
      '',
      ...candidates.map((c) => {
        const mark = selected.has(c) ? '○ 사용' : c.depth < MIN_HAND_DEPTH * (engaged ? 0.85 : 1) ? '× 너무 멂' : '× 밀림';
        return (
          `${mark}  ${c.handedness.padEnd(5)} depth ${c.depth.toFixed(3)}  ` +
          `중심이탈 ${c.centerOffset.toFixed(2)}  점수 ${c.score.toFixed(4)}`
        );
      }),
      '',
      comboLine,
    ];
    readout.textContent = lines.join('\n');
  };

  loop();
}

main().catch((err) => {
  readout.textContent = `실패: ${String(err)}`;
});
