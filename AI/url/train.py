"""
CANINE Fine-tuning for Vietnamese URL Phishing Detection (Multi-class)
======================================================================
Optimized for RTX 5060 8GB VRAM

Dataset: PhishVN v3 (Mendeley Data)
- Multi-class classification:
    0: benign
    1: phishing_bank
    2: phishing_ecommerce
    3: phishing_gaming
    4: phishing_gov
    5: phishing_other (social, telecom, delivery, tax, unknown)

CANINE is a tokenizer-free, character-level Transformer from Google.
Processes raw Unicode characters directly -- ideal for URL analysis.

Usage:
    python train.py
    python train.py --epochs 50 --batch_size 32 --lr 2e-5
"""

import os
import sys

# Redirect model cache to M: drive (C: drive is full)
os.environ["HF_HOME"] = r"M:\BaoTin\AI\.hf_cache"
os.environ["TORCH_HOME"] = r"M:\BaoTin\AI\.torch_cache"

# Add custom library path
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
    CanineForSequenceClassification,
    CanineTokenizer,
    get_linear_schedule_with_warmup,
    get_cosine_schedule_with_warmup,
)
from sklearn.metrics import (
    accuracy_score,
    precision_recall_fscore_support,
    classification_report,
    confusion_matrix,
)
from tqdm import tqdm

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import seaborn as sns

warnings.filterwarnings("ignore", category=FutureWarning)
warnings.filterwarnings("ignore", category=UserWarning)

# --- Constants ---
MODEL_NAME = "google/canine-c"
MAX_LENGTH = 128
SEED = 42

# Multi-class label mapping
# Merge small phishing scenarios into phishing_other
NUM_LABELS = 6
LABEL_MAP = {
    0: "benign",
    1: "phishing_bank",
    2: "phishing_ecommerce",
    3: "phishing_gaming",
    4: "phishing_gov",
    5: "phishing_other",
}
LABEL_NAMES = list(LABEL_MAP.values())

# Scenario -> multi-class label for phishing URLs
SCENARIO_TO_LABEL = {
    "bank": 1,
    "ecommerce": 2,
    "gaming": 3,
    "gov": 4,
    # All others merged into phishing_other
    "other": 5,
    "social": 5,
    "telecom": 5,
    "delivery": 5,
    "tax": 5,
}

# --- Paths ---
BASE_DIR = Path(__file__).parent
DATA_DIR = BASE_DIR / "data" / "data" / "splits"
MAIN_DATA = BASE_DIR / "data" / "data" / "dataset_url.csv"
OUTPUT_DIR = BASE_DIR / "output"
BEST_MODEL_DIR = OUTPUT_DIR / "best_model"
LOGS_DIR = OUTPUT_DIR / "logs"
PLOTS_DIR = OUTPUT_DIR / "plots"


def set_seed(seed: int = SEED):
    random.seed(seed)
    np.random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)
    torch.backends.cudnn.deterministic = True
    torch.backends.cudnn.benchmark = False


# --- Data Preparation ---
def assign_multiclass_label(row):
    """Convert (label, scenario) pair to multi-class integer label."""
    if row["label"] == "benign":
        return 0
    # phishing: map scenario to label
    scenario = str(row.get("scenario", "other")).lower().strip()
    return SCENARIO_TO_LABEL.get(scenario, 5)  # default: phishing_other


def load_split(csv_path: Path):
    """Load a split CSV and assign multi-class labels."""
    df = pd.read_csv(csv_path, low_memory=False)
    df.dropna(subset=["url", "label"], inplace=True)

    # Assign multi-class label
    df["mc_label"] = df.apply(assign_multiclass_label, axis=1)
    return df


# --- Dataset ---
class URLDataset(Dataset):
    def __init__(self, urls, labels, tokenizer, max_length=MAX_LENGTH):
        self.urls = urls
        self.labels = labels
        self.tokenizer = tokenizer
        self.max_length = max_length

    def __len__(self):
        return len(self.urls)

    def __getitem__(self, idx):
        url = str(self.urls[idx])
        label = self.labels[idx]
        encoding = self.tokenizer(
            url, max_length=self.max_length, padding="max_length",
            truncation=True, return_tensors="pt",
        )
        return {
            "input_ids": encoding["input_ids"].squeeze(0),
            "attention_mask": encoding["attention_mask"].squeeze(0),
            "token_type_ids": encoding["token_type_ids"].squeeze(0),
            "label": torch.tensor(label, dtype=torch.long),
        }


