#!/usr/bin/env python
# -*- coding: utf-8 -*-
import csv
import copy
import time
import argparse
import itertools
from collections import Counter
from collections import deque

import cv2 as cv
import numpy as np
import mediapipe as mp

from utils import CvFpsCalc
from utils import SkillEffect
from utils import HandLandmarkSmoother
from utils import measure_hand_proximity
from utils import hand_proximity_factor
from utils import hand_gap_limit_for
from utils import describe_hand_candidate
from utils import select_combo_hands
from utils import MIN_HAND_DEPTH
from model import KeyPointClassifier
from model import PointHistoryClassifier

# 한 손만으로 이 라벨이 인식되면 스킬 이펙트가 발동합니다 (지금은 전부 양손 조합이라 비어있음)
SKILL_EFFECT_LABELS = set()
# 이 라벨들은 양손을 맞춰야 나오는 조합 포즈라, 양손이 다 잡혔을 때만 판정/발동합니다
COMBO_SKILL_EFFECT_LABELS = {'snake', 'girl_V', 'mouse', 'Horse', 'cow', 'rabbit',
                             'cat', 'spider', 'sailor_moon'}
# keypoint_classifier_label.csv 기준 조합 전용 클래스 id — 지금은 전부 양손 조합
COMBO_CLASS_IDS = {0, 1, 2, 3, 4, 5, 6, 7, 8}

# 스킬 = 제스처 시퀀스(순서대로 완성해야 발동). gesture 하나하나는 의미 없는 "원소"이고,
# 이 순서로 이어붙였을 때만 스킬로 인정된다.
# display_name은 cv.putText(Hershey 폰트)가 한글을 못 그려서 화면 디버그 표시 전용으로 둔 영문 alias.
# 실제 파티클/색 이펙트는 프론트에서 외부 이펙트로 구현할 예정이라 여기서는 다루지 않는다 —
# 이 데모는 시퀀스 판정/홀드/데미지 로직 검증용.
SKILLS = [
    {
        'name': '뇌절', 'display_name': 'Noejeol(Lightning)',
        'sequence': ['Horse', 'snake'], 'damage': 20,
    },
    {
        'name': '봉선화의 술', 'display_name': 'Bongseonhwa(Fire)',
        'sequence': ['snake', 'mouse', 'cow'], 'damage': 30,
    },
    {
        'name': '수룡탄의 술', 'display_name': 'Suryongtan(Water)',
        'sequence': ['sailor_moon', 'cow', 'rabbit'], 'damage': 30,
    },
    {
        'name': '냥냥펀치', 'display_name': 'NyangPunch(Cat)',
        'sequence': ['girl_V', 'cat'], 'damage': 15,
    },
    {
        'name': '바람의 상처', 'display_name': 'WindScar(Wind)',
        'sequence': ['spider', 'Horse', 'mouse', 'sailor_moon'], 'damage': 45,
    },
]
# 접미사 매칭 시 짧은 시퀀스가 긴 시퀀스보다 먼저 우발적으로 걸리지 않도록 긴 것부터 검사
SKILLS_BY_LENGTH_DESC = sorted(SKILLS, key=lambda s: -len(s['sequence']))

SEQUENCE_STEP_TIMEOUT = 3.0  # 이전 동작 후 이 시간(초) 안에 다음 동작을 이어야 콤보가 유지됨
HOLD_DURATION = 1.0  # 전환 중 스쳐 지나가는 포즈를 걸러내기 위해, 같은 포즈를 이 시간 이상 유지해야 확정


def get_args():
    parser = argparse.ArgumentParser()

    parser.add_argument("--device", type=int, default=0)
    parser.add_argument("--width", help='cap width', type=int, default=960)
    parser.add_argument("--height", help='cap height', type=int, default=540)

    parser.add_argument('--use_static_image_mode', action='store_true')
    parser.add_argument("--min_detection_confidence",
                        help='min_detection_confidence',
                        type=float,
                        default=0.7)
    parser.add_argument("--min_tracking_confidence",
                        help='min_tracking_confidence',
                        type=int,
                        default=0.5)

    args = parser.parse_args()

    return args


