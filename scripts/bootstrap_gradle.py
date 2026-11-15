"""Install the official Gradle wrapper, verifying the JAR against Gradle's checksum."""
import hashlib
import pathlib
import urllib.request

ROOT = pathlib.Path(__file__).resolve().parents[1]
VERSION = "8.11.1"
BASE = f"https://raw.githubusercontent.com/gradle/gradle/v{VERSION}"


def fetch(url):
    with urllib.request.urlopen(url, timeout=30) as response:
        return response.read()


def main():
    destination = ROOT / "android" / "gradle" / "wrapper"
    destination.mkdir(parents=True, exist_ok=True)
    jar = fetch(f"{BASE}/gradle/wrapper/gradle-wrapper.jar")
    expected = fetch(f"https://services.gradle.org/distributions/gradle-{VERSION}-wrapper.jar.sha256").decode().strip().split()[0]
    if hashlib.sha256(jar).hexdigest() != expected:
        raise RuntimeError("Gradle wrapper checksum mismatch")
    (destination / "gradle-wrapper.jar").write_bytes(jar)
    distribution_sha = fetch(f"https://services.gradle.org/distributions/gradle-{VERSION}-bin.zip.sha256").decode().strip().split()[0]
    (destination / "gradle-wrapper.properties").write_text(
        f"distributionBase=GRADLE_USER_HOME\ndistributionPath=wrapper/dists\n"
        f"distributionUrl=https\\://services.gradle.org/distributions/gradle-{VERSION}-bin.zip\n"
        f"distributionSha256Sum={distribution_sha}\nnetworkTimeout=30000\nvalidateDistributionUrl=true\n"
        "zipStoreBase=GRADLE_USER_HOME\nzipStorePath=wrapper/dists\n", encoding="utf-8"
    )
    for filename in ("gradlew", "gradlew.bat"):
        (ROOT / "android" / filename).write_bytes(fetch(f"{BASE}/{filename}"))
    print(f"Installed verified Gradle {VERSION} wrapper.")


if __name__ == "__main__":
    main()
