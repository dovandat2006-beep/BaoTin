"""
Evaluate CANINE URL Phishing Detection Model (Multi-class, Post-Training)
==========================================================================
Loads the best saved model and generates evaluation outputs
without retraining.

Usage:
    python eval.py
    python eval.py --model_dir output/best_model
"""

import os
import sys

# Redirect model cache to M: drive (C: drive is full)
os.environ["HF_HOME"] = r"M:\BaoTin\AI\.hf_cache"
os.environ["TORCH_HOME"] = r"M:\BaoTin\AI\.torch_cache"

sys.path.insert(0, r"L:\python_libs")

import json
import argparse
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from torch.amp import autocast
from transformers import CanineForSequenceClassification, CanineTokenizer
from sklearn.metrics import (
    accuracy_score,
    precision_recall_fscore_support,
    classification_report,
    confusion_matrix,
)

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import seaborn as sns

# --- Constants ---
MODEL_NAME = "google/canine-c"
MAX_LENGTH = 128
NUM_LABELS = 6
LABEL_MAP = {
    0: "benign", 1: "phishing_bank", 2: "phishing_ecommerce",
    3: "phishing_gaming", 4: "phishing_gov", 5: "phishing_other",
}
LABEL_NAMES = list(LABEL_MAP.values())
SCENARIO_TO_LABEL = {
    "bank": 1, "ecommerce": 2, "gaming": 3, "gov": 4,
    "other": 5, "social": 5, "telecom": 5, "delivery": 5, "tax": 5,
}

# --- Paths ---
BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data" / "data" / "splits"
OUTPUT_DIR = BASE_DIR / "output"
DEFAULT_MODEL_DIR = OUTPUT_DIR / "best_model"
DEFAULT_RESULTS_FILE = OUTPUT_DIR / "training_results.json"
PLOTS_DIR = OUTPUT_DIR / "plots"


def assign_multiclass_label(row):
    if row["label"] == "benign":
        return 0
    scenario = str(row.get("scenario", "other")).lower().strip()
    return SCENARIO_TO_LABEL.get(scenario, 5)


@torch.no_grad()
def evaluate_model(model, tokenizer, test_df, device, batch_size=64):
    model.eval()
    all_preds = []
    all_labels = test_df["mc_label"].tolist()
    texts = test_df["url"].tolist()
    total = len(texts)

    for i in range(0, total, batch_size):
        batch_texts = texts[i : i + batch_size]
        encodings = tokenizer(batch_texts, max_length=MAX_LENGTH, padding="max_length", truncation=True, return_tensors="pt")
        input_ids = encodings["input_ids"].to(device)
        attention_mask = encodings["attention_mask"].to(device)
        token_type_ids = encodings["token_type_ids"].to(device)

        if device.type == "cuda":
            with autocast(device_type="cuda"):
                outputs = model(input_ids=input_ids, attention_mask=attention_mask, token_type_ids=token_type_ids)
        else:
            outputs = model(input_ids=input_ids, attention_mask=attention_mask, token_type_ids=token_type_ids)

        preds = torch.argmax(outputs.logits, dim=-1)
        all_preds.extend(preds.cpu().numpy().tolist())
        done = min(i + batch_size, total)
        print(f"\r  Processed {done}/{total} samples...", end="")
    print()
    return all_labels, all_preds


def plot_confusion_matrix(cm, label_names, save_path):
    fig, ax = plt.subplots(figsize=(10, 8))
    sns.heatmap(cm, annot=True, fmt="d", cmap="Blues",
        xticklabels=label_names, yticklabels=label_names,
        ax=ax, annot_kws={"size": 11}, linewidths=0.5, linecolor="gray")
    ax.set_xlabel("Predicted Label", fontsize=13)
    ax.set_ylabel("True Label", fontsize=13)
    ax.set_title("Confusion Matrix (Multi-class)", fontsize=15, fontweight="bold")
    plt.xticks(rotation=30, ha="right"); plt.yticks(rotation=0)
    plt.tight_layout()
    plt.savefig(save_path, dpi=150, bbox_inches="tight"); plt.close()
    print(f"  [PLOT] Saved: {save_path.name}")


