-- Flyway가 아직 없는 프로젝트이므로 배포 DB에 한 번 수동 적용한다.
-- 동일 주제에 같은 유형/키워드가 중복되어 있으면 가장 작은 mission_id만 남긴다.
DELETE duplicate_mission
FROM missions duplicate_mission
JOIN missions kept_mission
  ON duplicate_mission.game_id = kept_mission.game_id
 AND duplicate_mission.topic_id = kept_mission.topic_id
 AND duplicate_mission.mission_type = kept_mission.mission_type
 AND duplicate_mission.keyword = kept_mission.keyword
 AND duplicate_mission.mission_id > kept_mission.mission_id
WHERE duplicate_mission.topic_id IS NOT NULL;

ALTER TABLE missions
    ADD CONSTRAINT uk_missions_game_topic_type_keyword
    UNIQUE (game_id, topic_id, mission_type, keyword);
