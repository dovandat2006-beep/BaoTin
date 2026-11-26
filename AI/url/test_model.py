"""
Test CANINE URL Phishing Detection Model (Multi-class)
=======================================================
Interactive and batch testing. Supports PyTorch and ONNX.

Usage:
    python test_model.py
    python test_model.py --text "example-phishing-site.com"
    python test_model.py --eval
    python test_model.py --onnx
"""

import os
import sys

# Redirect model cache to M: drive (C: drive is full)
os.environ["HF_HOME"] = r"M:\BaoTin\AI\.hf_cache"
os.environ["TORCH_HOME"] = r"M:\BaoTin\AI\.torch_cache"

sys.path.insert(0, r"L:\python_libs")

import argparse
import time
import json
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from transformers import CanineForSequenceClassification, CanineTokenizer
from sklearn.metrics import (
    accuracy_score,
    precision_recall_fscore_support,
    classification_report,
    confusion_matrix,
)

try:
    import onnxruntime as ort
    ONNX_AVAILABLE = True
except ImportError:
    ONNX_AVAILABLE = False

# --- Constants ---
BASE_DIR = Path(__file__).parent
MODEL_DIR = BASE_DIR / "output" / "best_model"
ONNX_DIR = BASE_DIR / "output" / "onnx"
DATA_DIR = BASE_DIR / "data" / "data" / "splits"
MAX_LENGTH = 128
NUM_LABELS = 6

LABEL_MAP = {
    0: "benign", 1: "phishing_bank", 2: "phishing_ecommerce",
    3: "phishing_gaming", 4: "phishing_gov", 5: "phishing_other",
}
LABEL_TAG = {
    0: "[OK]", 1: "[PHISH:BANK]", 2: "[PHISH:ECOM]",
    3: "[PHISH:GAME]", 4: "[PHISH:GOV]", 5: "[PHISH:OTHER]",
}
SCENARIO_TO_LABEL = {
    "bank": 1, "ecommerce": 2, "gaming": 3, "gov": 4,
    "other": 5, "social": 5, "telecom": 5, "delivery": 5, "tax": 5,
}


def assign_multiclass_label(row):
    if row["label"] == "benign":
        return 0
    scenario = str(row.get("scenario", "other")).lower().strip()
    return SCENARIO_TO_LABEL.get(scenario, 5)


class CANINEPredictor:
    def __init__(self, model_dir: str, use_onnx: bool = False, device: str = "auto"):
        self.use_onnx = use_onnx
        if use_onnx:
            if not ONNX_AVAILABLE:
                raise ImportError("onnxruntime not installed")
            onnx_path = ONNX_DIR / "canine_url_phishing.onnx"
            if not onnx_path.exists():
                raise FileNotFoundError(f"ONNX model not found: {onnx_path}")
            self.tokenizer = CanineTokenizer.from_pretrained(str(ONNX_DIR))
            self.session = ort.InferenceSession(str(onnx_path))
            self.backend = "ONNX Runtime"
        else:
            if device == "auto":
                self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
            else:
                self.device = torch.device(device)
            self.tokenizer = CanineTokenizer.from_pretrained(str(model_dir))
            self.model = CanineForSequenceClassification.from_pretrained(str(model_dir))
            self.model.to(self.device)
            self.model.eval()
            self.backend = f"PyTorch ({self.device})"
        print(f"[BACKEND] {self.backend}")

    def predict(self, url: str):
        inputs = self.tokenizer(
            url, return_tensors="pt" if not self.use_onnx else "np",
            max_length=MAX_LENGTH, padding="max_length", truncation=True,
        )
        start = time.perf_counter()
        if self.use_onnx:
            ort_inputs = {k: inputs[k] for k in ["input_ids", "attention_mask", "token_type_ids"]}
            logits = self.session.run(["logits"], ort_inputs)[0]
            probs = self._softmax(logits[0])
        else:
            with torch.no_grad():
                out = self.model(
                    input_ids=inputs["input_ids"].to(self.device),
                    attention_mask=inputs["attention_mask"].to(self.device),
                    token_type_ids=inputs["token_type_ids"].to(self.device),
                )
                probs = torch.softmax(out.logits, dim=-1)[0].cpu().numpy()
        ms = (time.perf_counter() - start) * 1000
        label = int(np.argmax(probs))
        return {
            "label": label,
            "label_text": LABEL_MAP[label],
            "confidence": float(probs[label]),
            "probabilities": {LABEL_MAP[i]: float(probs[i]) for i in range(NUM_LABELS)},
            "inference_time_ms": ms,
        }

    @staticmethod
    def _softmax(x):
        e = np.exp(x - np.max(x))
        return e / e.sum()


def print_prediction(url: str, result: dict):
    tag = LABEL_TAG[result["label"]]
    bar = "#" * int(result["confidence"] * 20) + "-" * (20 - int(result["confidence"] * 20))
    print(f"\n{'-' * 70}")
    print(f"[URL] {url[:100]}{'...' if len(url) > 100 else ''}")
    print(f"{'-' * 70}")
    print(f"  {tag} {result['label_text']}")
    print(f"  [CONF] [{bar}] {result['confidence']*100:.1f}%")
    print(f"  Probabilities:")
    for name, prob in sorted(result["probabilities"].items(), key=lambda x: -x[1]):
        indicator = " <<" if name == result["label_text"] else ""
        print(f"    {name:>20}: {prob*100:6.2f}%{indicator}")
    print(f"  [TIME] {result['inference_time_ms']:.2f} ms")


