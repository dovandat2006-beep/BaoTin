# CANINE Vietnamese URL Phishing Detection (Multi-class)

Fine-tuning [Google CANINE](https://huggingface.co/google/canine-c) (`google/canine-c`)
for multi-class Vietnamese URL phishing detection.

CANINE is a tokenizer-free, character-level Transformer that processes raw Unicode
characters directly -- ideal for URL analysis where subword tokenizers may split
meaningful patterns like domain names and paths.

---

## Dataset

**PhishVN v3 - Vietnamese URL Phishing Dataset**

- ScienceDirect: [Data in Brief Article](https://www.sciencedirect.com/science/article/pii/S2352340926007420)
- Mendeley Data: [PhishVN v3 Repository (DOI: 10.17632/b97hxbxtpd.3)](https://data.mendeley.com/datasets/b97hxbxtpd/3)
- Language: Vietnamese
- Task: Multi-class classification (6 classes)

### Data Format

Main dataset (`dataset_url.csv`) with 29 columns. Key columns used:

| Column              | Type   | Description                                     |
|---------------------|--------|-------------------------------------------------|
| `url`               | string | Raw URL string                                  |
| `label`             | string | `benign` or `phishing`                          |
| `scenario`          | string | Impersonation target: gov, bank, ecommerce, ... |
| `tier`              | string | Confidence tier: gold, silver, bronze           |
| `channel`           | string | Source channel (url, social)                     |
| `impersonated_org`  | string | Target organization name                        |
| `url_len`           | int    | URL character length                            |
| `num_dots`          | int    | Number of dots in URL                           |
| `num_subdomains`    | int    | Number of subdomains                            |
| `has_ip`            | bool   | URL contains IP address                         |
| `has_at`            | bool   | URL contains @ symbol                           |
| `suspicious_tld`    | bool   | TLD is flagged as suspicious                    |

Additional file: `abuse_type.csv` (phishing URLs only, with abuse_type and rule columns).

### Multi-class Labels

The model combines `label` + `scenario` into 6 classes:

| ID | Class                | Description                        | Train  | Test  |
|----|----------------------|------------------------------------|--------|-------|
| 0  | `benign`             | Legitimate URL                     | 11,362 | 3,845 |
| 1  | `phishing_bank`      | Impersonating banks                | 1,066  | 219   |
| 2  | `phishing_ecommerce` | Impersonating e-commerce platforms | 927    | 487   |
| 3  | `phishing_gaming`    | Impersonating gaming services      | 353    | 15    |
| 4  | `phishing_gov`       | Impersonating government sites     | 5,025  | 22    |
| 5  | `phishing_other`     | Other phishing (social, telecom, delivery, tax, unknown) | 18,176 | 4,353 |

Small phishing scenarios (social=167, telecom=71, delivery=67, tax=24) are merged
into `phishing_other` to avoid severe class imbalance.

### Data Splits

Pre-split CSV files in `data/data/splits/`:

| Split | File           | Samples |
|-------|----------------|---------|
| Train | `url_train.csv`| 36,909  |
| Val   | `url_val.csv`  | 7,266   |
| Test  | `url_test.csv` | 8,941   |
| Total |                | 53,116  |

### Sample Data

```
url,label,scenario
google.com,benign,other
tiktokshopseller-vn.com,phishing,ecommerce
chinhphu2025.com,phishing,gov
vtp-vandon.online,phishing,telecom
```

---

## Model

**Google CANINE-C** (Character Architecture with No tokenization In Neural Encoders)

- Tokenizer-free character-level Transformer
- 130M parameters
- Processes raw Unicode characters (no BPE/WordPiece needed)
- Downsampling strategy for efficient long-sequence handling
- Max sequence length: 128 characters

### Results (Test Set)

| Metric           | Score  |
|------------------|--------|
| Accuracy         | 91.21% |
| F1 (weighted)    | 0.9107 |
| F1 (macro)       | 0.6812 |
| Precision (macro)| 0.6856 |
| Recall (macro)   | 0.6817 |

### Per-class Performance

| Class              | Precision | Recall | F1-Score | Support |
|--------------------|-----------|--------|----------|---------|
| benign             | 0.90      | 0.93   | 0.92     | 3,845   |
| phishing_bank      | 0.92      | 0.74   | 0.82     | 219     |
| phishing_ecommerce | 0.92      | 0.98   | 0.95     | 487     |
| phishing_gaming    | 0.44      | 0.53   | 0.48     | 15      |
| phishing_gov       | 0.00      | 0.00   | 0.00     | 22      |
| phishing_other     | 0.92      | 0.90   | 0.91     | 4,353   |

Note: `phishing_gov` and `phishing_gaming` have very low test support (22 and 15 samples),
which leads to unreliable per-class metrics for those classes. Weighted F1 = 0.91 reflects
the overall model quality more accurately.

---

## Project Structure

```
url/
  data/
    data/
      dataset_url.csv           # Main dataset (53,116 records, 29 columns)
      abuse_type.csv            # Abuse type labels for phishing URLs
      vn_compphish.csv          # 21 lexical features
      splits/
        url_train.csv           # Training split (36,909)
        url_val.csv             # Validation split (7,266)
        url_test.csv            # Test split (8,941)
  output/
    best_model/                 # Best fine-tuned CANINE model
      config.json               # Model config
      model.safetensors         # Model weights
      tokenizer_config.json     # Tokenizer config
      label_map.json            # Multi-class label mapping
    onnx/
      canine_url_phishing.onnx            # Standard ONNX model (459 MB)
      canine_url_phishing_optimized.onnx  # Optimized ONNX model (411 MB)
    plots/
      confusion_matrix.png      # 6x6 confusion matrix heatmap
      loss_curves.png           # Train/Val loss over epochs
      accuracy_f1.png           # Accuracy & F1 (macro/weighted)
      precision_recall.png      # Precision & Recall curves
      learning_rate.png         # LR schedule
      dashboard.png             # Combined 2x2 dashboard
    logs/                       # Training log files (JSON)
    training_results.json       # Full training metrics & history
    evaluation_report.txt       # Text report with all metrics
  train.py                      # Main training script
  eval.py                       # Post-training evaluation (no retrain)
  test_model.py                 # Interactive/batch testing
  convert_to_onnx.py            # ONNX conversion & benchmark
  download_data.py              # Dataset downloader (Mendeley)
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
# Default: 50 epochs, batch_size=32, lr=2e-5, patience=7
python train.py

# Custom parameters
python train.py --epochs 50 --batch_size 32 --lr 2e-5 --patience 7 --scheduler cosine
```

### 4. Evaluate (without retraining)

```bash
python eval.py
python eval.py --model_dir output/best_model --results_file output/training_results.json
```

### 5. Test Model

```bash
# Interactive mode (type URLs to classify)
python test_model.py

# Single URL
python test_model.py --text "vietcombank-xacnhan.tk"

# Evaluate on full test set
python test_model.py --eval

# Use ONNX backend
python test_model.py --onnx

# Batch test from file (one URL per line)
python test_model.py --batch urls.txt
```

### 6. Convert to ONNX

```bash
# Basic conversion
python convert_to_onnx.py

# With optimization and benchmark
python convert_to_onnx.py --optimize --benchmark
```

ONNX benchmark results:
- PyTorch (CPU): 30.68 ms/sample
- ONNX (CPU): 15.02 ms/sample
- Speedup: 2.04x

---

## Hardware

Optimized for **NVIDIA RTX 5060 8GB**:

- Mixed precision training (FP16 via GradScaler + autocast)
- Batch size: 32
- Max sequence length: 128 characters
- Training time: ~18 minutes (7 epochs with early stopping)
- VRAM usage: ~5-6 GB

## Training Features

- **Early Stopping**: patience=7, monitors validation loss
- **Best Model Saving**: saves checkpoint with best macro F1-score
- **Class Weighting**: automatic inverse-frequency weights for imbalanced classes
- **Mixed Precision**: FP16 for faster training and lower VRAM usage
- **Cosine LR Schedule**: with linear warmup (10% of total steps)
- **Gradient Clipping**: max_norm=1.0
- **ONNX Export**: static shapes (batch=1, seq=128), optimization, and benchmark
- **Multi-class Labels**: 6-class scheme combining label + scenario columns
