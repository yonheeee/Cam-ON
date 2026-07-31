import { useState } from 'react';
import { PixelAmaterasuEffect } from './PixelAmaterasuEffect';
import { PixelCatPunchEffect } from './PixelCatPunchEffect';
import { PixelCherryBlossomSlashEffect } from './PixelCherryBlossomSlashEffect';
import { PixelLightningEffect } from './PixelLightningEffect';
import { PixelPhoenixFlowerEffect } from './PixelPhoenixFlowerEffect';
import { PixelRasenganEffect } from './PixelRasenganEffect';
import { PixelWaterDragonEffect } from './PixelWaterDragonEffect';
import { PixelWindScarEffect } from './PixelWindScarEffect';
// Pixel*Effect 들이 .ninja-effect-overlay(--pixel) 클래스를 쓴다.
import './pixelEffectOverlay.css';

export function NinjaEffectPreview() {
  const [playbackKey, setPlaybackKey] = useState(0);
  const [effect, setEffect] = useState<
    | 'lightning'
    | 'cat-punch'
    | 'phoenix-cast'
    | 'phoenix-hit'
    | 'water-dragon-cast'
    | 'water-dragon-hit'
    | 'wind-scar'
    | 'cherry-blossom-slash'
    | 'rasengan'
    | 'amaterasu'
  >('lightning');
  const isCatPunch = effect === 'cat-punch';
  const isPhoenixCast = effect === 'phoenix-cast';
  const isPhoenixHit = effect === 'phoenix-hit';
  const isPhoenixFlower = isPhoenixCast || isPhoenixHit;
  const isWaterDragonCast = effect === 'water-dragon-cast';
  const isWaterDragonHit = effect === 'water-dragon-hit';
  const isWaterDragon = isWaterDragonCast || isWaterDragonHit;
  const isWindScar = effect === 'wind-scar';
  const isCherryBlossomSlash = effect === 'cherry-blossom-slash';
  const isRasengan = effect === 'rasengan';
  const isAmaterasu = effect === 'amaterasu';

  return (
    <main
      style={{
        minHeight: '100vh',
        display: 'grid',
        placeItems: 'center',
        overflow: 'hidden',
        color: '#eafcff',
        background:
          'radial-gradient(circle at 50% 35%, #18354f 0%, #0a1726 38%, #03070d 78%)',
      }}
    >
      {isAmaterasu ? (
        <PixelAmaterasuEffect key={`amaterasu-${playbackKey}`} />
      ) : isRasengan ? (
        <PixelRasenganEffect key={`rasengan-${playbackKey}`} />
      ) : isCherryBlossomSlash ? (
        <PixelCherryBlossomSlashEffect
          key={`cherry-blossom-slash-${playbackKey}`}
        />
      ) : isWindScar ? (
        <PixelWindScarEffect key={`wind-scar-${playbackKey}`} />
      ) : isWaterDragonCast ? (
        <PixelWaterDragonEffect
          key={`water-dragon-cast-${playbackKey}`}
          variant="cast"
        />
      ) : isWaterDragonHit ? (
        <PixelWaterDragonEffect
          key={`water-dragon-hit-${playbackKey}`}
          variant="hit"
        />
      ) : isPhoenixFlower ? (
        <PixelPhoenixFlowerEffect
          key={`${effect}-${playbackKey}`}
          variant={isPhoenixCast ? 'cast' : 'hit'}
        />
      ) : isCatPunch ? (
        <PixelCatPunchEffect key={`cat-punch-${playbackKey}`} />
      ) : (
        <PixelLightningEffect key={`lightning-${playbackKey}`} />
      )}
      <section
        style={{
          position: 'relative',
          zIndex: 30,
          width: 'min(34rem, calc(100vw - 2rem))',
          padding: '2rem',
          textAlign: 'center',
          border: '1px solid rgba(124, 225, 255, 0.35)',
          borderRadius: '1rem',
          background: 'rgba(3, 12, 22, 0.72)',
          boxShadow: '0 1.5rem 5rem rgba(0, 0, 0, 0.45)',
          backdropFilter: 'blur(12px)',
        }}
      >
        <p style={{ margin: 0, color: '#66e4ff', letterSpacing: '0.16em' }}>NINJA SKILL FX</p>
        <h1
          style={{
            margin: '0.45rem 0 0.75rem',
            color: '#f5fdff',
            fontSize: 'clamp(2rem, 8vw, 4.5rem)',
            textShadow: '0 0 1.5rem rgba(78, 220, 255, 0.6)',
          }}
        >
          {isAmaterasu
            ? '아마테라스 · 테스트'
            : isRasengan
            ? '나선환 · 테스트'
            : isCherryBlossomSlash
            ? '벚꽃 참격'
            : isWindScar
            ? '바람의 상처'
            : isWaterDragon
            ? `수룡탄의 술 · ${isWaterDragonCast ? '공격' : '피격'}`
            : isPhoenixFlower
            ? `봉선화의 술 · ${isPhoenixCast ? '공격' : '피격'}`
            : isCatPunch
              ? '냥냥펀치'
              : '번개'}
        </h1>
        <p style={{ margin: '0 0 1.5rem', opacity: 0.72 }}>
          {isAmaterasu
            ? '눈 개안 · 붉은 6엽 문양 · 회전 각성 · 흑염 발동'
            : isRasengan
            ? '차크라 응축 · 내부 소용돌이 · 회전 궤적 · 파동 방출'
            : isCherryBlossomSlash
            ? '5잎 꽃 문양 · 벚꽃 개화 · 흩날리는 꽃잎'
            : isWindScar
            ? '초승달 칼날 집결 · 황금 거대 참격 · 압력파 · 바람 파편'
            : isWaterDragonCast
            ? '하단 생성 · S자 수룡 승천 · 물 갈기 · 긴 수염'
            : isWaterDragonHit
              ? '상단 수룡 강하 · 중앙 피격 · 물보라 · 청색 파동'
              : isPhoenixCast
            ? '중앙 발동점 · 다섯 불씨 방출 · 곡선 비행 · 도트 불꽃'
            : isPhoenixHit
              ? '다섯 불씨 집중 · 중앙 연속 피격 · 폭발 · 불꽃 파편'
            : isCatPunch
              ? '곡선 발톱 참격 · 타원 파동 · 별 파편 · 분홍 플래시'
              : '픽셀 볼트 · 마름모 충격파 · 사각 파편 · 계단식 플래시'}
        </p>
        <div
          style={{
            display: 'flex',
            flexWrap: 'wrap',
            justifyContent: 'center',
            gap: '0.5rem',
            marginBottom: '1rem',
          }}
        >
          {([
            ['lightning', '번개'],
            ['cat-punch', '냥냥펀치'],
            ['phoenix-cast', '봉선화(공격)'],
            ['phoenix-hit', '봉선화(피격)'],
            ['water-dragon-cast', '수룡탄(공격)'],
            ['water-dragon-hit', '수룡탄(피격)'],
            ['wind-scar', '바람의 상처'],
            ['cherry-blossom-slash', '벚꽃 참격'],
            ['rasengan', '나선환(테스트)'],
            ['amaterasu', '아마테라스(테스트)'],
          ] as const).map(([value, label]) => (
            <button
              key={value}
              type="button"
              onClick={() => {
                setEffect(value);
                setPlaybackKey((key) => key + 1);
              }}
              style={{
                padding: '0.55rem 0.8rem',
                color: effect === value ? '#03111c' : '#a9c7d4',
                fontWeight: 700,
                border: '1px solid rgba(111, 226, 255, 0.35)',
                borderRadius: '0.45rem',
                background:
                  effect === value
                    ? value === 'cat-punch'
                      ? '#ff86c8'
                      : value === 'phoenix-cast'
                        ? '#ff9a2f'
                        : value === 'phoenix-hit'
                          ? '#ff5c38'
                          : value === 'water-dragon-cast'
                            ? '#5beaff'
                            : value === 'water-dragon-hit'
                              ? '#3a9cff'
                              : value === 'wind-scar'
                                ? '#dfff63'
                                : value === 'cherry-blossom-slash'
                                  ? '#ff8fce'
                                  : value === 'rasengan'
                                    ? '#67ddff'
                                    : value === 'amaterasu'
                                      ? '#a84dff'
                        : '#65e5ff'
                    : 'rgba(7, 22, 34, 0.75)',
                cursor: 'pointer',
              }}
            >
              {label}
            </button>
          ))}
        </div>
        <button
          type="button"
          onClick={() => setPlaybackKey((key) => key + 1)}
          style={{
            padding: '0.85rem 1.35rem',
            color: '#03111c',
            fontWeight: 800,
            border: 0,
            borderRadius: '0.6rem',
            background: 'linear-gradient(135deg, #f4fdff, #4edcff)',
            cursor: 'pointer',
          }}
        >
          다시 재생
        </button>
      </section>
    </main>
  );
}
