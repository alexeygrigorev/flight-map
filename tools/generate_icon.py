#!/usr/bin/env python3
"""Generate Flight Map launcher icons and design vector."""

import os
import subprocess
from PIL import Image
import numpy as np

# Extracted vector path of airplane silhouette
PLANE_PATH = (
    "M2992 2630 c-212 -57 -315 -116 -783 -451 l-206 -148 -693 155 c-443 98 -702 152 "
    "-719 148 -35 -9 -222 -127 -218 -138 2 -5 64 -43 138 -84 74 -42 303 -171 509 -289 "
    "206 -117 380 -215 385 -217 6 -2 4 -10 -5 -19 -20 -20 -548 -407 -564 -413 -7 -3 "
    "-151 31 -320 75 -168 45 -316 81 -329 81 -25 0 -179 -97 -184 -116 -2 -7 15 -23 39 "
    "-36 332 -190 573 -336 583 -352 8 -12 45 -147 84 -300 39 -154 73 -281 75 -284 7 -6 "
    "154 99 163 117 4 9 10 101 13 206 l5 190 90 54 c116 69 474 265 604 330 71 36 103 48 "
    "106 39 6 -18 231 -1038 246 -1115 6 -35 13 -63 16 -63 2 0 44 27 94 61 103 69 124 97 "
    "132 177 9 83 84 742 112 987 13 116 27 245 31 288 l6 77 192 133 c105 74 254 176 330 "
    "228 267 180 432 376 460 545 10 56 -12 107 -56 130 -51 28 -240 30 -336 4z"
)


def build_icon_svg(
    scale: float = 1.75,
    nose: tuple = (1090, 275),
    trail_w_end: float = 15.0,
    start_top_y: float = 1060.0,
    start_bot_y: float = 1240.0,
) -> str:
    """Generate SVG XML for the Flight Map launcher icon."""
    tx = nose[0] - scale * 338.0
    ty = nose[1] - scale * 10.0

    tail_target = np.array([tx + scale * 64.0, ty + scale * 185.0])
    nose_target = np.array(nose, dtype=float)

    heading = nose_target - tail_target
    dist = np.hypot(heading[0], heading[1])
    dir_u = heading / dist
    norm_u = np.array([dir_u[1], -dir_u[0]])  # top-left normal

    # Slight penetration (8px * scale) so plane sits ON trail
    penetration = scale * 8.0
    end_center = tail_target + dir_u * penetration

    w_end = scale * trail_w_end
    end_top = end_center + norm_u * (w_end / 2.0)
    end_bot = end_center - norm_u * (w_end / 2.0)

    start_top = np.array([-10.0, float(start_top_y)])
    start_bot = np.array([-10.0, float(start_bot_y)])

    chord_top = end_top - start_top
    chord_len_top = np.hypot(chord_top[0], chord_top[1])
    t_dir = np.array([0.42, -0.907])

    cp1_top = start_top + t_dir * (chord_len_top * 0.46)
    cp2_top = end_top - dir_u * (chord_len_top * 0.40)

    chord_bot = end_bot - start_bot
    chord_len_bot = np.hypot(chord_bot[0], chord_bot[1])
    cp1_bot = start_bot + t_dir * (chord_len_bot * 0.46)
    cp2_bot = end_bot - dir_u * (chord_len_bot * 0.40)

    trail_d = (
        f"M {start_top[0]:.2f} {start_top[1]:.2f} "
        f"C {cp1_top[0]:.2f} {cp1_top[1]:.2f}, {cp2_top[0]:.2f} {cp2_top[1]:.2f}, {end_top[0]:.2f} {end_top[1]:.2f} "
        f"L {end_bot[0]:.2f} {end_bot[1]:.2f} "
        f"C {cp2_bot[0]:.2f} {cp2_bot[1]:.2f}, {cp1_bot[0]:.2f} {cp1_bot[1]:.2f}, {start_bot[0]:.2f} {start_bot[1]:.2f} "
        f"Z"
    )

    plane_tx = tx
    plane_ty = ty + scale * 265.0
    plane_scale_x = scale * 0.1
    plane_scale_y = -scale * 0.1

    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 1254 1254" width="1254" height="1254">
  <rect width="1254" height="1254" fill="#0B1420"/>
  <path d="{trail_d}" fill="#8DE0C6"/>
  <g transform="translate({plane_tx:.3f},{plane_ty:.3f}) scale({plane_scale_x:.5f},{plane_scale_y:.5f})" fill="#FFFFFF">
    <path d="{PLANE_PATH}"/>
  </g>
</svg>
"""


def main():
    repo_root = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    design_dir = os.path.join(repo_root, "docs", "design")
    svg_path = os.path.join(design_dir, "app-icon.svg")
    master_png_path = os.path.join(design_dir, "app-icon.png")

    svg_content = build_icon_svg()
    with open(svg_path, "w") as f:
        f.write(svg_content)
    print(f"Wrote {svg_path}")

    # Render master PNG
    subprocess.run(
        ["convert", "-background", "#0B1420", "-resize", "1254x1254", svg_path, master_png_path],
        check=True,
    )
    master_im = Image.open(master_png_path).convert("RGB")
    master_im.save(master_png_path, "PNG")
    print(f"Rendered {master_png_path} ({master_im.size})")

    # Densities
    densities = {
        "mdpi": 48,
        "hdpi": 72,
        "xhdpi": 96,
        "xxhdpi": 144,
        "xxxhdpi": 192,
    }

    for d, sz in densities.items():
        dest = os.path.join(repo_root, "app", "src", "main", "res", f"mipmap-{d}", "ic_launcher.png")
        resized = master_im.resize((sz, sz), Image.Resampling.LANCZOS)
        resized.save(dest, "PNG")
        print(f"Rendered {dest} ({resized.size})")


if __name__ == "__main__":
    main()
