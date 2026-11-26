"""
Test PhoBERT SMS Spam Detection Model
======================================
Interactive and batch testing for the fine-tuned PhoBERT model.
Supports both PyTorch and ONNX inference.

Usage:
    # Interactive mode (type messages to classify)
    python test_model.py

    # Test with specific text
    python test_model.py --text "Bạn đã trúng thưởng 100 triệu"

    # Evaluate on test set
    python test_model.py --eval

    # Use ONNX model
    python test_model.py --onnx

    # Batch test from file
    python test_model.py --batch test_messages.txt
"""

import os
import sys

# Redirect model cache to M: drive (C: drive is full)
os.environ["HF_HOME"] = r"M:\BaoTin\AI\.hf_cache"
os.environ["TORCH_HOME"] = r"M:\BaoTin\AI\.torch_cache"

# Add custom library path (packages installed to L drive)
sys.path.insert(0, r"L:\python_libs")

import argparse
import time
import json
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from transformers import AutoTokenizer, AutoModelForSequenceClassification
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


# --- Paths ---
BASE_DIR = Path(__file__).parent
MODEL_DIR = BASE_DIR / "output" / "best_model"
ONNX_DIR = BASE_DIR / "output" / "onnx"
ONNX_MODEL_PATH = ONNX_DIR / "phobert_sms_spam.onnx"
DATA_DIR = BASE_DIR / "data"

LABEL_MAP = {0: "Hop le (Legitimate)", 1: "Spam/Lua dao (Fraud)"}
LABEL_TAG = {0: "[OK]", 1: "[SPAM]"}
MAX_LENGTH = 256


class PhoBERTPredictor:
    """Unified predictor supporting both PyTorch and ONNX backends."""

    def __init__(self, model_dir: str, use_onnx: bool = False, device: str = "auto"):
        self.use_onnx = use_onnx

        if use_onnx:
            if not ONNX_AVAILABLE:
                raise ImportError("onnxruntime is not installed. Run: pip install onnxruntime")

            onnx_path = ONNX_DIR / "phobert_sms_spam.onnx"
            if not onnx_path.exists():
                raise FileNotFoundError(
                    f"ONNX model not found at {onnx_path}\n"
                    "Run convert_to_onnx.py first."
                )

            self.tokenizer = AutoTokenizer.from_pretrained(str(ONNX_DIR))
            self.session = ort.InferenceSession(str(onnx_path))
            self.backend = "ONNX Runtime"
            print(f"[BACKEND] ONNX Runtime")
        else:
            if device == "auto":
                self.device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
            else:
                self.device = torch.device(device)

            self.tokenizer = AutoTokenizer.from_pretrained(str(model_dir))
            self.model = AutoModelForSequenceClassification.from_pretrained(str(model_dir))
            self.model.to(self.device)
            self.model.eval()
            self.backend = f"PyTorch ({self.device})"
            print(f"[BACKEND] PyTorch ({self.device})")

    def predict(self, text: str):
        """
        Predict spam/legitimate for a single message.

        Returns:
            dict: {
                'label': int (0 or 1),
                'label_text': str,
                'confidence': float,
                'probabilities': {'legitimate': float, 'spam': float},
                'inference_time_ms': float
            }
        """
        inputs = self.tokenizer(
            text,
            return_tensors="pt" if not self.use_onnx else "np",
            max_length=MAX_LENGTH,
            padding="max_length",
            truncation=True,
        )

        start_time = time.perf_counter()

        if self.use_onnx:
            ort_inputs = {
                "input_ids": inputs["input_ids"],
                "attention_mask": inputs["attention_mask"],
            }
            logits = self.session.run(["logits"], ort_inputs)[0]
            probs = self._softmax(logits[0])
        else:
            with torch.no_grad():
                input_ids = inputs["input_ids"].to(self.device)
                attention_mask = inputs["attention_mask"].to(self.device)
                outputs = self.model(input_ids=input_ids, attention_mask=attention_mask)
                probs = torch.softmax(outputs.logits, dim=-1)[0].cpu().numpy()

        inference_time = (time.perf_counter() - start_time) * 1000

        label = int(np.argmax(probs))
        confidence = float(probs[label])

        return {
            "label": label,
            "label_text": LABEL_MAP[label],
            "confidence": confidence,
            "probabilities": {
                "legitimate": float(probs[0]),
                "spam": float(probs[1]),
            },
            "inference_time_ms": inference_time,
        }

    def predict_batch(self, texts: list):
        """Predict for a batch of messages."""
        results = []
        for text in texts:
            results.append(self.predict(text))
        return results

    @staticmethod
    def _softmax(x):
        exp_x = np.exp(x - np.max(x))
        return exp_x / exp_x.sum()


