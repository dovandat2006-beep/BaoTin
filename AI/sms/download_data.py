"""
Download Vietnamese SMS Spam Dataset
======================================
Source: https://github.com/trannguyenthaituan251209/vietnamese_sms_dataset

This dataset contains Vietnamese SMS messages labeled for spam detection.
- Binary classification: 0 = Legitimate, 1 = Spam/Fraud
- Columns: message, label
- Pre-split: train.csv, test.csv

Usage:
    python download_data.py
"""

import os
import sys
import zipfile
import shutil
from pathlib import Path

try:
    import requests
except ImportError:
    print("[ERROR] 'requests' not installed. Run: pip install requests")
    sys.exit(1)

BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"

# GitHub raw URLs for the dataset
GITHUB_REPO = "trannguyenthaituan251209/vietnamese_sms_dataset"
BRANCH = "main"
BASE_URL = f"https://raw.githubusercontent.com/{GITHUB_REPO}/{BRANCH}"

FILES_TO_DOWNLOAD = [
    "train.csv",
    "test.csv",
    "full_dataset.csv",
    "README.md",
    "QUICKSTART_GUIDE.md",
]


def download_file(url, dest_path):
    """Download a file with progress."""
    print(f"  Downloading {dest_path.name}...")
    resp = requests.get(url, stream=True, timeout=60)
    resp.raise_for_status()

    total = int(resp.headers.get("content-length", 0))
    downloaded = 0

    with open(dest_path, "wb") as f:
        for chunk in resp.iter_content(chunk_size=8192):
            f.write(chunk)
            downloaded += len(chunk)
            if total > 0:
                pct = downloaded / total * 100
                print(f"\r  {downloaded / 1024:.0f} KB / {total / 1024:.0f} KB ({pct:.0f}%)", end="")

    if total > 0:
        print()
    print(f"  [OK] Saved: {dest_path.name} ({dest_path.stat().st_size / 1024:.0f} KB)")


def try_github_download():
    """Download individual files from GitHub."""
    success = 0
    for filename in FILES_TO_DOWNLOAD:
        url = f"{BASE_URL}/{filename}"
        dest = DATA_DIR / filename
        try:
            download_file(url, dest)
            success += 1
        except Exception as e:
            print(f"  [SKIP] {filename}: {e}")
    return success > 0


def try_zip_download():
    """Download entire repo as ZIP."""
    zip_url = f"https://github.com/{GITHUB_REPO}/archive/refs/heads/{BRANCH}.zip"
    zip_path = DATA_DIR / "repo.zip"

    print(f"  Downloading repo ZIP...")
    try:
        resp = requests.get(zip_url, stream=True, timeout=120)
        resp.raise_for_status()
        with open(zip_path, "wb") as f:
            for chunk in resp.iter_content(8192):
                f.write(chunk)

        print(f"  Extracting...")
        with zipfile.ZipFile(zip_path, "r") as zf:
            zf.extractall(DATA_DIR)

        # Move files from extracted folder to data/
        extracted_dir = DATA_DIR / f"vietnamese_sms_dataset-{BRANCH}"
        if extracted_dir.exists():
            for item in extracted_dir.iterdir():
                dest = DATA_DIR / item.name
                if not dest.exists():
                    shutil.move(str(item), str(dest))
            shutil.rmtree(extracted_dir, ignore_errors=True)

        zip_path.unlink(missing_ok=True)
        return True
    except Exception as e:
        print(f"  [ERROR] ZIP download failed: {e}")
        zip_path.unlink(missing_ok=True)
        return False


def verify_data():
    """Verify that required files exist."""
    required = ["train.csv", "test.csv"]
    missing = [f for f in required if not (DATA_DIR / f).exists()]
    return len(missing) == 0, missing


def main():
    print("=" * 60)
    print("DOWNLOAD VIETNAMESE SMS SPAM DATASET")
    print("=" * 60)
    print(f"  Source: https://github.com/{GITHUB_REPO}")
    print(f"  Target: {DATA_DIR}")
    print()

    DATA_DIR.mkdir(parents=True, exist_ok=True)

    # Check if data already exists
    ok, missing = verify_data()
    if ok:
        print("[INFO] Dataset already exists:")
        for f in sorted(DATA_DIR.glob("*")):
            if f.is_file():
                print(f"  - {f.name} ({f.stat().st_size / 1024:.0f} KB)")

        resp = input("\nRe-download? (y/N): ").strip().lower()
        if resp != "y":
            print("[SKIP] Using existing data.")
            return

    # Try GitHub raw download first
    print("[1/2] Trying GitHub raw download...")
    if try_github_download():
        ok, missing = verify_data()
        if ok:
            print("\n[OK] Download complete!")
            return

    # Fallback: ZIP download
    print("\n[2/2] Trying ZIP download...")
    if try_zip_download():
        ok, missing = verify_data()
        if ok:
            print("\n[OK] Download complete!")
            return

    # Manual instructions
    if not ok:
        print(f"\n[MANUAL] Automatic download failed.")
        print(f"  Please download manually:")
        print(f"  1. Go to: https://github.com/{GITHUB_REPO}")
        print(f"  2. Download train.csv and test.csv")
        print(f"  3. Place them in: {DATA_DIR}")
        print(f"\n  Missing files: {', '.join(missing)}")
        return

    # Summary
    print(f"\n{'=' * 60}")
    print("[DONE] Files in data/:")
    for f in sorted(DATA_DIR.glob("*")):
        if f.is_file():
            print(f"  {f.name} ({f.stat().st_size / 1024:.0f} KB)")
    print(f"{'=' * 60}")


if __name__ == "__main__":
    main()
