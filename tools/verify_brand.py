"""Keep the user-approved launcher artwork unchanged across editions and updates."""
import hashlib
import json
import xml.etree.ElementTree as ET
from pathlib import Path

root = Path(__file__).resolve().parents[1]
approved = json.loads((root / "docs/approved-brand.json").read_text(encoding="utf-8"))
resources = root / "app/src/main/res"
stems = {"buzin_launcher", "buzin_tv_banner", "buzin_banner"}
actual = {
    path.relative_to(root).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
    for path in resources.rglob("*") if path.is_file() and path.stem in stems
}
if actual != approved["resources"]:
    raise SystemExit("Approved launcher artwork changed. Preserve the logo; a redesign requires Henrique's explicit request.")

android = "{http://schemas.android.com/apk/res/android}"
application = ET.parse(root / "app/src/main/AndroidManifest.xml").getroot().find("application")
for name, value in approved["application"].items():
    if application.get(android + name) != value:
        raise SystemExit("Launcher artwork reference changed: " + name)
for component in application:
    launcher = any(
        category.get(android + "name") in ("android.intent.category.LAUNCHER", "android.intent.category.LEANBACK_LAUNCHER")
        for category in component.findall("intent-filter/category")
    )
    if launcher:
        for name in ("icon", "banner"):
            if component.get(android + name, application.get(android + name)) != approved["application"][name]:
                raise SystemExit("Launcher component uses different artwork: " + component.get(android + "name", ""))
print("Approved launcher artwork preserved: %d resources, all launcher entries." % len(actual))
