// 동시 접속자 N명(N/4개 방 x 방 정원 4명) 시나리오 부하 테스트 — ROOMS 환경변수로 규모 조절.
//
// 실행 (예: 10,000명 = 2,500방):
//   docker run --rm -i --network camon-network --ulimit nofile=65536:65536 \
//     -e BASE_URL=http://backend:8080 -e ROOMS=2500 -e TOTAL_ROUNDS=3 \
//     -e READY_POLL_ATTEMPTS=200 -e STATE_POLL_ATTEMPTS=200 \
//     grafana/k6 run - < backend/loadtest/ninja-concurrent-load.js
//
// 주의: 이 백엔드는 Tomcat max-threads(기본 200)/HikariCP maximumPoolSize(기본 10)를
// 별도 튜닝하지 않은 스프링 부트 기본값이다. 수백~수천 단위 동시 접속에서는 이 기본값
// 자체가 병목으로 드러날 수 있다 — 그건 스크립트 버그가 아니라 정확히 이 테스트가
// 찾아내야 하는 지점이다. 대규모 실행에서 에러율이 0%가 아니어도 당황하지 말 것.
//
// 흐름(사람이 실제로 하는 것과 동일한 순서, 프론트가 라운드 콘텐츠를 REST GET으로
// 폴링하는 것도 그대로 흉내):
//   1. 방장들은 setup()에서 미리 방 생성(실제로는 방 생성이 N명이 동시에 몰리는
//      이벤트가 아니라 방마다 한 명이 먼저 만들고 코드를 공유하는 흐름이라 setup 단계로 분리).
//   2. VU N개가 동시에 시작 — 방장은 준비 상태 폴링, 나머지는 세션 발급 +
//      방 참가(POST /api/rooms/join)를 동시에 수행 (진짜 동시 접속 트래픽 구간).
//   3. 전원 준비 완료 PATCH, 방장은 전원 ready 확인 후 dev/ninja/seed로 세션 시작.
//   4. 라운드마다 4명 전원이 GET skill 폴링 → POST attack(전원 동시 시도, 1명만 Redis
//      HSETNX로 공격권 선점 성공, 나머지는 409 — 이건 버그가 아니라 기대되는 경합 결과이므로
//      별도 카운터로 집계) → 승자만 POST target.

import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const ROOMS = Number(__ENV.ROOMS || 25); // 기본 25개 방 x 4명 = 100명 동시 접속
const PLAYERS_PER_ROOM = 4;
const TOTAL_ROUNDS = Number(__ENV.TOTAL_ROUNDS || 3);
const NINJA_GAME_ID = 1; // MySQL games 테이블 미시딩 상태라 임의 고정값 — resolve()가 room별 저장값과만 대조하므로 무관.
// 대규모 실행에서는 풀링/스레드 경합으로 응답이 늦어질 수 있어 폴링 재시도 상한을 넉넉히 잡는다
// (0.3초 간격 기준 기본값 = 준비 9초/세션 시작 12초 대기 — 큰 규모에서는 env로 늘릴 것).
const READY_POLL_ATTEMPTS = Number(__ENV.READY_POLL_ATTEMPTS || 30);
const STATE_POLL_ATTEMPTS = Number(__ENV.STATE_POLL_ATTEMPTS || 40);

export const options = {
  setupTimeout: __ENV.SETUP_TIMEOUT || '10m', // ROOMS가 커지면 setup()의 순차 방 생성이 오래 걸림
  scenarios: {
    ninja_concurrent: {
      executor: 'per-vu-iterations',
      vus: ROOMS * PLAYERS_PER_ROOM,
      iterations: 1,
      maxDuration: __ENV.RUN_MAX_DURATION || '15m',
    },
  },
  thresholds: {
    'http_req_failed': ['rate<0.05'],
    'http_req_duration{endpoint:createSession}': ['p(95)<500'],
    'http_req_duration{endpoint:joinRoom}': ['p(95)<800'],
    'http_req_duration{endpoint:ready}': ['p(95)<500'],
    'http_req_duration{endpoint:roundSkill}': ['p(95)<500'],
    'http_req_duration{endpoint:attack}': ['p(95)<800'],
    'http_req_duration{endpoint:target}': ['p(95)<800'],
  },
};

