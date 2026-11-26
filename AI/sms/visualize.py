"""
Visualize Training Results
===========================
Generate plots for training metrics (loss, accuracy, F1, etc.)

Usage:
    python visualize.py
    python visualize.py --results_file output/training_results.json
"""

import json
import argparse
from pathlib import Path

import matplotlib.pyplot as plt
import matplotlib
import numpy as np

matplotlib.use("Agg")  # Non-interactive backend


# --- Paths ---
BASE_DIR = Path(__file__).parent
DEFAULT_RESULTS = BASE_DIR / "output" / "training_results.json"
PLOTS_DIR = BASE_DIR / "output" / "plots"


def plot_training_curves(history: dict, save_dir: Path):
    """Generate comprehensive training visualization."""
    save_dir.mkdir(parents=True, exist_ok=True)
    epochs = list(range(1, len(history["train_loss"]) + 1))

    # Style
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

    # Mark best epoch (lowest val loss)
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

    # Mark best F1
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

    # Loss
    axes[0, 0].plot(epochs, history["train_loss"], color=colors["train"], linewidth=2, label="Train")
    axes[0, 0].plot(epochs, history["val_loss"], color=colors["val"], linewidth=2, label="Val")
    axes[0, 0].set_title("Loss", fontsize=12, fontweight="bold")
    axes[0, 0].legend()
    axes[0, 0].grid(True, alpha=0.3)

    # Accuracy
    axes[0, 1].plot(epochs, history["val_accuracy"], color=colors["acc"], linewidth=2)
    axes[0, 1].set_title("Accuracy", fontsize=12, fontweight="bold")
    axes[0, 1].set_ylim(0, 1.05)
    axes[0, 1].grid(True, alpha=0.3)

    # F1
    axes[1, 0].plot(epochs, history["val_f1"], color=colors["f1"], linewidth=2)
    axes[1, 0].set_title("F1-Score", fontsize=12, fontweight="bold")
    axes[1, 0].set_ylim(0, 1.05)
    axes[1, 0].grid(True, alpha=0.3)

    # Precision & Recall
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


def main():
    parser = argparse.ArgumentParser(description="Visualize Training Results")
    parser.add_argument("--results_file", type=str, default=str(DEFAULT_RESULTS))
    args = parser.parse_args()

    results_path = Path(args.results_file)
    if not results_path.exists():
        print(f"[ERROR] Results file not found: {results_path}")
        print("   Please run train.py first.")
        return

    print("=" * 60)
    print("GENERATING TRAINING VISUALIZATIONS")
    print("=" * 60)

    with open(results_path, "r", encoding="utf-8") as f:
        results = json.load(f)

    history = results.get("training_history", results)

    # Print summary
    if "test_accuracy" in results:
        print(f"\n  Model: {results.get('model', 'N/A')}")
        print(f"  Best epoch: {results.get('best_epoch', 'N/A')}")
        print(f"  Test Accuracy: {results.get('test_accuracy', 0):.4f}")
        print(f"  Test F1-Score: {results.get('test_f1', 0):.4f}")
        print()

    plot_training_curves(history, PLOTS_DIR)

    print(f"\n[DONE] All plots saved to: {PLOTS_DIR}")


if __name__ == "__main__":
    main()
