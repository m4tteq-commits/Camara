"""Download verified JDK/Android command-line tools into .tools, without a system install.

SDK packages and licenses are managed separately by the official sdkmanager.
"""
import concurrent.futures
import hashlib
import json
from pathlib import Path
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = ROOT / ".tools"


def fetch(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return response.read()


def unpack_verified(url, expected, algorithm, target, filename):
    downloads = TOOLS / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    archive = downloads / filename
    if not archive.exists():
        with urllib.request.urlopen(url, timeout=60) as response, archive.open("wb") as output:
            while chunk := response.read(1024 * 1024):
                output.write(chunk)
    if hashlib.new(algorithm, archive.read_bytes()).hexdigest() != expected:
        raise RuntimeError(f"Checksum mismatch: {filename}")
    target.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(archive) as bundle:
        for entry in bundle.infolist():
            resolved = (target / entry.filename).resolve()
            if not resolved.is_relative_to(target.resolve()):
                raise RuntimeError("Archive contains an unsafe path")
        bundle.extractall(target)
    print(f"Verified and extracted {filename}", flush=True)


def jdk():
    existing = list((TOOLS / "jdk").glob("*/bin/java.exe"))
    if existing:
        print(f"JAVA_HOME={existing[0].parent.parent}", flush=True)
        return
    release = json.loads(fetch("https://api.github.com/repos/adoptium/temurin17-binaries/releases/latest"))
    if not any("-jdk_x64_windows_hotspot_" in a["name"] and a["name"].endswith(".zip") for a in release["assets"]):
        release = json.loads(fetch("https://api.github.com/repos/adoptium/temurin17-binaries/releases/tags/jdk-17.0.16%2B8"))
    asset = next(a for a in release["assets"] if a["name"].startswith("OpenJDK17U-jdk_x64_windows_hotspot_") and a["name"].endswith(".zip"))
    checksum_asset = next(a for a in release["assets"] if a["name"] == asset["name"] + ".sha256.txt")
    checksum = fetch(checksum_asset["browser_download_url"]).decode().split()[0]
    unpack_verified(asset["browser_download_url"], checksum, "sha256", TOOLS / "jdk", "jdk17.zip")
    java = next((TOOLS / "jdk").glob("*/bin/java.exe"))
    print(f"JAVA_HOME={java.parent.parent}", flush=True)


def android():
    if (TOOLS / "android-sdk/cmdline-tools/latest/bin/sdkmanager.bat").exists():
        print("Android command-line tools already prepared.", flush=True)
        return
    repository = ET.fromstring(fetch("https://dl.google.com/android/repository/repository2-3.xml"))
    package = next(p for p in repository.findall(".//{*}remotePackage") if p.attrib["path"] == "cmdline-tools;latest")
    archive = next(a for a in package.findall(".//{*}archive") if a.findtext("{*}host-os") == "windows")
    url = archive.findtext("{*}complete/{*}url")
    checksum = archive.find("{*}complete/{*}checksum")
    target = TOOLS / "android-sdk" / "cmdline-tools"
    unpack_verified("https://dl.google.com/android/repository/" + url, checksum.text, checksum.attrib.get("type", "sha1"), target, "android-cmdline.zip")
    source = (target / "cmdline-tools").resolve()
    latest = (target / "latest").resolve()
    if not source.is_relative_to(TOOLS.resolve()) or not latest.is_relative_to(TOOLS.resolve()):
        raise RuntimeError("Unexpected tools location")
    if not latest.exists():
        source.rename(latest)
    print(f"ANDROID_HOME={TOOLS / 'android-sdk'}", flush=True)


if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        for result in [pool.submit(jdk), pool.submit(android)]:
            result.result()