def main():
    # Argument parsing #################################################################
    args = get_args()

    cap_device = args.device
    cap_width = args.width
    cap_height = args.height

    use_static_image_mode = args.use_static_image_mode
    min_detection_confidence = args.min_detection_confidence
    min_tracking_confidence = args.min_tracking_confidence

    use_brect = True

    # Camera preparation ###############################################################
    cap = cv.VideoCapture(cap_device, cv.CAP_DSHOW)
    cap.set(cv.CAP_PROP_FRAME_WIDTH, cap_width)
    cap.set(cv.CAP_PROP_FRAME_HEIGHT, cap_height)

    # 카메라가 열린 직후엔 첫 프레임을 못 읽는 경우가 있어 잠깐 워밍업한다
    for _ in range(30):
        ret, _ = cap.read()
        if ret:
            break
        time.sleep(0.1)

    # Model load #############################################################
    mp_hands = mp.solutions.hands
    hands = mp_hands.Hands(
        static_image_mode=use_static_image_mode,
        # 판정에 쓰는 손은 2개(왼손+오른손)뿐인데 4개를 받는 이유: Hands는 "가까운 손"이 아니라
        # 자기 detection 점수 순으로 상한까지 내놓는다. 2로 두면 뒤쪽 사람의 손이나 손처럼 생긴
        # 물체가 상위에 올라오는 순간 플레이어의 손 하나가 밀려나 조합 판정이 죽는다. 후보를
        # 넉넉히 받아서 그중 가까운 손·가운데 손을 utils/hand_selection.py가 직접 고른다.
        max_num_hands=4,
        min_detection_confidence=min_detection_confidence,
        min_tracking_confidence=min_tracking_confidence,
    )

    keypoint_classifier = KeyPointClassifier()

    point_history_classifier = PointHistoryClassifier()

    # Read labels ###########################################################
    with open('model/keypoint_classifier/keypoint_classifier_label.csv',
              encoding='utf-8-sig') as f:
        keypoint_classifier_labels = csv.reader(f)
        keypoint_classifier_labels = [
            row[0] for row in keypoint_classifier_labels
        ]
    with open(
            'model/point_history_classifier/point_history_classifier_label.csv',
            encoding='utf-8-sig') as f:
        point_history_classifier_labels = csv.reader(f)
        point_history_classifier_labels = [
            row[0] for row in point_history_classifier_labels
        ]

    # FPS Measurement ########################################################
    cvFpsCalc = CvFpsCalc(buffer_len=10)

    # Coordinate history (per-hand, keyed by handedness label) #########################
    history_length = 16
    point_history = {
        'Left': deque(maxlen=history_length),
        'Right': deque(maxlen=history_length),
    }

    # Finger gesture history (per-hand) #####################################
    finger_gesture_history = {
        'Left': deque(maxlen=history_length),
        'Right': deque(maxlen=history_length),
    }

    # Skill effect (particle burst on trigger gestures) #####################
    skill_effect = SkillEffect()
    previous_hand_sign = {'Left': None, 'Right': None}
    previous_combo_sign = None

    combo_sequence_buffer = deque(maxlen=max(len(s['sequence']) for s in SKILLS))
    last_triggered_skill = None  # (name, display_name, damage, triggered_at)
    combo_hold_start = 0.0
    combo_hold_confirmed = False
    # 프레임 하나 튀는 오인식(손 떨림 등) 때문에 홀드 타이머가 리셋되는 걸 막기 위한 다수결 스무딩
    combo_sign_history = deque(maxlen=5)

    # 손가락/손이 겹쳐서 검출이 흔들릴 때 좌표 떨림을 줄이는 1€ 필터
    landmark_smoother = HandLandmarkSmoother(min_cutoff=1.0, beta=0.3)

    # 직전 프레임에서 손을 잡고 있었는지 — 깊이 하한의 이력(hysteresis)에 쓴다(hand_selection.py)
    hands_engaged = False

    #  ########################################################################
    mode = 0

    while True:
        fps = cvFpsCalc.get()

        # Process Key (ESC: end) #################################################
        key = cv.waitKey(10)
        if key == 27:  # ESC
            break
        number, mode = select_mode(key, mode)

        # Camera capture #####################################################
        ret, image = cap.read()
        if not ret:
            break
        image = cv.flip(image, 1)  # Mirror display
        debug_image = copy.deepcopy(image)

        # Detection implementation #############################################################
        image = cv.cvtColor(image, cv.COLOR_BGR2RGB)

        image.flags.writeable = False
        results = hands.process(image)
        image.flags.writeable = True

        #  ####################################################################
        preprocessed_by_hand = {'Left': None, 'Right': None}
        # 손 사이 거리 판정용 원본(픽셀) 좌표. 분류기 입력은 손마다 따로 정규화돼서 두 손의
        # 상대 위치가 지워지므로, 거리는 정규화 전 좌표로 재야 한다.
        pixel_by_hand = {'Left': None, 'Right': None}
        detected_hands = set()
        far_hand_count = 0
        nearest_depth = None
        if results.multi_hand_landmarks is not None:
            # 후보를 먼저 전부 재보고(크기·중앙 여부) 판정에 쓸 한 쌍만 고른다 — 스무딩보다 앞서야
            # 한다. 1€ 필터는 손 라벨별로 상태를 들고 있어서, 버릴 손까지 넣으면 뒤쪽 사람의 손
            # 좌표가 같은 라벨 슬롯에 섞여 들어가 플레이어의 손이 그쪽으로 끌려간다.
            frame_height, frame_width = debug_image.shape[0], debug_image.shape[1]
            candidates = []
            for hand_landmarks, handedness in zip(results.multi_hand_landmarks,
                                                  results.multi_handedness):
                candidate = describe_hand_candidate(
                    handedness.classification[0].label,
                    (hand_landmarks, handedness),
                    calc_landmark_list(debug_image, hand_landmarks),
                    frame_width, frame_height)
                if candidate is not None:
                    candidates.append(candidate)

            selection = select_combo_hands(candidates, hands_engaged)
            hands_engaged = len(selection.hands) > 0
            far_hand_count = selection.far_hand_count
            nearest_depth = selection.nearest_depth

            for candidate in selection.hands:
                hand_landmarks, handedness = candidate.source
                hand_label = candidate.handedness
                detected_hands.add(hand_label)

                # Bounding box calculation
                brect = calc_bounding_rect(debug_image, hand_landmarks)
                # Landmark calculation (선별 단계에서 계산해둔 픽셀 좌표를 그대로 쓴다)
                landmark_list = candidate.pixels
                # 겹침으로 인한 좌표 떨림 완화
                landmark_list = landmark_smoother.smooth(
                    hand_label, landmark_list, time.time())
                pixel_by_hand[hand_label] = landmark_list

                # Conversion to relative coordinates / normalized coordinates
                pre_processed_landmark_list = pre_process_landmark(
                    landmark_list)
                preprocessed_by_hand[hand_label] = pre_processed_landmark_list
                pre_processed_point_history_list = pre_process_point_history(
                    debug_image, point_history[hand_label])
                logging_csv_point_history(number, mode,
                                          pre_processed_point_history_list)

                # Hand sign classification (한 손 기준: 뒤 42칸은 0으로 채운 84차원 입력)
                padded_landmark_list = pre_processed_landmark_list + [0.0] * 42
                # 조합 전용 클래스(horse/cow/rabbit)는 양손이 다 잡혔을 때만 수집한다 —
                # 여기서 로그하면 한 손짜리 데이터로 그 클래스를 오염시키게 됨
                if number not in COMBO_CLASS_IDS:
                    logging_csv_keypoint(number, mode, padded_landmark_list)

                hand_sign_id = keypoint_classifier(padded_landmark_list)
                if hand_sign_id == 2:  # Point gesture
                    point_history[hand_label].append(landmark_list[8])
                else:
                    point_history[hand_label].append([0, 0])

                # Finger gesture classification
                finger_gesture_id = 0
                point_history_len = len(pre_processed_point_history_list)
                if point_history_len == (history_length * 2):
                    finger_gesture_id = point_history_classifier(
                        pre_processed_point_history_list)

                # Calculates the gesture IDs in the latest detection
                finger_gesture_history[hand_label].append(finger_gesture_id)
                most_common_fg_id = Counter(
                    finger_gesture_history[hand_label]).most_common()

                # Drawing part
                debug_image = draw_bounding_rect(use_brect, debug_image, brect)
                debug_image = draw_landmarks(debug_image, landmark_list)
                debug_image = draw_info_text(
                    debug_image,
                    brect,
                    handedness,
                    "",
                    point_history_classifier_labels[most_common_fg_id[0][0]],
                )

                # Skill effect: 한 손 트리거 손모양이 "새로" 바뀐 순간에만 파티클 발동
                hand_sign_label = keypoint_classifier_labels[hand_sign_id]
                if (hand_sign_label in SKILL_EFFECT_LABELS
                        and previous_hand_sign[hand_label] != hand_sign_label):
                    center = ((brect[0] + brect[2]) // 2,
                             (brect[1] + brect[3]) // 2)
                    skill_effect.spawn(center)
                previous_hand_sign[hand_label] = hand_sign_label
        else:
            hands_engaged = False

        for hand_label in ('Left', 'Right'):
            if hand_label not in detected_hands:
                point_history[hand_label].append([0, 0])
                previous_hand_sign[hand_label] = None
                landmark_smoother.reset(hand_label)

        # 양손 조합 판정 (horse/cow/rabbit처럼 두 손을 맞춰야 하는 포즈)
        both_hands_detected = detected_hands == {'Left', 'Right'}
        if both_hands_detected:
            combined_landmark_list = combine_two_hand_landmarks(preprocessed_by_hand)

            if number in COMBO_CLASS_IDS:
                logging_csv_keypoint(number, mode, combined_landmark_list)

            combo_sign_id = keypoint_classifier(combined_landmark_list)
            combo_sign_label = keypoint_classifier_labels[combo_sign_id]
            now = time.time()

            # 모양이 맞아도 두 손이 떨어져 있으면 조합 포즈로 인정하지 않는다
            # (이유·기준은 utils/hand_proximity.py). 스케일을 못 구한 프레임은 거리 조건 없이
            # 모양 판정만 쓴다 — 잘못된 스케일로 정상 포즈를 떨어뜨리는 것보다 낫다.
            proximity = measure_hand_proximity(pixel_by_hand['Left'],
                                               pixel_by_hand['Right'])
            gap_text = ""
            if proximity is not None:
                gap_text = " gap:{:.1f}".format(proximity[0])
                if hand_proximity_factor(combo_sign_label, proximity) <= 0:
                    gap_text = " TOO-FAR({:.1f}>{:.1f})".format(
                        proximity[0], hand_gap_limit_for(combo_sign_label))
                    combo_sign_label = None

            # 프레임 하나짜리 오인식에 흔들리지 않도록 최근 몇 프레임의 다수결로 안정화.
            # 거리 조건에 걸린 프레임(None)도 다수결에 넣는다 — 건너뛰면 손을 벌린 뒤에도 직전
            # 판정이 남아 계속 인식된 것처럼 보인다.
            combo_sign_history.append(combo_sign_label)
            stable_combo_sign_label = Counter(
                combo_sign_history).most_common(1)[0][0]

            # 안정화된 포즈가 바뀔 때만 홀드 타이머를 리셋 —
            # 같은 포즈를 HOLD_DURATION 이상 유지해야만 그 스텝이 "완성"으로 인정된다.
            if stable_combo_sign_label != previous_combo_sign:
                combo_hold_start = now
                combo_hold_confirmed = False
                previous_combo_sign = stable_combo_sign_label
            hold_elapsed = now - combo_hold_start

            # gap 수치는 안정화 이전(이번 프레임) 값이라 stable 라벨과 한 프레임 어긋날 수 있다 —
            # 라벨별 한계를 실측으로 튜닝할 때 보는 디버그용 숫자다.
            sign_text = "SIGN:" + (stable_combo_sign_label or "-") + gap_text
            if stable_combo_sign_label in COMBO_SKILL_EFFECT_LABELS and not combo_hold_confirmed:
                sign_text += " ({:.1f}/{:.1f}s)".format(
                    min(hold_elapsed, HOLD_DURATION), HOLD_DURATION)
            cv.putText(debug_image, sign_text, (10, 140),
                       cv.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 0), 4, cv.LINE_AA)
            cv.putText(debug_image, sign_text, (10, 140),
                       cv.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2,
                       cv.LINE_AA)

            if (not combo_hold_confirmed
                    and stable_combo_sign_label in COMBO_SKILL_EFFECT_LABELS
                    and hold_elapsed >= HOLD_DURATION):
                combo_hold_confirmed = True
                if (combo_sequence_buffer
                        and now - combo_sequence_buffer[-1][1] > SEQUENCE_STEP_TIMEOUT):
                    combo_sequence_buffer.clear()
                combo_sequence_buffer.append((stable_combo_sign_label, now))

                matched_skill = match_skill(combo_sequence_buffer, SKILLS_BY_LENGTH_DESC)
                if matched_skill is not None:
                    last_triggered_skill = (matched_skill['display_name'],
                                            matched_skill['damage'], now)
                    combo_sequence_buffer.clear()
        else:
            previous_combo_sign = None
            combo_hold_confirmed = False
            combo_sign_history.clear()

        # 깊이 하한에 걸려 무시한 손 — 정상 거리인데 손이 안 잡히면 이 숫자(실측 depth)를 보고
        # hand_selection.py의 MIN_HAND_DEPTH를 조정한다. 프론트 환경설정 미리보기의 디버그
        # readout과 같은 값이다.
        if far_hand_count > 0:
            far_text = "FAR-HANDS:{} depth:{:.3f}/{:.3f}".format(
                far_hand_count, nearest_depth or 0.0, MIN_HAND_DEPTH)
            cv.putText(debug_image, far_text, (10, 170),
                       cv.FONT_HERSHEY_SIMPLEX, 0.7, (0, 0, 0), 4, cv.LINE_AA)
            cv.putText(debug_image, far_text, (10, 170),
                       cv.FONT_HERSHEY_SIMPLEX, 0.7, (255, 255, 255), 2,
                       cv.LINE_AA)

        debug_image = draw_point_history(debug_image, point_history['Left'])
        debug_image = draw_point_history(debug_image, point_history['Right'])
        debug_image = skill_effect.update_and_draw(debug_image)
        debug_image = draw_combo_progress(debug_image, combo_sequence_buffer)
        debug_image = draw_triggered_skill(debug_image, last_triggered_skill)
        debug_image = draw_info(debug_image, fps, mode, number)

        # Screen reflection #############################################################
        cv.imshow('Hand Gesture Recognition', debug_image)

    cap.release()
    cv.destroyAllWindows()


