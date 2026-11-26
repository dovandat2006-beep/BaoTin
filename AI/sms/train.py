"""
PhoBERT Fine-tuning for Vietnamese SMS Spam Detection
=====================================================
Optimized for RTX 5060 8GB VRAM

Dataset: Vietnamese-SMS-Dataset (Kaggle)
- Binary classification: 0 = Legitimate, 1 = Spam/Fraud
- Train: 2,394 samples | Test: 597 samples

Usage:
    python train.py
    python train.py --epochs 50 --batch_size 16 --lr 2e-5
"""

import os
import sys

# Redirect model cache to M: drive (C: drive is full)
os.environ["HF_HOME"] = r"M:\BaoTin\AI\.hf_cache"
os.environ["TORCH_HOME"] = r"M:\BaoTin\AI\.torch_cache"

# Add custom library path (packages installed to L drive)
sys.path.insert(0, r"L:\python_libs")

import json
import time
import random
import argparse
import warnings
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd
import torch
import torch.nn as nn
from torch.utils.data import Dataset, DataLoader
from torch.amp import GradScaler, autocast
from transformers import (
    AutoTokenizer,
    AutoModelForSequenceClassification,
    get_linear_schedule_with_warmup,
    get_cosine_schedule_with_warmup,
)
from sklearn.metrics import (
    accuracy_score,
    precision_recall_fscore_support,
    classification_report,
    confusion_matrix,
)
from sklearn.model_selection import train_test_split
from tqdm import tqdm

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import seaborn as sns

warnings.filterwarnings("ignore", category=FutureWarning)
warnings.filterwarnings("ignore", category=UserWarning)

# --- Constants ---
MODEL_NAME = "vinai/phobert-base-v2"
MAX_LENGTH = 256
LABEL_MAP = {0: "Hop le", 1: "Spam/Lua dao"}
SEED = 42

# --- Paths ---
BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data"
OUTPUT_DIR = BASE_DIR / "output"
BEST_MODEL_DIR = OUTPUT_DIR / "best_model"
LOGS_DIR = OUTPUT_DIR / "logs"
PLOTS_DIR = OUTPUT_DIR / "plots"


def set_seed(seed: int = SEED):
    """Ensure reproducibility across runs."""
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    torch.backends.cudnn.deterministic = True
    torch.backends.cudnn.benchmark = False


# --- Dataset ---
class SMSDataset(Dataset):
    """Vietnamese SMS Dataset for PhoBERT fine-tuning."""

    def __init__(self, texts, labels, tokenizer, max_length=MAX_LENGTH):
        self.texts = texts
        self.labels = labels
        self.tokenizer = tokenizer
        self.max_length = max_length

    def __len__(self):
        return len(self.texts)

    def __getitem__(self, idx):
        text = str(self.texts[idx])
        label = self.labels[idx]

        encoding = self.tokenizer(
            text,
            max_length=self.max_length,
            padding="max_length",
            truncation=True,
            return_tensors="pt",
        )

        return {
            "input_ids": encoding["input_ids"].squeeze(0),
            "attention_mask": encoding["attention_mask"].squeeze(0),
            "label": torch.tensor(label, dtype=torch.long),
        }


# --- Training Utilities ---
class EarlyStopping:
    """Early stopping to prevent overfitting."""

    def __init__(self, patience=7, min_delta=0.001, mode="min"):
        self.patience = patience
        self.min_delta = min_delta
        self.mode = mode
        self.counter = 0
        self.best_score = None
        self.early_stop = False

        if mode == "min":
            self.compare = lambda current, best: current < best - min_delta
        else:
            self.compare = lambda current, best: current > best + min_delta

    def __call__(self, score):
        if self.best_score is None:
            self.best_score = score
            return True  # First epoch, always save

        if self.compare(score, self.best_score):
            self.best_score = score
            self.counter = 0
            return True  # Improved, save model
        else:
            self.counter += 1
            if self.counter >= self.patience:
                self.early_stop = True
            return False  # No improvement


class TrainingLogger:
    """Log training metrics to console and file."""

    def __init__(self, log_dir: Path):
        log_dir.mkdir(parents=True, exist_ok=True)
        timestamp = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.log_file = log_dir / f"training_{timestamp}.json"
        self.history = {
            "train_loss": [],
            "val_loss": [],
            "val_accuracy": [],
            "val_f1": [],
            "val_precision": [],
            "val_recall": [],
            "learning_rate": [],
            "epoch_time": [],
        }

    def log_epoch(self, epoch, metrics):
        for key, value in metrics.items():
            if key in self.history:
                self.history[key].append(value)

        # Save to file after each epoch
        with open(self.log_file, "w", encoding="utf-8") as f:
            json.dump(self.history, f, indent=2, ensure_ascii=False)

    def get_history(self):
        return self.history


