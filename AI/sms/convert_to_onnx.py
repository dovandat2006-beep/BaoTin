"""
Convert Best PhoBERT Model to ONNX Format
==========================================
Converts the fine-tuned PhoBERT model to ONNX format for efficient
inference and deployment.

Features:
- Dynamic axes for variable-length input
- ONNX model optimization (graph optimization)
- Output verification against PyTorch model
- Model size comparison

Usage:
    python convert_to_onnx.py
    python convert_to_onnx.py --model_dir output/best_model --output_dir output/onnx
    python convert_to_onnx.py --opset 14 --optimize
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
from pathlib import Path

import numpy as np
import torch
import onnx
import onnxruntime as ort
from transformers import AutoTokenizer, AutoModelForSequenceClassification


# --- Paths ---
BASE_DIR = Path(__file__).parent
DEFAULT_MODEL_DIR = BASE_DIR / "output" / "best_model"
DEFAULT_OUTPUT_DIR = BASE_DIR / "output" / "onnx"


def convert_to_onnx(model_dir: Path, output_dir: Path, opset_version: int = 14):
    """
    Convert PyTorch PhoBERT model to ONNX format.

    Args:
        model_dir: Path to the fine-tuned model directory
        output_dir: Path to save the ONNX model
        opset_version: ONNX opset version
    """
    output_dir.mkdir(parents=True, exist_ok=True)
    onnx_path = output_dir / "phobert_sms_spam.onnx"

    print("=" * 60)
    print("CONVERTING PhoBERT TO ONNX")
    print("=" * 60)

    # ── Load Model & Tokenizer ───────────────────────────────────
    print(f"\n[LOAD] Loading model from: {model_dir}")
    tokenizer = AutoTokenizer.from_pretrained(model_dir)
    model = AutoModelForSequenceClassification.from_pretrained(model_dir)
    model.eval()

    # Save tokenizer alongside ONNX model
    tokenizer.save_pretrained(output_dir)
    print("  [OK] Tokenizer saved to ONNX directory")

    # ── Create Dummy Input ───────────────────────────────────────
    dummy_text = "Xin chào, đây là tin nhắn test"
    inputs = tokenizer(
        dummy_text,
        return_tensors="pt",
        max_length=256,
        padding="max_length",
        truncation=True,
    )

    input_ids = inputs["input_ids"]
    attention_mask = inputs["attention_mask"]

    # ── Export to ONNX ───────────────────────────────────────────
    print(f"\n[EXPORT] Exporting to ONNX (opset={opset_version})...")
    start_time = time.time()

    torch.onnx.export(
        model,
        (input_ids, attention_mask),
        str(onnx_path),
        export_params=True,
        opset_version=opset_version,
        do_constant_folding=True,
        input_names=["input_ids", "attention_mask"],
        output_names=["logits"],
        dynamic_axes={
            "input_ids": {0: "batch_size", 1: "sequence_length"},
            "attention_mask": {0: "batch_size", 1: "sequence_length"},
            "logits": {0: "batch_size"},
        },
    )

    export_time = time.time() - start_time
    print(f"  [OK] Export complete in {export_time:.1f}s")

    # ── Verify ONNX Model ────────────────────────────────────────
    print("\n[VERIFY] Verifying ONNX model...")
    onnx_model = onnx.load(str(onnx_path))
    onnx.checker.check_model(onnx_model)
    print("  [OK] ONNX model is valid")

    # ── Model Size ───────────────────────────────────────────────
    pytorch_size = sum(
        f.stat().st_size for f in model_dir.rglob("*") if f.is_file()
    ) / (1024**2)
    onnx_size = onnx_path.stat().st_size / (1024**2)

    print(f"\n[SIZE] Model Size Comparison:")
    print(f"  PyTorch model: {pytorch_size:.1f} MB")
    print(f"  ONNX model:    {onnx_size:.1f} MB")
    print(f"  Reduction:     {(1 - onnx_size/pytorch_size)*100:.1f}%")

    return onnx_path


def optimize_onnx(onnx_path: Path, output_dir: Path):
    """
    Optimize ONNX model with graph optimizations.
    """
    optimized_path = output_dir / "phobert_sms_spam_optimized.onnx"

    print(f"\n[OPT] Optimizing ONNX model...")

    # Use ONNX Runtime's graph optimization
    sess_options = ort.SessionOptions()
    sess_options.optimized_model_filepath = str(optimized_path)
    sess_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_ENABLE_ALL

    # Create session to trigger optimization
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
    """
    Verify ONNX model outputs match PyTorch model outputs.
    """
    print("\n[VERIFY] Verifying outputs match PyTorch model...")

    # Load PyTorch model
    tokenizer = AutoTokenizer.from_pretrained(model_dir)
    pt_model = AutoModelForSequenceClassification.from_pretrained(model_dir)
    pt_model.eval()

    # Test sentences
    test_texts = [
        "Bạn đã trúng thưởng 100 triệu đồng, nhấn vào link để nhận thưởng",
        "Ma OTP xac thuc GD la 066595, hieu luc 5 phut",
        "Chào anh, anh có khỏe không?",
        "Cơ hội đầu tư siêu lợi nhuận, liên hệ ngay số hotline",
    ]

    ort_session = ort.InferenceSession(str(onnx_path))

    all_match = True
    for i, text in enumerate(test_texts):
        inputs = tokenizer(
            text,
            return_tensors="pt",
            max_length=256,
            padding="max_length",
            truncation=True,
        )

        # PyTorch inference
        with torch.no_grad():
            pt_outputs = pt_model(**inputs)
            pt_logits = pt_outputs.logits.numpy()

        # ONNX inference
        ort_inputs = {
            "input_ids": inputs["input_ids"].numpy(),
            "attention_mask": inputs["attention_mask"].numpy(),
        }
        ort_logits = ort_session.run(["logits"], ort_inputs)[0]

        # Compare
        max_diff = np.max(np.abs(pt_logits - ort_logits))
        pt_pred = np.argmax(pt_logits, axis=-1)[0]
        ort_pred = np.argmax(ort_logits, axis=-1)[0]
        match = pt_pred == ort_pred

        if not match:
            all_match = False

        label = "Spam" if ort_pred == 1 else "Hop le"
        status = "[OK]" if match else "[FAIL]"
        print(f"  {status} [{label:>7}] max_diff={max_diff:.6f} | {text[:60]}...")

    if all_match:
        print("\n  [OK] All predictions match between PyTorch and ONNX!")
    else:
        print("\n  [WARN] Some predictions differ - check model conversion")

    return all_match


def benchmark_speed(model_dir: Path, onnx_path: Path, n_runs: int = 100):
    """
    Benchmark inference speed: PyTorch vs ONNX.
    """
    print(f"\n[BENCH] Benchmarking inference speed ({n_runs} runs)...")

    tokenizer = AutoTokenizer.from_pretrained(model_dir)
    test_text = "Bạn đã trúng thưởng 100 triệu đồng, nhấn vào link để nhận thưởng ngay"
    inputs = tokenizer(
        test_text,
        return_tensors="pt",
        max_length=256,
        padding="max_length",
        truncation=True,
    )

    # PyTorch benchmark
    pt_model = AutoModelForSequenceClassification.from_pretrained(model_dir)
    pt_model.eval()

    # Warmup
    for _ in range(5):
        with torch.no_grad():
            pt_model(**inputs)

    pt_times = []
    for _ in range(n_runs):
        start = time.perf_counter()
        with torch.no_grad():
            pt_model(**inputs)
        pt_times.append(time.perf_counter() - start)

    # ONNX benchmark
    ort_session = ort.InferenceSession(str(onnx_path))
    ort_inputs = {
        "input_ids": inputs["input_ids"].numpy(),
        "attention_mask": inputs["attention_mask"].numpy(),
    }

    # Warmup
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
    parser = argparse.ArgumentParser(description="Convert PhoBERT to ONNX")
    parser.add_argument("--model_dir", type=str, default=str(DEFAULT_MODEL_DIR), help="Path to fine-tuned model")
    parser.add_argument("--output_dir", type=str, default=str(DEFAULT_OUTPUT_DIR), help="Path to save ONNX model")
    parser.add_argument("--opset", type=int, default=14, help="ONNX opset version (default: 14)")
    parser.add_argument("--optimize", action="store_true", help="Apply ONNX graph optimizations")
    parser.add_argument("--benchmark", action="store_true", help="Run inference speed benchmark")
    parser.add_argument("--no_verify", action="store_true", help="Skip output verification")
    args = parser.parse_args()

    model_dir = Path(args.model_dir)
    output_dir = Path(args.output_dir)

    if not model_dir.exists():
        print(f"[ERROR] Model directory not found: {model_dir}")
        print("   Please run train.py first to fine-tune the model.")
        sys.exit(1)

    # Convert
    onnx_path = convert_to_onnx(model_dir, output_dir, args.opset)

    # Optimize
    if args.optimize:
        onnx_path = optimize_onnx(onnx_path, output_dir)

    # Verify
    if not args.no_verify:
        verify_outputs(model_dir, onnx_path)

    # Benchmark
    if args.benchmark:
        benchmark_speed(model_dir, onnx_path)

    print(f"\n{'=' * 60}")
    print(f"[DONE] ONNX CONVERSION COMPLETE")
    print(f"   Model saved to: {onnx_path}")
    print(f"{'=' * 60}")


if __name__ == "__main__":
    main()