# --- Training Utilities ---
class EarlyStopping:
    def __init__(self, patience=7, min_delta=0.001, mode="min"):
        self.patience = patience
        self.min_delta = min_delta
        self.mode = mode
        self.counter = 0
        self.best_score = None
        self.early_stop = False
        if mode == "min":
            self.compare = lambda cur, best: cur < best - min_delta
        else:
            self.compare = lambda cur, best: cur > best + min_delta

    def __call__(self, score):
        if self.best_score is None:
            self.best_score = score
            return True
        if self.compare(score, self.best_score):
            self.best_score = score
            self.counter = 0
            return True
        else:
            self.counter += 1
            if self.counter >= self.patience:
                self.early_stop = True
            return False


class TrainingLogger:
    def __init__(self, log_dir: Path):
        log_dir.mkdir(parents=True, exist_ok=True)
        ts = datetime.now().strftime("%Y%m%d_%H%M%S")
        self.log_file = log_dir / f"training_{ts}.json"
        self.history = {
            "train_loss": [], "val_loss": [],
            "val_accuracy": [], "val_f1_macro": [], "val_f1_weighted": [],
            "val_precision_macro": [], "val_recall_macro": [],
            "learning_rate": [], "epoch_time": [],
        }

    def log_epoch(self, epoch, metrics):
        for k, v in metrics.items():
            if k in self.history:
                self.history[k].append(v)
        with open(self.log_file, "w", encoding="utf-8") as f:
            json.dump(self.history, f, indent=2, ensure_ascii=False)

    def get_history(self):
        return self.history


def get_class_weights(labels, device, num_classes=NUM_LABELS):
    from collections import Counter
    counts = Counter(labels)
    total = len(labels)
    weights = []
    for i in range(num_classes):
        c = counts.get(i, 1)
        weights.append(total / (num_classes * c))
    weights = torch.tensor(weights, dtype=torch.float32).to(device)
    print("  Class weights:")
    for i, w in enumerate(weights):
        print(f"    {LABEL_MAP[i]:>20}: {w:.4f} (n={counts.get(i, 0)})")
    return weights


# --- Core Training Functions ---
def train_epoch(model, dataloader, optimizer, scheduler, criterion, device, scaler):
    model.train()
    total_loss = 0
    progress_bar = tqdm(dataloader, desc="Training", leave=False)
    for batch in progress_bar:
        input_ids = batch["input_ids"].to(device)
        attention_mask = batch["attention_mask"].to(device)
        token_type_ids = batch["token_type_ids"].to(device)
        labels = batch["label"].to(device)
        optimizer.zero_grad()
        with autocast(device_type="cuda"):
            outputs = model(input_ids=input_ids, attention_mask=attention_mask, token_type_ids=token_type_ids)
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
    model.eval()
    total_loss = 0
    all_preds, all_labels = [], []
    for batch in tqdm(dataloader, desc="Evaluating", leave=False):
        input_ids = batch["input_ids"].to(device)
        attention_mask = batch["attention_mask"].to(device)
        token_type_ids = batch["token_type_ids"].to(device)
        labels = batch["label"].to(device)
        with autocast(device_type="cuda"):
            outputs = model(input_ids=input_ids, attention_mask=attention_mask, token_type_ids=token_type_ids)
            loss = criterion(outputs.logits, labels)
        total_loss += loss.item()
        preds = torch.argmax(outputs.logits, dim=-1)
        all_preds.extend(preds.cpu().numpy())
        all_labels.extend(labels.cpu().numpy())

    avg_loss = total_loss / len(dataloader)
    accuracy = accuracy_score(all_labels, all_preds)
    prec_macro, rec_macro, f1_macro, _ = precision_recall_fscore_support(
        all_labels, all_preds, average="macro", zero_division=0)
    _, _, f1_weighted, _ = precision_recall_fscore_support(
        all_labels, all_preds, average="weighted", zero_division=0)
    return {
        "loss": avg_loss, "accuracy": accuracy,
        "precision_macro": prec_macro, "recall_macro": rec_macro,
        "f1_macro": f1_macro, "f1_weighted": f1_weighted,
        "predictions": all_preds, "labels": all_labels,
    }


