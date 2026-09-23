"""Print marker sheets and measure ArUco detection on the real camera.

Detection only. This tool never connects to the motor path, so it is safe to
run before the Arduino is attached. Use it to learn the practical range of
your printed marker before writing any steering code.
"""
import argparse
import sys
import time
import urllib.request
from pathlib import Path

import cv2
import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from detection.markers import DROP_OFF_ID, MarkerDetector, draw, sheet  # noqa: E402


def generate(args):
    ids = [int(value) for value in args.ids.split(',')]
    image = sheet(ids, pixels=args.pixels)
    out = Path(args.out)
    if not cv2.imwrite(str(out), image):
        raise SystemExit('Could not write ' + str(out))
    print(f'Wrote {out} with IDs {ids}')
    print(f'ID {DROP_OFF_ID} is the drop-off point. Keep the white border when printing.')


def frames(args):
    """Pull JPEGs from a running pc_dashboard, which already handles Pi auth."""
    while True:
        try:
            with urllib.request.urlopen(f'{args.source}/frame?t={time.time()}', timeout=5) as response:
                data = response.read()
        except OSError as exc:
            print('frame unavailable:', type(exc).__name__, exc)
            time.sleep(1)
            continue
        frame = cv2.imdecode(np.frombuffer(data, dtype=np.uint8), cv2.IMREAD_COLOR)
        if frame is not None:
            yield frame


def probe(args):
    detector = MarkerDetector()
    seen, total, started = 0, 0, time.monotonic()
    best = {}
    save = Path(args.save) if args.save else None
    for frame in frames(args):
        markers = detector.detect(frame)
        total += 1
        if markers:
            seen += 1
            for marker in markers:
                kept = best.get(marker['id'])
                if kept is None or marker['side_px'] > kept['side_px']:
                    best[marker['id']] = marker
            top = markers[0]
            print(f"id={top['id']:<3} bearing={top['bearing']:+.3f} side={top['side_px']:>6.1f}px "
                  f"skew={top['skew']:.3f} role={top['role']} others={len(markers)-1}")
            if save is not None:
                cv2.imwrite(str(save), draw(frame, markers))
                save = None
        elif total % 20 == 0:
            print(f'no marker  ({seen}/{total} frames had one)')
        if time.monotonic()-started >= args.seconds:
            break
    print(f'\ndetected in {seen}/{total} frames')
    for marker in sorted(best.values(), key=lambda m: m['id']):
        print(f"  best id={marker['id']}: side={marker['side_px']}px fill={marker['fill']}")
    if not seen:
        print('Nothing detected. Check marker size, lighting, focus and that the '
              'printed border is intact.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='mode', required=True)
    make = sub.add_parser('generate', help='write a printable marker sheet')
    make.add_argument('--ids', default='0,1,2')
    make.add_argument('--pixels', type=int, default=240)
    make.add_argument('--out', default='aruco_sheet.png')
    make.set_defaults(func=generate)
    look = sub.add_parser('probe', help='measure detection on the live camera')
    look.add_argument('--source', default='http://127.0.0.1:8081',
                      help='a running pc_dashboard')
    look.add_argument('--seconds', type=float, default=30.0)
    look.add_argument('--save', help='write one annotated frame here')
    look.set_defaults(func=probe)
    args = parser.parse_args()
    args.func(args)


if __name__ == '__main__':
    main()
