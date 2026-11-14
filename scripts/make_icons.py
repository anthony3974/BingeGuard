#!/usr/bin/env python3
"""Draws PNG versions of the launcher icon (the adaptive icon's shield on a rounded square).

Phones use the adaptive icon (res/mipmap-anydpi/ic_launcher.xml). These PNGs are for F-Droid,
which can only show an app's icon if the APK has PNGs named like the icon resource.

    python3 scripts/make_icons.py
"""
import math
import os

import cairo

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res")
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
NAVY = (0x1F / 255, 0x3A / 255, 0x5F / 255)
AMBER = (0xF2 / 255, 0xB1 / 255, 0x34 / 255)


def rounded_square(ctx, size, radius):
    ctx.new_sub_path()
    ctx.arc(size - radius, radius, radius, -math.pi / 2, 0)
    ctx.arc(size - radius, size - radius, radius, 0, math.pi / 2)
    ctx.arc(radius, size - radius, radius, math.pi / 2, math.pi)
    ctx.arc(radius, radius, radius, math.pi, 3 * math.pi / 2)
    ctx.close_path()


def draw(size):
    surface = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    ctx = cairo.Context(surface)
    rounded_square(ctx, size, size * 0.22)
    ctx.set_source_rgb(*NAVY)
    ctx.fill()

    # The foreground drawable's 108-unit viewport, cropped to its middle 72 units (the part a
    # launcher mask shows) so the shield fills the square.
    scale = size / 72.0
    ctx.scale(scale, scale)
    ctx.translate(-18, -18)
    ctx.move_to(54, 30)
    ctx.line_to(74, 37)
    ctx.line_to(74, 53)
    ctx.curve_to(74, 66, 65, 75, 54, 80)
    ctx.curve_to(43, 75, 34, 66, 34, 53)
    ctx.line_to(34, 37)
    ctx.close_path()
    ctx.set_source_rgb(*AMBER)
    ctx.fill()
    ctx.rectangle(46.5, 45, 5, 18)
    ctx.rectangle(56.5, 45, 5, 18)
    ctx.set_source_rgb(*NAVY)
    ctx.fill()
    return surface


for density, px in SIZES.items():
    out_dir = os.path.join(ROOT, f"mipmap-{density}")
    os.makedirs(out_dir, exist_ok=True)
    draw(px).write_to_png(os.path.join(out_dir, "ic_launcher.png"))
    print(f"mipmap-{density}/ic_launcher.png ({px}px)")