# --- Visualization ---
def plot_confusion_matrix(cm, label_names, save_path):
    fig, ax = plt.subplots(figsize=(10, 8))
    sns.heatmap(cm, annot=True, fmt="d", cmap="Blues",
        xticklabels=label_names, yticklabels=label_names,
        ax=ax, annot_kws={"size": 11}, linewidths=0.5, linecolor="gray")
    ax.set_xlabel("Predicted Label", fontsize=13)
    ax.set_ylabel("True Label", fontsize=13)
    ax.set_title("Confusion Matrix (Multi-class)", fontsize=15, fontweight="bold")
    plt.xticks(rotation=30, ha="right")
    plt.yticks(rotation=0)
    plt.tight_layout()
    plt.savefig(save_path, dpi=150, bbox_inches="tight")
    plt.close()
    print(f"  [PLOT] Saved: {save_path.name}")


def plot_training_history(history, save_dir):
    save_dir.mkdir(parents=True, exist_ok=True)
    epochs = list(range(1, len(history["train_loss"]) + 1))
    plt.style.use("seaborn-v0_8-whitegrid")
    colors = {"train": "#2196F3", "val": "#F44336", "f1m": "#4CAF50",
              "f1w": "#8BC34A", "acc": "#FF9800", "prec": "#9C27B0",
              "rec": "#00BCD4", "lr": "#607D8B"}

    # 1. Loss
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.plot(epochs, history["train_loss"], color=colors["train"], lw=2, label="Train Loss", marker="o", ms=3)
    ax.plot(epochs, history["val_loss"], color=colors["val"], lw=2, label="Val Loss", marker="s", ms=3)
    ax.set_xlabel("Epoch"); ax.set_ylabel("Loss")
    ax.set_title("Training & Validation Loss", fontsize=14, fontweight="bold")
    ax.legend(); ax.grid(True, alpha=0.3)
    best_idx = np.argmin(history["val_loss"])
    ax.axvline(x=epochs[best_idx], color="gray", ls="--", alpha=0.5)
    ax.annotate(f"Best: {history['val_loss'][best_idx]:.4f}",
        xy=(epochs[best_idx], history["val_loss"][best_idx]),
        xytext=(10, 10), textcoords="offset points", fontsize=10,
        arrowprops=dict(arrowstyle="->", color="gray"))
    plt.tight_layout(); plt.savefig(save_dir / "loss_curves.png", dpi=150); plt.close()
    print(f"  [PLOT] Saved: loss_curves.png")

    # 2. Accuracy & F1
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.plot(epochs, history["val_accuracy"], color=colors["acc"], lw=2, label="Accuracy", marker="o", ms=3)
    ax.plot(epochs, history["val_f1_macro"], color=colors["f1m"], lw=2, label="F1 (macro)", marker="s", ms=3)
    ax.plot(epochs, history["val_f1_weighted"], color=colors["f1w"], lw=2, label="F1 (weighted)", marker="^", ms=3)
    ax.set_xlabel("Epoch"); ax.set_ylabel("Score")
    ax.set_title("Validation Accuracy & F1-Scores", fontsize=14, fontweight="bold")
    ax.legend(); ax.grid(True, alpha=0.3); ax.set_ylim(0, 1.05)
    plt.tight_layout(); plt.savefig(save_dir / "accuracy_f1.png", dpi=150); plt.close()
    print(f"  [PLOT] Saved: accuracy_f1.png")

    # 3. Precision & Recall (macro)
    fig, ax = plt.subplots(figsize=(10, 6))
    ax.plot(epochs, history["val_precision_macro"], color=colors["prec"], lw=2, label="Precision (macro)", marker="o", ms=3)
    ax.plot(epochs, history["val_recall_macro"], color=colors["rec"], lw=2, label="Recall (macro)", marker="s", ms=3)
    ax.set_xlabel("Epoch"); ax.set_ylabel("Score")
    ax.set_title("Validation Precision & Recall (Macro)", fontsize=14, fontweight="bold")
    ax.legend(); ax.grid(True, alpha=0.3); ax.set_ylim(0, 1.05)
    plt.tight_layout(); plt.savefig(save_dir / "precision_recall.png", dpi=150); plt.close()
    print(f"  [PLOT] Saved: precision_recall.png")

    # 4. LR
    if history.get("learning_rate"):
        fig, ax = plt.subplots(figsize=(10, 4))
        ax.plot(epochs, history["learning_rate"], color=colors["lr"], lw=2)
        ax.set_xlabel("Epoch"); ax.set_ylabel("Learning Rate")
        ax.set_title("Learning Rate Schedule", fontsize=14, fontweight="bold")
        ax.grid(True, alpha=0.3)
        plt.tight_layout(); plt.savefig(save_dir / "learning_rate.png", dpi=150); plt.close()
        print(f"  [PLOT] Saved: learning_rate.png")

    # 5. Dashboard
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
    axes[1, 1].set_title("Precision & Recall (Macro)"); axes[1, 1].set_ylim(0, 1.05)
    axes[1, 1].legend(); axes[1, 1].grid(True, alpha=0.3)
    for ax in axes.flat: ax.set_xlabel("Epoch")
    plt.tight_layout(); plt.savefig(save_dir / "dashboard.png", dpi=150); plt.close()
    print(f"  [PLOT] Saved: dashboard.png")