def select_mode(key, mode):
    number = -1
    if 48 <= key <= 57:  # 0 ~ 9
        number = key - 48
    if key == 110:  # n
        mode = 0
    if key == 107:  # k
        mode = 1
    if key == 104:  # h
        mode = 2
    return number, mode


def calc_bounding_rect(image, landmarks):
    image_width, image_height = image.shape[1], image.shape[0]

    landmark_array = np.empty((0, 2), int)

    for _, landmark in enumerate(landmarks.landmark):
        landmark_x = min(int(landmark.x * image_width), image_width - 1)
        landmark_y = min(int(landmark.y * image_height), image_height - 1)

        landmark_point = [np.array((landmark_x, landmark_y))]

        landmark_array = np.append(landmark_array, landmark_point, axis=0)

    x, y, w, h = cv.boundingRect(landmark_array)

    return [x, y, x + w, y + h]


def calc_landmark_list(image, landmarks):
    image_width, image_height = image.shape[1], image.shape[0]

    landmark_point = []

    # Keypoint
    for _, landmark in enumerate(landmarks.landmark):
        landmark_x = min(int(landmark.x * image_width), image_width - 1)
        landmark_y = min(int(landmark.y * image_height), image_height - 1)
        # landmark_z = landmark.z

        landmark_point.append([landmark_x, landmark_y])

    return landmark_point


