"""QRScan 交互改版设计稿：首页模式选择 / 解析页 / 极简生成页。

输出 1080x2400 三屏 + 三联总览，参数与实现使用的 dp 完全对齐（density 2.625）。
"""
import os
from PIL import Image, ImageDraw, ImageFont

W, H = 1080, 2400
D = 2.625
HERE = os.path.dirname(os.path.abspath(__file__))
PROJ = os.path.dirname(HERE)
OUT = os.path.join(PROJ, "docs", "design")
QR = os.path.join(PROJ, "docs", "qrs", "qr_url.png")

BG = "#0B0B0F"
SURF = "#17171C"
SURF2 = "#23232B"
STROKE = "#2E2E38"
TEXT = "#F2F2F7"
TEXT2 = "#8E8E99"
TEXT3 = "#5F5F6B"
ACCENT = "#4ADE80"
ACCENT_DIM = "#1F2A22"
BTN_FILL = "#25252E"
BTN_STROKE = "#3A3A45"

F_DIR = r"C:\Windows\Fonts"
F_REG = os.path.join(F_DIR, "Noto Sans SC (TrueType).otf")
F_MED = os.path.join(F_DIR, "Noto Sans SC Medium (TrueType).otf")
F_BOLD = os.path.join(F_DIR, "Noto Sans SC Bold (TrueType).otf")


def dp(v):
    return int(round(v * D))


def font(sp, weight="reg"):
    path = {"reg": F_REG, "med": F_MED, "bold": F_BOLD}[weight]
    return ImageFont.truetype(path, int(round(sp * D)))


def px_font(px, weight="reg"):
    path = {"reg": F_REG, "med": F_MED, "bold": F_BOLD}[weight]
    return ImageFont.truetype(path, px)


def tw(d, s, f):
    b = d.textbbox((0, 0), s, font=f)
    return b[2] - b[0]


def status_bar(d):
    f = font(12, "med")
    d.text((54, 30), "11:16", font=f, fill=TEXT)
    x = 830
    for i in range(4):
        h = 8 + i * 4
        d.rounded_rectangle([x + i * 10, 58 - h, x + i * 10 + 6, 58], radius=2, fill=TEXT)
    d.arc([896, 34, 928, 66], 200, 340, fill=TEXT, width=3)
    d.arc([903, 42, 921, 60], 200, 340, fill=TEXT, width=3)
    d.ellipse([909, 52, 915, 58], fill=TEXT)
    d.rounded_rectangle([946, 36, 998, 60], radius=5, outline=TEXT, width=2)
    d.rounded_rectangle([950, 40, 984, 56], radius=3, fill=ACCENT)
    d.rounded_rectangle([1000, 43, 1004, 53], radius=2, fill=TEXT)


def icon_chevron(d, cx, cy, color=TEXT3, size=9):
    s = dp(size)
    d.line([cx - s * 0.35, cy - s, cx + s * 0.35, cy], fill=color, width=3)
    d.line([cx + s * 0.35, cy, cx - s * 0.35, cy + s], fill=color, width=3)


def icon_back(d, cx, cy, color=TEXT, size=11):
    s = dp(size)
    d.line([cx + s * 0.4, cy - s, cx - s * 0.4, cy], fill=color, width=4)
    d.line([cx - s * 0.4, cy, cx + s * 0.4, cy + s], fill=color, width=4)


def circle_button(d, cx, cy, r, fill=BTN_FILL, stroke=BTN_STROKE):
    d.ellipse([cx - r, cy - r, cx + r, cy + r], fill=fill, outline=stroke, width=2)


def icon_camera(d, cx, cy, s, color=ACCENT):
    w = s
    h = s * 0.76
    d.rounded_rectangle([cx - w / 2, cy - h / 2, cx + w / 2, cy + h / 2],
                        radius=s * 0.14, outline=color, width=4)
    d.rounded_rectangle([cx - w * 0.22, cy - h / 2 - s * 0.13, cx + w * 0.22, cy - h / 2 + s * 0.06],
                        radius=s * 0.05, outline=color, width=4)
    d.ellipse([cx - s * 0.2, cy - s * 0.2, cx + s * 0.2, cy + s * 0.2], outline=color, width=4)


