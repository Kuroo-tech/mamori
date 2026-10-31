#!/usr/bin/env python3
"""Static layout guards for the Compose dashboard.

Phase 3 replaced the View-system dashboard (and its XML layouts) with Compose, so the
old geometry checks no longer had anything to inspect. These guards cover what is still
statically verifiable and what actually regressed historically:

1. No XML dashboard layout may creep back in.
2. No fixed-width container may be introduced (the reason compact HyperOS phones were
   tuned in the first place): widths/spacers are fixed only up to 96 dp.
3. The four top-level destinations must exist, so navigation cannot silently lose a
   screen.
4. No hardcoded user-facing string may appear in Compose sources (localization guard).
5. Vietnamese translations stay complete and format-compatible with English.
6. `vi` stays reachable from the locale config, the picker and both arrays.
7. The build identity (`applicationId`, `minSdk`, `targetSdk`, version format), the four
   permissions and the three manifest components keep their documented values.
8. The removed custom views must not return without a documented decision.

Real resource compilation still happens in assembleDebug; compact-width behaviour is
covered by Compose layout rules here and by instrumentation tests in Phase 5.
"""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
UI = ROOT / "app/src/main/java/com/reed/fcmguard/ui"
LAYOUTS = ROOT / "app/src/main/res/layout"

failures = []


def fail(message: str) -> None:
    failures.append(message)


# 1. No XML dashboard layout.
if LAYOUTS.exists():
    leftovers = sorted(p.name for p in LAYOUTS.glob("*.xml"))
    if leftovers:
        fail(f"XML layouts are no longer used by the dashboard but still exist: {leftovers}")

# 2. Fixed widths stay small. Anything above 96 dp cannot fit a 320 dp device next to
#    screen padding once a second element is present.
MAX_FIXED_DP = 96.0
width_pattern = re.compile(r"(?:required)?[Ww]idth\(\s*(\d+(?:\.\d+)?)\.dp")
for source in UI.rglob("*.kt"):
    text = source.read_text(encoding="utf-8")
    for match in width_pattern.finditer(text):
        value = float(match.group(1))
        if value > MAX_FIXED_DP:
            fail(f"{source.relative_to(ROOT)}: fixed width {value}dp exceeds {MAX_FIXED_DP}dp")

# 3. Destination set is part of the information architecture.
shell = UI / "MamoriApp.kt"
if not shell.exists():
    fail("ui/MamoriApp.kt is missing")
else:
    shell_text = shell.read_text(encoding="utf-8")
    for route in ("home", "fcm", "history", "settings", "language"):
        if f'"{route}"' not in shell_text:
            fail(f"navigation destination '{route}' is missing from MamoriApp.kt")

# 4. No hardcoded user-facing strings in Compose.
literal_text = re.compile(r'\bText\(\s*"')
for source in UI.rglob("*.kt"):
    for number, line in enumerate(source.read_text(encoding="utf-8").splitlines(), 1):
        if literal_text.search(line):
            fail(f"{source.relative_to(ROOT)}:{number}: literal Text(\"...\") - use a string resource")

# 5. Vietnamese parity: every English key exists in values-vi with the same format
#    specifiers, and no Vietnamese-only key is orphaned.

def string_map(path: Path) -> dict:
    root = ET.parse(path).getroot()
    return {node.attrib["name"]: node.text or "" for node in root.findall("string")}


def format_specs(text: str):
    return sorted(re.findall(r"%(?:\d+\$)?[sdfx]", text))


for name in ("strings.xml", "feature_strings.xml", "language_strings.xml"):
    english = string_map(ROOT / "app/src/main/res/values" / name)
    vietnamese = string_map(ROOT / "app/src/main/res/values-vi" / name)
    for key, value in english.items():
        if key not in vietnamese:
            fail(f"values-vi/{name}: missing translation for '{key}'")
        elif format_specs(value) != format_specs(vietnamese[key]):
            fail(
                f"values-vi/{name}: format args differ for '{key}' "
                f"({format_specs(value)} vs {format_specs(vietnamese[key])})"
            )
    for key in vietnamese:
        if key not in english:
            fail(f"values-vi/{name}: '{key}' has no English source string")

