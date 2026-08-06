#!/usr/bin/env sh
# 배포 MySQL에 playtest_metric_* / playtest2_metric_* 뷰를 적용한다. 몇 번 돌려도 안전하다.
#
# 왜 필요한가: 배포 백엔드는 local 프로필(ddl-auto: update)로 돌아서 playtest_sessions /
# playtest_events 테이블은 JPA가 자동으로 만든다. 하지만 대시보드 카드가 읽는 것은 테이블이
# 아니라 뷰 4개(playtest_metric_*)이고, 뷰는 엔티티가 없어서 JPA가 만들어 주지 않는다.
# 이걸 빼먹으면 Metabase 카드 전부가 "Table doesn't exist"로 죽는다.
#
# 비밀번호는 이 스크립트가 다루지 않는다 — 이미 mysql 컨테이너 안에 환경변수로 있으므로
# 컨테이너 내부 셸에서 참조하게 한다(호스트 프로세스 목록/로그에 남지 않는다).
set -eu

SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MIGRATION_DIR="${1:-${SCRIPT_DIR}/../../backend/src/main/resources/db/manual}"
MYSQL_CONTAINER="${MYSQL_CONTAINER:-camon-mysql}"

if [ ! -d "${MIGRATION_DIR}" ]; then
    echo "마이그레이션 디렉터리를 찾을 수 없다: ${MIGRATION_DIR}" >&2
    echo "사용법: $0 [db/manual 경로]" >&2
    exit 1
fi

run_sql() {
    docker exec -i "${MYSQL_CONTAINER}" sh -c \
        'exec mysql -uroot -p"$MYSQL_ROOT_PASSWORD" "$MYSQL_DATABASE"'
}

# 1) analytics_user_key 컬럼/인덱스 보정.
#
# 컬럼은 엔티티에 있으니 ddl-auto: update가 만들어 주지만, 인덱스는 엔티티에 선언돼 있지 않아
# 자동 생성되지 않는다. 둘 다 "없으면 만든다"로 처리한다 — 이미 있는 DB에 ALTER를 그냥 던지면
# Duplicate column/key로 실패한다.
echo "1/3 analytics_user_key 컬럼·인덱스 확인"
run_sql <<'SQL'
SET @column_exists := (
    SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE()
      AND table_name = 'playtest_events'
      AND column_name = 'analytics_user_key'
);
SET @statement := IF(
    @column_exists = 0,
    'ALTER TABLE playtest_events
        ADD COLUMN analytics_user_key CHAR(64) NULL AFTER participant_key',
    'DO 0'
);
PREPARE stmt FROM @statement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @index_exists := (
    SELECT COUNT(*) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'playtest_events'
      AND index_name = 'idx_playtest_events_analytics_user_time'
);
SET @statement := IF(
    @index_exists = 0,
    'ALTER TABLE playtest_events
        ADD INDEX idx_playtest_events_analytics_user_time (
            analytics_user_key, occurred_at
        )',
    'DO 0'
);
PREPARE stmt FROM @statement;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
SQL

# 2) 베이스 테이블(CREATE TABLE IF NOT EXISTS이라 이미 있으면 no-op) + 뷰 3개
#    (전부 CREATE OR REPLACE VIEW라 재적용 가능).
echo "2/3 베이스 테이블 확인 + V20260730_03 뷰 3개 적용"
run_sql <"${MIGRATION_DIR}/V20260730_01__create_playtest_analytics_tables.sql"
run_sql <"${MIGRATION_DIR}/V20260730_03__create_playtest_metric_views.sql"

# 3) V20260731_01 — ALTER TABLE + 뷰 1개가 한 파일에 섞여 있다. ALTER는 위 1)에서 조건부로
#    처리했으므로 여기서는 떼어내고 뷰만 적용한다(뷰 정의를 이 스크립트에 복사해 두면 원본이
#    둘로 갈라지므로, 파일에서 읽되 ALTER 구문만 잘라낸다).
echo "3/4 V20260731_01 뷰 1개 적용"
sed '/^ALTER TABLE/,/;[[:space:]]*$/d' \
    "${MIGRATION_DIR}/V20260731_01__add_anonymous_analytics_user.sql" \
    | run_sql

# 4) 2차 유저테스트 테이블·뷰.
#
# 백엔드는 이제 playtest2_* 에 기록한다. 테이블은 ddl-auto: update가 만들지만 뷰는 엔티티가
# 없어서 자동으로 생기지 않으므로 여기서 적용해야 한다 — 빼먹으면 2차 대시보드 카드가 전부
# "Table doesn't exist"로 죽는다(1차와 같은 함정).
#
# 1차(위 1~3단계)를 계속 적용하는 이유는 1차 뷰가 그대로 살아 있어야 두 회차를 비교할 수
# 있어서다. 1차 테이블에는 더 이상 새 데이터가 쌓이지 않는다.
echo "4/4 2차(playtest2_*) 테이블·뷰 적용"
run_sql <"${MIGRATION_DIR}/V20260806_03__create_playtest2_tables.sql"
run_sql <"${MIGRATION_DIR}/V20260806_04__create_playtest2_metric_views.sql"

echo "--- 적용 결과 ---"
run_sql <<'SQL'
SELECT table_name AS view_name
FROM information_schema.views
WHERE table_schema = DATABASE()
  AND table_name LIKE 'playtest%_metric_%'
ORDER BY table_name;
SQL
