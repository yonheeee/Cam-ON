import { useCallback, useEffect, useRef, useState } from 'react';
import { useLocalParticipant, useMediaDeviceSelect } from '@livekit/components-react';
import type { LocalAudioTrack } from 'livekit-client';
import { DrawingUtils, HandLandmarker } from '@mediapipe/tasks-vision';
import { useHandGestureRecognition } from '../../gesture/hooks/useHandGestureRecognition';
import { aiApi } from '../../fetch/api/aiApi';
import './SettingsModal.css';

// ---- 마이크 입력 레벨 (0~5칸) ----
// components-react의 useTrackVolume(@alpha)이 로컬 마이크에서 0에 머무는 문제가 있어
// WebAudio 분석기로 직접 잰다. RMS(말소리 기준 대략 0.02~0.3)를 칸 수로 양자화해서
// 칸이 바뀔 때만 리렌더한다 (매 프레임 setState 방지).
const MIC_LEVEL_STEPS = [0.02, 0.05, 0.1, 0.18, 0.28];

function useMicLevelBars(track: LocalAudioTrack | undefined): number {
  const [bars, setBars] = useState(0);
  useEffect(() => {
    const mediaTrack = track?.mediaStreamTrack;
    if (!mediaTrack) {
      setBars(0);
      return;
    }
    const ctx = new AudioContext();
    void ctx.resume(); // 사용자 제스처(모달 열기) 직후라 허용됨 — suspended로 시작하는 브라우저 대비
    const source = ctx.createMediaStreamSource(new MediaStream([mediaTrack]));
    const analyser = ctx.createAnalyser();
    analyser.fftSize = 512;
    analyser.smoothingTimeConstant = 0.5;
    source.connect(analyser);
    const data = new Uint8Array(analyser.fftSize);
    let raf = 0;
    let lastBars = -1;
    const tick = () => {
      analyser.getByteTimeDomainData(data);
      let sum = 0;
      for (let i = 0; i < data.length; i += 1) {
        const v = (data[i] - 128) / 128;
        sum += v * v;
      }
      const rms = Math.sqrt(sum / data.length);
      const next = MIC_LEVEL_STEPS.filter((t) => rms > t).length;
      if (next !== lastBars) {
        lastBars = next;
        setBars(next);
      }
      raf = requestAnimationFrame(tick);
    };
    tick();
    return () => {
      cancelAnimationFrame(raf);
      source.disconnect();
      void ctx.close();
    };
  }, [track]);
  return bars;
}

// 스피커 테스트음 — 게임 정답 효과음을 재사용한다 (별도 에셋 없이 짧고 듣기 좋은 소리).
const SPEAKER_TEST_SOUND = '/assets/sounds/correct.mp3';

interface SettingsModalProps {
  onClose: () => void;
}

// 장치 목록으로 권한/연결 상태를 판별한다. enumerateDevices()는 권한이 없으면
// label이 빈 문자열인 장치를 돌려주고, 장치가 아예 없으면 빈 배열을 준다.
type DeviceStatus = 'ok' | 'permission' | 'none';
function deviceStatus(devices: MediaDeviceInfo[]): DeviceStatus {
  if (devices.length === 0) return 'none';
  if (devices.every((d) => !d.label)) return 'permission';
  return 'ok';
}

