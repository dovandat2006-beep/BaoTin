"""
Convert Best CANINE Model to ONNX Format
==========================================
Converts the fine-tuned CANINE model to ONNX format for
efficient inference and deployment.

Usage:
    python convert_to_onnx.py
    python convert_to_onnx.py --optimize --benchmark
"""

import os
import sys

# Redirect model cache to M: drive (C: drive is full)
os.environ["HF_HOME"] = r"M:\BaoTin\AI\.hf_cache"
os.environ["TORCH_HOME"] = r"M:\BaoTin\AI\.torch_cache"

sys.path.insert(0, r"L:\python_libs")

import argparse
import time
from pathlib import Path

import numpy as np
import torch
import onnx
import onnxruntime as ort
from transformers import CanineForSequenceClassification, CanineTokenizer

# --- Paths ---
BASE_DIR = Path(__file__).parent
DEFAULT_MODEL_DIR = BASE_DIR / "output" / "best_model"
DEFAULT_OUTPUT_DIR = BASE_DIR / "output" / "onnx"
MAX_LENGTH = 128
LABEL_MAP = {
    0: "benign", 1: "phishing_bank", 2: "phishing_ecommerce",
    3: "phishing_gaming", 4: "phishing_gov", 5: "phishing_other",
}


def convert_to_onnx(model_dir: Path, output_dir: Path, opset_version: int = 14):
    output_dir.mkdir(parents=True, exist_ok=True)
    onnx_path = output_dir / "canine_url_phishing.onnx"

    print("=" * 60)
    print("CONVERTING CANINE TO ONNX")
    print("=" * 60)

    print(f"\n[LOAD] Loading model from: {model_dir}")
    tokenizer = CanineTokenizer.from_pretrained(model_dir)
    model = CanineForSequenceClassification.from_pretrained(model_dir)
    model.eval()

    tokenizer.save_pretrained(output_dir)
    print("  [OK] Tokenizer saved to ONNX directory")

    # Dummy input
    dummy_text = "example-phishing-site.com"
    inputs = tokenizer(dummy_text, return_tensors="pt", max_length=MAX_LENGTH, padding="max_length", truncation=True)

    input_ids = inputs["input_ids"]
    attention_mask = inputs["attention_mask"]
    token_type_ids = inputs["token_type_ids"]

    print(f"\n[EXPORT] Exporting to ONNX (opset={opset_version})...")
    print("  Note: CANINE uses static shapes (batch=1, seq=128) due to")
    print("  its downsampling architecture not supporting dynamic axes.")
    start_time = time.time()

    # CANINE's internal downsampling/upsampling breaks with dynamic axes.
    # Use static shapes and force legacy exporter to avoid torch.export issues.
    torch.onnx.export(
        model,
        (input_ids, attention_mask, token_type_ids),
        str(onnx_path),
        export_params=True,
        opset_version=opset_version,
        do_constant_folding=True,
        input_names=["input_ids", "attention_mask", "token_type_ids"],
        output_names=["logits"],
        dynamo=False,  # Force legacy exporter (avoid torch.export crash)
    )

    export_time = time.time() - start_time
    print(f"  [OK] Export complete in {export_time:.1f}s")

    print("\n[VERIFY] Verifying ONNX model...")
    onnx_model = onnx.load(str(onnx_path))
    onnx.checker.check_model(onnx_model)
    print("  [OK] ONNX model is valid")

    pytorch_size = sum(f.stat().st_size for f in model_dir.rglob("*") if f.is_file()) / (1024**2)
    onnx_size = onnx_path.stat().st_size / (1024**2)
    print(f"\n[SIZE] Model Size Comparison:")
    print(f"  PyTorch model: {pytorch_size:.1f} MB")
    print(f"  ONNX model:    {onnx_size:.1f} MB")
    print(f"  Reduction:     {(1 - onnx_size/pytorch_size)*100:.1f}%")

    return onnx_path


def optimize_onnx(onnx_path: Path, output_dir: Path):
    optimized_path = output_dir / "canine_url_phishing_optimized.onnx"
    print(f"\n[OPT] Optimizing ONNX model...")

    sess_options = ort.SessionOptions()
    sess_options.optimized_model_filepath = str(optimized_path)
    sess_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL
    ort.InferenceSession(str(onnx_path), sess_options)

    if optimized_path.exists():
        opt_size = optimized_path.stat().st_size / (1024**2)
        orig_size = onnx_path.stat().st_size / (1024**2)
        print(f"  [OK] Optimized model saved: {opt_size:.1f} MB (was {orig_size:.1f} MB)")
        return optimized_path
    else:
        print("  [WARN] Optimization didn't produce a separate file")
        return onnx_path