def icon_grid(d, cx, cy, s, color=ACCENT):
    unit = s * 0.30
    gap = s * 0.09
    for (ix, iy) in [(0, 0), (1, 0), (0, 1)]:
        x0 = cx - s / 2 + ix * (unit + gap)
        y0 = cy - s / 2 + iy * (unit + gap)
        d.rounded_rectangle([x0, y0, x0 + unit, y0 + unit], radius=unit * 0.18,
                            outline=color, width=4)
        d.rounded_rectangle([x0 + unit * 0.32, y0 + unit * 0.32,
                             x0 + unit * 0.68, y0 + unit * 0.68],
                            radius=unit * 0.10, fill=color)
    for (ix, iy) in [(1, 1), (2, 2)]:
        x0 = cx - s / 2 + ix * (unit + gap)
        y0 = cy - s / 2 + iy * (unit + gap)
        d.rounded_rectangle([x0, y0, x0 + unit, y0 + unit], radius=unit * 0.18, fill=color)


def icon_photo(d, cx, cy, s, color=TEXT):
    d.rounded_rectangle([cx - s / 2, cy - s * 0.4, cx + s / 2, cy + s * 0.4],
                        radius=s * 0.12, outline=color, width=4)
    d.line([cx - s * 0.36, cy + s * 0.26, cx - s * 0.06, cy - s * 0.12], fill=color, width=4)
    d.line([cx - s * 0.06, cy - s * 0.12, cx + s * 0.34, cy + s * 0.28], fill=color, width=4)
    d.ellipse([cx + s * 0.14, cy - s * 0.28, cx + s * 0.3, cy - s * 0.12], outline=color, width=4)


def icon_flash(d, cx, cy, s, color=TEXT):
    pts = [(cx + s * 0.16, cy - s * 0.46), (cx - s * 0.26, cy + s * 0.04),
           (cx - s * 0.02, cy + s * 0.04), (cx - s * 0.16, cy + s * 0.48),
           (cx + s * 0.28, cy - s * 0.06), (cx + s * 0.04, cy - s * 0.06)]
    d.polygon(pts, fill=color)


def icon_flip(d, cx, cy, s, color=TEXT):
    y1 = cy - s * 0.16
    y2 = cy + s * 0.16
    d.line([cx - s * 0.42, y1, cx + s * 0.34, y1], fill=color, width=4)
    d.line([cx + s * 0.34, y1, cx + s * 0.16, y1 - s * 0.2], fill=color, width=4)
    d.line([cx + s * 0.34, y1, cx + s * 0.16, y1 + s * 0.2], fill=color, width=4)
    d.line([cx + s * 0.42, y2, cx - s * 0.34, y2], fill=color, width=4)
    d.line([cx - s * 0.34, y2, cx - s * 0.16, y2 - s * 0.2], fill=color, width=4)
    d.line([cx - s * 0.34, y2, cx - s * 0.16, y2 + s * 0.2], fill=color, width=4)


def icon_download(d, cx, cy, s, color="#08110C"):
    d.line([cx, cy - s * 0.34, cx, cy + s * 0.14], fill=color, width=4)
    d.line([cx - s * 0.2, cy - s * 0.04, cx, cy + s * 0.16], fill=color, width=4)
    d.line([cx + s * 0.2, cy - s * 0.04, cx, cy + s * 0.16], fill=color, width=4)
    d.line([cx - s * 0.32, cy + s * 0.36, cx + s * 0.32, cy + s * 0.36], fill=color, width=4)


def icon_share(d, cx, cy, s, color=TEXT):
    r = s * 0.12
    for (px, py) in [(cx + s * 0.3, cy - s * 0.3), (cx - s * 0.3, cy), (cx + s * 0.3, cy + s * 0.3)]:
        d.ellipse([px - r * 1.6, py - r * 1.6, px + r * 1.6, py + r * 1.6], outline=color, width=4)
    d.line([cx - s * 0.16, cy + s * 0.03, cx + s * 0.16, cy - s * 0.16], fill=color, width=4)
    d.line([cx - s * 0.16, cy - s * 0.03, cx + s * 0.16, cy + s * 0.16], fill=color, width=4)