def pre_process_landmark(landmark_list):
    temp_landmark_list = copy.deepcopy(landmark_list)

    # Convert to relative coordinates
    base_x, base_y = 0, 0
    for index, landmark_point in enumerate(temp_landmark_list):
        if index == 0:
            base_x, base_y = landmark_point[0], landmark_point[1]

        temp_landmark_list[index][0] = temp_landmark_list[index][0] - base_x
        temp_landmark_list[index][1] = temp_landmark_list[index][1] - base_y

    # Convert to a one-dimensional list
    temp_landmark_list = list(
        itertools.chain.from_iterable(temp_landmark_list))

    # Normalization
    max_value = max(list(map(abs, temp_landmark_list)))

    def normalize_(n):
        return n / max_value

    temp_landmark_list = list(map(normalize_, temp_landmark_list))

    return temp_landmark_list


def combine_two_hand_landmarks(preprocessed_by_hand):
    # 양손이 다 보이면 Left->Right 순서로 이어붙여 "조합 포즈"(horse/cow/rabbit)로 취급.
    # 한 손만 보이면 어느 손인지 상관없이 앞쪽 42칸에 채우고 뒤 42칸은 0으로 채운다 —
    # 기존 Open~Rock 학습 데이터가 손 구분 없이 모아진 것과 형식을 맞추기 위함.
    left = preprocessed_by_hand.get('Left')
    right = preprocessed_by_hand.get('Right')
    if left is not None and right is not None:
        return left + right
    one_hand = left if left is not None else right
    return one_hand + [0.0] * 42


