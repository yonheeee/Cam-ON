import { create } from 'zustand';

export interface GestureBoardEntry {
  comboLabel: string | null;
  confidence: number;
  updatedAt: number;
}

interface GestureBoardState {
  entries: Record<string, GestureBoardEntry>;
  setEntry: (identity: string, entry: Omit<GestureBoardEntry, 'updatedAt'>) => void;
}

// 참가자별 최신 손동작(스킬) 판정 결과. 로컬 참가자는 GesturePanel이 직접 쓰고,
// 다른 참가자 결과는 GestureBoard가 LiveKit 데이터 채널(gesture-result 토픽) 수신으로 채운다.
export const useGestureBoardStore = create<GestureBoardState>((set) => ({
  entries: {},
  setEntry: (identity, entry) =>
    set((state) => ({
      entries: { ...state.entries, [identity]: { ...entry, updatedAt: Date.now() } },
    })),
}));