def verify_outputs(model_dir: Path, onnx_path: Path):
    print("\n[VERIFY] Verifying outputs match PyTorch model...")

    tokenizer = CanineTokenizer.from_pretrained(model_dir)
    pt_model = CanineForSequenceClassification.from_pretrained(model_dir)
    pt_model.eval()

    test_urls = [
        "facebook-login-verify.xyz",
        "google.com",
        "vietcombank-xacnhan.tk",
        "vnexpress.net",
    ]

    ort_session = ort.InferenceSession(str(onnx_path))
    all_match = True

    for url in test_urls:
        inputs = tokenizer(url, return_tensors="pt", max_length=MAX_LENGTH, padding="max_length", truncation=True)

        with torch.no_grad():
            pt_outputs = pt_model(**inputs)
            pt_logits = pt_outputs.logits.numpy()

        ort_inputs = {
            "input_ids": inputs["input_ids"].numpy(),
            "attention_mask": inputs["attention_mask"].numpy(),
            "token_type_ids": inputs["token_type_ids"].numpy(),
        }
        ort_logits = ort_session.run(["logits"], ort_inputs)[0]

        max_diff = np.max(np.abs(pt_logits - ort_logits))
        pt_pred = np.argmax(pt_logits, axis=-1)[0]
        ort_pred = np.argmax(ort_logits, axis=-1)[0]
        match = pt_pred == ort_pred

        if not match:
            all_match = False

        label = LABEL_MAP.get(int(ort_pred), "unknown")
        status = "[OK]" if match else "[FAIL]"
        print(f"  {status} [{label:>20}] max_diff={max_diff:.6f} | {url}")

    if all_match:
        print("\n  [OK] All predictions match between PyTorch and ONNX!")
    else:
        print("\n  [WARN] Some predictions differ - check model conversion")

    return all_match


def benchmark_speed(model_dir: Path, onnx_path: Path, n_runs: int = 100):
    print(f"\n[BENCH] Benchmarking inference speed ({n_runs} runs)...")

    tokenizer = CanineTokenizer.from_pretrained(model_dir)
    test_url = "facebook-login-verify-account.xyz"
    inputs = tokenizer(test_url, return_tensors="pt", max_length=MAX_LENGTH, padding="max_length", truncation=True)

    pt_model = CanineForSequenceClassification.from_pretrained(model_dir)
    pt_model.eval()

    for _ in range(5):
        with torch.no_grad():
            pt_model(**inputs)

    pt_times = []
    for _ in range(n_runs):
        start = time.perf_counter()
        with torch.no_grad():
            pt_model(**inputs)
        pt_times.append(time.perf_counter() - start)

    ort_session = ort.InferenceSession(str(onnx_path))
    ort_inputs = {
        "input_ids": inputs["input_ids"].numpy(),
        "attention_mask": inputs["attention_mask"].numpy(),
        "token_type_ids": inputs["token_type_ids"].numpy(),
    }

    for _ in range(5):
        ort_session.run(["logits"], ort_inputs)

    ort_times = []
    for _ in range(n_runs):
        start = time.perf_counter()
        ort_session.run(["logits"], ort_inputs)
        ort_times.append(time.perf_counter() - start)

    pt_avg = np.mean(pt_times) * 1000
    ort_avg = np.mean(ort_times) * 1000
    speedup = pt_avg / ort_avg

    print(f"  PyTorch (CPU): {pt_avg:.2f} ms/sample")
    print(f"  ONNX (CPU):    {ort_avg:.2f} ms/sample")
    print(f"  Speedup:       {speedup:.2f}x")


def main():
    parser = argparse.ArgumentParser(description="Convert CANINE to ONNX")
    parser.add_argument("--model_dir", type=str, default=str(DEFAULT_MODEL_DIR))
    parser.add_argument("--output_dir", type=str, default=str(DEFAULT_OUTPUT_DIR))
    parser.add_argument("--opset", type=int, default=14)
    parser.add_argument("--optimize", action="store_true")
    parser.add_argument("--benchmark", action="store_true")
    parser.add_argument("--no_verify", action="store_true")
    args = parser.parse_args()

    model_dir = Path(args.model_dir)
    output_dir = Path(args.output_dir)

    if not model_dir.exists():
        print(f"[ERROR] Model directory not found: {model_dir}")
        print("   Please run train.py first.")
        sys.exit(1)

    onnx_path = convert_to_onnx(model_dir, output_dir, args.opset)

    if args.optimize:
        onnx_path = optimize_onnx(onnx_path, output_dir)

    if not args.no_verify:
        verify_outputs(model_dir, onnx_path)

    if args.benchmark:
        benchmark_speed(model_dir, onnx_path)

    print(f"\n{'=' * 60}")
    print("[DONE] ONNX CONVERSION COMPLETE")
    print(f"   Model saved to: {onnx_path}")
    print(f"{'=' * 60}")


if __name__ == "__main__":
    main()
