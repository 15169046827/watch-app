"""Deterministic SVG master -> shared Android adaptive and fallback resources."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
NS = {"s": "http://www.w3.org/2000/svg"}
RES = ROOT / "shared/src/main/res"


def generate():
    svg = ET.parse(ROOT / "design/app-icon.svg").getroot()
    background = svg.find("s:path[@id='background']", NS)
    paths = list(svg.find("s:g[@id='foreground']", NS))
    assert svg.attrib["viewBox"] == "0 0 48 48"
    assert background.attrib["d"] == "M0,0H48V48H0Z"

    def path(node):
        return f'    <path android:fillColor="{node.attrib["fill"]}" android:pathData="{node.attrib["d"]}" />\n'

    start = ('<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
             '    android:width="48dp" android:height="48dp"\n'
             '    android:viewportWidth="48" android:viewportHeight="48">\n')
    foreground = (start + '    <group android:pivotX="24" android:pivotY="24"\n'
                  '        android:scaleX="0.72" android:scaleY="0.72">\n'
                  + ''.join(path(node) for node in paths) + '    </group>\n</vector>\n')
    fallback = start + path(background) + ''.join(path(node) for node in paths) + '</vector>\n'
    return {
        RES / "drawable/ic_schedule_foreground.xml": foreground,
        RES / "mipmap-anydpi/ic_schedule.xml": fallback,
        RES / "values/icon.xml": '<resources>\n    <color name="schedule_icon_background">'
        + background.attrib["fill"] + '</color>\n</resources>\n',
        RES / "mipmap-anydpi-v26/ic_schedule.xml":
        '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
        '    <background android:drawable="@color/schedule_icon_background" />\n'
        '    <foreground android:drawable="@drawable/ic_schedule_foreground" />\n</adaptive-icon>\n',
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    for target, expected in generate().items():
        if args.check:
            assert target.read_text(encoding="utf-8") == expected, f"Stale resource: {target}"
        else:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(expected, encoding="utf-8", newline="\n")
    print("Schedule icon resources match the SVG master." if args.check else "Schedule icons generated.")


if __name__ == "__main__":
    main()