def match_skill(combo_sequence_buffer, skills_by_length_desc):
    # buffer 뒤쪽 n개가 스킬의 시퀀스와 정확히 일치하는지 검사 (긴 시퀀스부터 우선 검사)
    labels = [label for label, _ in combo_sequence_buffer]
    for skill in skills_by_length_desc:
        seq = skill['sequence']
        if len(labels) >= len(seq) and labels[-len(seq):] == seq:
            return skill
    return None


def pre_process_point_history(image, point_history):
    image_width, image_height = image.shape[1], image.shape[0]

    temp_point_history = copy.deepcopy(point_history)

    # Convert to relative coordinates
    base_x, base_y = 0, 0
    for index, point in enumerate(temp_point_history):
        if index == 0:
            base_x, base_y = point[0], point[1]

        temp_point_history[index][0] = (temp_point_history[index][0] -
                                        base_x) / image_width
        temp_point_history[index][1] = (temp_point_history[index][1] -
                                        base_y) / image_height

    # Convert to a one-dimensional list
    temp_point_history = list(
        itertools.chain.from_iterable(temp_point_history))

    return temp_point_history


def logging_csv_keypoint(number, mode, landmark_list):
    if mode == 1 and (0 <= number <= 9):
        csv_path = 'model/keypoint_classifier/keypoint.csv'
        with open(csv_path, 'a', newline="") as f:
            writer = csv.writer(f)
            writer.writerow([number, *landmark_list])


