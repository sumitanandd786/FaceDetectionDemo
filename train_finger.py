import torch
from ultralytics import YOLO

def main():
    """Fine-tune YOLOv8 on a single-class finger dataset."""
    base_weights = "yolov8n.pt"  # nano model, change to yolov8s.pt for higher accuracy
    model = YOLO(base_weights)

    # Training
    results = model.train(
        data="finger.yaml",
        epochs=100,
        imgsz=640,
        batch=16,
        patience=20,
        optimizer="SGD",
        lr0=0.01,
        device=0 if torch.cuda.is_available() else "cpu",
        plots=True,
    )

    # Export best weights
    best_pt = "runs/detect/train/weights/best.pt"
    yolo_trained = YOLO(best_pt)
    yolo_trained.export(format="onnx")


if __name__ == "__main__":
    main()