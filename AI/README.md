# Vietnamese Threat Detection: SMS Spam & Phishing URL Classification

Fine-tuned Deep Learning and Transformer models for detecting Vietnamese SMS scams and malicious phishing URLs.

---

## Overview

This repository contains two core AI modules designed to combat cybersecurity threats targeting Vietnamese users:

1. **SMS Scam Detection (`sms/`)**:
   - Model: **PhoBERT-base-v2** (`vinai/phobert-base-v2`)
   - Task: Binary classification (Legitimate vs. Spam/Fraud)
   - Approach: Pre-trained RoBERTa architecture tailored for Vietnamese syllable-level semantics.

2. **URL Phishing Detection (`url/`)**:
   - Model: **Google CANINE-C** (`google/canine-c`)
   - Task: Multi-class classification (6 classes: Benign, Banking, E-commerce, Gaming, Government, Other Phishing)
   - Approach: Tokenizer-free, character-level Transformer operating directly on raw Unicode codepoints, preventing subword fragmentation in obfuscated URLs.

Both modules include complete end-to-end pipelines: dataset downloader, fine-tuning scripts with FP16 mixed precision, evaluation with confusion matrices and classification reports, interactive inference scripts, and ONNX runtime export with benchmark tools.

---

## Datasets

### 1. Vietnamese Quality-Assured SMS Scam Dataset