def logging_csv_point_history(number, mode, point_history_list):
    if mode == 2 and (0 <= number <= 9):
        csv_path = 'model/point_history_classifier/point_history.csv'
        with open(csv_path, 'a', newline="") as f:
            writer = csv.writer(f)
            writer.writerow([number, *point_history_list])


def draw_landmarks(image, landmark_point):
    if len(landmark_point) > 0:
        # Thumb
        cv.line(image, tuple(landmark_point[2]), tuple(landmark_point[3]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[2]), tuple(landmark_point[3]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[3]), tuple(landmark_point[4]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[3]), tuple(landmark_point[4]),
                (255, 255, 255), 2)

        # Index finger
        cv.line(image, tuple(landmark_point[5]), tuple(landmark_point[6]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[5]), tuple(landmark_point[6]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[6]), tuple(landmark_point[7]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[6]), tuple(landmark_point[7]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[7]), tuple(landmark_point[8]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[7]), tuple(landmark_point[8]),
                (255, 255, 255), 2)

        # Middle finger
        cv.line(image, tuple(landmark_point[9]), tuple(landmark_point[10]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[9]), tuple(landmark_point[10]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[10]), tuple(landmark_point[11]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[10]), tuple(landmark_point[11]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[11]), tuple(landmark_point[12]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[11]), tuple(landmark_point[12]),
                (255, 255, 255), 2)

        # Ring finger
        cv.line(image, tuple(landmark_point[13]), tuple(landmark_point[14]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[13]), tuple(landmark_point[14]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[14]), tuple(landmark_point[15]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[14]), tuple(landmark_point[15]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[15]), tuple(landmark_point[16]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[15]), tuple(landmark_point[16]),
                (255, 255, 255), 2)

        # Little finger
        cv.line(image, tuple(landmark_point[17]), tuple(landmark_point[18]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[17]), tuple(landmark_point[18]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[18]), tuple(landmark_point[19]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[18]), tuple(landmark_point[19]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[19]), tuple(landmark_point[20]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[19]), tuple(landmark_point[20]),
                (255, 255, 255), 2)

        # Palm
        cv.line(image, tuple(landmark_point[0]), tuple(landmark_point[1]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[0]), tuple(landmark_point[1]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[1]), tuple(landmark_point[2]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[1]), tuple(landmark_point[2]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[2]), tuple(landmark_point[5]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[2]), tuple(landmark_point[5]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[5]), tuple(landmark_point[9]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[5]), tuple(landmark_point[9]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[9]), tuple(landmark_point[13]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[9]), tuple(landmark_point[13]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[13]), tuple(landmark_point[17]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[13]), tuple(landmark_point[17]),
                (255, 255, 255), 2)
        cv.line(image, tuple(landmark_point[17]), tuple(landmark_point[0]),
                (0, 0, 0), 6)
        cv.line(image, tuple(landmark_point[17]), tuple(landmark_point[0]),
                (255, 255, 255), 2)

    # Key Points
    for index, landmark in enumerate(landmark_point):
        if index == 0:  # 手首1
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 1:  # 手首2
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 2:  # 親指：付け根
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 3:  # 親指：第1関節
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 4:  # 親指：指先
            cv.circle(image, (landmark[0], landmark[1]), 8, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 8, (0, 0, 0), 1)
        if index == 5:  # 人差指：付け根
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 6:  # 人差指：第2関節
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 7:  # 人差指：第1関節
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 8:  # 人差指：指先
            cv.circle(image, (landmark[0], landmark[1]), 8, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 8, (0, 0, 0), 1)
        if index == 9:  # 中指：付け根
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 10:  # 中指：第2関節
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 11:  # 中指：第1関節
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 12:  # 中指：指先
            cv.circle(image, (landmark[0], landmark[1]), 8, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 8, (0, 0, 0), 1)
        if index == 13:  # 薬指：付け根
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 14:  # 薬指：第2関節
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 15:  # 薬指：第1関節
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 16:  # 薬指：指先
            cv.circle(image, (landmark[0], landmark[1]), 8, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 8, (0, 0, 0), 1)
        if index == 17:  # 小指：付け根
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 18:  # 小指：第2関節
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 19:  # 小指：第1関節
            cv.circle(image, (landmark[0], landmark[1]), 5, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 5, (0, 0, 0), 1)
        if index == 20:  # 小指：指先
            cv.circle(image, (landmark[0], landmark[1]), 8, (255, 255, 255),
                      -1)
            cv.circle(image, (landmark[0], landmark[1]), 8, (0, 0, 0), 1)

    return image


