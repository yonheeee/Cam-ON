-- 손동작 beam 추가.
--
-- 분류기(hand-gesture-recognition-mediapipe/model/keypoint_classifier/keypoint_classifier_label.csv)
-- 에서 girl_V의 학습 데이터를 지우고 같은 클래스 id(1)를 beam으로 재정의했다. gesture.name은
-- 분류기 라벨과의 문자열 계약이라 DB에도 beam 행이 있어야 프론트가 보내는 라벨과 매칭된다.
--
-- girl_V 행은 남겨 둔다(유령 행). 어떤 skill_gesture도 참조하지 않고, gesture 테이블을 통째로
-- 훑는 코드가 없어서(GestureRepository는 시더 전용, 제스처는 skill_gesture 조인으로만 나간다)
-- 어디에도 노출되지 않는다. 지우면 id가 비고, 리네임하면 DB마다 id가 갈린다 --
-- DevNinjaDataSeeder는 name 기준으로 없는 것만 추가하므로 리네임을 재현하지 못하기 때문이다.
-- 그래서 "시더가 하는 일과 똑같은 것"만 여기서 한다: 없으면 넣는다.
--
-- 02번보다 반드시 먼저 실행할 것 -- 02번의 탈모빔 콤보가 gesture.name = 'beam'을 조인으로
-- 찾는다. beam이 없으면 4번째 단계만 조용히 빠져서 3단 콤보가 된다.
INSERT INTO gesture (name, label_kr)
SELECT 'beam', '빔'
WHERE NOT EXISTS (SELECT 1 FROM gesture WHERE name = 'beam');
