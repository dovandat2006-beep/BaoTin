"""
Evaluate PhoBERT SMS Spam Detection Model (Post-Training)
==========================================================
Loads the best saved model, evaluates on test set, and generates:
- Confusion matrix plot (PNG)
- Training history plots (PNG)
- Evaluation report (TXT)

No retraining required -- uses saved model and training_results.json.

Usage:
    python eval.py
    python eval.py --model_dir output/best_model --results_file output/training_results.json
"""

import os
import sys

# Redirect model cache to M: drive (C: drive is full)
os.environ["HF_HOME"] = r"M:\BaoTin\AI\.hf_cache"
os.environ["TORCH_HOME"] = r"M:\BaoTin\AI\.torch_cache"

# Add custom library path (packages installed to L drive)
sys.path.insert(0, r"L:\python_libs")

import json
import argparse
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd
import torch
from torch.amp import autocast
from transformers import AutoTokenizer, AutoModelForSequenceClassification
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
MODEL_NAME = "vinai/phobert-base-v2"
MAX_LENGTH = 256
LABEL_MAP = {0: "Hop le", 1: "Spam/Lua dao"}

# --- Paths ---
BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
OUTPUT_DIR = BASE_DIR / "output"
DEFAULT_MODEL_DIR = OUTPUT_DIR / "best_model"
DEFAULT_RESULTS_FILE = OUTPUT_DIR / "training_results.json"
PLOTS_DIR = OUTPUT_DIR / "plots"


# --- Evaluation ---
@torch.no_grad()
def evaluate_model(model, tokenizer, test_df, device, batch_size=32):
    """Run inference on the test set and return predictions + labels."""
    model.eval()
    all_preds = []
    all_labels = test_df["label"].tolist()

    texts = test_df["message"].tolist()
    total = len(texts)

    for i in range(0, total, batch_size):
        batch_texts = texts[i : i + batch_size]
        encodings = tokenizer(
            batch_texts,
            max_length=MAX_LENGTH,
            padding="max_length",
            truncation=True,
            return_tensors="pt",
        )
        input_ids = encodings["input_ids"].to(device)
        attention_mask = encodings["attention_mask"].to(device)

        if device.type == "cuda":
            with autocast(device_type="cuda"):
                outputs = model(input_ids=input_ids, attention_mask=attention_mask)
        else:
            outputs = model(input_ids=input_ids, attention_mask=attention_mask)

        preds = torch.argmax(outputs.logits, dim=-1)
        all_preds.extend(preds.cpu().numpy().tolist())

        done = min(i + batch_size, total)
        print(f"  Processed {done}/{total} samples...", end="\r")

    print()
    return all_labels, all_preds


# --- Plotting ---
def plot_confusion_matrix(cm, label_names, save_path):
    """Plot and save confusion matrix as a heatmap."""
    fig, ax = plt.subplots(figsize=(8, 6))
    sns.heatmap(
        cm,
        annot=True,
        fmt="d",
        cmap="Blues",
        xticklabels=label_names,
        yticklabels=label_names,
        ax=ax,
        annot_kws={"size": 14},
        linewidths=0.5,
        linecolor="gray",
    )
    ax.set_xlabel("Predicted Label", fontsize=13)
    ax.set_ylabel("True Label", fontsize=13)
    ax.set_title("Confusion Matrix", fontsize=15, fontweight="bold")
    plt.tight_layout()
    plt.savefig(save_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"  [PLOT] Saved: {save_path.name}")