def draw_bounding_rect(use_brect, image, brect):
    if use_brect:
        # Outer rectangle
        cv.rectangle(image, (brect[0], brect[1]), (brect[2], brect[3]),
                     (0, 0, 0), 1)

    return image


def draw_info_text(image, brect, handedness, hand_sign_text,
                   finger_gesture_text):
    cv.rectangle(image, (brect[0], brect[1]), (brect[2], brect[1] - 22),
                 (0, 0, 0), -1)

    info_text = handedness.classification[0].label[0:]
    if hand_sign_text != "":
        info_text = info_text + ':' + hand_sign_text
    cv.putText(image, info_text, (brect[0] + 5, brect[1] - 4),
               cv.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 1, cv.LINE_AA)

    if finger_gesture_text != "":
        cv.putText(image, "Finger Gesture:" + finger_gesture_text, (10, 60),
                   cv.FONT_HERSHEY_SIMPLEX, 1.0, (0, 0, 0), 4, cv.LINE_AA)
        cv.putText(image, "Finger Gesture:" + finger_gesture_text, (10, 60),
                   cv.FONT_HERSHEY_SIMPLEX, 1.0, (255, 255, 255), 2,
                   cv.LINE_AA)

    return image


def draw_point_history(image, point_history):
    for index, point in enumerate(point_history):
        if point[0] != 0 and point[1] != 0:
            cv.circle(image, (point[0], point[1]), 1 + int(index / 2),
                      (152, 251, 152), 2)

    return image


