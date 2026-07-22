# Room Redis schema

Room data lives only while a room is active. Keys owned by a room are removed
when `RoomRepository.delete(roomId)` is called.

## Keys

### `room:{roomId}` — HASH

| Field | Java type | Description |
| --- | --- | --- |
| `room_id` | `UUID` | REST path identifier |
| `room_code` | `String` | Six-character invite/join code |
| `title` | `String` | Room title |
| `host_participant_id` | `UUID` | Current host participant ID |
| `max_players` | `int` | Maximum participants |
| `status` | `RoomStatus` | `WAITING`, `PLAYING`, or `FINISHED` |
| `created_at` | `Instant` | ISO-8601 creation time |

Example: `room:550e8400-e29b-41d4-a716-446655440000`

### `room-code:{roomCode}` — STRING

The value is the corresponding `roomId`. This reverse index is used only at
the invite/join boundary; internal room APIs use `roomId`.

### `room:{roomId}:participants` — SET

Contains participant UUID strings. `findAll` reads participant HASH values and
sorts them by `joined_at`; the SET itself does not represent join order.

### `room:{roomId}:participant:{participantId}` — HASH

| Field | Java type | Description |
| --- | --- | --- |
| `nickname` | `String` | Display nickname |
| `connection_status` | `ConnectionStatus` | Connection state |
| `ready` | `boolean` | Ready state |
| `joined_at` | `Instant` | ISO-8601 join time |

### `room:{roomId}:nicknames` — SET

Contains nicknames currently in the room. Nicknames are compared exactly as
received; trimming and normalization belong to the service validation policy.

### `session:{participantId}:alive` — STRING with TTL

The value is `roomId`. A heartbeat refreshes this key with the TTL supplied by
the service. The current architecture recommends 15 seconds.

### `guest:session:{participantId}` — HASH

| Field | Java type | Description |
| --- | --- | --- |
| `participant_id` | `UUID` | Guest participant identifier |
| `nickname` | `String` | Nickname entered when the session was created |
| `created_at` | `Instant` | ISO-8601 creation time |

Guest sessions expire at the exact same `expiresAt` instant as the JWT access
token (currently 12 hours after issuance). Saving the HASH and setting its
`PEXPIREAT` value happen in one Lua script. A valid JWT is rejected when this
Redis session is absent; expired sessions are not refreshed and the client must
create a new guest session.

## Atomic operations

`RoomRepository.tryCreate` uses one Lua script to reserve both the room ID and
room code, then creates the room, its code index, the host participant HASH,
participants SET, and nickname SET together. A room therefore cannot be
visible without its creator already registered as host.

`RoomRepository.saveIfAbsent` reserves the room ID and room code together. It
is retained for repository-level operations; the room creation use case calls
`tryCreate`.

`ParticipantRepository.tryAdd` uses one Lua script to check and update all of
the following as a single Redis operation:

1. Room existence
2. `WAITING` room status
3. Duplicate participant ID
4. Maximum participant count
5. Duplicate nickname
6. Participant HASH, membership SET, and nickname SET creation

Room and participant field updates also check existence inside Lua so that an
update racing with room deletion cannot recreate a partial HASH.

## Serialization

All keys, HASH fields, and values use strings through `StringRedisTemplate`.
UUID and `Instant` values use their standard string forms, so data remains
readable with `redis-cli` and is not coupled to Java native serialization.
