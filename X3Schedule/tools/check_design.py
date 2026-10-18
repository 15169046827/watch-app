"""Check readable native palettes, icon generation, and both manifest entry points."""
import xml.etree.ElementTree as ET
from generate_icons import ROOT, generate


def luminance(color):
    rgb = [int(color[index:index+2], 16) / 255 for index in (1, 3, 5)]
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in rgb]
    return sum(value * weight for value, weight in zip(linear, (0.2126, 0.7152, 0.0722)))


def contrast(first, second):
    values = sorted((luminance(first), luminance(second)))
    return (values[1] + 0.05) / (values[0] + 0.05)


def check_progress(role, colors):
    palette = {node.attrib["name"].removeprefix("schedule_"): node.text
               for node in ET.parse(ROOT / "shared/src/main/res/values/progress.xml").getroot()}
    if role == "watch":
        palette.update({node.attrib["name"].removeprefix("schedule_"): node.text
                        for node in ET.parse(ROOT / "watch/src/main/res/values/progress.xml").getroot()})
    for name in ("result_none", "score_low", "score_minimum", "result_basic", "result_perfect"):
        for surface in ("background", "card"):
            assert contrast(palette[name], colors[surface]) >= 4.5, (role, name, surface)
    alpha = 0.16 if role == "watch" else 0.08
    for name, tint in palette.items():
        base = colors["card"]
        blended = "#" + "".join(f"{int(int(base[i:i+2], 16) * (1-alpha) + int(tint[i:i+2], 16) * alpha):02x}"
                                for i in (1, 3, 5))
        assert contrast(colors["text"], blended) >= 4.5, (role, name, "grade card")
    print(f"{role} progress score text and tinted-card contrast passed.")


def check():
    for path, expected in generate().items():
        assert path.read_text(encoding="utf-8") == expected, f"Stale icon export: {path}"
    namespace = "{http://schemas.android.com/apk/res/android}"
    base = ROOT / "shared/src/main/res/values/schedule.xml"
    for role in ("phone", "watch"):
        colors = {x.attrib["name"].removeprefix("schedule_"): x.text
                  for x in ET.parse(base).getroot().findall("color")}
        if role == "watch":
            colors.update({x.attrib["name"].removeprefix("schedule_"): x.text
                           for x in ET.parse(ROOT / "watch/src/main/res/values/schedule.xml")
                           .getroot().findall("color")})
        pairs = [("text", "background"), ("text", "card"), ("muted", "background"),
                 ("muted", "card"), ("accent", "subtle"), ("on_accent", "accent"),
                 ("error", "background")]
        for foreground, background in pairs:
            ratio = contrast(colors[foreground], colors[background])
            assert ratio >= 4.5, (role, foreground, background, ratio)
            print(f"{role} {foreground}/{background}: {ratio:.2f}:1")
        check_progress(role, colors)
        app = ET.parse(ROOT / role / "src/main/AndroidManifest.xml").getroot().find("application")
        assert app.attrib[namespace + "icon"] == "@mipmap/ic_schedule"
        assert app.attrib[namespace + "roundIcon"] == app.attrib[namespace + "icon"]
        assert app.attrib[namespace + "theme"] == "@style/Schedule" + role.title() + "Theme"
    print("Both palettes, shared icon exports, themes and launcher references passed.")


if __name__ == "__main__":
    check()