def draw_combo_progress(image, combo_sequence_buffer):
    if combo_sequence_buffer:
        labels = [label for label, _ in combo_sequence_buffer]
        text = "COMBO: " + " -> ".join(labels)
        cv.putText(image, text, (10, 170), cv.FONT_HERSHEY_SIMPLEX, 0.7,
                   (0, 0, 0), 3, cv.LINE_AA)
        cv.putText(image, text, (10, 170), cv.FONT_HERSHEY_SIMPLEX, 0.7,
                   (255, 255, 255), 1, cv.LINE_AA)
    return image


def draw_triggered_skill(image, last_triggered_skill):
    if last_triggered_skill is None:
        return image
    display_name, damage, triggered_at = last_triggered_skill
    if time.time() - triggered_at > 2.5:
        return image
    text = "SKILL! {} (-{})".format(display_name, damage)
    cv.putText(image, text, (10, 200), cv.FONT_HERSHEY_SIMPLEX, 1.0,
               (0, 0, 0), 4, cv.LINE_AA)
    cv.putText(image, text, (10, 200), cv.FONT_HERSHEY_SIMPLEX, 1.0,
               (0, 215, 255), 2, cv.LINE_AA)
    return image


def draw_info(image, fps, mode, number):
    cv.putText(image, "FPS:" + str(fps), (10, 30), cv.FONT_HERSHEY_SIMPLEX,
               1.0, (0, 0, 0), 4, cv.LINE_AA)
    cv.putText(image, "FPS:" + str(fps), (10, 30), cv.FONT_HERSHEY_SIMPLEX,
               1.0, (255, 255, 255), 2, cv.LINE_AA)

    mode_string = ['Logging Key Point', 'Logging Point History']
    if 1 <= mode <= 2:
        cv.putText(image, "MODE:" + mode_string[mode - 1], (10, 90),
                   cv.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 1,
                   cv.LINE_AA)
        if 0 <= number <= 9:
            cv.putText(image, "NUM:" + str(number), (10, 110),
                       cv.FONT_HERSHEY_SIMPLEX, 0.6, (255, 255, 255), 1,
                       cv.LINE_AA)
    return image


if __name__ == '__main__':
    main()
