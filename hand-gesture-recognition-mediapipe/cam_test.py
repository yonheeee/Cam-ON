import cv2 as cv

cap = cv.VideoCapture(0, cv.CAP_DSHOW)
cap.set(cv.CAP_PROP_FRAME_WIDTH, 640)
cap.set(cv.CAP_PROP_FRAME_HEIGHT, 480)

print("isOpened:", cap.isOpened())

for i in range(20):
    ret, frame = cap.read()
    print(i, "ret=", ret, "shape=", frame.shape if ret else None)

cap.release()
print("done")
