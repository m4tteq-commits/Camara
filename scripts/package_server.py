"""Create a source deployment bundle with an explicit allowlist (no secrets or local data)."""
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    destination = ROOT / "releases" / "camara-server-0.9.8.zip"
    destination.parent.mkdir(parents=True, exist_ok=True)
    files = [ROOT / "compose.yaml", ROOT / ".env.example", ROOT / "backend/Dockerfile",
             ROOT / "backend/.dockerignore", ROOT / "backend/requirements.txt", ROOT / "backend/requirements-lock.txt",
             ROOT / "scripts/fetch_local_ai.py", ROOT / "scripts/export_zima_backup.py"]
    files.extend(sorted((ROOT / "backend/app").glob("*.py")))
    with zipfile.ZipFile(destination, "w", zipfile.ZIP_DEFLATED) as bundle:
        for path in files:
            bundle.write(path, path.relative_to(ROOT).as_posix())
        bundle.write(ROOT / "docs/ZIMAOS.md", "README.md")
        bundle.write(ROOT / "docs/FEATURES-0.3.md", "docs/FEATURES-0.3.md")
        bundle.write(ROOT / "docs/FEATURES-0.4.md", "docs/FEATURES-0.4.md")
        bundle.write(ROOT / "docs/FEATURES-0.5.md", "docs/FEATURES-0.5.md")
        bundle.write(ROOT / "docs/AI-MEMORY.md", "docs/AI-MEMORY.md")
        bundle.write(ROOT / "scripts/start_zima.sh", "start_zima.sh")
    print(destination)


if __name__ == "__main__":
    main()
