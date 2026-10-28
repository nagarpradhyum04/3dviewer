"""Generate the printable ArUco calibration marker used for metric scale.

The reconstruction pipeline detects this marker (dictionary DICT_4X4_50,
id 0 by default) in the captured photos and uses its known printed side
length to convert the reconstruction into real-world units.

Usage:
    python tools/make_marker.py --size-mm 50 --out marker_50mm.png
Print at 100 % scale (no "fit to page") and MEASURE the printed black
square with a ruler; enter that measured value in the app.
"""
import argparse

import cv2
import numpy as np

DICTIONARY = cv2.aruco.DICT_4X4_50


def marker_image(marker_id: int, cell_px: int, margin_cells: int = 1) -> np.ndarray:
    aruco_dict = cv2.aruco.getPredefinedDictionary(DICTIONARY)
    cells = 6  # 4x4 data bits + 1-cell black border on each side
    core = cv2.aruco.generateImageMarker(aruco_dict, marker_id, cells * cell_px)
    return cv2.copyMakeBorder(core, *(margin_cells * cell_px,) * 4, cv2.BORDER_CONSTANT, value=255)


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--size-mm", type=float, default=50.0, help="side length of the black square")
    ap.add_argument("--id", type=int, default=0)
    ap.add_argument("--dpi", type=int, default=300)
    ap.add_argument("--out", default="marker.png")
    ap.add_argument("--plain", action="store_true", help="no caption (used by the synthetic renderer)")
    args = ap.parse_args()

    px_per_mm = args.dpi / 25.4
    cell_px = max(4, int(round(args.size_mm * px_per_mm / 6)))
    img = marker_image(args.id, cell_px, margin_cells=1 if args.plain else 2)
    if not args.plain:
        caption = f"Scan3D AI marker id={args.id}  black square = {args.size_mm:g} mm  (print at 100%)"
        pad = np.full((int(12 * px_per_mm), img.shape[1]), 255, np.uint8)
        cv2.putText(pad, caption, (int(2 * px_per_mm), int(7 * px_per_mm)), cv2.FONT_HERSHEY_SIMPLEX,
                    max(0.4, img.shape[1] / 2200), 0, 2, cv2.LINE_AA)
        img = np.vstack([img, pad])
    cv2.imwrite(args.out, img, [cv2.IMWRITE_PNG_COMPRESSION, 9])
    print(f"wrote {args.out} ({img.shape[1]}x{img.shape[0]} px @ {args.dpi} dpi)")


if __name__ == "__main__":
    main()
