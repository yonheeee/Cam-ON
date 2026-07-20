import cv2 as cv
import mediapipe as mp

print("step1: opening camera")
cap = cv.VideoCapture(0, cv.CAP_DSHOW)
cap.set(cv.CAP_PROP_FRAME_WIDTH, 640)
cap.set(cv.CAP_PROP_FRAME_HEIGHT, 480)

print("step2: creating mediapipe Hands")
mp_hands = mp.solutions.hands
hands = mp_hands.Hands(
    static_image_mode=False,
    max_num_hands=1,
    min_detection_confidence=0.5,
    min_tracking_confidence=0.5,
)
print("step3: hands created")

for i in range(10):
    ret, frame = cap.read()
    print(i, "read ret=", ret)
    if not ret:
        continue
    image_rgb = cv.cvtColor(frame, cv.COLOR_BGR2RGB)
    print(i, "before process")
    results = hands.process(image_rgb)
    print(i, "after process, hand_landmarks=", results.multi_hand_landmarks is not None)

    print(i, "before imshow")
    cv.imshow("cam_test2", frame)
    cv.waitKey(1)
    print(i, "after imshow")

cap.release()
cv.destroyAllWindows()
print("done")
