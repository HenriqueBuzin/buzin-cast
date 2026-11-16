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
for module in ("app", "fireos-legacy"):
    application = ET.parse(root / module / "src/main/AndroidManifest.xml").getroot().find("application")
    for name, value in approved["application"].items():
        if module == "fireos-legacy" and name == "roundIcon":
            continue  # Native API17 frontend only declares the TV icon/banner.
        if application.get(android + name) != value:
            raise SystemExit("Launcher artwork reference changed: " + module + ":" + name)
    for component in application:
        launcher = any(
            category.get(android + "name") in ("android.intent.category.LAUNCHER", "android.intent.category.LEANBACK_LAUNCHER")
            for category in component.findall("intent-filter/category")
        )
        if launcher:
            for name in ("icon", "banner"):
                if component.get(android + name, application.get(android + name)) != approved["application"][name]:
                    raise SystemExit("Launcher component uses different artwork: " + module + ":" + component.get(android + "name", ""))
print("Approved launcher artwork preserved: %d resources, modern and API17 launcher entries." % len(actual))
