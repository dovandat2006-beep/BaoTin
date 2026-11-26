"""
Download PhishVN dataset from Mendeley Data.
Dataset: PhishVN v3 - Vietnamese URL Phishing Dataset
DOI: 10.17632/b97hxbxtpd.3

This script downloads and extracts the dataset files needed for training.
"""

import os
import sys
import zipfile
import requests
from pathlib import Path

BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
DATA_DIR.mkdir(parents=True, exist_ok=True)

# Mendeley Data API endpoint for this dataset
DATASET_DOI = "b97hxbxtpd"
DATASET_VERSION = "3"
API_BASE = "https://data.mendeley.com/api/datasets"


def get_dataset_files():
    """Fetch file list from Mendeley Data API."""
    url = f"https://data.mendeley.com/public-files/datasets/{DATASET_DOI}/files"
    print(f"[INFO] Fetching file list from Mendeley API...")

    # Try the public API
    resp = requests.get(url, timeout=30)
    if resp.status_code == 200:
        return resp.json()

    # Alternative: try versioned endpoint
    url2 = f"https://data.mendeley.com/api/datasets/{DATASET_DOI}/versions/{DATASET_VERSION}/files"
    resp2 = requests.get(url2, timeout=30)
    if resp2.status_code == 200:
        return resp2.json()

    print(f"[WARN] API returned status {resp.status_code} / {resp2.status_code}")
    return None


def download_file(url, dest_path, desc=""):
    """Download a file with progress."""
    print(f"  Downloading {desc or dest_path.name}...")
    resp = requests.get(url, stream=True, timeout=120)
    resp.raise_for_status()

    total = int(resp.headers.get("content-length", 0))
    downloaded = 0

    with open(dest_path, "wb") as f:
        for chunk in resp.iter_content(chunk_size=8192):
            f.write(chunk)
            downloaded += len(chunk)
            if total > 0:
                pct = downloaded / total * 100
                print(f"\r  {downloaded / 1024 / 1024:.1f} MB / {total / 1024 / 1024:.1f} MB ({pct:.0f}%)", end="")

    print(f"\n  [OK] Saved: {dest_path}")


def try_direct_download():
    """Try to download dataset ZIP directly."""
    # Common Mendeley download patterns
    urls_to_try = [
        f"https://data.mendeley.com/datasets/{DATASET_DOI}/{DATASET_VERSION}/files/dataset_url.csv",
        f"https://prod-dcd-datasets-cache-zipfiles.s3.eu-west-1.amazonaws.com/{DATASET_DOI}-{DATASET_VERSION}.zip",
        f"https://data.mendeley.com/public-files/datasets/{DATASET_DOI}/files/dataset_url.csv/file_view",
    ]

    for url in urls_to_try:
        try:
            print(f"[TRY] {url[:80]}...")
            resp = requests.head(url, timeout=15, allow_redirects=True)
            if resp.status_code == 200:
                ext = ".zip" if "zip" in url else ".csv"
                dest = DATA_DIR / f"phishvn{ext}"
                download_file(url, dest, f"PhishVN dataset")

                if ext == ".zip":
                    print(f"[INFO] Extracting ZIP...")
                    with zipfile.ZipFile(dest, "r") as zf:
                        zf.extractall(DATA_DIR)
                    print(f"  [OK] Extracted to {DATA_DIR}")
                return True
        except Exception as e:
            print(f"  [SKIP] {e}")
            continue

    return False


def main():
    print("=" * 60)
    print("DOWNLOAD PhishVN DATASET")
    print("=" * 60)
    print(f"  Source: https://data.mendeley.com/datasets/{DATASET_DOI}/{DATASET_VERSION}")
    print(f"  Target: {DATA_DIR}")
    print()

    # Check if data already exists
    existing = list(DATA_DIR.glob("*.csv"))
    if existing:
        print(f"[INFO] Found {len(existing)} CSV files already in data/:")
        for f in existing:
            print(f"  - {f.name} ({f.stat().st_size / 1024:.0f} KB)")
        resp = input("\nRe-download? (y/N): ").strip().lower()
        if resp != "y":
            print("[SKIP] Using existing data.")
            return

    # Try API first
    files = get_dataset_files()
    if files:
        print(f"[INFO] Found {len(files)} files via API")
        for finfo in files:
            name = finfo.get("filename") or finfo.get("name", "unknown")
            dl_url = finfo.get("download_url") or finfo.get("content_details", {}).get("download_url")
            if dl_url and (name.endswith(".csv") or name.endswith(".zip")):
                dest = DATA_DIR / name
                download_file(dl_url, dest, name)
    else:
        print("[INFO] Trying direct download...")
        if not try_direct_download():
            print()
            print("[MANUAL] Automatic download failed. Please download manually:")
            print(f"  1. Go to: https://data.mendeley.com/datasets/{DATASET_DOI}/{DATASET_VERSION}")
            print(f"  2. Click 'Download All' or download individual CSV files")
            print(f"  3. Place the files in: {DATA_DIR}")
            print()
            print("  Required files:")
            print("    - dataset_url.csv (full dataset)")
            print("    - OR splits/url_train.csv, splits/url_val.csv, splits/url_test.csv")
            return

    # List what we got
    print()
    print("[DONE] Files in data/:")
    for f in sorted(DATA_DIR.rglob("*")):
        if f.is_file():
            print(f"  {f.relative_to(DATA_DIR)} ({f.stat().st_size / 1024:.0f} KB)")


if __name__ == "__main__":
    main()
