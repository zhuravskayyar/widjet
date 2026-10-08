"""Prepare the public metadata consumed by Stone Clock's updater."""
import hashlib
import json
import os
import shutil
from pathlib import Path

run = int(os.environ["GITHUB_RUN_NUMBER"])
version = f"0.4.{run}"
repository = os.environ["GITHUB_REPOSITORY"]
assert repository == "zhuravskayyar/widjet"
source = Path("app/build/outputs/apk/release/app-release.apk")
output = Path("release")
output.mkdir(exist_ok=True)
apk = output / "stone-clock.apk"
shutil.copyfile(source, apk)
metadata = {
    "versionCode": 100 + run,
    "versionName": version,
    "apkUrl": f"https://github.com/{repository}/releases/download/v{version}/stone-clock.apk",
    "sha256": hashlib.sha256(apk.read_bytes()).hexdigest(),
    "size": apk.stat().st_size,
    "commit": os.environ["GITHUB_SHA"],
}
(output / "update.json").write_text(json.dumps(metadata, indent=2) + "\n", encoding="utf-8")
(output / "notes.md").write_text(
    f"Stone Clock {version}\n\n"
    "Оновлення без USB: відкрий Stone Clock → Оновлення застосунку → Перевірити оновлення.\n\n"
    "Для першого встановлення завантаж stone-clock.apk нижче та відкрий його на телефоні.\n\n"
    f"Код цієї версії: {os.environ['GITHUB_SHA']}.\n", encoding="utf-8")
print(f"Prepared v{version}, versionCode={metadata['versionCode']}")