def plot_training_history(history, save_dir):
    save_dir.mkdir(parents=True, exist_ok=True)
    epochs = list(range(1, len(history["train_loss"]) + 1))
    plt.style.use("seaborn-v0_8-whitegrid")
    colors = {"train": "#2196F3", "val": "#F44336", "f1m": "#4CAF50",
              "f1w": "#8BC34A", "acc": "#FF9800", "prec": "#9C27B0",
              "rec": "#00BCD4", "lr": "#607D8B"}

    # Loss
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.plot(epochs, history["train_loss"], color=colors["train"], lw=2, label="Train", marker="o", ms=3)
    ax.plot(epochs, history["val_loss"], color=colors["val"], lw=2, label="Val", marker="s", ms=3)
    ax.set_xlabel("Epoch"); ax.set_ylabel("Loss")
    ax.set_title("Training & Validation Loss", fontsize=14, fontweight="bold")
    ax.legend(); ax.grid(True, alpha=0.3)
    plt.tight_layout(); plt.savefig(save_dir / "loss_curves.png", dpi=150); plt.close()
    print(f"  [PLOT] Saved: loss_curves.png")

    # Accuracy & F1
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.plot(epochs, history["val_accuracy"], color=colors["acc"], lw=2, label="Accuracy", marker="o", ms=3)
    ax.plot(epochs, history["val_f1_macro"], color=colors["f1m"], lw=2, label="F1 (macro)", marker="s", ms=3)
    ax.plot(epochs, history["val_f1_weighted"], color=colors["f1w"], lw=2, label="F1 (weighted)", marker="^", ms=3)
    ax.set_xlabel("Epoch"); ax.set_ylabel("Score")
    ax.set_title("Validation Accuracy & F1", fontsize=14, fontweight="bold")
    ax.legend(); ax.grid(True, alpha=0.3); ax.set_ylim(0, 1.05)
    plt.tight_layout(); plt.savefig(save_dir / "accuracy_f1.png", dpi=150); plt.close()
    print(f"  [PLOT] Saved: accuracy_f1.png")

    # Precision & Recall
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.plot(epochs, history["val_precision_macro"], color=colors["prec"], lw=2, label="Precision", marker="o", ms=3)
    ax.plot(epochs, history["val_recall_macro"], color=colors["rec"], lw=2, label="Recall", marker="s", ms=3)
    ax.set_xlabel("Epoch"); ax.set_ylabel("Score")
    ax.set_title("Validation Precision & Recall (Macro)", fontsize=14, fontweight="bold")
    ax.legend(); ax.grid(True, alpha=0.3); ax.set_ylim(0, 1.05)
    plt.tight_layout(); plt.savefig(save_dir / "precision_recall.png", dpi=150); plt.close()
    print(f"  [PLOT] Saved: precision_recall.png")

    # LR
    if history.get("learning_rate"):
        fig, ax = plt.subplots(figsize=(10, 4))
        ax.plot(epochs, history["learning_rate"], color=colors["lr"], lw=2)
        ax.set_xlabel("Epoch"); ax.set_ylabel("LR")
        ax.set_title("Learning Rate", fontsize=14, fontweight="bold"); ax.grid(True, alpha=0.3)
        plt.tight_layout(); plt.savefig(save_dir / "learning_rate.png", dpi=150); plt.close()
        print(f"  [PLOT] Saved: learning_rate.png")

    # Dashboard
    fig, axes = plt.subplots(2, 2, figsize=(14, 10))
    fig.suptitle("CANINE URL Phishing Multi-class - Dashboard", fontsize=16, fontweight="bold")
    axes[0, 0].plot(epochs, history["train_loss"], color=colors["train"], lw=2, label="Train")
    axes[0, 0].plot(epochs, history["val_loss"], color=colors["val"], lw=2, label="Val")
    axes[0, 0].set_title("Loss"); axes[0, 0].legend(); axes[0, 0].grid(True, alpha=0.3)
    axes[0, 1].plot(epochs, history["val_accuracy"], color=colors["acc"], lw=2)
    axes[0, 1].set_title("Accuracy"); axes[0, 1].set_ylim(0, 1.05); axes[0, 1].grid(True, alpha=0.3)
    axes[1, 0].plot(epochs, history["val_f1_macro"], color=colors["f1m"], lw=2, label="Macro")
    axes[1, 0].plot(epochs, history["val_f1_weighted"], color=colors["f1w"], lw=2, label="Weighted")
    axes[1, 0].set_title("F1-Score"); axes[1, 0].set_ylim(0, 1.05); axes[1, 0].legend(); axes[1, 0].grid(True, alpha=0.3)
    axes[1, 1].plot(epochs, history["val_precision_macro"], color=colors["prec"], lw=2, label="Prec")
    axes[1, 1].plot(epochs, history["val_recall_macro"], color=colors["rec"], lw=2, label="Rec")
    axes[1, 1].set_title("Precision & Recall"); axes[1, 1].set_ylim(0, 1.05); axes[1, 1].legend(); axes[1, 1].grid(True, alpha=0.3)
    for ax in axes.flat: ax.set_xlabel("Epoch")
    plt.tight_layout(); plt.savefig(save_dir / "dashboard.png", dpi=150); plt.close()
    print(f"  [PLOT] Saved: dashboard.png")


