#!/usr/bin/env python3
"""Package the EXISTING AI exports; never downloads, trains or substitutes weights.

PhoBERT: AI/sms/convert_to_onnx.py, max_length=256, identity preprocessing.
CANINE: AI/url/convert_to_onnx.py, max_length=128, identity preprocessing.
Python golden tensors AND probabilities are mandatory Java startup checks.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import tempfile

import numpy as np
import onnx
import onnxruntime as ort
from transformers import AutoTokenizer, CanineTokenizer

LABELS = {
    "text": ["HAM", "SPAM"],
    "url": ["benign", "phishing_bank", "phishing_ecommerce", "phishing_gaming", "phishing_gov", "phishing_other"],
}
CORPUS = {
    "text": ["Chào anh, chiều nay mình gặp nhau nhé.", "Bạn đã trúng thưởng 100 triệu đồng, nhấn vào link để nhận thưởng",
             "Ma OTP xac thuc GD la 066595, hieu luc 5 phut", "https://vietcombank-xacnhan.example/otp?x=1",
             "Tiếng Việt: café ca\u0301m ơn 😀𠀀", "Xin\nchào\t bạn\u00a0ơi\u0085test\u001ctest",
             "<s>Xin <mask> chào</s> <unk><pad>", "", "a", " a  b \r\n c ", "x " * 400],
    "url": ["google.com", "https://vnexpress.net", "vietcombank-xacnhan.tk", "facebook-login-verify.xyz",
            "https://ví-dụ.example/đăng-nhập?x=😀𠀀", "https://example.test/" + "a" * 256,
            "", "a", "x \ue003test", "https://example.test/a%2Fb?b=2&a=1"],
}


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def external_files(message):
    """Walk all protobuf fields, including subgraphs and tensor attributes."""
    if isinstance(message, onnx.TensorProto) and message.data_location == onnx.TensorProto.EXTERNAL:
        location = dict((item.key, item.value) for item in message.external_data).get("location")
        if not location:
            raise ValueError("Missing ONNX external-data location")
        yield location
    for field, value in message.ListFields():
        if field.type == field.TYPE_MESSAGE:
            for child in (value if field.is_repeated else [value]):
                yield from external_files(child)


def package(kind, onnx_path, tokenizer_path, output, version):
    onnx_path, tokenizer_path, output = Path(onnx_path).resolve(), Path(tokenizer_path).resolve(), Path(output).resolve()
    if output.exists():
        raise ValueError("Output already exists; use a new version directory")
    if not version or len(version) > 50 or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789._-" for c in version):
        raise ValueError("Invalid model version")
    tokenizer = (AutoTokenizer.from_pretrained(str(tokenizer_path), local_files_only=True, trust_remote_code=False, use_fast=False)
                 if kind == "text" else CanineTokenizer.from_pretrained(str(tokenizer_path), local_files_only=True))
    expected = "PhobertTokenizer" if kind == "text" else "CanineTokenizer"
    if type(tokenizer).__name__ != expected or tokenizer.padding_side != "right" or tokenizer.truncation_side != "right" or tokenizer.split_special_tokens:
        raise ValueError("Unsupported tokenizer configuration; do not approximate preprocessing")
    added = []
    for token_id, token in tokenizer.added_tokens_decoder.items():
        if token.single_word or not token.content:
            raise ValueError("Unsupported added-token configuration")
        added.append({"id": token_id, "content": token.content, "lstrip": token.lstrip, "rstrip": token.rstrip})
    maximum = 256 if kind == "text" else 128
    input_names = ["input_ids", "attention_mask"] + (["token_type_ids"] if kind == "url" else [])
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix=".artifact-", dir=output.parent) as temp:
        stage = Path(temp) / "artifact"
        stage.mkdir()
        model = onnx.load(str(onnx_path), load_external_data=False)
        shutil.copy2(onnx_path, stage / "model.onnx")
        for relative in set(external_files(model)):
            path = Path(relative)
            source = (onnx_path.parent / path).resolve()
            if path.is_absolute() or ".." in path.parts or not source.is_relative_to(onnx_path.parent):
                raise ValueError("Unsafe external-data path")
            (stage / path).parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, stage / path)
        tokenizer.save_pretrained(stage)
        write_json(stage / "metadata.json", {"modelName": "phobert-sms" if kind == "text" else "canine-url", "modelVersion": version,
                                             "tokenizer": "phobert" if kind == "text" else "canine", "referenceRuntime": ort.__version__,
                                             "referenceTransformers": "4.57.1", "source": f"vietluccuu/AI/{'sms' if kind == 'text' else 'url'}"})
        write_json(stage / "labels.json", {"labels": LABELS[kind], "benignClassIds": [0]})
        write_json(stage / "preprocessing.json", {"normalization": "identity", "maxLength": maximum, "padding": "max_length", "truncation": True})
        write_json(stage / "tokenizer-spec.json", {"clsId": tokenizer.cls_token_id, "sepId": tokenizer.sep_token_id,
                                                  "padId": tokenizer.pad_token_id, "addedTokens": added})
        options = ort.SessionOptions()
        options.intra_op_num_threads = 2
        session = ort.InferenceSession(str(stage / "model.onnx"), sess_options=options, providers=["CPUExecutionProvider"])
        if {item.name for item in session.get_inputs()} != set(input_names):
            raise ValueError("Unexpected ONNX input names")
        golden = []
        for text in CORPUS[kind]:
            encoded = tokenizer(text, max_length=maximum, padding="max_length", truncation=True, return_tensors="np")
            inputs = {name: np.asarray(encoded[name], dtype=np.int64) for name in input_names}
            logits = session.run(["logits"], inputs)[0].astype(np.float64)
            if logits.shape != (1, len(LABELS[kind])) or not np.isfinite(logits).all():
                raise ValueError("Invalid ONNX logits")
            probabilities = np.exp(logits[0] - logits[0].max())
            probabilities /= probabilities.sum()
            golden.append({"input": text, "inputs": {name: value[0].tolist() for name, value in inputs.items()},
                           "probabilities": probabilities.tolist(), "label": LABELS[kind][int(probabilities.argmax())]})
        write_json(stage / "parity.json", golden)
        write_json(stage / "sha256.json", {p.relative_to(stage).as_posix(): sha256(p) for p in sorted(stage.rglob("*")) if p.is_file()})
        os.rename(stage, output)
    print(f"Packaged {kind} {version}: {output}; {len(golden)} Python reference samples")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--kind", choices=["text", "url"], required=True)
    parser.add_argument("--onnx", type=Path, required=True)
    parser.add_argument("--tokenizer", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    package(args.kind, args.onnx, args.tokenizer, args.output, args.version)
