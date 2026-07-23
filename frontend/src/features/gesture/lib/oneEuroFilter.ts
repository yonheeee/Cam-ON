// utils/landmark_smoother.py 그대로 포팅 (1€ 필터, Casiez et al. 2012).
// 정지/저속 구간은 강하게 스무딩해서 떨림을 줄이고, 빠르게 움직일 땐 컷오프를 올려
// 지연(lag)이 커지지 않게 한다. 손가락이 겹쳐서 검출이 흔들릴 때(정지 상태에 가까움)를
// 완화하는 데 적합하다. timestamp 인자는 초(seconds) 단위여야 컷오프 주파수(Hz) 의미가 맞다.
function smoothingFactor(timeElapsed: number, cutoff: number): number {
  const r = 2 * Math.PI * cutoff * timeElapsed;
  return r / (r + 1);
}

class LowPassFilter {
  private y = 0;
  private initialized = false;

  filter(value: number, alpha: number): number {
    if (!this.initialized) {
      this.y = value;
      this.initialized = true;
    } else {
      this.y = alpha * value + (1 - alpha) * this.y;
    }
    return this.y;
  }
}

class OneEuroFilter {
  private xFilter = new LowPassFilter();
  private dxFilter = new LowPassFilter();
  private lastTime: number | null = null;
  private lastX: number | null = null;
  private minCutoff: number;
  private beta: number;
  private dCutoff: number;

  constructor(minCutoff = 1.0, beta = 0.0, dCutoff = 1.0) {
    this.minCutoff = minCutoff;
    this.beta = beta;
    this.dCutoff = dCutoff;
  }

  apply(t: number, x: number): number {
    const timeElapsed = this.lastTime === null ? 1.0 : Math.max(t - this.lastTime, 1e-6);
    this.lastTime = t;

    const prevX = this.lastX ?? x;
    const dx = (x - prevX) / timeElapsed;
    const dxHat = this.dxFilter.filter(dx, smoothingFactor(timeElapsed, this.dCutoff));

    const cutoff = this.minCutoff + this.beta * Math.abs(dxHat);
    const result = this.xFilter.filter(x, smoothingFactor(timeElapsed, cutoff));
    this.lastX = result;
    return result;
  }
}

type HandLabel = 'Left' | 'Right';

// 손(Left/Right)별로 21개 랜드마크의 x, y 각각에 독립적인 1€ 필터를 적용한다.
// 한쪽 손이 화면에서 사라졌다 다시 잡히면 이전 값으로 이어붙이지 않도록 reset()으로
// 해당 손의 필터 상태를 버려야 한다(안 그러면 사라지기 직전 위치에서 스냅되며 튐).
export class HandLandmarkSmoother {
  private filters: Record<HandLabel, Array<[OneEuroFilter, OneEuroFilter]> | null> = {
    Left: null,
    Right: null,
  };
  private minCutoff: number;
  private beta: number;
  private dCutoff: number;

  constructor(minCutoff = 1.0, beta = 0.3, dCutoff = 1.0) {
    this.minCutoff = minCutoff;
    this.beta = beta;
    this.dCutoff = dCutoff;
  }

  smooth(handLabel: HandLabel, landmarkList: Array<[number, number]>, timestamp: number): Array<[number, number]> {
    if (!this.filters[handLabel]) {
      this.filters[handLabel] = landmarkList.map(
        () => [new OneEuroFilter(this.minCutoff, this.beta, this.dCutoff), new OneEuroFilter(this.minCutoff, this.beta, this.dCutoff)],
      );
    }
    const filters = this.filters[handLabel]!;
    return landmarkList.map(([x, y], i) => {
      const [filterX, filterY] = filters[i];
      return [Math.round(filterX.apply(timestamp, x)), Math.round(filterY.apply(timestamp, y))];
    });
  }

  reset(handLabel: HandLabel): void {
    this.filters[handLabel] = null;
  }
}