def get_class_weights(labels, device):
    """Compute class weights for imbalanced dataset."""
    from collections import Counter

    counts = Counter(labels)
    total = len(labels)
    n_classes = len(counts)
    weights = []
    for i in range(n_classes):
        weights.append(total / (n_classes * counts[i]))
    weights = torch.tensor(weights, dtype=torch.float32).to(device)
    print(f"  Class weights: {weights.tolist()}")
    return weights


# --- Core Training Functions ---
def train_epoch(model, dataloader, optimizer, scheduler, criterion, device, scaler):
    """Train for one epoch with mixed precision."""
    model.train()
    total_loss = 0
    progress_bar = tqdm(dataloader, desc="Training", leave=False)

    for batch in progress_bar:
        input_ids = batch["input_ids"].to(device)
        attention_mask = batch["attention_mask"].to(device)
        labels = batch["label"].to(device)

        optimizer.zero_grad()

        with autocast(device_type="cuda"):
            outputs = model(input_ids=input_ids, attention_mask=attention_mask)
            loss = criterion(outputs.logits, labels)

        scaler.scale(loss).backward()
        scaler.unscale_(optimizer)
        torch.nn.utils.clip_grad_norm_(model.parameters(), max_norm=1.0)
        scaler.step(optimizer)
        scaler.update()
        scheduler.step()

        total_loss += loss.item()
        progress_bar.set_postfix({"loss": f"{loss.item():.4f}"})

    return total_loss / len(dataloader)


@torch.no_grad()
def evaluate(model, dataloader, criterion, device):
    """Evaluate model on validation/test set."""
    model.eval()
    total_loss = 0
    all_preds = []
    all_labels = []

    for batch in tqdm(dataloader, desc="Evaluating", leave=False):
        input_ids = batch["input_ids"].to(device)
        attention_mask = batch["attention_mask"].to(device)
        labels = batch["label"].to(device)

        with autocast(device_type="cuda"):
            outputs = model(input_ids=input_ids, attention_mask=attention_mask)
            loss = criterion(outputs.logits, labels)

        total_loss += loss.item()
        preds = torch.argmax(outputs.logits, dim=-1)
        all_preds.extend(preds.cpu().numpy())
        all_labels.extend(labels.cpu().numpy())

    avg_loss = total_loss / len(dataloader)
    accuracy = accuracy_score(all_labels, all_preds)
    precision, recall, f1, _ = precision_recall_fscore_support(
        all_labels, all_preds, average="binary", zero_division=0
    )

    return {
        "loss": avg_loss,
        "accuracy": accuracy,
        "precision": precision,
        "recall": recall,
        "f1": f1,
        "predictions": all_preds,
        "labels": all_labels,
    }


# --- Visualization & Saving ---
def plot_confusion_matrix(cm, label_names, save_path):
    """Plot and save confusion matrix using matplotlib/seaborn."""
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
    print(f"  [PLOT] Saved: {save_path}")


def plot_training_history(history, save_dir):
    """Plot train loss, val loss, accuracy, F1, precision, recall, LR and save as PNG."""
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
    if "learning_rate" in history:
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


