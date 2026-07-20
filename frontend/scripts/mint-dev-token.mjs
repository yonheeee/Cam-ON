// 로컬 `livekit-server --dev` 서버(placeholder key devkey/secret) 테스트용 임시 토큰 발급 스크립트.
// 실제 서비스에서는 Spring 백엔드가 LiveKit Server SDK(Java, 0.12.x)로 입장 시점에 토큰을 발급한다.
// 이 스크립트는 그 전까지 프론트 WebRTC 연동을 눈으로 확인하기 위한 로컬 전용 도구다.
import { AccessToken } from 'livekit-server-sdk';

const [, , roomName, participantName] = process.argv;

if (!roomName || !participantName) {
  console.error('Usage: node scripts/mint-dev-token.mjs <roomName> <participantName>');
  process.exit(1);
}

const token = new AccessToken('devkey', 'secret', { identity: participantName });
token.addGrant({ roomJoin: true, room: roomName, canPublish: true, canSubscribe: true });

console.log(await token.toJwt());