const attackWinCounter = new Counter('ninja_attack_won');
const attackConflictCounter = new Counter('ninja_attack_conflict'); // 기대되는 409(공격권 경합 패배)
const roundCompletedCounter = new Counter('ninja_round_completed');
const roomReadyWaitTrend = new Trend('room_all_ready_wait_ms', true);
const sessionStartWaitTrend = new Trend('ninja_session_start_wait_ms', true);

function authHeaders(token, endpoint, expectedStatuses) {
  const params = {
    headers: { 'Content-Type': 'application/json', Authorization: `Bearer ${token}` },
    tags: { endpoint },
  };
  if (expectedStatuses) {
    params.responseCallback = http.expectedStatuses(...expectedStatuses);
  }
  return params;
}

function createSession(nickname) {
  const res = http.post(
    `${BASE_URL}/api/sessions`,
    JSON.stringify({ nickname }),
    { headers: { 'Content-Type': 'application/json' }, tags: { endpoint: 'createSession' } },
  );
  check(res, { 'session created (201)': (r) => r.status === 201 });
  const body = res.json('data');
  return { accessToken: body.accessToken, participantId: body.participantId };
}

export function setup() {
  const rooms = [];
  for (let i = 0; i < ROOMS; i++) {
    const host = createSession(`h${i}`);
    const createRes = http.post(
      `${BASE_URL}/api/rooms`,
      JSON.stringify({ maxPlayers: PLAYERS_PER_ROOM }),
      authHeaders(host.accessToken, 'createRoom'),
    );
    check(createRes, { 'room created (201)': (r) => r.status === 201 });
    const room = createRes.json('data.room');
    rooms.push({
      roomId: room.roomId,
      roomCode: room.roomCode,
      hostAccessToken: host.accessToken,
      hostParticipantId: host.participantId,
    });
  }
  console.log(`[setup] ${rooms.length}개 방 생성 완료`);
  return { rooms };
}