def print_prediction(text: str, result: dict):
    """Pretty-print a single prediction."""
    tag = LABEL_TAG[result["label"]]
    confidence_bar = "#" * int(result["confidence"] * 20) + "-" * (20 - int(result["confidence"] * 20))

    print(f"\n{'-' * 70}")
    print(f"[MSG] {text[:100]}{'...' if len(text) > 100 else ''}")
    print(f"{'-' * 70}")
    print(f"  {tag} Result: {result['label_text']}")
    print(f"  [CONF] [{confidence_bar}] {result['confidence']*100:.1f}%")
    print(f"     Hop le:      {result['probabilities']['legitimate']*100:.2f}%")
    print(f"     Spam:        {result['probabilities']['spam']*100:.2f}%")
    print(f"  [TIME] Inference: {result['inference_time_ms']:.2f} ms")


def evaluate_test_set(predictor: PhoBERTPredictor):
    """Evaluate the model on the official test set."""
    test_path = DATA_DIR / "test.csv"
    if not test_path.exists():
        print(f"[ERROR] Test file not found: {test_path}")
        return

    print(f"\n[EVAL] Evaluating on test set: {test_path}")
    df = pd.read_csv(test_path)
    df = df.dropna(subset=["message", "label"]).reset_index(drop=True)

    print(f"  Total samples: {len(df)}")
    print(f"  Label distribution: {dict(df['label'].value_counts())}")
    print(f"  Processing...\n")

    all_preds = []
    all_labels = df["label"].tolist()
    total_time = 0

    for idx, row in df.iterrows():
        result = predictor.predict(row["message"])
        all_preds.append(result["label"])
        total_time += result["inference_time_ms"]

        if (idx + 1) % 100 == 0:
            print(f"  Processed {idx + 1}/{len(df)} samples...")

    # Metrics
    accuracy = accuracy_score(all_labels, all_preds)
    precision, recall, f1, _ = precision_recall_fscore_support(
        all_labels, all_preds, average="binary", zero_division=0
    )

    print(f"\n{'=' * 60}")
    print(f"TEST SET RESULTS ({predictor.backend})")
    print(f"{'=' * 60}")
    print(f"  Accuracy:  {accuracy:.4f} ({accuracy*100:.2f}%)")
    print(f"  Precision: {precision:.4f}")
    print(f"  Recall:    {recall:.4f}")
    print(f"  F1-Score:  {f1:.4f}")
    print(f"  Avg inference time: {total_time/len(df):.2f} ms/sample")
    print(f"  Total time: {total_time/1000:.1f}s")
    print()

    # Classification report
    report = classification_report(
        all_labels,
        all_preds,
        target_names=["Hop le", "Spam/Lua dao"],
    )
    print("Classification Report:")
    print(report)

    # Confusion matrix
    cm = confusion_matrix(all_labels, all_preds)
    print("Confusion Matrix:")
    print(f"  {'':>18} {'Pred: Hop le':>15} {'Pred: Spam':>15}")
    print(f"  {'True: Hop le':>18} {cm[0][0]:>15} {cm[0][1]:>15}")
    print(f"  {'True: Spam':>18} {cm[1][0]:>15} {cm[1][1]:>15}")

    # Find misclassified examples
    print(f"\n[ERRORS] Misclassified examples (up to 10):")
    errors = [(i, all_labels[i], all_preds[i]) for i in range(len(all_labels)) if all_labels[i] != all_preds[i]]
    for i, (idx, true_label, pred_label) in enumerate(errors[:10]):
        msg = df.iloc[idx]["message"][:80]
        print(f"  [{i+1}] True: {LABEL_MAP[true_label]:>25} | Pred: {LABEL_MAP[pred_label]:>25}")
        print(f"      {msg}...")