- Kaggle: [Vietnamese SMS Dataset](https://www.kaggle.com/datasets/tuan251209/vietnamese-sms-dataset)
- GitHub: [trannguyenthaituan251209/vietnamese_sms_dataset](https://github.com/trannguyenthaituan251209/vietnamese_sms_dataset)
- Language: Vietnamese
- Task: Binary Classification

#### Format:
CSV files containing 2 columns:
- `message` (string): Vietnamese SMS message text.
- `label` (int): `0` for Legitimate (Ham), `1` for Scam/Fraud (Spam).

#### Distribution:
- Training Split: 2,394 samples (1,754 legitimate, 640 spam)
- Testing Split: 597 samples (439 legitimate, 158 spam)
- Total: 2,991 quality-curated SMS messages (Spam ratio: ~26.7%)

---

### 2. PhishVN v3 - Vietnamese URL Phishing Dataset

- ScienceDirect: [Data in Brief Article](https://www.sciencedirect.com/science/article/pii/S2352340926007420)
- Mendeley Data: [PhishVN v3 Dataset Repository](https://data.mendeley.com/datasets/b97hxbxtpd/3)
- DOI: `10.17632/b97hxbxtpd.3`
- Language: Vietnamese context (domains, paths, impersonations)
- Task: Multi-class Classification (6 target classes)

#### Format:
- `dataset_url.csv`: Primary dataset with 53,116 URL samples and 29 columns including `url`, `label`, `scenario`, `tier`, `channel`, and 21 lexical/structural feature indicators.
- `abuse_type.csv`: Phishing-specific metadata, abuse classifications, and detection rules.
- `splits/`: Pre-partitioned train, validation, and test sets.

#### Class Mapping Scheme:
The multi-class target is synthesized from `label` and `scenario`:

| Class ID | Label Name           | Description                                                 | Train Samples | Test Samples |
|----------|----------------------|-------------------------------------------------------------|---------------|--------------|
| 0        | `benign`             | Legitimate, authentic websites                             | 11,362        | 3,845        |
| 1        | `phishing_bank`      | Impersonation of commercial banks and financial portals     | 1,066         | 219          |
| 2        | `phishing_ecommerce` | Fake e-commerce stores and merchant promotions              | 927           | 487          |
| 3        | `phishing_gaming`    | Gaming login scams, top-up fraud, virtual asset theft       | 353           | 15           |
| 4        | `phishing_gov`       | Impersonation of ministries, national portals, police      | 5,025         | 22           |
| 5        | `phishing_other`     | Social networks, telecoms, logistics, tax, and generic scams | 18,176        | 4,353        |

---

## Model Benchmark & Evaluation Summary

Both models were evaluated on independent, unseen test splits:

| Module | Architecture | Parameters | Target Task | Accuracy | F1 Score | Notes |
|--------|--------------|------------|-------------|----------|----------|-------|
| `sms/` | PhoBERT-base-v2 | 135M | Binary (Ham vs Spam) | 94.97% | 0.9057 (Spam F1) | Macro Recall: 91.14% |
| `url/` | Google CANINE-C | 130M | Multi-class (6 classes) | 91.21% | 0.9107 (Weighted) | Macro F1: 0.6812 |

### ONNX Optimization Performance

Exported models feature static/dynamic input tensors and graph optimizations for low-latency production deployment:

- **URL CANINE Model**:
  - PyTorch CPU latency: ~30.68 ms/sample
  - ONNX Runtime CPU latency: ~15.02 ms/sample
  - Latency speedup: 2.04x
  - Model footprint: 503.9 MB (PyTorch) -> 411.3 MB (Optimized ONNX)

---

## Project Structure

```
AI/
├── .gitignore                      # Shared git ignore (caches, checkpoints)
├── README.md                       # Workspace overview and documentation
│
├── sms/                            # SMS Scam Detection Module
│   ├── .gitignore                  # Module-specific ignore (data/, output/)
│   ├── README.md                   # Detailed SMS module documentation
│   ├── requirements.txt            # SMS dependencies
│   ├── download_data.py            # Automated dataset downloader
│   ├── train.py                    # Training pipeline with FP16 and early stopping
│   ├── eval.py                     # Evaluation script on existing checkpoints
│   ├── test_model.py               # Interactive CLI and batch inference
│   ├── convert_to_onnx.py          # ONNX exporter with benchmark suite
│   ├── visualize.py                # Loss and metric plotting utility
│   ├── data/                       # Dataset directory (ignored by git)
│   └── output/                     # Checkpoints, ONNX files, plots (ignored by git)
│
└── url/                            # URL Phishing Detection Module
    ├── .gitignore                  # Module-specific ignore (data/, output/)
    ├── README.md                   # Detailed URL module documentation
    ├── requirements.txt            # URL dependencies
    ├── download_data.py            # Mendeley dataset downloader
    ├── train.py                    # Multi-class CANINE training pipeline
    ├── eval.py                     # Evaluation script generating 6x6 confusion matrix
    ├── test_model.py               # Interactive CLI and batch inference
    ├── convert_to_onnx.py          # ONNX conversion, optimization, and benchmark
    ├── data/                       # Dataset directory (ignored by git)
    └── output/                     # Checkpoints, ONNX files, plots (ignored by git)
```

---

## Quick Start

### 1. Environment Setup

Python 3.10 or higher is recommended. Install PyTorch with CUDA support matching your system:

```bash
# Example for CUDA 12.8 / 12.x
pip install torch torchvision torchaudio --index-url https://download.pytorch.org/whl/cu128
```

### 2. SMS Scam Detection Workflow

```bash
cd sms

# Install dependencies
pip install -r requirements.txt

# Download dataset
python download_data.py

# Train PhoBERT model
python train.py --epochs 50 --batch_size 16 --lr 2e-5

# Evaluate checkpoint
python eval.py

# Interactive test
python test_model.py --text "Quy khach duoc tang 100k, bam vao link de nhan"

# Export to ONNX
python convert_to_onnx.py --optimize --benchmark
```

### 3. URL Phishing Detection Workflow

```bash
cd url

# Install dependencies
pip install -r requirements.txt

# Download PhishVN v3 dataset
python download_data.py

# Train CANINE model
python train.py --epochs 50 --batch_size 32 --lr 2e-5

# Evaluate on test set
python eval.py

# Interactive test
python test_model.py --text "https://vietcombank-xacnhan-otp.com"

# Export and benchmark ONNX
python convert_to_onnx.py --optimize --benchmark
```

---

## Hardware & Training Notes

All models and hyperparameters are tuned and verified on:
- **GPU**: NVIDIA GeForce RTX 5060 (8GB VRAM)
- **OS**: Windows 11 / Linux compatible
- **Mixed Precision**: FP16 enabled (`torch.amp.autocast`) to minimize VRAM footprint and accelerate training.
- **Batch Sizing**:
  - SMS: Batch size 16 with gradient accumulation 2 (effective batch 32).
  - URL: Batch size 32, max sequence length 128 characters.
- **Regularization**: Early stopping (patience = 7), gradient clipping (`max_norm = 1.0`), and Cosine Annealing with linear warmup.

---

## Citations and References

- PhishVN v3 Dataset: [ScienceDirect Data in Brief](https://www.sciencedirect.com/science/article/pii/S2352340926007420) / [Mendeley Data DOI 10.17632/b97hxbxtpd.3](https://data.mendeley.com/datasets/b97hxbxtpd/3)
- Vietnamese SMS Scam Dataset: [Kaggle Dataset](https://www.kaggle.com/datasets/tuan251209/vietnamese-sms-dataset)
- PhoBERT: Nguyen, D. Q., & Nguyen, A. T. (2020). PhoBERT: Pre-trained language models for Vietnamese. *Findings of EMNLP 2020*.
- CANINE: Clark, J. H., Garrette, D., Turc, I., & Whittaker, J. (2022). Canine: Pre-training an efficient tokenization-free encoder for language representation. *TACL*.