export default function (data) {
  const vu = __VU;
  const roomIndex = Math.floor((vu - 1) / PLAYERS_PER_ROOM);
  const posInRoom = (vu - 1) % PLAYERS_PER_ROOM;
  const room = data.rooms[roomIndex];
  const isHost = posInRoom === 0;

  let accessToken;
  let participantId;

  if (isHost) {
    accessToken = room.hostAccessToken;
    participantId = room.hostParticipantId;
  } else {
    const session = createSession(`u${vu}`);
    accessToken = session.accessToken;
    participantId = session.participantId;
    const joinRes = http.post(
      `${BASE_URL}/api/rooms/join`,
      JSON.stringify({ roomCode: room.roomCode }),
      authHeaders(accessToken, 'joinRoom'),
    );
    check(joinRes, { 'join room (200)': (r) => r.status === 200 });
  }

  const readyRes = http.patch(
    `${BASE_URL}/api/rooms/${room.roomId}/members/me/ready`,
    JSON.stringify({ ready: true }),
    authHeaders(accessToken, 'ready'),
  );
  check(readyRes, { 'ready (200)': (r) => r.status === 200 });

  if (isHost) {
    const waitStart = Date.now();
    let snapshot = null;
    for (let attempt = 0; attempt < READY_POLL_ATTEMPTS; attempt++) {
      const snapshotRes = http.get(
        `${BASE_URL}/api/rooms/${room.roomId}`,
        authHeaders(accessToken, 'roomSnapshot'),
      );
      if (snapshotRes.status === 200) {
        const body = snapshotRes.json('data');
        if (body.participants.length === PLAYERS_PER_ROOM && body.participants.every((p) => p.ready)) {
          snapshot = body;
          break;
        }
      }
      sleep(0.3);
    }
    roomReadyWaitTrend.add(Date.now() - waitStart);

    if (snapshot) {
      const participantTokens = snapshot.participants.map((p) => p.participantId);
      const seedRes = http.post(
        `${BASE_URL}/api/dev/ninja/seed`,
        JSON.stringify({
          roomId: room.roomId,
          gameId: NINJA_GAME_ID,
          participantTokens,
          totalRounds: TOTAL_ROUNDS,
        }),
        authHeaders(accessToken, 'seed'),
      );
      check(seedRes, { 'ninja seed (200)': (r) => r.status === 200 });
    } else {
      console.warn(`[room ${room.roomCode}] 전원 준비 대기 타임아웃 — seed 스킵`);
    }
  }

  // 전원(방장 포함)이 세션 시작을 폴링으로 확인 — 실제 프론트가 라운드 콘텐츠를
  // REST GET으로 조회하는 것과 동일한 패턴.
  const startWaitBegin = Date.now();
  let round = 0;
  let totalRounds = TOTAL_ROUNDS;
  for (let attempt = 0; attempt < STATE_POLL_ATTEMPTS; attempt++) {
    const stateRes = http.get(
      `${BASE_URL}/api/games/${NINJA_GAME_ID}/ninja/state`,
      // seed 전에는 NINJA_SESSION_NOT_FOUND(404)가 정상 — 세션 시작을 폴링으로 기다리는 구간.
      authHeaders(accessToken, 'state', [200, 404]),
    );
    if (stateRes.status === 200) {
      const body = stateRes.json('data');
      if (body.round >= 1) {
        round = body.round;
        totalRounds = body.totalRounds;
        break;
      }
    }
    sleep(0.3);
  }
  sessionStartWaitTrend.add(Date.now() - startWaitBegin);

  if (round === 0) {
    console.warn(`[room ${room.roomCode}] 닌자 세션 시작 대기 타임아웃`);
    return;
  }

  for (let r = round; r <= totalRounds; r++) {
    const skillRes = http.get(
      `${BASE_URL}/api/games/${NINJA_GAME_ID}/ninja/rounds/${r}/skill`,
      // 다른 참가자의 target 제출로 라운드가 이미 넘어갔거나 게임이 끝난 경우 404 — 정상 종료 경로.
      authHeaders(accessToken, 'roundSkill', [200, 404]),
    );
    if (skillRes.status !== 200) {
      break;
    }
    const skillId = skillRes.json('data.skillId');

    sleep(Math.random() * 0.3); // 손동작 인식 지연 흉내

    const attackRes = http.post(
      `${BASE_URL}/api/games/${NINJA_GAME_ID}/ninja/rounds/${r}/attack`,
      JSON.stringify({ skillId }),
      // 409(공격권 이미 선점됨)은 4명이 동시에 콤보를 완성했을 때 3명에게는 정상적으로
      // 나오는 응답이라 http_req_failed 집계에서 "실패"로 잡히면 안 된다.
      authHeaders(accessToken, 'attack', [200, 409]),
    );

    if (attackRes.status === 200) {
      attackWinCounter.add(1);
      const stateRes = http.get(
        `${BASE_URL}/api/games/${NINJA_GAME_ID}/ninja/state`,
        authHeaders(accessToken, 'state'),
      );
      const candidates = (stateRes.json('data.alivePlayers') || []).filter((t) => t !== participantId);
      if (candidates.length > 0) {
        const target = candidates[Math.floor(Math.random() * candidates.length)];
        const targetRes = http.post(
          `${BASE_URL}/api/games/${NINJA_GAME_ID}/ninja/rounds/${r}/target`,
          JSON.stringify({ targetToken: target }),
          // 라운드 타임아웃과 극단적으로 겹치면 NINJA_ROUND_CLOSED(409)가 날 수 있음 — 드물지만 정상 경로.
          authHeaders(accessToken, 'target', [200, 409]),
        );
        check(targetRes, { 'target 200/409': (res) => res.status === 200 || res.status === 409 });
        if (targetRes.status === 200) roundCompletedCounter.add(1);
      }
    } else if (attackRes.status === 409) {
      attackConflictCounter.add(1);
    }

    sleep(0.3); // 다음 라운드가 열릴 시간을 잠깐 기다림
  }
}
