// 로컬 `livekit-server --dev`(placeholder key devkey/secret) 또는 LiveKit Cloud 프로젝트 테스트용
// 임시 토큰 발급 스크립트. 실제 서비스에서는 Spring 백엔드가 LiveKit Server SDK(Java, 0.12.x)로
// 입장 시점에 토큰을 발급한다. 이 스크립트는 그 전까지 프론트 WebRTC 연동을 눈으로 확인하기 위한
// 로컬 전용 도구다.
// LiveKit Cloud를 쓰려면 backend/.env의 LIVEKIT_API_KEY/LIVEKIT_API_SECRET과 반드시 같은 값을
// 환경변수로 넘겨야 한다 — 안 넘기면 로컬 dev 서버용 devkey/secret로 만들어져서 Cloud가 거부한다.
//   PowerShell: $env:LIVEKIT_API_KEY="..."; $env:LIVEKIT_API_SECRET="..."; node scripts/mint-dev-token.mjs <room> <name>
import { AccessToken } from 'livekit-server-sdk';

const [, , roomName, participantName] = process.argv;

if (!roomName || !participantName) {
  console.error('Usage: node scripts/mint-dev-token.mjs <roomName> <participantName>');
  process.exit(1);
}

const apiKey = process.env.LIVEKIT_API_KEY ?? 'devkey';
const apiSecret = process.env.LIVEKIT_API_SECRET ?? 'secret';
if (apiKey === 'devkey') {
  console.error('(참고: LIVEKIT_API_KEY 환경변수가 없어 로컬 devkey/secret로 발급함 — LiveKit Cloud면 안 맞을 수 있음)');
}

const token = new AccessToken(apiKey, apiSecret, { identity: participantName });
token.addGrant({ roomJoin: true, room: roomName, canPublish: true, canSubscribe: true });

console.log(await token.toJwt());
