"""Optional Pillow design reference, not Android screenshots. Reads production colors and SVG."""
import argparse
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]


def palette(watch=False):
    values = {}
    files = [ROOT / "shared/src/main/res/values/schedule.xml"]
    if watch:
        files.append(ROOT / "watch/src/main/res/values/schedule.xml")
    for file in files:
        values.update({node.attrib["name"].removeprefix("schedule_"): node.text
                       for node in ET.parse(file).getroot().findall("color")})
    return values


def polygons(data):
    """Only the absolute M/L/H/V/Q/Z commands used in the maintained master."""
    tokens = re.findall(r"[A-Za-z]|-?\d+(?:\.\d+)?", data)
    cursor, x, y, points = 0, 0, 0, []

    def number():
        nonlocal cursor
        value = float(tokens[cursor])
        cursor += 1
        return value

    while cursor < len(tokens):
        cmd = tokens[cursor]
        cursor += 1
        if cmd in ("M", "L"):
            x, y = number(), number()
            points.append((x, y))
        elif cmd == "H":
            x = number()
            points.append((x, y))
        elif cmd == "V":
            y = number()
            points.append((x, y))
        elif cmd == "Q":
            cx, cy, ex, ey = number(), number(), number(), number()
            for step in range(1, 17):
                t = step / 16
                points.append(((1-t)**2*x + 2*(1-t)*t*cx + t*t*ex,
                               (1-t)**2*y + 2*(1-t)*t*cy + t*t*ey))
            x, y = ex, ey
        elif cmd == "Z":
            yield points
            points = []
        else:
            raise ValueError(f"Unsupported SVG command: {cmd}")


def icon(size, circle=False, foreground_scale=1):
    svg = ET.parse(ROOT / "design/app-icon.svg").getroot()
    paths = list(svg.iter("{http://www.w3.org/2000/svg}path"))
    large = size * 4
    image = Image.new("RGBA", (large, large), paths[0].attrib["fill"])
    draw = ImageDraw.Draw(image)
    for path in paths[1:]:
        for polygon in polygons(path.attrib["d"]):
            transformed = [((24 + (x-24)*foreground_scale)*large/48,
                            (24 + (y-24)*foreground_scale)*large/48) for x, y in polygon]
            draw.polygon(transformed, fill=path.attrib["fill"])
    if circle:
        mask = Image.new("L", image.size, 0)
        ImageDraw.Draw(mask).ellipse((0, 0, large-1, large-1), fill=255)
        image.putalpha(mask)
    return image.resize((size, size), Image.Resampling.LANCZOS)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--font", required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    canvas = Image.new("RGB", (1260, 970), "#E8EBE9")
    draw = ImageDraw.Draw(canvas)

    def text(x, y, value, size=22, color="#13251E"):
        draw.text((x, y), value, font=ImageFont.truetype(args.font, size), fill=color, spacing=9)

    def card(x, y, width, height, fill):
        draw.rounded_rectangle((x, y, x+width, y+height), radius=18, fill=fill)

    text(40, 24, "日程 · 视觉参考", 30)
    text(40, 72, "来自资源配色与矢量母版 · 合成内容 / 非实机截图", 18, "#667770")
    phone, watch = palette(), palette(True)
    card(40, 124, 360, 760, phone["background"])
    text(62, 148, "今天", 28)
    card(60, 200, 320, 72, phone["subtle"])
    text(76, 211, "设备已连接\n日程已同步", 17, phone["accent"])
    text(62, 284, "9月30日 · 星期三", 15, phone["muted"])
    card(60, 324, 320, 50, phone["accent"])
    text(155, 334, "＋ 新建日程", 18, phone["on_accent"])
    text(62, 398, "当前与下一项", 21)
    card(60, 442, 320, 102, phone["card"])
    text(76, 456, "整理今日计划", 23)
    text(76, 501, "09-30 09:00 · 执行型", 16, phone["muted"])
    card(60, 556, 320, 102, phone["card"])
    text(76, 570, "23楼开会", 23)
    text(76, 615, "09-30 13:00 · 仅提醒型", 16, phone["muted"])
    text(62, 682, "需要处理", 21)
    card(60, 726, 320, 50, phone["card"])
    text(133, 737, "待确认 · 2 项", 18)
    for index, label in enumerate(["今天", "日程", "报告", "设置"]):
        x = 60 + index * 80
        card(x, 812, 76, 48, phone["accent"] if index == 0 else phone["card"])
        text(x+18, 824, label, 18, phone["on_accent"] if index == 0 else phone["text"])
    text(460, 126, "手表 · 深色界面与安全留边", 22)
    dial = Image.new("RGB", (466, 466), watch["background"])
    d = ImageDraw.Draw(dial)
    font = lambda size: ImageFont.truetype(args.font, size)
    d.text((187, 66), "日程", font=font(38), fill=watch["text"])
    d.text((72, 127), "当前日程", font=font(29), fill=watch["text"])
    d.rounded_rectangle((72, 179, 394, 307), radius=30, fill=watch["card"])
    d.text((94, 193), "整理今日计划", font=font(29), fill=watch["text"])
    d.text((94, 252), "09:00 · 执行型", font=font(25), fill=watch["muted"])
    d.rounded_rectangle((72, 323, 394, 419), radius=30, fill=watch["accent"])
    d.text((177, 347), "刚刚完成", font=font(28), fill=watch["on_accent"])
    mask = Image.new("L", (466, 466), 0)
    ImageDraw.Draw(mask).ellipse((0, 0, 465, 465), fill=255)
    canvas.paste(dial, (445, 174), mask)
    text(470, 666, "后续内容纵向滚动：下一项 / 清单 / 设备", 17, "#667770")
    text(960, 128, "同一产品图形", 22)
    canvas.paste(icon(180), (975, 182))
    canvas.paste(icon(120, True), (1005, 398), icon(120, True))
    text(984, 535, "圆形蒙版参考", 18)
    canvas.paste(icon(48, True), (982, 597), icon(48, True))
    text(1045, 608, "48 px", 18)
    text(470, 752, "灰莓红  #B56F8C", 24)
    text(470, 800, "暖白与深炭前景 · 扁平日历与完成标记", 18)
    text(40, 916, "说明：此图用于审阅色彩和信息层级；系统字体、窗口边距与启动器遮罩需实机验收。", 18)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(args.out)
    print(args.out)


if __name__ == "__main__":
    main()