# 6. Vietnamese is reachable from every place a locale list lives.
locales_config = (ROOT / "app/src/main/res/xml/locales_config.xml").read_text(encoding="utf-8")
if 'android:name="vi"' not in locales_config:
    fail("locales_config.xml does not declare 'vi'")

settings_source = (UI / "settings/SettingsScreen.kt").read_text(encoding="utf-8")
if '"vi"' not in settings_source:
    fail("the language picker does not offer Vietnamese")

base_array = ET.parse(ROOT / "app/src/main/res/values/arrays.xml").getroot()
vi_array = ET.parse(ROOT / "app/src/main/res/values-vi/arrays.xml").getroot()
base_entries = base_array.find("string-array[@name='language_entries']")
vi_entries = vi_array.find("string-array[@name='language_entries']")
if base_entries is None or vi_entries is None:
    fail("language_entries array missing")
else:
    codes = re.search(r"val codes = listOf\((.*?)\)", settings_source, re.S)
    code_count = len(re.findall(r'"[^"]+"', codes.group(1))) if codes else -1
    if code_count != len(base_entries) or code_count != len(vi_entries):
        fail(
            "language picker codes and language_entries arrays disagree "
            f"(codes={code_count}, en={len(base_entries)}, vi={len(vi_entries)})"
        )

# 7. Build and manifest invariants. These are the constraints that must never be broken
#    casually: the fork identity, the load-bearing targetSdk, and the privileged surface.
build_gradle = (ROOT / "app/build.gradle").read_text(encoding="utf-8")
for pattern, message in (
    (r'applicationId\s+"com\.reed\.fcmguard"', "applicationId must stay com.reed.fcmguard"),
    (r"targetSdk\s+22\b", "targetSdk must stay 22 (legacy write path depends on it)"),
    (r"minSdk\s+21\b", "minSdk must stay 21"),
    (r'versionName\s+"\d+\.\d+\.\d+"', "versionName must match the CI-parsed x.y.z form"),
):
    if not re.search(pattern, build_gradle):
        fail(message)

manifest_root = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
android_ns = "{http://schemas.android.com/apk/res/android}"
required_permissions = {
    "android.permission.WRITE_SETTINGS",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.POST_NOTIFICATIONS",
}
declared = {
    node.attrib.get(android_ns + "name")
    for node in manifest_root.findall("uses-permission")
}
missing_permissions = required_permissions - declared
if missing_permissions:
    fail(f"manifest is missing permissions: {sorted(missing_permissions)}")

application = manifest_root.find("application")
if application is None:
    fail("manifest has no <application>")
else:
    components = {
        "activity": (".MainActivity", "true"),
        "service": (".GuardService", "false"),
        "receiver": (".BootReceiver", "true"),
    }
    for tag, (name, exported) in components.items():
        node = application.find(tag)
        if node is None:
            fail(f"manifest is missing the {tag} {name}")
            continue
        actual_name = node.attrib.get(android_ns + "name")
        actual_exported = node.attrib.get(android_ns + "exported")
        if actual_name != name:
            fail(f"{tag} name changed: {actual_name} (expected {name})")
        if actual_exported != exported:
            fail(f"{tag} {name} exported={actual_exported} (expected {exported})")

# 8. Removed View UI stays removed.
for legacy in (
    "app/src/main/java/com/reed/fcmguard/StickyDashboardLayout.java",
    "app/src/main/java/com/reed/fcmguard/CollapsingStatusCard.java",
    "app/src/main/java/com/reed/fcmguard/MainActivity.java",
):
    if (ROOT / legacy).exists():
        fail(f"legacy View UI reappeared: {legacy}")

if failures:
    print("layout profile checks: FAIL")
    for failure in failures:
        print(f"  - {failure}")
    sys.exit(1)

print("layout profile checks: PASS")
print("  - no XML dashboard layout")
print(f"  - no fixed width above {MAX_FIXED_DP}dp in ui/")
print("  - four top-level destinations present")
print("  - no hardcoded Text(\"...\") literals in ui/")
print("  - legacy View-system classes absent")
print("  - Vietnamese translations complete and format-compatible")
print("  - 'vi' declared in locale config, picker and both arrays")
print("  - build identity, targetSdk, permissions and exported components unchanged")