// ---- 아이콘 (시안의 원형 칩 안에 들어가는 단순 픽토그램) ----
const HandIcon = (
  <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden>
    <path d="M9 3h2v8h1V2h2v9h1V3h2v9h1V6h2v10c0 3.3-2.7 6-6 6h-2c-1.8 0-3.4-.8-4.5-2.1L4 15.5 5.5 14l2.5 2V3z" />
  </svg>
);
const CubeIcon = (
  <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden>
    <path d="M12 2 3 7v10l9 5 9-5V7l-9-5zm0 2.3L18.5 8 12 11.7 5.5 8 12 4.3zM5 9.7l6 3.4v6.6l-6-3.4V9.7zm8 10v-6.6l6-3.4v6.6l-6 3.4z" />
  </svg>
);
const MicIcon = (
  <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden>
    <path d="M12 14a3 3 0 0 0 3-3V5a3 3 0 0 0-6 0v6a3 3 0 0 0 3 3zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.9V21h2v-3.1A7 7 0 0 0 19 11h-2z" />
  </svg>
);
const SpeakerIcon = (
  <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden>
    <path d="M4 9v6h4l5 4V5L8 9H4zm12.5 3a3.5 3.5 0 0 0-2-3.2v6.4a3.5 3.5 0 0 0 2-3.2zm-2-7v2.1c2 .9 3.5 2.7 3.5 4.9s-1.5 4-3.5 4.9V19c3.2-1 5.5-3.7 5.5-7s-2.3-6-5.5-7z" />
  </svg>
);
const CameraIcon = (
  <svg viewBox="0 0 24 24" width="16" height="16" fill="currentColor" aria-hidden>
    <path d="M3 7a2 2 0 0 1 2-2h10a2 2 0 0 1 2 2v2.5l4-2.5v10l-4-2.5V17a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V7z" />
  </svg>
);
const PlayIcon = (
  <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden>
    <path d="M8 5v14l11-7L8 5z" />
  </svg>
);
const PauseIcon = (
  <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden>
    <path d="M7 5h4v14H7V5zm6 0h4v14h-4V5z" />
  </svg>
);
const RetryIcon = (
  <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden>
    <path d="M12 5V2L7 6l5 4V7a5 5 0 1 1-5 5H5a7 7 0 1 0 7-7z" />
  </svg>
);
const CamBlockedIcon = (
  <svg viewBox="0 0 24 24" width="40" height="40" fill="currentColor" aria-hidden>
    <path d="M3.3 2 2 3.3l3 3H5a2 2 0 0 0-2 2v8a2 2 0 0 0 2 2h10c.4 0 .8-.1 1.1-.3l4.6 4.6 1.3-1.3L3.3 2zM17 10.5V8.3a1 1 0 0 0-1-1h-3.2L21 15.5v-7l-4 2z" />
  </svg>
);
const CamMissingIcon = (
  <svg viewBox="0 0 24 24" width="40" height="40" fill="currentColor" aria-hidden>
    <path d="M5 5a2 2 0 0 0-2 2v10a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2v-3.5l4 2.5V8l-4 2.5V7a2 2 0 0 0-2-2H5zm5 3a3.5 3.5 0 1 1 0 7 3.5 3.5 0 0 1 0-7z" />
  </svg>
);

// ---- 장치 드롭다운 (시안: 아이콘 + 이름 + 화살표, 펼치면 점 찍힌 목록) ----
interface DeviceSelectProps {
  icon: React.ReactNode;
  devices: MediaDeviceInfo[];
  activeDeviceId: string;
  onSelect: (deviceId: string) => void;
  /** 상태별 표시 라벨 — ok면 활성 장치명, 아니면 안내 문구(핑크 강조) */
  status: DeviceStatus;
  fallbackLabel: string; // 예: "기본 카메라"
  permissionLabel: string; // 예: "카메라 권한 필요"
}

