-- 스킬 3종 추가: 벚꽃 참격(30) / 탈모빔(45) / slap(20).
--
-- DevNinjaDataSeeder가 항목 단위로 멱등하게 같은 내용을 넣으므로 이 파일은 "이미 돌아가고 있는
-- DB를 재시작 없이 맞추는" 용도다(코드 쪽 선언이 원본). 순서가 중요하다 — skill.id가
-- auto_increment이고 프론트 features/ninja/lib/skillEffects.tsx가 id 8/9/10으로 이펙트를
-- 매핑하므로, 벚꽃 참격 → 탈모빔 → slap 순으로 넣어야 시더가 만드는 id와 같아진다.
--
-- 콤보 길이는 데미지 규칙(20→2단, 30→3단, 45→4단)을 따르고, 어떤 콤보도 다른 콤보의 접두/접미가
-- 되지 않는다(app.py 프로토타입의 접미 매칭에서 짧은 콤보가 먼저 터지지 않게).
INSERT INTO skill (name, damage, effect_id)
SELECT '벚꽃 참격', 30, (SELECT MIN(id) FROM effect)
WHERE NOT EXISTS (SELECT 1 FROM skill WHERE name = '벚꽃 참격');

INSERT INTO skill (name, damage, effect_id)
SELECT '탈모빔', 45, (SELECT MIN(id) FROM effect)
WHERE NOT EXISTS (SELECT 1 FROM skill WHERE name = '탈모빔');

INSERT INTO skill (name, damage, effect_id)
SELECT 'slap', 20, (SELECT MIN(id) FROM effect)
WHERE NOT EXISTS (SELECT 1 FROM skill WHERE name = 'slap');

-- 콤보. (skill_id, seq)가 PK라 중복 실행은 IGNORE로 흘린다.
INSERT IGNORE INTO skill_gesture (skill_id, seq, gesture_id)
SELECT s.id, c.seq, g.id
FROM (
    SELECT '벚꽃 참격' AS skill_name, 1 AS seq, 'sailor_moon' AS gesture_name
    UNION ALL SELECT '벚꽃 참격', 2, 'spider'
    UNION ALL SELECT '벚꽃 참격', 3, 'cat'
    UNION ALL SELECT '탈모빔', 1, 'cow'
    UNION ALL SELECT '탈모빔', 2, 'cat'
    UNION ALL SELECT '탈모빔', 3, 'rabbit'
    UNION ALL SELECT '탈모빔', 4, 'beam'
    UNION ALL SELECT 'slap', 1, 'mouse'
    UNION ALL SELECT 'slap', 2, 'spider'
) c
JOIN skill s ON s.name = c.skill_name
JOIN gesture g ON g.name = c.gesture_name;
