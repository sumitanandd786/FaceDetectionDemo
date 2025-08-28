import cv2
from ultralytics import YOLO


def run_demo(weights_path="runs/detect/train/weights/best.pt", video_source=0):
    """Run real-time finger detection.

    Args:
        weights_path (str): Path to YOLOv8 trained weights (.pt).
        video_source (int | str): Webcam index or video file path.
    """
    model = YOLO(weights_path)
    cap = cv2.VideoCapture(video_source)

    while True:
        ret, frame = cap.read()
        if not ret:
            break

        results = model(frame, imgsz=640, conf=0.3)
        annotated = results[0].plot()

        cv2.imshow("Finger detector", annotated)
        if cv2.waitKey(1) & 0xFF == ord("q"):
            break

    cap.release()
    cv2.destroyAllWindows()


if __name__ == "__main__":
    run_demo()