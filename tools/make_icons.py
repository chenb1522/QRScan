"""生成 App 图标：深色圆角底 + 绿色二维码定位角 + 扫描线。"""
import os
from PIL import Image, ImageDraw

HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)
ROOT = os.path.join(PROJ, "app", "src", "main", "res")
SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

BG = (14, 15, 19, 255)
ACCENT = (74, 222, 128, 255)
ACCENT_DIM = (74, 222, 128, 150)


def draw_icon(size: int) -> Image.Image:
    S = size * 8
    img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)

    d.rounded_rectangle([0, 0, S - 1, S - 1], radius=int(S * 0.205), fill=BG)

    def box(x0, y0, x1, y1, w):
        d.rounded_rectangle([int(x0 * S), int(y0 * S), int(x1 * S), int(y1 * S)],
                            radius=int(S * 0.016), outline=ACCENT, width=int(w * S))

    def dot(cx, cy, half):
        d.rectangle([int((cx - half) * S), int((cy - half) * S),
                     int((cx + half) * S), int((cy + half) * S)], fill=ACCENT)

    # 三个定位角
    box(0.215, 0.215, 0.435, 0.435, 0.042)
    box(0.565, 0.215, 0.785, 0.435, 0.042)
    box(0.215, 0.565, 0.435, 0.785, 0.042)

    # 定位角中心块
    dot(0.325, 0.325, 0.045)
    dot(0.675, 0.325, 0.045)
    dot(0.325, 0.675, 0.045)

    # 右下角数据点阵
    for cx, cy in [(0.585, 0.585), (0.665, 0.585), (0.745, 0.585),
                   (0.585, 0.665), (0.745, 0.665),
                   (0.585, 0.745), (0.665, 0.745), (0.745, 0.745)]:
        dot(cx, cy, 0.034)

    # 中央扫描线（三段渐隐）
    y = 0.5
    for i in range(3):
        alpha = int(ACCENT_DIM[3] * (0.45 + 0.3 * i))
        col = (ACCENT[0], ACCENT[1], ACCENT[2], alpha)
        x0 = 0.18 + 0.02 * i
        x1 = 0.82 - 0.02 * i
        d.rectangle([int(x0 * S), int((y - 0.013) * S), int(x1 * S), int((y + 0.013) * S)],
                    fill=col)

    return img.resize((size, size), Image.LANCZOS)


def main():
    for density, size in SIZES.items():
        folder = os.path.join(ROOT, "mipmap-" + density)
        os.makedirs(folder, exist_ok=True)
        icon = draw_icon(size)
        out = os.path.join(folder, "ic_launcher.png")
        icon.save(out, "PNG")
        print("wrote", out, os.path.getsize(out), "bytes")

    # 512 供商店/预览用
    os.makedirs(os.path.join(ROOT, "..", "..", "..", "out"), exist_ok=True)
    preview = draw_icon(512)
    p = os.path.join(PROJ, "out", "icon-preview.png")
    preview.save(p, "PNG")
    print("wrote", p)


if __name__ == "__main__":
    main()