def plot_training_history(history, save_dir):
    """Plot training history curves from saved JSON data."""
    save_dir.mkdir(parents=True, exist_ok=True)
    epochs = list(range(1, len(history["train_loss"]) + 1))

    plt.style.use("seaborn-v0_8-whitegrid")
    colors = {
        "train": "#2196F3",
        "val": "#F44336",
        "f1": "#4CAF50",
        "acc": "#FF9800",
        "prec": "#9C27B0",
        "rec": "#00BCD4",
        "lr": "#607D8B",
    }

    # -- 1. Loss Curves --
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.plot(epochs, history["train_loss"], color=colors["train"], linewidth=2, label="Train Loss", marker="o", markersize=4)
    ax.plot(epochs, history["val_loss"], color=colors["val"], linewidth=2, label="Validation Loss", marker="s", markersize=4)
    ax.set_xlabel("Epoch", fontsize=12)
    ax.set_ylabel("Loss", fontsize=12)
    ax.set_title("Training & Validation Loss", fontsize=14, fontweight="bold")
    ax.legend(fontsize=11)
    ax.grid(True, alpha=0.3)
    best_val_idx = np.argmin(history["val_loss"])
    ax.axvline(x=epochs[best_val_idx], color="gray", linestyle="--", alpha=0.5)
    ax.annotate(
        f"Best: {history['val_loss'][best_val_idx]:.4f}",
        xy=(epochs[best_val_idx], history["val_loss"][best_val_idx]),
        xytext=(10, 10),
        textcoords="offset points",
        fontsize=10,
        arrowprops=dict(arrowstyle="->", color="gray"),
    )
    plt.tight_layout()
    plt.savefig(save_dir / "loss_curves.png", dpi=150, bbox_inches="tight")
    plt.close()
    print(f"  [PLOT] Saved: loss_curves.png")

    # -- 2. Accuracy & F1 --
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.plot(epochs, history["val_accuracy"], color=colors["acc"], linewidth=2, label="Accuracy", marker="o", markersize=4)
    ax.plot(epochs, history["val_f1"], color=colors["f1"], linewidth=2, label="F1-Score", marker="s", markersize=4)
    ax.set_xlabel("Epoch", fontsize=12)
    ax.set_ylabel("Score", fontsize=12)
    ax.set_title("Validation Accuracy & F1-Score", fontsize=14, fontweight="bold")
    ax.legend(fontsize=11)
    ax.grid(True, alpha=0.3)
    ax.set_ylim(0, 1.05)
    best_f1_idx = np.argmax(history["val_f1"])
    ax.annotate(
        f"Best F1: {history['val_f1'][best_f1_idx]:.4f}",
        xy=(epochs[best_f1_idx], history["val_f1"][best_f1_idx]),
        xytext=(10, -20),
        textcoords="offset points",
        fontsize=10,
        arrowprops=dict(arrowstyle="->", color="gray"),
    )
    plt.tight_layout()
    plt.savefig(save_dir / "accuracy_f1.png", dpi=150, bbox_inches="tight")
    plt.close()
    print(f"  [PLOT] Saved: accuracy_f1.png")

    # -- 3. Precision & Recall --
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.plot(epochs, history["val_precision"], color=colors["prec"], linewidth=2, label="Precision", marker="o", markersize=4)
    ax.plot(epochs, history["val_recall"], color=colors["rec"], linewidth=2, label="Recall", marker="s", markersize=4)
    ax.set_xlabel("Epoch", fontsize=12)
    ax.set_ylabel("Score", fontsize=12)
    ax.set_title("Validation Precision & Recall", fontsize=14, fontweight="bold")
    ax.legend(fontsize=11)
    ax.grid(True, alpha=0.3)
    ax.set_ylim(0, 1.05)
    plt.tight_layout()
    plt.savefig(save_dir / "precision_recall.png", dpi=150, bbox_inches="tight")
    plt.close()
    print(f"  [PLOT] Saved: precision_recall.png")

    # -- 4. Learning Rate Schedule --
    if "learning_rate" in history and history["learning_rate"]:
        fig, ax = plt.subplots(figsize=(10, 4))
        ax.plot(epochs, history["learning_rate"], color=colors["lr"], linewidth=2)
        ax.set_xlabel("Epoch", fontsize=12)
        ax.set_ylabel("Learning Rate", fontsize=12)
        ax.set_title("Learning Rate Schedule", fontsize=14, fontweight="bold")
        ax.grid(True, alpha=0.3)
        ax.ticklabel_format(axis="y", style="sci", scilimits=(-4, -4))
        plt.tight_layout()
        plt.savefig(save_dir / "learning_rate.png", dpi=150, bbox_inches="tight")
        plt.close()
        print(f"  [PLOT] Saved: learning_rate.png")

    # -- 5. Combined Dashboard --
    fig, axes = plt.subplots(2, 2, figsize=(14, 10))
    fig.suptitle("PhoBERT SMS Spam Detection - Training Dashboard", fontsize=16, fontweight="bold")

    axes[0, 0].plot(epochs, history["train_loss"], color=colors["train"], linewidth=2, label="Train")
    axes[0, 0].plot(epochs, history["val_loss"], color=colors["val"], linewidth=2, label="Val")
    axes[0, 0].set_title("Loss", fontsize=12, fontweight="bold")
    axes[0, 0].legend()
    axes[0, 0].grid(True, alpha=0.3)

    axes[0, 1].plot(epochs, history["val_accuracy"], color=colors["acc"], linewidth=2)
    axes[0, 1].set_title("Accuracy", fontsize=12, fontweight="bold")
    axes[0, 1].set_ylim(0, 1.05)
    axes[0, 1].grid(True, alpha=0.3)

    axes[1, 0].plot(epochs, history["val_f1"], color=colors["f1"], linewidth=2)
    axes[1, 0].set_title("F1-Score", fontsize=12, fontweight="bold")
    axes[1, 0].set_ylim(0, 1.05)
    axes[1, 0].grid(True, alpha=0.3)

    axes[1, 1].plot(epochs, history["val_precision"], color=colors["prec"], linewidth=2, label="Precision")
    axes[1, 1].plot(epochs, history["val_recall"], color=colors["rec"], linewidth=2, label="Recall")
    axes[1, 1].set_title("Precision & Recall", fontsize=12, fontweight="bold")
    axes[1, 1].set_ylim(0, 1.05)
    axes[1, 1].legend()
    axes[1, 1].grid(True, alpha=0.3)

    for ax in axes.flat:
        ax.set_xlabel("Epoch")

    plt.tight_layout()
    plt.savefig(save_dir / "dashboard.png", dpi=150, bbox_inches="tight")
    plt.close()
    print(f"  [PLOT] Saved: dashboard.png")