def save_evaluation_txt(test_results, report_str, cm, training_time, best_epoch, best_val_f1, args, save_path):
    """Save all evaluation metrics to a plain text file."""
    with open(save_path, "w", encoding="utf-8") as f:
        f.write("=" * 70 + "\n")
        f.write("PhoBERT SMS Spam Detection - Evaluation Report\n")
        f.write(f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write("=" * 70 + "\n\n")

        f.write("--- Model Configuration ---\n")
        f.write(f"  Model:            {MODEL_NAME}\n")
        f.write(f"  Epochs:           {args.epochs}\n")
        f.write(f"  Batch Size:       {args.batch_size}\n")
        f.write(f"  Learning Rate:    {args.lr}\n")
        f.write(f"  Weight Decay:     {args.weight_decay}\n")
        f.write(f"  Warmup Ratio:     {args.warmup_ratio}\n")
        f.write(f"  Max Length:       {args.max_length}\n")
        f.write(f"  Scheduler:        {args.scheduler}\n")
        f.write(f"  Patience:         {args.patience}\n")
        f.write(f"  Grad Accumulation:{args.gradient_accumulation}\n")
        f.write(f"  Seed:             {args.seed}\n\n")

        f.write("--- Training Summary ---\n")
        f.write(f"  Best Epoch:       {best_epoch}\n")
        f.write(f"  Best Val F1:      {best_val_f1:.4f}\n")
        f.write(f"  Total Time:       {training_time/60:.1f} minutes\n\n")

        f.write("--- Test Set Results ---\n")
        f.write(f"  Accuracy:         {test_results['accuracy']:.4f} ({test_results['accuracy']*100:.2f}%)\n")
        f.write(f"  Precision:        {test_results['precision']:.4f}\n")
        f.write(f"  Recall:           {test_results['recall']:.4f}\n")
        f.write(f"  F1-Score:         {test_results['f1']:.4f}\n\n")

        f.write("--- Classification Report ---\n")
        f.write(report_str + "\n\n")

        f.write("--- Confusion Matrix ---\n")
        label_names = list(LABEL_MAP.values())
        header = f"  {'':>15} {'Pred: ' + label_names[0]:>20} {'Pred: ' + label_names[1]:>20}\n"
        f.write(header)
        f.write(f"  {'True: ' + label_names[0]:>15} {cm[0][0]:>20} {cm[0][1]:>20}\n")
        f.write(f"  {'True: ' + label_names[1]:>15} {cm[1][0]:>20} {cm[1][1]:>20}\n")
        f.write("\n" + "=" * 70 + "\n")

    print(f"  [FILE] Saved: {save_path}")


# --- Main ---
def main():
    parser = argparse.ArgumentParser(description="Fine-tune PhoBERT for Vietnamese SMS Spam Detection")
    parser.add_argument("--epochs", type=int, default=50, help="Max training epochs (default: 50)")
    parser.add_argument("--batch_size", type=int, default=16, help="Batch size (default: 16, RTX 5060 8GB)")
    parser.add_argument("--lr", type=float, default=2e-5, help="Learning rate (default: 2e-5)")
    parser.add_argument("--weight_decay", type=float, default=0.01, help="Weight decay (default: 0.01)")
    parser.add_argument("--warmup_ratio", type=float, default=0.1, help="Warmup ratio (default: 0.1)")
    parser.add_argument("--patience", type=int, default=7, help="Early stopping patience (default: 7)")
    parser.add_argument("--val_split", type=float, default=0.15, help="Validation split ratio (default: 0.15)")
    parser.add_argument("--max_length", type=int, default=256, help="Max token length (default: 256)")
    parser.add_argument("--gradient_accumulation", type=int, default=2, help="Gradient accumulation steps (default: 2)")
    parser.add_argument("--scheduler", type=str, default="cosine", choices=["linear", "cosine"], help="LR scheduler")
    parser.add_argument("--seed", type=int, default=SEED, help="Random seed (default: 42)")
    args = parser.parse_args()

    # Set seed for reproducibility
    set_seed(args.seed)

    # Device setup
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    if torch.cuda.is_available():
        gpu_name = torch.cuda.get_device_name(0)
        gpu_mem = torch.cuda.get_device_properties(0).total_memory / (1024**3)
        print(f"[GPU] {gpu_name} ({gpu_mem:.1f} GB)")
    else:
        print("[WARN] No GPU found, training on CPU (will be very slow)")

    print(f"[MODEL] {MODEL_NAME}")
    print(f"[CONFIG] epochs={args.epochs}, batch_size={args.batch_size}, lr={args.lr}")
    print(f"   patience={args.patience}, max_length={args.max_length}")
    print(f"   grad_accum={args.gradient_accumulation}, scheduler={args.scheduler}")
    print()

    # -- Load Data --
    print("[DATA] Loading dataset...")
    train_df = pd.read_csv(DATA_DIR / "train.csv")
    test_df = pd.read_csv(DATA_DIR / "test.csv")

    # Drop NaN rows
    train_df = train_df.dropna(subset=["message", "label"]).reset_index(drop=True)
    test_df = test_df.dropna(subset=["message", "label"]).reset_index(drop=True)

    print(f"  Train: {len(train_df)} samples (label distribution: {dict(train_df['label'].value_counts())})")
    print(f"  Test:  {len(test_df)} samples (label distribution: {dict(test_df['label'].value_counts())})")

    # Split train into train + validation (stratified)
    train_texts, val_texts, train_labels, val_labels = train_test_split(
        train_df["message"].tolist(),
        train_df["label"].tolist(),
        test_size=args.val_split,
        random_state=args.seed,
        stratify=train_df["label"].tolist(),
    )
    print(f"  Split: train={len(train_texts)}, val={len(val_texts)}")
    print()

    # -- Tokenizer --
    print("[TOKENIZER] Loading tokenizer...")
    tokenizer = AutoTokenizer.from_pretrained(MODEL_NAME)
    print(f"  Vocab size: {tokenizer.vocab_size}")

    # -- Datasets & DataLoaders --
    train_dataset = SMSDataset(train_texts, train_labels, tokenizer, args.max_length)
    val_dataset = SMSDataset(val_texts, val_labels, tokenizer, args.max_length)
    test_dataset = SMSDataset(
        test_df["message"].tolist(),
        test_df["label"].tolist(),
        tokenizer,
        args.max_length,
    )

    # Use effective batch size = batch_size * gradient_accumulation
    # num_workers=0 on Windows to prevent MemoryError from worker process forking
    train_loader = DataLoader(
        train_dataset,
        batch_size=args.batch_size,
        shuffle=True,
        num_workers=0,
        pin_memory=True,
        drop_last=True,
    )
    val_loader = DataLoader(
        val_dataset,
        batch_size=args.batch_size * 2,  # Can use larger batch for eval
        shuffle=False,
        num_workers=0,
        pin_memory=True,
    )
    test_loader = DataLoader(
        test_dataset,
        batch_size=args.batch_size * 2,
        shuffle=False,
        num_workers=0,
        pin_memory=True,
    )

    # -- Model --
    print("[MODEL] Loading PhoBERT model...")
    model = AutoModelForSequenceClassification.from_pretrained(
        MODEL_NAME,
        num_labels=2,
        problem_type="single_label_classification",
    )
    model.to(device)

    # Count parameters
    total_params = sum(p.numel() for p in model.parameters())
    trainable_params = sum(p.numel() for p in model.parameters() if p.requires_grad)
    print(f"  Total params:     {total_params:,}")
    print(f"  Trainable params: {trainable_params:,}")
    print()

    # -- Loss, Optimizer, Scheduler --
    class_weights = get_class_weights(train_labels, device)
    criterion = nn.CrossEntropyLoss(weight=class_weights)

    # Use AdamW with weight decay
    no_decay = ["bias", "LayerNorm.weight", "LayerNorm.bias"]
    optimizer_grouped_parameters = [
        {
            "params": [p for n, p in model.named_parameters() if not any(nd in n for nd in no_decay)],
            "weight_decay": args.weight_decay,
        },
        {
            "params": [p for n, p in model.named_parameters() if any(nd in n for nd in no_decay)],
            "weight_decay": 0.0,
        },
    ]
    optimizer = torch.optim.AdamW(optimizer_grouped_parameters, lr=args.lr, eps=1e-8)

    total_steps = len(train_loader) * args.epochs
    warmup_steps = int(total_steps * args.warmup_ratio)

    if args.scheduler == "cosine":
        scheduler = get_cosine_schedule_with_warmup(optimizer, warmup_steps, total_steps)
    else:
        scheduler = get_linear_schedule_with_warmup(optimizer, warmup_steps, total_steps)

    print(f"  Total steps: {total_steps}, Warmup: {warmup_steps}")

    # Mixed precision scaler
    scaler = GradScaler("cuda")

    # Early stopping and logging
    early_stopping = EarlyStopping(patience=args.patience, min_delta=0.001, mode="min")
    logger = TrainingLogger(LOGS_DIR)

    # -- Training Loop --
    print()
    print("=" * 70)
    print("STARTING TRAINING")
    print("=" * 70)
    best_val_f1 = 0.0
    best_epoch = 0
    training_start = time.time()

    for epoch in range(1, args.epochs + 1):
        epoch_start = time.time()
        current_lr = optimizer.param_groups[0]["lr"]

        print(f"\n{'-' * 70}")
        print(f"[EPOCH] {epoch}/{args.epochs} | LR: {current_lr:.2e}")
        print(f"{'-' * 70}")

        # Train
        train_loss = train_epoch(model, train_loader, optimizer, scheduler, criterion, device, scaler)

        # Validate
        val_results = evaluate(model, val_loader, criterion, device)
        epoch_time = time.time() - epoch_start

        # Log metrics
        metrics = {
            "train_loss": train_loss,
            "val_loss": val_results["loss"],
            "val_accuracy": val_results["accuracy"],
            "val_f1": val_results["f1"],
            "val_precision": val_results["precision"],
            "val_recall": val_results["recall"],
            "learning_rate": current_lr,
            "epoch_time": epoch_time,
        }
        logger.log_epoch(epoch, metrics)

        # Print epoch results
        print(f"  [TRAIN] Loss: {train_loss:.4f}")
        print(
            f"  [VAL]   Loss: {val_results['loss']:.4f} | "
            f"Acc: {val_results['accuracy']:.4f} | "
            f"F1: {val_results['f1']:.4f} | "
            f"P: {val_results['precision']:.4f} | "
            f"R: {val_results['recall']:.4f}"
        )
        print(f"  [TIME]  {epoch_time:.1f}s")

        # Check for best model (by F1 score)
        if val_results["f1"] > best_val_f1:
            best_val_f1 = val_results["f1"]
            best_epoch = epoch
            BEST_MODEL_DIR.mkdir(parents=True, exist_ok=True)
            model.save_pretrained(BEST_MODEL_DIR)
            tokenizer.save_pretrained(BEST_MODEL_DIR)
            print(f"  [SAVE] New best model saved! (F1: {best_val_f1:.4f})")

        # Early stopping (based on val loss)
        improved = early_stopping(val_results["loss"])
        if early_stopping.early_stop:
            print(f"\n[STOP] Early stopping triggered at epoch {epoch}")
            print(f"   Best val loss was at epoch {epoch - early_stopping.patience}")
            break
        elif not improved:
            print(f"  [WAIT] No improvement ({early_stopping.counter}/{early_stopping.patience})")

        # VRAM monitoring
        if torch.cuda.is_available():
            allocated = torch.cuda.memory_allocated(0) / (1024**3)
            reserved = torch.cuda.memory_reserved(0) / (1024**3)
            print(f"  [VRAM] {allocated:.2f}GB allocated / {reserved:.2f}GB reserved")

    total_time = time.time() - training_start
    print(f"\n{'=' * 70}")
    print(f"TRAINING COMPLETE")
    print(f"   Total time: {total_time/60:.1f} minutes")
    print(f"   Best epoch: {best_epoch} (F1: {best_val_f1:.4f})")
    print(f"{'=' * 70}")

    # -- Final Evaluation on Test Set --
    print("\n[EVAL] EVALUATING BEST MODEL ON TEST SET...")
    best_model = AutoModelForSequenceClassification.from_pretrained(BEST_MODEL_DIR)
    best_model.to(device)

    test_results = evaluate(best_model, test_loader, criterion, device)

    print(f"\n{'=' * 70}")
    print("TEST SET RESULTS")
    print(f"{'=' * 70}")
    print(f"  Accuracy:  {test_results['accuracy']:.4f} ({test_results['accuracy']*100:.2f}%)")
    print(f"  Precision: {test_results['precision']:.4f}")
    print(f"  Recall:    {test_results['recall']:.4f}")
    print(f"  F1-Score:  {test_results['f1']:.4f}")
    print()

    # Classification report
    label_names = list(LABEL_MAP.values())
    report = classification_report(
        test_results["labels"],
        test_results["predictions"],
        target_names=label_names,
    )
    print("Classification Report:")
    print(report)

    # Confusion matrix
    cm = confusion_matrix(test_results["labels"], test_results["predictions"])
    print("Confusion Matrix:")
    print(f"  {'':>15} {'Pred: ' + label_names[0]:>20} {'Pred: ' + label_names[1]:>20}")
    print(f"  {'True: ' + label_names[0]:>15} {cm[0][0]:>20} {cm[0][1]:>20}")
    print(f"  {'True: ' + label_names[1]:>15} {cm[1][0]:>20} {cm[1][1]:>20}")
    print()

    # -- Save outputs --
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    PLOTS_DIR.mkdir(parents=True, exist_ok=True)

    # 1. Save confusion matrix plot (PNG)
    plot_confusion_matrix(cm, label_names, PLOTS_DIR / "confusion_matrix.png")

    # 2. Save training history plots (PNG)
    plot_training_history(logger.get_history(), PLOTS_DIR)

    # 3. Save evaluation report (TXT)
    save_evaluation_txt(
        test_results, report, cm, total_time, best_epoch, best_val_f1, args,
        OUTPUT_DIR / "evaluation_report.txt",
    )

    # 4. Save full results JSON (same as before)
    results = {
        "model": MODEL_NAME,
        "best_epoch": best_epoch,
        "best_val_f1": best_val_f1,
        "test_accuracy": test_results["accuracy"],
        "test_precision": test_results["precision"],
        "test_recall": test_results["recall"],
        "test_f1": test_results["f1"],
        "total_training_time_minutes": total_time / 60,
        "config": vars(args),
        "confusion_matrix": cm.tolist(),
        "training_history": logger.get_history(),
    }

    with open(OUTPUT_DIR / "training_results.json", "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    print(f"  [FILE] Results saved to {OUTPUT_DIR / 'training_results.json'}")
    print(f"  [FILE] Best model saved to {BEST_MODEL_DIR}")
    print(f"  [FILE] All plots saved to {PLOTS_DIR}")


if __name__ == "__main__":
    main()