def interactive_mode(predictor: PhoBERTPredictor):
    """Interactive testing mode - type messages to classify."""
    print(f"\n{'=' * 60}")
    print("INTERACTIVE MODE - Vietnamese SMS Spam Detector")
    print(f"{'=' * 60}")
    print("Nhập tin nhắn SMS để kiểm tra (gõ 'quit' hoặc 'q' để thoát)")
    print("Gõ 'demo' để xem ví dụ")
    print()

    demo_messages = [
        "Ma OTP xac thuc GD la 066595, hieu luc 5 phut. KHONG chia se ma nay voi bat ky ai.",
        "Chúc mừng bạn đã trúng thưởng 500 triệu VNĐ! Truy cập link để nhận ngay: http://example.com",
        "Anh ơi chiều nay anh có rảnh không? Em muốn gặp anh bàn chuyện công việc",
        "KHUYẾN MÃI SỐC: Vay tiền online lãi suất 0%, giải ngân trong 5 phút. LH: 09xx xxx xxx",
        "Cam on Quy khach da su dung dich vu. So du TK: [MONEY]. Chi tiet tai app.",
        "Cảnh báo: Tài khoản của bạn bị khóa. Đăng nhập ngay tại link dưới để mở khóa",
    ]

    while True:
        try:
            text = input("\n> Nhap tin nhan: ").strip()
        except (EOFError, KeyboardInterrupt):
            print("\n\nGoodbye!")
            break

        if not text:
            continue
        if text.lower() in ("quit", "q", "exit"):
            print("\nGoodbye!")
            break
        if text.lower() == "demo":
            print("\n[DEMO] Running demo with sample messages:\n")
            for msg in demo_messages:
                result = predictor.predict(msg)
                print_prediction(msg, result)
            continue

        result = predictor.predict(text)
        print_prediction(text, result)


def batch_test(predictor: PhoBERTPredictor, file_path: str):
    """Test messages from a file (one per line)."""
    path = Path(file_path)
    if not path.exists():
        print(f"[ERROR] File not found: {path}")
        return

    with open(path, "r", encoding="utf-8") as f:
        messages = [line.strip() for line in f if line.strip()]

    print(f"\n[BATCH] Testing {len(messages)} messages from {path}")
    print()

    results = []
    for msg in messages:
        result = predictor.predict(msg)
        results.append(result)
        print_prediction(msg, result)

    # Summary
    spam_count = sum(1 for r in results if r["label"] == 1)
    legit_count = sum(1 for r in results if r["label"] == 0)
    avg_time = np.mean([r["inference_time_ms"] for r in results])

    print(f"\n{'=' * 60}")
    print(f"BATCH SUMMARY")
    print(f"{'=' * 60}")
    print(f"  Total messages: {len(results)}")
    print(f"  Hop le: {legit_count}")
    print(f"  Spam:   {spam_count}")
    print(f"  Avg inference: {avg_time:.2f} ms/sample")


def main():
    parser = argparse.ArgumentParser(description="Test PhoBERT SMS Spam Detection Model")
    parser.add_argument("--text", type=str, default=None, help="Single text to classify")
    parser.add_argument("--eval", action="store_true", help="Evaluate on test set")
    parser.add_argument("--batch", type=str, default=None, help="File with messages (one per line)")
    parser.add_argument("--onnx", action="store_true", help="Use ONNX model instead of PyTorch")
    parser.add_argument("--model_dir", type=str, default=str(MODEL_DIR), help="Path to model directory")
    parser.add_argument("--device", type=str, default="auto", help="Device: auto, cpu, cuda")
    args = parser.parse_args()

    model_dir = Path(args.model_dir)
    if not model_dir.exists():
        print(f"[ERROR] Model not found at {model_dir}")
        print("   Please run train.py first to fine-tune the model.")
        sys.exit(1)

    print("=" * 60)
    print("PhoBERT Vietnamese SMS Spam Detector")
    print("=" * 60)

    # Initialize predictor
    predictor = PhoBERTPredictor(
        model_dir=str(model_dir),
        use_onnx=args.onnx,
        device=args.device,
    )

    if args.text:
        # Single text prediction
        result = predictor.predict(args.text)
        print_prediction(args.text, result)

    elif args.eval:
        # Evaluate on test set
        evaluate_test_set(predictor)

    elif args.batch:
        # Batch test from file
        batch_test(predictor, args.batch)

    else:
        # Interactive mode
        interactive_mode(predictor)


if __name__ == "__main__":
    main()