# --- Report ---
def save_evaluation_txt(test_results, report_str, cm, config, training_info, save_path):
    """Save all evaluation metrics to a plain text file."""
    label_names = list(LABEL_MAP.values())
    with open(save_path, "w", encoding="utf-8") as f:
        f.write("=" * 70 + "\n")
        f.write("PhoBERT SMS Spam Detection - Evaluation Report\n")
        f.write(f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write("=" * 70 + "\n\n")

        if config:
            f.write("--- Model Configuration ---\n")
            f.write(f"  Model:            {config.get('model', MODEL_NAME)}\n")
            for key, val in config.items():
                if key != "model":
                    f.write(f"  {key:18s} {val}\n")
            f.write("\n")

        if training_info:
            f.write("--- Training Summary ---\n")
            f.write(f"  Best Epoch:       {training_info.get('best_epoch', 'N/A')}\n")
            f.write(f"  Best Val F1:      {training_info.get('best_val_f1', 'N/A')}\n")
            total_min = training_info.get("total_training_time_minutes", None)
            if total_min is not None:
                f.write(f"  Total Time:       {total_min:.1f} minutes\n")
            f.write("\n")

        f.write("--- Test Set Results ---\n")
        f.write(f"  Accuracy:         {test_results['accuracy']:.4f} ({test_results['accuracy']*100:.2f}%)\n")
        f.write(f"  Precision:        {test_results['precision']:.4f}\n")
        f.write(f"  Recall:           {test_results['recall']:.4f}\n")
        f.write(f"  F1-Score:         {test_results['f1']:.4f}\n\n")

        f.write("--- Classification Report ---\n")
        f.write(report_str + "\n\n")

        f.write("--- Confusion Matrix ---\n")
        header = f"  {'':>15} {'Pred: ' + label_names[0]:>20} {'Pred: ' + label_names[1]:>20}\n"
        f.write(header)
        f.write(f"  {'True: ' + label_names[0]:>15} {cm[0][0]:>20} {cm[0][1]:>20}\n")
        f.write(f"  {'True: ' + label_names[1]:>15} {cm[1][0]:>20} {cm[1][1]:>20}\n")
        f.write("\n" + "=" * 70 + "\n")

    print(f"  [FILE] Saved: {save_path.name}")


# --- Main ---
def main():
    parser = argparse.ArgumentParser(
        description="Evaluate trained PhoBERT model (no retraining required)"
    )
    parser.add_argument(
        "--model_dir", type=str, default=str(DEFAULT_MODEL_DIR),
        help="Path to saved best model directory",
    )
    parser.add_argument(
        "--results_file", type=str, default=str(DEFAULT_RESULTS_FILE),
        help="Path to training_results.json (for training history plots)",
    )
    parser.add_argument(
        "--batch_size", type=int, default=32,
        help="Batch size for evaluation (default: 32)",
    )
    parser.add_argument(
        "--device", type=str, default="auto",
        help="Device: auto, cpu, cuda (default: auto)",
    )
    args = parser.parse_args()

    model_dir = Path(args.model_dir)
    results_file = Path(args.results_file)

    # Validate paths
    if not model_dir.exists():
        print(f"[ERROR] Model directory not found: {model_dir}")
        print("   Please run train.py first.")
        sys.exit(1)

    test_csv = DATA_DIR / "test.csv"
    if not test_csv.exists():
        print(f"[ERROR] Test data not found: {test_csv}")
        sys.exit(1)

    # Device
    if args.device == "auto":
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    else:
        device = torch.device(args.device)

    print("=" * 70)
    print("POST-TRAINING EVALUATION")
    print("=" * 70)
    print(f"  Model dir:    {model_dir}")
    print(f"  Device:       {device}")
    print()

    # -- Load model --
    print("[LOAD] Loading model and tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(str(model_dir))
    model = AutoModelForSequenceClassification.from_pretrained(str(model_dir))
    model.to(device)
    model.eval()
    print("  Model loaded successfully.")

    # -- Load test data --
    print("[DATA] Loading test set...")
    test_df = pd.read_csv(test_csv)
    test_df = test_df.dropna(subset=["message", "label"]).reset_index(drop=True)
    print(f"  Test samples: {len(test_df)}")
    print(f"  Label dist:   {dict(test_df['label'].value_counts())}")
    print()

    # -- Evaluate --
    print("[EVAL] Running evaluation on test set...")
    all_labels, all_preds = evaluate_model(model, tokenizer, test_df, device, args.batch_size)

    accuracy = accuracy_score(all_labels, all_preds)
    precision, recall, f1, _ = precision_recall_fscore_support(
        all_labels, all_preds, average="binary", zero_division=0
    )

    test_results = {
        "accuracy": accuracy,
        "precision": precision,
        "recall": recall,
        "f1": f1,
    }

    label_names = list(LABEL_MAP.values())

    print(f"\n{'=' * 70}")
    print("TEST SET RESULTS")
    print(f"{'=' * 70}")
    print(f"  Accuracy:  {accuracy:.4f} ({accuracy*100:.2f}%)")
    print(f"  Precision: {precision:.4f}")
    print(f"  Recall:    {recall:.4f}")
    print(f"  F1-Score:  {f1:.4f}")
    print()

    # Classification report
    report_str = classification_report(
        all_labels, all_preds, target_names=label_names,
    )
    print("Classification Report:")
    print(report_str)

    # Confusion matrix
    cm = confusion_matrix(all_labels, all_preds)
    print("Confusion Matrix:")
    print(f"  {'':>15} {'Pred: ' + label_names[0]:>20} {'Pred: ' + label_names[1]:>20}")
    print(f"  {'True: ' + label_names[0]:>15} {cm[0][0]:>20} {cm[0][1]:>20}")
    print(f"  {'True: ' + label_names[1]:>15} {cm[1][0]:>20} {cm[1][1]:>20}")
    print()

    # -- Load training results (for history plots) --
    config = None
    training_info = None
    history = None

    if results_file.exists():
        print(f"[LOAD] Loading training history from {results_file.name}...")
        with open(results_file, "r", encoding="utf-8") as f:
            saved_results = json.load(f)
        config = saved_results.get("config", None)
        training_info = {
            "best_epoch": saved_results.get("best_epoch"),
            "best_val_f1": saved_results.get("best_val_f1"),
            "total_training_time_minutes": saved_results.get("total_training_time_minutes"),
        }
        history = saved_results.get("training_history", None)
        print("  Training history loaded.")
    else:
        print(f"[WARN] training_results.json not found at {results_file}")
        print("  Skipping training history plots.")

    # -- Generate outputs --
    PLOTS_DIR.mkdir(parents=True, exist_ok=True)
    print()

    # 1. Confusion matrix plot
    print("[PLOT] Generating plots...")
    plot_confusion_matrix(cm, label_names, PLOTS_DIR / "confusion_matrix.png")

    # 2. Training history plots (if available)
    if history:
        plot_training_history(history, PLOTS_DIR)

    # 3. Evaluation report TXT
    print()
    print("[FILE] Saving evaluation report...")
    save_evaluation_txt(
        test_results, report_str, cm, config, training_info,
        OUTPUT_DIR / "evaluation_report.txt",
    )

    # -- Summary --
    print()
    print("=" * 70)
    print("[DONE] EVALUATION COMPLETE")
    print(f"  Plots saved to:  {PLOTS_DIR}")
    print(f"  Report saved to: {OUTPUT_DIR / 'evaluation_report.txt'}")
    print("=" * 70)


if __name__ == "__main__":
    main()
