"""Fetch the public Apache-2.0 Qwen model for Android CPU inference; verify its hash."""
from pathlib import Path
import hashlib
import json
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REPO = "litert-community/Qwen3-0.6B"
FILENAME = "Qwen3-0.6B_dynamic_wi4b32_afp32.litertlm"
REVISION = "2670e142a0d16a1b685be6604e5b4f2b31a826d7"
EXPECTED_SHA = "bf1aba9ce8d963124407353c6aa4cbf72eba78db21ae133de983eac672ce83e0"


def main():
    destination = ROOT / ".tools/models"
    destination.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(f"https://huggingface.co/api/models/{REPO}/revision/{REVISION}?blobs=true", timeout=30) as response:
        metadata = json.load(response)
    entry = next(x for x in metadata["siblings"] if x["rfilename"] == FILENAME)
    expected = entry["lfs"]["sha256"]
    if expected != EXPECTED_SHA:
        raise RuntimeError("The pinned model metadata does not match the application.")
    target = destination / "qwen3-0.6b.litertlm"
    def verified(path):
        if not path.is_file():
            return False
        with path.open('rb') as source:
            return hashlib.file_digest(source, 'sha256').hexdigest() == expected

    if not verified(target):
        url = f"https://huggingface.co/{REPO}/resolve/{metadata['sha']}/{FILENAME}?download=true&cache={int(time.time())}"
        temporary = target.with_suffix(".partial")
        with urllib.request.urlopen(url, timeout=60) as response, temporary.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
        if not verified(temporary):
            raise RuntimeError("Model checksum does not match the official repository; existing model was preserved.")
        temporary.replace(target)
    actual = expected
    (destination / "model-info.json").write_text(json.dumps({"name": "Qwen3-0.6B CPU int4", "sha256": actual,
        "bytes": target.stat().st_size, "repo": REPO, "revision": metadata["sha"], "filename": FILENAME}, indent=2), encoding="utf-8")
    print(f"Verified model: {target.stat().st_size} bytes, SHA256 {actual}")


if __name__ == "__main__":
    main()
