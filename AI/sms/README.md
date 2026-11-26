# PhoBERT Vietnamese SMS Spam Detection

Fine-tuning [PhoBERT](https://github.com/VinAIResearch/PhoBERT) (`vinai/phobert-base-v2`)
for Vietnamese SMS spam/fraud detection.

---

## Dataset

**Vietnamese Quality-Assured SMS Scam Dataset**

- Kaggle: [Vietnamese SMS Dataset (Kaggle)](https://www.kaggle.com/datasets/tuan251209/vietnamese-sms-dataset)
- GitHub: [trannguyenthaituan251209/vietnamese_sms_dataset](https://github.com/trannguyenthaituan251209/vietnamese_sms_dataset)
- Language: Vietnamese
- Task: Binary classification (Legitimate vs Spam/Fraud)

### Data Format

CSV files with 2 columns:

| Column    | Type   | Description                          |
|-----------|--------|--------------------------------------|
| `message` | string | Raw Vietnamese SMS text              |
| `label`   | int    | 0 = Legitimate, 1 = Spam/Fraud      |

### Data Split

| Split | Samples | Legitimate (0) | Spam (1) | Spam Ratio |
|-------|---------|----------------|----------|------------|
| Train | 2,394   | 1,754          | 640      | 26.7%      |
| Test  | 597     | 439            | 158      | 26.5%      |

### Sample Data

```
message,label
"Tai khoan cua quy khach da bi tam khoa...",1
"Em gui hoa don cho anh nha",0
```

---

## Model

**PhoBERT-base-v2** (VinAI Research)

- Pre-trained Vietnamese language model based on RoBERTa architecture
- 135M parameters
- Tokenizer: BPE (Byte Pair Encoding) optimized for Vietnamese

### Results (Test Set)

| Metric         | Score  |
|----------------|--------|
| Accuracy       | 94.97% |
| F1-Score       | 0.9057 |
| Precision      | 0.9000 |
| Recall         | 0.9114 |

### Per-class Performance

| Class        | Precision | Recall | F1-Score | Support |
|--------------|-----------|--------|----------|---------|
| Legitimate   | 0.97      | 0.96   | 0.97     | 439     |
| Spam/Fraud   | 0.90      | 0.91   | 0.91     | 158     |

---

## Project Structure

```
sms/
  data/
    train.csv                   # Training data (2,394 samples)
    test.csv                    # Test data (597 samples)
    full_dataset.csv            # Full dataset (combined)
    README.md                   # Dataset documentation
    QUICKSTART_GUIDE.md         # Quick start guide
  output/
    best_model/                 # Best fine-tuned PhoBERT model
    onnx/                       # ONNX exported model
    logs/                       # Training log files (JSON)
    plots/
      confusion_matrix.png      # Confusion matrix heatmap
      loss_curves.png           # Train/Val loss over epochs
      f1_scores.png             # F1-Score progression
      precision_recall.png      # Precision & Recall curves
      learning_rate.png         # LR schedule
      dashboard.png             # Combined dashboard
    training_results.json       # Full training metrics & history
    evaluation_report.txt       # Text report with all metrics
  train.py                      # Main training script
  eval.py                       # Post-training evaluation (no retrain)
  test_model.py                 # Interactive/batch testing
  convert_to_onnx.py            # ONNX conversion & benchmark
  visualize.py                  # Training visualization
  download_data.py              # Dataset downloader
  requirements.txt              # Python dependencies
  .gitignore
  README.md
```

---

## Usage

### 1. Download Dataset

```bash
python download_data.py
```

### 2. Install Dependencies

```bash
pip install -r requirements.txt
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu128
```

### 3. Train Model

```bash
# Default: 50 epochs, batch_size=16, lr=2e-5, patience=7
python train.py

# Custom parameters
python train.py --epochs 50 --batch_size 16 --lr 2e-5 --patience 7
```

### 4. Evaluate (without retraining)

```bash
python eval.py
python eval.py --model_dir output/best_model --results_file output/training_results.json
```

### 5. Test Model

```bash
# Interactive mode (type messages to classify)
python test_model.py

# Single text
python test_model.py --text "Ban da trung thuong 100 trieu"

# Evaluate on test set
python test_model.py --eval

# Use ONNX backend
python test_model.py --onnx --eval
```

### 6. Convert to ONNX

```bash
python convert_to_onnx.py
python convert_to_onnx.py --optimize --benchmark
```

### 7. Visualize Training

```bash
python visualize.py
```

---

## Hardware

Optimized for **NVIDIA RTX 5060 8GB**:

- Mixed precision training (FP16 via GradScaler + autocast)
- Batch size 16 with gradient accumulation 2 (effective batch = 32)
- Max sequence length: 256 tokens
- Training time: ~3.6 minutes (9 epochs with early stopping)

## Training Features

- **Early Stopping**: patience=7, monitors validation loss
- **Best Model Saving**: saves checkpoint with best F1-score
- **Class Weighting**: handles imbalanced dataset (73% legitimate vs 27% spam)
- **Mixed Precision**: FP16 for faster training and lower VRAM usage
- **Cosine LR Schedule**: with linear warmup (10% of total steps)
- **Gradient Clipping**: max_norm=1.0
- **ONNX Export**: dynamic axes, optimization, and benchmark