def save_evaluation_txt(test_results, report_str, cm, config, training_info, save_path):
    with open(save_path, "w", encoding="utf-8") as f:
        f.write("=" * 70 + "\n")
        f.write("CANINE URL Phishing Detection (Multi-class) - Report\n")
        f.write(f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write("=" * 70 + "\n\n")
        f.write("--- Classes ---\n")
        for k, v in LABEL_MAP.items(): f.write(f"  {k}: {v}\n")
        f.write("\n")
        if config:
            f.write("--- Configuration ---\n")
            for k, v in config.items(): f.write(f"  {k:18s} {v}\n")
            f.write("\n")
        if training_info:
            f.write("--- Training Summary ---\n")
            for k, v in training_info.items():
                if v is not None: f.write(f"  {k:30s} {v}\n")
            f.write("\n")
        f.write("--- Test Set Results ---\n")
        f.write(f"  Accuracy:         {test_results['accuracy']:.4f}\n")
        f.write(f"  F1 (macro):       {test_results['f1_macro']:.4f}\n")
        f.write(f"  F1 (weighted):    {test_results['f1_weighted']:.4f}\n")
        f.write(f"  Precision (macro):{test_results['precision_macro']:.4f}\n")
        f.write(f"  Recall (macro):   {test_results['recall_macro']:.4f}\n\n")
        f.write("--- Classification Report ---\n")
        f.write(report_str + "\n\n")
        f.write("--- Confusion Matrix ---\n")
        header = f"  {'':>20}"
        for name in LABEL_NAMES: header += f" {name:>12}"
        f.write(header + "\n")
        for i, name in enumerate(LABEL_NAMES):
            row = f"  {name:>20}"
            for j in range(len(LABEL_NAMES)): row += f" {cm[i][j]:>12}"
            f.write(row + "\n")
        f.write("\n" + "=" * 70 + "\n")
    print(f"  [FILE] Saved: {save_path.name}")


def main():
    parser = argparse.ArgumentParser(description="Evaluate CANINE multi-class model")
    parser.add_argument("--model_dir", type=str, default=str(DEFAULT_MODEL_DIR))
    parser.add_argument("--results_file", type=str, default=str(DEFAULT_RESULTS_FILE))
    parser.add_argument("--batch_size", type=int, default=64)
    parser.add_argument("--device", type=str, default="auto")
    args = parser.parse_args()

    model_dir = Path(args.model_dir)
    results_file = Path(args.results_file)

    if not model_dir.exists():
        print(f"[ERROR] Model not found: {model_dir}"); sys.exit(1)

    test_csv = DATA_DIR / "url_test.csv"
    if not test_csv.exists():
        print(f"[ERROR] Test data not found: {test_csv}"); sys.exit(1)

    device = torch.device("cuda" if args.device == "auto" and torch.cuda.is_available() else args.device if args.device != "auto" else "cpu")

    print("=" * 70)
    print("POST-TRAINING EVALUATION (MULTI-CLASS)")
    print("=" * 70)
    print(f"  Model: {model_dir}")
    print(f"  Device: {device}")
    print()

    print("[LOAD] Loading model...")
    tokenizer = CanineTokenizer.from_pretrained(str(model_dir))
    model = CanineForSequenceClassification.from_pretrained(str(model_dir))
    model.to(device); model.eval()

    print("[DATA] Loading test set...")
    test_df = pd.read_csv(test_csv, low_memory=False)
    test_df.dropna(subset=["url", "label"], inplace=True)
    test_df["mc_label"] = test_df.apply(assign_multiclass_label, axis=1)
    print(f"  Test samples: {len(test_df)}")
    print()

    print("[EVAL] Running evaluation...")
    all_labels, all_preds = evaluate_model(model, tokenizer, test_df, device, args.batch_size)

    accuracy = accuracy_score(all_labels, all_preds)
    prec_m, rec_m, f1_m, _ = precision_recall_fscore_support(all_labels, all_preds, average="macro", zero_division=0)
    _, _, f1_w, _ = precision_recall_fscore_support(all_labels, all_preds, average="weighted", zero_division=0)
    test_results = {"accuracy": accuracy, "precision_macro": prec_m, "recall_macro": rec_m, "f1_macro": f1_m, "f1_weighted": f1_w}

    print(f"\n{'=' * 70}")
    print("TEST SET RESULTS")
    print(f"{'=' * 70}")
    print(f"  Accuracy:       {accuracy:.4f} ({accuracy*100:.2f}%)")
    print(f"  F1 (macro):     {f1_m:.4f}")
    print(f"  F1 (weighted):  {f1_w:.4f}")
    print()

    report_str = classification_report(all_labels, all_preds, target_names=LABEL_NAMES, zero_division=0)
    print("Classification Report:")
    print(report_str)

    cm = confusion_matrix(all_labels, all_preds)

    # Load training history
    config, training_info, history = None, None, None
    if results_file.exists():
        with open(results_file, "r", encoding="utf-8") as f:
            saved = json.load(f)
        config = saved.get("config")
        training_info = {k: saved.get(k) for k in ["best_epoch", "best_val_f1_macro", "total_training_time_minutes"]}
        history = saved.get("training_history")

    PLOTS_DIR.mkdir(parents=True, exist_ok=True)
    print("\n[PLOT] Generating plots...")
    plot_confusion_matrix(cm, LABEL_NAMES, PLOTS_DIR / "confusion_matrix.png")
    if history:
        plot_training_history(history, PLOTS_DIR)

    print("\n[FILE] Saving report...")
    save_evaluation_txt(test_results, report_str, cm, config, training_info, OUTPUT_DIR / "evaluation_report.txt")

    print(f"\n[DONE] Outputs saved to {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
