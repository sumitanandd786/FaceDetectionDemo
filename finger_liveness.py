import cv2
import numpy as np
import time
from collections import deque
from typing import Tuple, Optional

try:
    # tflite_runtime is lighter than full TensorFlow and works on Android via chaquopy
    import tflite_runtime.interpreter as tflite
except ImportError:
    # If tflite_runtime is not available fall back to TensorFlow (may not work on Android)
    import tensorflow as tf
    tflite = None  # type: ignore
    
from scipy.signal import welch


class VideoLivenessDetector:
    """Detect finger liveness from a short video stream using remote PPG (blood-flow) analysis.

    The algorithm looks for periodic intensity changes (pulse) in the green channel
    of the region of interest (ROI). A live finger should produce a clear heart-rate
    peak between 0.75–4 Hz (45–240 bpm) in the power spectral density.
    """

    def __init__(self,
                 device_index: int = 0,
                 roi: Tuple[int, int, int, int] = (0, 0, 0, 0),
                 sample_seconds: float = 5.0,
                 fps: int = 30,
                 power_threshold: float = 0.35):
        """Parameters
        ----------
        device_index : int
            Camera index for cv2.VideoCapture.
        roi : (x, y, w, h)
            Region of interest in frame coordinates. If zeros, the central square
            will be used automatically.
        sample_seconds : float
            Duration of video to analyse.
        fps : int
            Frames per second expected from the camera.
        power_threshold : float
            Minimum relative spectral power inside the pulse band to accept liveness.
        """
        self.device_index = device_index
        self.roi = roi
        self.sample_seconds = sample_seconds
        self.fps = fps
        self.power_threshold = power_threshold

    def _select_roi(self, frame: np.ndarray) -> Tuple[int, int, int, int]:
        if any(self.roi):
            return self.roi
        h, w = frame.shape[:2]
        size = min(h, w) // 3  # central third
        x = (w - size) // 2
        y = (h - size) // 2
        return x, y, size, size

    def analyse(self) -> bool:
        cap = cv2.VideoCapture(self.device_index)
        if not cap.isOpened():
            raise RuntimeError("Unable to open camera device %d" % self.device_index)

        buffer: deque[float] = deque()
        start = time.time()

        while True:
            ret, frame = cap.read()
            if not ret:
                break

            x, y, w, h = self._select_roi(frame)
            roi_frame = frame[y:y + h, x:x + w]

            # Average green channel intensity (works better for PPG)
            avg_intensity = roi_frame[:, :, 1].mean()
            buffer.append(avg_intensity)

            # Keep buffer length reasonable
            while len(buffer) > self.sample_seconds * self.fps:
                buffer.popleft()

            cv2.rectangle(frame, (x, y), (x + w, y + h), (0, 255, 0), 1)
            cv2.imshow("Finger Liveness", frame)
            if cv2.waitKey(1) & 0xFF == ord('q'):
                break

            if time.time() - start >= self.sample_seconds:
                break

        cap.release()
        cv2.destroyAllWindows()

        if len(buffer) < self.fps * 2:  # at least 2 seconds of data
            return False

        signal = np.array(buffer) - np.mean(buffer)
        fs = self.fps
        freqs, psd = welch(signal, fs=fs, nperseg=min(256, len(signal)))

        # Heart rate band 0.75–4 Hz
        band_mask = (freqs >= 0.75) & (freqs <= 4.0)
        band_power = psd[band_mask].sum()
        total_power = psd.sum() + 1e-8
        ratio = band_power / total_power
        return ratio >= self.power_threshold


class ImageLivenessDetector:
    """Detect finger liveness from a static fingerprint image using a TFLite model.

    The model must be trained separately to output a single sigmoid unit: 1.0 for
    live, 0.0 for spoof. It should accept 224×224 RGB input in the range [0, 1].
    """

    def __init__(self, model_path: str):
        if tflite is not None:
            self.interpreter = tflite.Interpreter(model_path=model_path)
            self.interpreter.allocate_tensors()
            self.input_details = self.interpreter.get_input_details()
            self.output_details = self.interpreter.get_output_details()
        else:
            # Fall back to TF saved model
            self.model = tf.keras.models.load_model(model_path)

    @staticmethod
    def _preprocess(img: np.ndarray) -> np.ndarray:
        img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
        img = cv2.resize(img, (224, 224))
        img = img.astype(np.float32) / 255.0
        return img[np.newaxis, ...]  # add batch dim

    def predict(self, img: np.ndarray) -> float:
        input_tensor = self._preprocess(img)
        if hasattr(self, 'interpreter'):
            self.interpreter.set_tensor(self.input_details[0]['index'], input_tensor)
            self.interpreter.invoke()
            output = self.interpreter.get_tensor(self.output_details[0]['index'])
            return float(output[0][0])
        else:
            prob = self.model.predict(input_tensor, verbose=0)
            return float(prob[0][0])

    def is_live(self, img: np.ndarray, threshold: float = 0.5) -> bool:
        return self.predict(img) >= threshold


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="Finger liveness detection demo.")
    subparsers = parser.add_subparsers(dest="mode", required=True)

    live_parser = subparsers.add_parser("video", help="Video-based (PPG)")
    live_parser.add_argument("--seconds", type=float, default=5.0,
                             help="Sampling duration in seconds")
    live_parser.add_argument("--fps", type=int, default=30, help="Expected FPS")
    live_parser.add_argument("--threshold", type=float, default=0.35,
                             help="Minimum relative power threshold")

    img_parser = subparsers.add_parser("image", help="Static image model")
    img_parser.add_argument("model", help="Path to TFLite/SavedModel file")
    img_parser.add_argument("image", help="Path to fingerprint image")
    img_parser.add_argument("--threshold", type=float, default=0.5,
                            help="Probability threshold for liveness")

    args = parser.parse_args()

    if args.mode == "video":
        detector = VideoLivenessDetector(sample_seconds=args.seconds,
                                         fps=args.fps,
                                         power_threshold=args.threshold)
        is_live = detector.analyse()
        print("LIVE" if is_live else "SPOOF")
    else:
        img = cv2.imread(args.image)
        if img is None:
            raise FileNotFoundError(args.image)
        detector = ImageLivenessDetector(args.model)
        is_live = detector.is_live(img, threshold=args.threshold)
        print("LIVE" if is_live else "SPOOF")