def save_evaluation_txt(test_results, report_str, cm, training_time, best_epoch, best_val_f1, args, save_path):
    with open(save_path, "w", encoding="utf-8") as f:
        f.write("=" * 70 + "\n")
        f.write("CANINE URL Phishing Detection (Multi-class) - Report\n")
        f.write(f"Generated: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}\n")
        f.write("=" * 70 + "\n\n")
        f.write("--- Classes ---\n")
        for k, v in LABEL_MAP.items():
            f.write(f"  {k}: {v}\n")
        f.write("\n--- Model Configuration ---\n")
        f.write(f"  Model:            {MODEL_NAME}\n")
        f.write(f"  Num Labels:       {NUM_LABELS}\n")
        for k, v in vars(args).items():
            f.write(f"  {k:18s} {v}\n")
        f.write(f"\n--- Training Summary ---\n")
        f.write(f"  Best Epoch:       {best_epoch}\n")
        f.write(f"  Best Val F1:      {best_val_f1:.4f} (macro)\n")
        f.write(f"  Total Time:       {training_time/60:.1f} minutes\n\n")
        f.write("--- Test Set Results ---\n")
        f.write(f"  Accuracy:         {test_results['accuracy']:.4f} ({test_results['accuracy']*100:.2f}%)\n")
        f.write(f"  F1 (macro):       {test_results['f1_macro']:.4f}\n")
        f.write(f"  F1 (weighted):    {test_results['f1_weighted']:.4f}\n")
        f.write(f"  Precision (macro):{test_results['precision_macro']:.4f}\n")
        f.write(f"  Recall (macro):   {test_results['recall_macro']:.4f}\n\n")
        f.write("--- Classification Report ---\n")
        f.write(report_str + "\n\n")
        f.write("--- Confusion Matrix ---\n")
        header = f"  {'':>20}"
        for name in LABEL_NAMES:
            header += f" {name:>12}"
        f.write(header + "\n")
        for i, name in enumerate(LABEL_NAMES):
            row = f"  {name:>20}"
            for j in range(len(LABEL_NAMES)):
                row += f" {cm[i][j]:>12}"
            f.write(row + "\n")
        f.write("\n" + "=" * 70 + "\n")
    print(f"  [FILE] Saved: {save_path.name}")