def screen_home():
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    status_bar(d)

    x = dp(28)
    cards = [
        ("解析二维码", "", "camera"),
        ("生成二维码", "", "grid"),
    ]
    ch = dp(128)
    gap = dp(24)
    cw = W - dp(56)
    top = (H - (ch * 2 + gap)) // 2
    for i, (title, desc, kind) in enumerate(cards):
        y0 = top + i * (ch + gap)
        y1 = y0 + ch
        d.rounded_rectangle([x, y0, x + cw, y1], radius=dp(24), fill=SURF,
                            outline=STROKE, width=2)
        icx = x + dp(28) + dp(28)
        icy = (y0 + y1) // 2
        d.ellipse([icx - dp(28), icy - dp(28), icx + dp(28), icy + dp(28)], fill=ACCENT_DIM)
        if kind == "camera":
            icon_camera(d, icx, icy, dp(26))
        else:
            icon_grid(d, icx, icy, dp(26))

        tx = icx + dp(28) + dp(22)
        d.text((tx, y0 + ch // 2 - dp(13)), title, font=font(17, "bold"), fill=TEXT)
        icon_chevron(d, x + cw - dp(30), icy)

    return img


def screen_scan():
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)

    for y in range(H):
        t = y / H
        d.line([0, y, W, y], fill=(int(38 - 14 * t), int(40 - 15 * t), int(46 - 17 * t)))

    fs = dp(305)
    fx0, fy0 = (W - fs) // 2, dp(296)
    fx1, fy1 = fx0 + fs, fy0 + fs

    mask = Image.new("L", (W, H), 0)
    md = ImageDraw.Draw(mask)
    md.rectangle([0, 0, W, H], fill=178)
    md.rounded_rectangle([fx0, fy0, fx1, fy1], radius=dp(20), fill=0)
    img.paste(Image.new("RGB", (W, H), "#000000"), (0, 0), mask)

    ly = fy0 + fs * 0.44
    for i in range(30):
        a = (30 - i) / 30
        d.line([(fx0 + 4, ly - i), (fx1 - 4, ly - i)],
               fill=(int(10 + 20 * a), int(30 + 60 * a), int(16 + 34 * a)))

    arm = dp(56)
    r = dp(20)
    lw = int(dp(3.2))
    for (ax, ay, sx, sy) in [(fx0, fy0, 1, 1), (fx1, fy0, -1, 1), (fx0, fy1, 1, -1), (fx1, fy1, -1, -1)]:
        d.line([ax + sx * r, ay, ax + sx * (r + arm), ay], fill="white", width=lw)
        d.line([ax, ay + sy * r, ax, ay + sy * (r + arm)], fill="white", width=lw)
    d.line([fx0 + dp(2), ly, fx1 - dp(2), ly], fill=ACCENT, width=int(dp(2.6)))

    hint = "将二维码放入框内，即可自动识别"
    d.text(((W - tw(d, hint, font(12.5))) / 2, fy1 + dp(34)), hint, font=font(12.5),
           fill="#D8D8DC")

    status_bar(d)
    back_cx = dp(20) + dp(21)
    back_cy = dp(36) + dp(21)
    circle_button(d, back_cx, back_cy, dp(21))
    icon_back(d, back_cx, back_cy)
    d.text((back_cx + dp(21) + dp(14), back_cy - dp(11)), "解析二维码",
           font=font(19, "bold"), fill=TEXT)

    labels = [("photo", "相册"), ("flash", "补光"), ("flip", "翻转")]
    spacing = dp(38)
    bw = dp(58)
    total = len(labels) * bw + (len(labels) - 1) * spacing
    x0 = (W - total) / 2
    cy = H - dp(34) - dp(58) / 2 - dp(16)
    for i, (kind, label) in enumerate(labels):
        cx = x0 + i * (bw + spacing) + bw / 2
        circle_button(d, cx, cy, bw / 2)
        if kind == "photo":
            icon_photo(d, cx, cy, dp(30))
        elif kind == "flash":
            icon_flash(d, cx, cy, dp(30))
        else:
            icon_flip(d, cx, cy, dp(30))
        lw2 = tw(d, label, font(11))
        d.text((cx - lw2 / 2, cy + bw / 2 + dp(8)), label, font=font(11), fill=TEXT2)
    return img


def screen_generate():
    img = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(img)
    status_bar(d)

    back_cx = dp(20) + dp(21)
    back_cy = dp(36) + dp(21)
    circle_button(d, back_cx, back_cy, dp(21))
    icon_back(d, back_cx, back_cy)
    d.text((back_cx + dp(21) + dp(14), back_cy - dp(11)), "生成二维码",
           font=font(19, "bold"), fill=TEXT)

    px0 = dp(28)
    pw = W - dp(56)
    py0 = dp(150)
    d.rounded_rectangle([px0, py0, px0 + pw, py0 + pw], radius=dp(24), fill="white")

    if os.path.exists(QR):
        qr = Image.open(QR).convert("RGB")
        inner = int(pw - dp(22) * 2)
        qr = qr.resize((inner, inner), Image.LANCZOS)
        img.paste(qr, (int(px0 + dp(22)), int(py0 + dp(22))))

    label_y = py0 + pw + dp(28)
    d.text((px0 + dp(2), label_y), "内容", font=font(12), fill=TEXT2)
    iy0 = label_y + dp(30)
    ih = dp(160)
    d.rounded_rectangle([px0, iy0, px0 + pw, iy0 + ih], radius=dp(16),
                        fill=SURF2, outline=STROKE, width=2)
    d.text((px0 + dp(15), iy0 + dp(16)), "输入要生成二维码的文本…", font=font(13.5), fill=TEXT3)

    # 单个全宽主按钮：保存到相册
    bh = dp(52)
    by = H - dp(34) - bh
    d.rounded_rectangle([px0, by, px0 + pw, by + bh], radius=dp(14), fill=ACCENT)
    label = "保存到相册"
    lab_w = tw(d, label, font(14, "bold"))
    d.text((px0 + (pw - lab_w) / 2, by + bh / 2 - dp(10)), label,
           font=font(14, "bold"), fill="#08110C")
    return img


def overview(images):
    pad = 44
    tw_ = 420
    th_ = int(tw_ * H / W)
    gap = 34
    total_w = pad * 2 + tw_ * 3 + gap * 2
    total_h = 140 + th_ + 64
    canvas = Image.new("RGB", (total_w, total_h), "#050507")
    d = ImageDraw.Draw(canvas)
    d.text((pad, 30), "QRScan 交互改版 · 设计稿", font=px_font(30, "bold"), fill=TEXT)
    d.text((pad, 78),
           "① 首页先选模式　② 解析页只做解析　③ 生成页只留一个输入框（纠错固定 Q / 25%）",
           font=px_font(15), fill=TEXT2)
    for i, (img, cap) in enumerate(images):
        x = pad + i * (tw_ + gap)
        y = 136
        canvas.paste(img.resize((tw_, th_), Image.LANCZOS), (x, y))
        d.rounded_rectangle([x, y, x + tw_, y + th_], radius=14, outline=STROKE, width=2)
        cw_ = tw(d, cap, px_font(17, "med"))
        d.text((x + (tw_ - cw_) / 2, y + th_ + 18), cap, font=px_font(17, "med"), fill=TEXT)
    return canvas


def main():
    os.makedirs(OUT, exist_ok=True)
    home = screen_home()
    scan = screen_scan()
    gen = screen_generate()

    home.save(os.path.join(OUT, "01-home.png"))
    scan.save(os.path.join(OUT, "02-scan.png"))
    gen.save(os.path.join(OUT, "03-generate.png"))
    overview([(home, "① 首页：先选模式"), (scan, "② 解析：扫码 / 相册"),
              (gen, "③ 生成：只填文本")]).save(os.path.join(OUT, "00-overview.png"))

    for n in ["00-overview.png", "01-home.png", "02-scan.png", "03-generate.png"]:
        p = os.path.join(OUT, n)
        print("wrote", p, os.path.getsize(p), "bytes")


if __name__ == "__main__":
    main()