function DeviceSelect({
  icon,
  devices,
  activeDeviceId,
  onSelect,
  status,
  fallbackLabel,
  permissionLabel,
}: DeviceSelectProps) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  // 바깥 클릭으로 닫기
  useEffect(() => {
    if (!open) return;
    const onDown = (e: MouseEvent) => {
      if (rootRef.current && !rootRef.current.contains(e.target as Node)) setOpen(false);
    };
    document.addEventListener('mousedown', onDown);
    return () => document.removeEventListener('mousedown', onDown);
  }, [open]);

  const active = devices.find((d) => d.deviceId === activeDeviceId);
  const label =
    status === 'none'
      ? '장치 없음'
      : status === 'permission'
        ? permissionLabel
        : active?.label || fallbackLabel;

  return (
    <div className="settings-modal__select" ref={rootRef}>
      <button
        type="button"
        className={`pap-pixel-btn settings-modal__select-btn${
          status !== 'ok' ? ' settings-modal__select-btn--warn' : ''
        }`}
        onClick={() => setOpen((v) => !v)}
        disabled={status === 'none' || devices.length === 0}
      >
        <span className="settings-modal__select-icon">{icon}</span>
        <span className="settings-modal__select-label">{label}</span>
        <span className="settings-modal__select-chevron">{open ? '▲' : '▼'}</span>
      </button>
      {open && (
        <ul className="settings-modal__select-list" role="listbox">
          {devices.map((device) => {
            const selected = device.deviceId === activeDeviceId;
            return (
              <li key={device.deviceId}>
                <button
                  type="button"
                  role="option"
                  aria-selected={selected}
                  className={`settings-modal__select-item${
                    selected ? ' settings-modal__select-item--active' : ''
                  }`}
                  onClick={() => {
                    onSelect(device.deviceId);
                    setOpen(false);
                  }}
                >
                  <span className="settings-modal__select-dot" aria-hidden />
                  {device.label || fallbackLabel}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

// 환경설정 모달 — 카메라/마이크/스피커 장치 선택 + 카메라 미리보기(손 스켈레톤 오버레이) +
// 게임 인식 사전 점검(손동작/사물 인식/마이크 입력/스피커 출력). 피그마 확정안 기반.
// 장치 전환은 LiveKit switchActiveDevice로 즉시 적용되므로 "저장"은 사실상 닫기다.
export function SettingsModal({ onClose }: SettingsModalProps) {
  const { localParticipant, isCameraEnabled, cameraTrack, microphoneTrack } = useLocalParticipant();
  const videoRef = useRef<HTMLVideoElement>(null);
  const canvasRef = useRef<HTMLCanvasElement>(null);

  const cam = useMediaDeviceSelect({ kind: 'videoinput' });
  const mic = useMediaDeviceSelect({ kind: 'audioinput' });
  const spk = useMediaDeviceSelect({ kind: 'audiooutput' });
  const camStatus = deviceStatus(cam.devices);
  const micStatus = deviceStatus(mic.devices);
  // 스피커 목록은 권한 개념이 카메라/마이크와 달라서 (마이크 권한에 묶임) 단순 유무만 본다
  const spkStatus: DeviceStatus = spk.devices.length === 0 ? 'none' : 'ok';

  // ---- 카메라 미리보기 (로컬 트랙을 그대로 붙인다 — 새 getUserMedia 없이) ----
  // camStatus도 deps에 필요하다: 모달을 연 직후엔 장치 목록 조회가 안 끝나 camStatus가
  // 'ok'가 아니고, 그동안 <video>는 렌더되지 않는다. 목록이 도착해 <video>가 생겨도
  // track/isCameraEnabled는 그대로라 이 effect가 다시 안 돌아 미리보기가 검게 남았다
  // (카메라 토글을 껐다 켜야 붙던 버그). 'ok'로 바뀌는 렌더 뒤에 attach가 실행돼야 한다.
  const track = cameraTrack?.track;
  useEffect(() => {
    const video = videoRef.current;
    if (!video || !track || !isCameraEnabled) return;
    track.attach(video);
    return () => {
      track.detach(video);
    };
  }, [track, isCameraEnabled, camStatus]);

  // ---- 손동작 인식 (미리보기 위 스켈레톤 + 테스트 결과 판정) ----
  const previewActive = isCameraEnabled && camStatus === 'ok' && Boolean(track);
  const { results, combo, ready: handReady } = useHandGestureRecognition(videoRef, previewActive);
  const handDetected = results.length > 0;
  // 손동작 판정 디버그 표시 — 라벨/신뢰도와 함께 두 손 사이 거리(손바닥 길이 단위)와 그 라벨의
  // 허용 한계를 같이 보여준다. handProximity.ts의 라벨별 한계를 실측으로 튜닝하는 화면이
  // 여기다(닌자 게임은 2명부터라 방을 두 개 잡아야 하는데, 이 미리보기는 혼자서도 열린다).
  // 플레이어에게 보일 정보는 아니라 dev 빌드에서만 켜고, 배포된 플레이테스트 빌드에서
  // 재보고 싶을 때만 ?gestureDebug 쿼리로 연다.
  const [gestureDebug] = useState(
    () => import.meta.env.DEV || new URLSearchParams(window.location.search).has('gestureDebug'),
  );
  // 한 번이라도 손이 잡히면 "정상" 유지 (다시 테스트로 초기화)
  const [handSeen, setHandSeen] = useState(false);
  useEffect(() => {
    if (handDetected) setHandSeen(true);
  }, [handDetected]);

  useEffect(() => {
    const canvas = canvasRef.current;
    const video = videoRef.current;
    if (!canvas || !video || video.videoWidth === 0) return;
    canvas.width = video.videoWidth;
    canvas.height = video.videoHeight;
    const ctx = canvas.getContext('2d');
    if (!ctx) return;
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    const drawingUtils = new DrawingUtils(ctx);
    for (const hand of results) {
      drawingUtils.drawConnectors(hand.landmarks, HandLandmarker.HAND_CONNECTIONS, {
        color: '#2fc7be',
        lineWidth: 3,
      });
      drawingUtils.drawLandmarks(hand.landmarks, { color: '#fffdf5', radius: 3 });
    }
  }, [results]);

  // ---- 사물 인식 — AI 서버 연결 확인만 한다. 실제 인식을 여기서 돌리면 설정 모달이
  // "물건 미리 실험장"이 돼서 게임의 신선함을 해치고(제시어 풀 힌트 노출), 모달이 열린
  // 내내 GPU 서버에 불필요한 부하가 간다. 실패 원인의 대부분은 서버 꺼짐이라 헬스로 충분.
  const [aiState, setAiState] = useState<'checking' | 'ok' | 'down'>('checking');
  const checkAi = useCallback(() => {
    setAiState('checking');
    void aiApi.isHealthy().then((ok) => setAiState(ok ? 'ok' : 'down'));
  }, []);
  useEffect(() => {
    checkAi();
  }, [checkAi]);

  // ---- 마이크 입력 레벨 (5칸 미터) ----
  const micTrack = microphoneTrack?.track as LocalAudioTrack | undefined;
  const micBars = useMicLevelBars(micStatus === 'ok' ? micTrack : undefined);

  // ---- 스피커 출력 테스트 ----
  const audioRef = useRef<HTMLAudioElement | null>(null);
  const [playing, setPlaying] = useState(false);
  const toggleSpeakerTest = useCallback(async () => {
    if (playing) {
      audioRef.current?.pause();
      if (audioRef.current) audioRef.current.currentTime = 0;
      setPlaying(false);
      return;
    }
    let audio = audioRef.current;
    if (!audio) {
      audio = new Audio(SPEAKER_TEST_SOUND);
      audio.onended = () => setPlaying(false);
      audioRef.current = audio;
    }
    // 선택한 스피커로 출력 (setSinkId 미지원 브라우저는 기본 장치로 재생)
    if (spk.activeDeviceId && 'setSinkId' in audio) {
      try {
        await audio.setSinkId(spk.activeDeviceId);
      } catch {
        // 장치 전환 실패 — 기본 장치로 그대로 재생
      }
    }
    try {
      await audio.play();
      setPlaying(true);
    } catch {
      setPlaying(false);
    }
  }, [playing, spk.activeDeviceId]);
  useEffect(
    () => () => {
      audioRef.current?.pause();
    },
    [],
  );

  // ---- 하단 보조 버튼 — 상태에 따라 역할이 바뀐다 (시안: 다시 테스트/권한 다시 요청/장치 다시 검색) ----
  const retest = useCallback(async () => {
    setHandSeen(false);
    checkAi();
    if (camStatus === 'permission' || micStatus === 'permission') {
      // 권한 다시 요청 — 프롬프트만 띄우고 스트림은 즉시 반납 (장치는 LiveKit이 소유)
      try {
        const stream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
        stream.getTracks().forEach((t) => t.stop());
      } catch {
        // 거부됨 — 상태 표시는 devices 목록이 알아서 반영
      }
    }
    // 장치 목록 갱신 트리거 (devicechange 이벤트를 못 받은 경우 대비)
    void navigator.mediaDevices.enumerateDevices();
  }, [checkAi, camStatus, micStatus]);

  const retestLabel =
    camStatus === 'permission' || micStatus === 'permission'
      ? '권한 다시 요청'
      : camStatus === 'none'
        ? '장치 다시 검색'
        : '다시 테스트';

  const saveDisabled = camStatus !== 'ok';

  // ---- 테스트 결과 행 상태 ----
  const handRow =
    camStatus === 'none'
      ? { tone: 'danger' as const, desc: '카메라 연결이 필요해요.', chip: '확인 불가' }
      : camStatus === 'permission'
        ? { tone: 'danger' as const, desc: '카메라 권한을 허용해주세요.', chip: '권한 필요' }
        : handSeen
          ? { tone: 'ok' as const, desc: '손을 화면에 보여주세요.', chip: '정상' }
          : { tone: 'idle' as const, desc: handReady ? '손을 화면에 보여주세요.' : '인식기를 준비하는 중...', chip: null };

  const objectRow =
    camStatus === 'none'
      ? { tone: 'danger' as const, desc: '카메라 연결이 필요해요.', chip: '확인 불가' }
      : camStatus === 'permission'
        ? { tone: 'danger' as const, desc: '카메라 권한을 허용해주세요.', chip: '권한 필요' }
        : aiState === 'down'
          ? { tone: 'danger' as const, desc: 'AI 서버에 연결할 수 없어요.', chip: '확인 불가' }
          : aiState === 'checking'
            ? { tone: 'idle' as const, desc: '인식 서버를 확인하는 중...', chip: null }
            : { tone: 'ok' as const, desc: '게임 중 물체 인식이 가능해요.', chip: '정상' };

  return (
    <div className="pap-modal-backdrop">
      <div className="pap-modal settings-modal">
        <div className="settings-modal__card pap-pixel-card">
          <header className="settings-modal__header">
            <div>
              <h2 className="settings-modal__title pap-pixel-title">환경설정</h2>
              <p className="settings-modal__subtitle">
                카메라와 오디오 장치를 확인하고 게임 인식 상태를 테스트하세요.
              </p>
            </div>
            <button
              type="button"
              className="pap-pixel-btn settings-modal__close"
              onClick={onClose}
              aria-label="닫기"
              data-button-sound="cancel"
            >
              ✕
            </button>
          </header>

          <div className="settings-modal__devices">
            <DeviceSelect
              icon={CameraIcon}
              devices={cam.devices}
              activeDeviceId={cam.activeDeviceId}
              onSelect={(id) => void cam.setActiveMediaDevice(id)}
              status={camStatus}
              fallbackLabel="기본 카메라"
              permissionLabel="카메라 권한 필요"
            />
            <DeviceSelect
              icon={MicIcon}
              devices={mic.devices}
              activeDeviceId={mic.activeDeviceId}
              onSelect={(id) => void mic.setActiveMediaDevice(id)}
              status={micStatus}
              fallbackLabel="기본 마이크"
              permissionLabel="마이크 권한 필요"
            />
            <DeviceSelect
              icon={SpeakerIcon}
              devices={spk.devices}
              activeDeviceId={spk.activeDeviceId}
              onSelect={(id) => void spk.setActiveMediaDevice(id)}
              status={spkStatus}
              fallbackLabel="기본 스피커"
              permissionLabel="스피커 권한 필요"
            />
          </div>

          <div className="settings-modal__body">
            {/* 좌: 카메라 미리보기 */}
            <section className="settings-modal__panel">
              <h3 className="settings-modal__panel-title pap-pixel-title">카메라 미리보기</h3>
              <p className="settings-modal__panel-desc">화면과 손동작 인식 범위를 확인해요.</p>
              <div className="settings-modal__preview">
                {camStatus === 'ok' && isCameraEnabled ? (
                  <>
                    <video
                      ref={videoRef}
                      autoPlay
                      playsInline
                      muted
                      className="settings-modal__video"
                    />
                    <canvas ref={canvasRef} className="settings-modal__skeleton" aria-hidden />
                    {handDetected && (
                      <span className="settings-modal__preview-badge">손동작 인식 중</span>
                    )}
                    {gestureDebug && (
                      <span className="settings-modal__gesture-debug">
                        {combo.label
                          ? `${combo.label} ${(combo.confidence * 100).toFixed(0)}% · 손 거리 ${combo.handGap?.toFixed(2)} / 허용 ${combo.handGapLimit?.toFixed(2)}`
                          : combo.rawLabel
                            ? `${combo.rawLabel} 모양 · 두 손이 멀어요 ${combo.handGap?.toFixed(2)} > ${combo.handGapLimit?.toFixed(2)}`
                            : `양손을 보여주세요 (인식된 손 ${results.length}개)`}
                      </span>
                    )}
                  </>
                ) : (
                  <div className="settings-modal__preview-empty">
                    {camStatus === 'none' ? (
                      <>
                        <span className="settings-modal__preview-icon">{CamMissingIcon}</span>
                        <strong>카메라를 찾을 수 없어요</strong>
                        <span>장치를 연결한 뒤 목록을 새로 확인해주세요.</span>
                      </>
                    ) : camStatus === 'permission' ? (
                      <>
                        <span className="settings-modal__preview-icon">{CamBlockedIcon}</span>
                        <strong>카메라 권한이 필요해요</strong>
                        <span>브라우저 설정에서 카메라 권한을 허용해주세요.</span>
                      </>
                    ) : (
                      <>
                        <span className="settings-modal__preview-icon">{CamBlockedIcon}</span>
                        <strong>카메라가 꺼져 있어요</strong>
                        <span>아래 토글로 카메라를 켤 수 있어요.</span>
                      </>
                    )}
                  </div>
                )}
              </div>
              <label className="settings-modal__cam-toggle pap-pixel-card">
                <span>카메라 사용</span>
                <input
                  type="checkbox"
                  checked={isCameraEnabled}
                  onChange={() => void localParticipant.setCameraEnabled(!isCameraEnabled)}
                />
                <span className="settings-modal__switch" aria-hidden />
              </label>
            </section>

            {/* 우: 테스트 결과 */}
            <section className="settings-modal__panel">
              <h3 className="settings-modal__panel-title pap-pixel-title">테스트 결과</h3>
              <p className="settings-modal__panel-desc">게임에 필요한 장치와 인식 상태예요.</p>
              <ul className="settings-modal__tests">
                <li className={`settings-modal__test settings-modal__test--${handRow.tone}`}>
                  <span className="settings-modal__test-icon">{HandIcon}</span>
                  <span className="settings-modal__test-text">
                    <strong>손동작 인식</strong>
                    <span>{handRow.desc}</span>
                  </span>
                  {handRow.chip && (
                    <span className="settings-modal__chip">{handRow.chip}</span>
                  )}
                </li>
                <li className={`settings-modal__test settings-modal__test--${objectRow.tone}`}>
                  <span className="settings-modal__test-icon">{CubeIcon}</span>
                  <span className="settings-modal__test-text">
                    <strong>사물 인식</strong>
                    <span>{objectRow.desc}</span>
                  </span>
                  {objectRow.chip && (
                    <span className="settings-modal__chip">{objectRow.chip}</span>
                  )}
                </li>
                <li
                  className={`settings-modal__test${
                    micStatus !== 'ok' ? ' settings-modal__test--danger' : ''
                  }`}
                >
                  <span className="settings-modal__test-icon">{MicIcon}</span>
                  <span className="settings-modal__test-text">
                    <strong>마이크 입력</strong>
                    <span>
                      {micStatus === 'none'
                        ? '마이크 연결이 필요해요.'
                        : micStatus === 'permission'
                          ? '마이크 권한을 허용해주세요.'
                          : '입력 음량을 확인해요.'}
                    </span>
                  </span>
                  {micStatus === 'ok' ? (
                    <span className="settings-modal__meter" aria-label="마이크 입력 레벨">
                      {MIC_LEVEL_STEPS.map((threshold, i) => (
                        <span
                          key={threshold}
                          className={`settings-modal__meter-bar${
                            i < micBars ? ' settings-modal__meter-bar--on' : ''
                          }`}
                          style={{ height: `${8 + i * 3}px` }}
                        />
                      ))}
                    </span>
                  ) : (
                    <span className="settings-modal__chip">권한 필요</span>
                  )}
                </li>
                <li
                  className={`settings-modal__test${
                    playing ? ' settings-modal__test--ok' : ''
                  }`}
                >
                  <span className="settings-modal__test-icon">{SpeakerIcon}</span>
                  <span className="settings-modal__test-text">
                    <strong>스피커 출력</strong>
                    <span>{playing ? '테스트 소리를 재생하는 중이에요.' : '버튼을 눌러 확인하세요.'}</span>
                  </span>
                  <button
                    type="button"
                    className="settings-modal__play-btn"
                    onClick={() => void toggleSpeakerTest()}
                    aria-label={playing ? '테스트 소리 정지' : '테스트 소리 재생'}
                  >
                    {playing ? PauseIcon : PlayIcon}
                  </button>
                </li>
              </ul>

              <div className="settings-modal__actions">
                <button type="button" className="pap-pixel-btn" onClick={() => void retest()}>
                  <span className="settings-modal__btn-icon">{RetryIcon}</span> {retestLabel}
                </button>
                <button
                  type="button"
                  className="pap-pixel-btn pap-pixel-btn--coral"
                  onClick={onClose}
                  disabled={saveDisabled}
                >
                  ✓ 저장하고 닫기
                </button>
              </div>
            </section>
          </div>

          <p className="settings-modal__footnote">
            영상과 음성은 인식 테스트에만 사용되며 저장되지 않아요.
          </p>
        </div>
      </div>
    </div>
  );
}