# --- Main ---
def main():
    parser = argparse.ArgumentParser(description="Fine-tune CANINE for URL Phishing Detection (Multi-class)")
    parser.add_argument("--epochs", type=int, default=50)
    parser.add_argument("--batch_size", type=int, default=32)
    parser.add_argument("--lr", type=float, default=2e-5)
    parser.add_argument("--weight_decay", type=float, default=0.01)
    parser.add_argument("--warmup_ratio", type=float, default=0.1)
    parser.add_argument("--patience", type=int, default=7)
    parser.add_argument("--max_length", type=int, default=128)
    parser.add_argument("--scheduler", type=str, default="cosine", choices=["linear", "cosine"])
    parser.add_argument("--seed", type=int, default=SEED)
    args = parser.parse_args()

    set_seed(args.seed)

    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    if torch.cuda.is_available():
        gpu_name = torch.cuda.get_device_name(0)
        gpu_mem = torch.cuda.get_device_properties(0).total_memory / (1024**3)
        print(f"[GPU] {gpu_name} ({gpu_mem:.1f} GB)")
    else:
        print("[WARN] No GPU found, training on CPU")

    print(f"[MODEL] {MODEL_NAME} (multi-class, {NUM_LABELS} labels)")
    print(f"[CONFIG] epochs={args.epochs}, batch_size={args.batch_size}, lr={args.lr}")
    print(f"   patience={args.patience}, max_length={args.max_length}, scheduler={args.scheduler}")
    print()

    # -- Load Data --
    print("[DATA] Loading dataset splits...")
    train_df = load_split(DATA_DIR / "url_train.csv")
    val_df = load_split(DATA_DIR / "url_val.csv")
    test_df = load_split(DATA_DIR / "url_test.csv")

    train_labels = train_df["mc_label"].tolist()
    val_labels = val_df["mc_label"].tolist()
    test_labels = test_df["mc_label"].tolist()

    print(f"  Train: {len(train_df)} samples")
    print(f"  Val:   {len(val_df)} samples")
    print(f"  Test:  {len(test_df)} samples")
    print()
    print("  Class distribution (Train):")
    for label_id, name in LABEL_MAP.items():
        count = sum(1 for l in train_labels if l == label_id)
        print(f"    {name:>20}: {count:>6}")
    print()

    # -- Tokenizer --
    print("[TOKENIZER] Loading CANINE tokenizer...")
    tokenizer = CanineTokenizer.from_pretrained(MODEL_NAME)

    # -- Datasets & DataLoaders --
    train_dataset = URLDataset(train_df["url"].tolist(), train_labels, tokenizer, args.max_length)
    val_dataset = URLDataset(val_df["url"].tolist(), val_labels, tokenizer, args.max_length)
    test_dataset = URLDataset(test_df["url"].tolist(), test_labels, tokenizer, args.max_length)

    train_loader = DataLoader(train_dataset, batch_size=args.batch_size, shuffle=True, num_workers=0, pin_memory=True, drop_last=True)
    val_loader = DataLoader(val_dataset, batch_size=args.batch_size * 2, shuffle=False, num_workers=0, pin_memory=True)
    test_loader = DataLoader(test_dataset, batch_size=args.batch_size * 2, shuffle=False, num_workers=0, pin_memory=True)

    # -- Model --
    print(f"[MODEL] Loading CANINE (num_labels={NUM_LABELS})...")
    model = CanineForSequenceClassification.from_pretrained(MODEL_NAME, num_labels=NUM_LABELS)
    model.to(device)

    total_params = sum(p.numel() for p in model.parameters())
    print(f"  Total params: {total_params:,}")
    print()

    # -- Loss, Optimizer, Scheduler --
    class_weights = get_class_weights(train_labels, device)
    criterion = nn.CrossEntropyLoss(weight=class_weights)

    no_decay = ["bias", "LayerNorm.weight", "LayerNorm.bias"]
    optimizer_grouped = [
        {"params": [p for n, p in model.named_parameters() if not any(nd in n for nd in no_decay)], "weight_decay": args.weight_decay},
        {"params": [p for n, p in model.named_parameters() if any(nd in n for nd in no_decay)], "weight_decay": 0.0},
    ]
    optimizer = torch.optim.AdamW(optimizer_grouped, lr=args.lr, eps=1e-8)

    total_steps = len(train_loader) * args.epochs
    warmup_steps = int(total_steps * args.warmup_ratio)

    if args.scheduler == "cosine":
        scheduler = get_cosine_schedule_with_warmup(optimizer, warmup_steps, total_steps)
    else:
        scheduler = get_linear_schedule_with_warmup(optimizer, warmup_steps, total_steps)

    print(f"  Total steps: {total_steps}, Warmup: {warmup_steps}")

    scaler = GradScaler("cuda")
    early_stopping = EarlyStopping(patience=args.patience, min_delta=0.001, mode="min")
    logger = TrainingLogger(LOGS_DIR)

    # -- Training Loop --
    print()
    print("=" * 70)
    print("STARTING TRAINING (MULTI-CLASS)")
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

        train_loss = train_epoch(model, train_loader, optimizer, scheduler, criterion, device, scaler)
        val_results = evaluate(model, val_loader, criterion, device)
        epoch_time = time.time() - epoch_start

        metrics = {
            "train_loss": train_loss,
            "val_loss": val_results["loss"],
            "val_accuracy": val_results["accuracy"],
            "val_f1_macro": val_results["f1_macro"],
            "val_f1_weighted": val_results["f1_weighted"],
            "val_precision_macro": val_results["precision_macro"],
            "val_recall_macro": val_results["recall_macro"],
            "learning_rate": current_lr,
            "epoch_time": epoch_time,
        }
        logger.log_epoch(epoch, metrics)

        print(f"  [TRAIN] Loss: {train_loss:.4f}")
        print(
            f"  [VAL]   Loss: {val_results['loss']:.4f} | "
            f"Acc: {val_results['accuracy']:.4f} | "
            f"F1m: {val_results['f1_macro']:.4f} | "
            f"F1w: {val_results['f1_weighted']:.4f}"
        )
        print(f"  [TIME]  {epoch_time:.1f}s")

        if val_results["f1_macro"] > best_val_f1:
            best_val_f1 = val_results["f1_macro"]
            best_epoch = epoch
            BEST_MODEL_DIR.mkdir(parents=True, exist_ok=True)
            model.save_pretrained(BEST_MODEL_DIR)
            tokenizer.save_pretrained(BEST_MODEL_DIR)
            # Save label map alongside model
            with open(BEST_MODEL_DIR / "label_map.json", "w") as f:
                json.dump({"label_map": LABEL_MAP, "scenario_to_label": SCENARIO_TO_LABEL, "num_labels": NUM_LABELS}, f, indent=2)
            print(f"  [SAVE] New best model! (F1 macro: {best_val_f1:.4f})")

        improved = early_stopping(val_results["loss"])
        if early_stopping.early_stop:
            print(f"\n[STOP] Early stopping at epoch {epoch}")
            break
        elif not improved:
            print(f"  [WAIT] No improvement ({early_stopping.counter}/{early_stopping.patience})")

        if torch.cuda.is_available():
            alloc = torch.cuda.memory_allocated(0) / (1024**3)
            resv = torch.cuda.memory_reserved(0) / (1024**3)
            print(f"  [VRAM] {alloc:.2f}GB / {resv:.2f}GB")

    total_time = time.time() - training_start
    print(f"\n{'=' * 70}")
    print(f"TRAINING COMPLETE | {total_time/60:.1f} min | Best epoch: {best_epoch} (F1m: {best_val_f1:.4f})")
    print(f"{'=' * 70}")

    # -- Final Test Evaluation --
    print("\n[EVAL] EVALUATING BEST MODEL ON TEST SET...")
    best_model = CanineForSequenceClassification.from_pretrained(BEST_MODEL_DIR)
    best_model.to(device)

    test_results = evaluate(best_model, test_loader, criterion, device)

    print(f"\n{'=' * 70}")
    print("TEST SET RESULTS (MULTI-CLASS)")
    print(f"{'=' * 70}")
    print(f"  Accuracy:       {test_results['accuracy']:.4f} ({test_results['accuracy']*100:.2f}%)")
    print(f"  F1 (macro):     {test_results['f1_macro']:.4f}")
    print(f"  F1 (weighted):  {test_results['f1_weighted']:.4f}")
    print(f"  Precision (m):  {test_results['precision_macro']:.4f}")
    print(f"  Recall (m):     {test_results['recall_macro']:.4f}")
    print()

    report = classification_report(test_results["labels"], test_results["predictions"], target_names=LABEL_NAMES, zero_division=0)
    print("Classification Report:")
    print(report)

    cm = confusion_matrix(test_results["labels"], test_results["predictions"])
    print("Confusion Matrix:")
    header = f"  {'':>20}"
    for name in LABEL_NAMES:
        header += f" {name:>12}"
    print(header)
    for i, name in enumerate(LABEL_NAMES):
        row = f"  {name:>20}"
        for j in range(len(LABEL_NAMES)):
            row += f" {cm[i][j]:>12}"
        print(row)
    print()

    # -- Save Outputs --
    OUTPUT_DIR.mkdir(parents=True, exist_ok=True)
    PLOTS_DIR.mkdir(parents=True, exist_ok=True)

    plot_confusion_matrix(cm, LABEL_NAMES, PLOTS_DIR / "confusion_matrix.png")
    plot_training_history(logger.get_history(), PLOTS_DIR)
    save_evaluation_txt(test_results, report, cm, total_time, best_epoch, best_val_f1, args, OUTPUT_DIR / "evaluation_report.txt")

    results = {
        "model": MODEL_NAME,
        "num_labels": NUM_LABELS,
        "label_map": LABEL_MAP,
        "best_epoch": best_epoch,
        "best_val_f1_macro": best_val_f1,
        "test_accuracy": test_results["accuracy"],
        "test_f1_macro": test_results["f1_macro"],
        "test_f1_weighted": test_results["f1_weighted"],
        "test_precision_macro": test_results["precision_macro"],
        "test_recall_macro": test_results["recall_macro"],
        "total_training_time_minutes": total_time / 60,
        "config": vars(args),
        "confusion_matrix": cm.tolist(),
        "training_history": logger.get_history(),
    }
    with open(OUTPUT_DIR / "training_results.json", "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2, ensure_ascii=False)

    print(f"\n[DONE] All outputs saved to {OUTPUT_DIR}")


if __name__ == "__main__":
    main()
