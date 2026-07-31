-- V20260730_01을 이미 적용한 로컬/배포 DB에 한 번 수동 적용한다.
-- JPA ddl-auto=update가 먼저 컬럼을 0으로 추가했을 수 있어 기존 첫 기록을 1회차로 보정한다.
ALTER TABLE playtest_sessions
    ADD COLUMN attempt_number INT NOT NULL DEFAULT 1
    AFTER room_key;

UPDATE playtest_sessions
SET attempt_number = 1
WHERE attempt_number = 0;

ALTER TABLE playtest_sessions
    ALTER COLUMN attempt_number DROP DEFAULT;

ALTER TABLE playtest_sessions
    ADD CONSTRAINT uk_playtest_sessions_room_attempt
    UNIQUE (room_key, attempt_number);
