-- 손동작 라벨 girl_V → beam 이름 변경.
--
-- 분류기(hand-gesture-recognition-mediapipe/model/keypoint_classifier/keypoint_classifier_label.csv)
-- 에서 girl_V의 학습 데이터를 지우고 같은 클래스 id(1)를 beam으로 재정의했다. gesture.name은
-- 분류기 라벨과의 문자열 계약이라 DB도 같이 바꿔야 프론트가 보내는 라벨과 매칭된다.
--
-- id를 유지하는 UPDATE로 처리한다(DELETE+INSERT 아님) — skill_gesture가 gesture_id를 참조하므로
-- id가 바뀌면 콤보가 깨진다. 지금은 girl_V를 참조하는 skill_gesture가 없지만, 그래도 id는 보존한다.
-- DevNinjaDataSeeder는 name 기준으로 없는 것만 추가하므로, 이 UPDATE 후에는 beam을 다시 넣지 않는다.
UPDATE gesture
SET name = 'beam', label_kr = '빔'
WHERE name = 'girl_V';
