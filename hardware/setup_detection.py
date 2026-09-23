"""Run once after installing requirements-detection.txt; checks local custom weights only."""
from pathlib import Path


def main():
    from ultralytics import YOLO
    from detection.config import WEIGHTS, MODEL_SPECS
    WEIGHTS.mkdir(parents=True, exist_ok=True)
    for name,path,_ in MODEL_SPECS:
        if not path.is_file():
            raise FileNotFoundError(f'Local model missing: {path.name}')
        model = YOLO(str(path))
        if model.task != 'detect': raise ValueError(f'{name}: detection checkpoint required')
        print(name, model.names)
        # Verify local CPU inference before connecting the robot.
        import numpy as np
        model.predict(np.zeros((480,640,3),dtype=np.uint8),device='cpu',verbose=False)
    print('Models ready. Start pc_dashboard.py with your existing arguments.')


if __name__ == '__main__':
    main()
