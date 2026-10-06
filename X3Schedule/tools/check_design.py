"""Check readable native palettes, icon generation, and both manifest entry points."""
import xml.etree.ElementTree as ET
from generate_icons import ROOT, generate


def luminance(color):
    rgb = [int(color[index:index+2], 16) / 255 for index in (1, 3, 5)]
    linear = [v / 12.92 if v <= 0.04045 else ((v + 0.055) / 1.055) ** 2.4 for v in rgb]
    return sum(value * weight for value, weight in zip(linear, (0.2126, 0.7152, 0.0722)))


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
            values = sorted([luminance(colors[foreground]), luminance(colors[background])])
            contrast = (values[1] + 0.05) / (values[0] + 0.05)
            assert contrast >= 4.5, (role, foreground, background, contrast)
            print(f"{role} {foreground}/{background}: {contrast:.2f}:1")
        app = ET.parse(ROOT / role / "src/main/AndroidManifest.xml").getroot().find("application")
        assert app.attrib[namespace + "icon"] == "@mipmap/ic_schedule"
        assert app.attrib[namespace + "roundIcon"] == app.attrib[namespace + "icon"]
        assert app.attrib[namespace + "theme"] == "@style/Schedule" + role.title() + "Theme"
    print("Both palettes, shared icon exports, themes and launcher references passed.")


if __name__ == "__main__":
    check()