def evaluate_test_set(predictor: CANINEPredictor):
    test_path = DATA_DIR / "url_test.csv"
    if not test_path.exists():
        print(f"[ERROR] Not found: {test_path}"); return

    print(f"\n[EVAL] Test set: {test_path}")
    df = pd.read_csv(test_path, low_memory=False)
    df.dropna(subset=["url", "label"], inplace=True)
    df["mc_label"] = df.apply(assign_multiclass_label, axis=1)
    print(f"  Samples: {len(df)}\n")

    all_preds, all_labels = [], df["mc_label"].tolist()
    total_time = 0
    for idx, row in df.iterrows():
        result = predictor.predict(row["url"])
        all_preds.append(result["label"])
        total_time += result["inference_time_ms"]
        if (idx + 1) % 1000 == 0:
            print(f"  Processed {idx + 1}/{len(df)}...")

    accuracy = accuracy_score(all_labels, all_preds)
    _, _, f1_m, _ = precision_recall_fscore_support(all_labels, all_preds, average="macro", zero_division=0)
    _, _, f1_w, _ = precision_recall_fscore_support(all_labels, all_preds, average="weighted", zero_division=0)

    print(f"\n{'=' * 60}")
    print(f"TEST RESULTS ({predictor.backend})")
    print(f"{'=' * 60}")
    print(f"  Accuracy:      {accuracy:.4f} ({accuracy*100:.2f}%)")
    print(f"  F1 (macro):    {f1_m:.4f}")
    print(f"  F1 (weighted): {f1_w:.4f}")
    print(f"  Avg time:      {total_time/len(df):.2f} ms/sample")
    print()
    report = classification_report(all_labels, all_preds, target_names=list(LABEL_MAP.values()), zero_division=0)
    print(report)


def interactive_mode(predictor: CANINEPredictor):
    print(f"\n{'=' * 60}")
    print("INTERACTIVE MODE - URL Phishing Detector (Multi-class)")
    print(f"{'=' * 60}")
    print("Enter URL to classify (type 'quit' to exit, 'demo' for examples)")
    print()

    demo_urls = [
        "google.com", "facebook-login-verify.xyz", "vietcombank.com.vn",
        "vietcombank-xacnhan.tk", "tiktokshopseller-vn.com", "vnexpress.net",
        "chinhphu2025.com", "gov.vn", "shopee-khuyenmai.xyz",
    ]

    while True:
        try:
            url = input("\n> URL: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\nGoodbye!"); break
        if not url: continue
        if url.lower() in ("quit", "q", "exit"):
            print("\nGoodbye!"); break
        if url.lower() == "demo":
            for u in demo_urls:
                result = predictor.predict(u)
                print_prediction(u, result)
            continue
        result = predictor.predict(url)
        print_prediction(url, result)


def batch_test(predictor: CANINEPredictor, file_path: str):
    path = Path(file_path)
    if not path.exists():
        print(f"[ERROR] Not found: {path}"); return
    with open(path, "r", encoding="utf-8") as f:
        urls = [l.strip() for l in f if l.strip()]
    print(f"\n[BATCH] {len(urls)} URLs from {path}")
    results = []
    for url in urls:
        r = predictor.predict(url)
        results.append(r)
        print_prediction(url, r)
    from collections import Counter
    counts = Counter(r["label_text"] for r in results)
    print(f"\n{'=' * 60}\nBATCH SUMMARY\n{'=' * 60}")
    for name, count in counts.most_common():
        print(f"  {name:>20}: {count}")
    print(f"  Avg time: {np.mean([r['inference_time_ms'] for r in results]):.2f} ms")


def main():
    parser = argparse.ArgumentParser(description="Test CANINE URL Phishing Model (Multi-class)")
    parser.add_argument("--text", type=str, default=None)
    parser.add_argument("--eval", action="store_true")
    parser.add_argument("--batch", type=str, default=None)
    parser.add_argument("--onnx", action="store_true")
    parser.add_argument("--model_dir", type=str, default=str(MODEL_DIR))
    parser.add_argument("--device", type=str, default="auto")
    args = parser.parse_args()

    if not Path(args.model_dir).exists():
        print(f"[ERROR] Model not found: {args.model_dir}"); sys.exit(1)

    print("=" * 60)
    print("CANINE URL Phishing Detector (Multi-class)")
    print("=" * 60)

    predictor = CANINEPredictor(args.model_dir, use_onnx=args.onnx, device=args.device)

    if args.text:
        print_prediction(args.text, predictor.predict(args.text))
    elif args.eval:
        evaluate_test_set(predictor)
    elif args.batch:
        batch_test(predictor, args.batch)
    else:
        interactive_mode(predictor)


if __name__ == "__main__":
    main()
