#!/usr/bin/env python3
"""Generate MOTO-HUB T-Box Simulator app icon (.icns)."""

import math
import subprocess
import tempfile
import shutil
from pathlib import Path
from PIL import Image, ImageDraw

CANVAS = 1024
OUTPUT = Path(__file__).resolve().parent.parent / "MOTO-HUB-TBox-Simulator.icns"

def rounded_rect(draw, xy, r, fill=None):
    """Draw a filled rounded rectangle."""
    x0, y0, x1, y1 = xy
    draw.rounded_rectangle(xy, radius=r, fill=fill)
    return

def create_icon(size):
    """Create a 1x icon image at the given size."""
    img = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)

    scale = size / CANVAS

    # Colors
    bg_dark = (18, 25, 29)       # #12191d — carbone scuro
    bg_accent = (45, 216, 129)   # #2dd881 — verde MOTO-HUB
    fg_green = (45, 216, 129)
    fg_white = (200, 220, 215)
    fg_dim = (100, 130, 125)

    def s(v):
        return int(v * scale)

    # Outer rounded rect (standard iOS/macOS icon shape, slightly inset)
    margin = s(48)
    r = s(180)
    rounded_rect(draw, (margin, margin, size - margin, size - margin), r, fill=bg_dark)

    # Inner "TFT screen" panel
    panel_margin = s(160)
    panel_r = s(60)
    panel = (
        panel_margin,
        panel_margin + s(40),
        size - panel_margin,
        size - panel_margin - s(20)
    )
    rounded_rect(draw, panel, panel_r, fill=(26, 38, 44))

    # Screen content area (lighter)
    screen_m = s(20)
    screen_r = s(30)
    screen = (
        panel[0] + screen_m,
        panel[1] + screen_m + s(30),
        panel[2] - screen_m,
        panel[3] - screen_m
    )
    rounded_rect(draw, screen, screen_r, fill=(34, 50, 58))

    # Draw a stylized dashboard gauge (speedometer arc)
    cx = size // 2
    cy = screen[1] + s(180)
    gauge_r = s(140)
    # Arc background
    for i in range(0, 180, 2):
        a1 = math.radians(180 + i)
        a2 = math.radians(180 + i + 2)
        x1 = cx + int(gauge_r * math.cos(a1))
        y1 = cy + int(gauge_r * math.sin(a1))
        x2 = cx + int(gauge_r * math.cos(a2))
        y2 = cy + int(gauge_r * math.sin(a2))
        draw.line([(x1, y1), (x2, y2)], fill=fg_dim, width=s(6))

    # Active arc (green portion ~45% of gauge)
    for i in range(0, 80, 2):
        a1 = math.radians(180 + i)
        a2 = math.radians(180 + i + 2)
        x1 = cx + int(gauge_r * math.cos(a1))
        y1 = cy + int(gauge_r * math.sin(a1))
        x2 = cx + int(gauge_r * math.cos(a2))
        y2 = cy + int(gauge_r * math.sin(a2))
        draw.line([(x1, y1), (x2, y2)], fill=fg_green, width=s(6))

    # Needle (small triangle)
    needle_angle = math.radians(180 + 50)
    needle_len = s(110)
    nx = cx + int(needle_len * math.cos(needle_angle))
    ny = cy + int(needle_len * math.sin(needle_angle))
    draw.line([(cx, cy), (nx, ny)], fill=fg_green, width=s(6))

    # Center dot
    draw.ellipse(
        [cx - s(14), cy - s(14), cx + s(14), cy + s(14)],
        fill=fg_green,
    )

    # Speed value
    speed_font_size = s(72) if size > 256 else s(36)
    # We'll just draw a stylized speed number as simple shapes
    # Draw "NKPH" as small text approximation
    text_y = screen[1] + s(80)
    text_x = cx

    # Draw connection bars (signal strength) in upper-right corner
    bars_x = size - panel_margin - s(100)
    bars_y = panel[1] + s(50)
    for i in range(4):
        bar_w = s(12)
        bar_h = s(20 + i * 16)
        bar_x = bars_x + i * (bar_w + s(6))
        bar_y = bars_y + s(60) - bar_h
        draw.rectangle(
            [bar_x, bar_y, bar_x + bar_w, bar_y + bar_h],
            fill=fg_green if i < 3 else fg_dim,
        )

    # Wi-Fi / connectivity icon (simple arc)
    wifi_cx = size - panel_margin - s(60)
    wifi_cy = panel[1] + s(50)
    for r_w, th in [(20, 4), (30, 4), (40, 4)]:
        rr = s(r_w)
        draw.arc(
            [wifi_cx - rr, wifi_cy - rr, wifi_cx + rr, wifi_cy + rr],
            start=-120, end=-60,
            fill=fg_green, width=s(th),
        )
    # Dot at bottom
    draw.ellipse(
        [wifi_cx - s(6), wifi_cy + s(6), wifi_cx + s(6), wifi_cy + s(18)],
        fill=fg_green,
    )

    # "SIMULATOR" label at bottom
    label_y = size - margin - s(120)
    # Simple bar as text placeholder — clean look
    bar_h = s(10)
    bar_w = s(280)
    draw.rectangle(
        [cx - bar_w // 2, label_y, cx + bar_w // 2, label_y + bar_h],
        fill=fg_green,
    )

    return img

def main():
    sizes = [16, 32, 64, 128, 256, 512, 1024]

    # Create iconset directory
    iconset_dir = Path(tempfile.mkdtemp()) / "AppIcon.iconset"
    iconset_dir.mkdir(parents=True)

    for s in sizes:
        img = create_icon(s)
        if s == 1024:
            # No @2x for 1024
            img.save(iconset_dir / f"icon_{s}x{s}.png")
        else:
            img.save(iconset_dir / f"icon_{s}x{s}.png")
            # 2x version
            img2 = create_icon(s * 2)
            img2.save(iconset_dir / f"icon_{s}x{s}@2x.png")

    # Also copy 1024 as 512@2x
    shutil.copy(
        iconset_dir / "icon_1024x1024.png",
        iconset_dir / "icon_512x512@2x.png"
    )

    # Convert to .icns using iconutil
    iconutil = shutil.which("iconutil")
    if iconutil:
        output_path = str(OUTPUT)
        subprocess.run(
            [iconutil, "-c", "icns", "-o", output_path, str(iconset_dir)],
            check=True,
        )
        print(f"✅ Icona generata: {output_path}")
        print(f"   Dimensione: {Path(output_path).stat().st_size / 1024:.0f} KB")
    else:
        print("⚠️  iconutil non trovato, salvo come PNG singoli")
        output_dir = OUTPUT.parent / "icon-assets"
        output_dir.mkdir(exist_ok=True)
        for f in iconset_dir.iterdir():
            shutil.copy(f, output_dir / f.name)
        print(f"   Salvati in: {output_dir}")

    # Cleanup
    shutil.rmtree(iconset_dir.parent)

if __name__ == "__main__":
    main()
