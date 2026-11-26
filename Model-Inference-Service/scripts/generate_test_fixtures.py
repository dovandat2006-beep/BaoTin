#!/usr/bin/env python3
"""Rebuild test-only, input-dependent ONNX graphs. NEVER use these as production weights.
Defaults to a small synthetic BPE vocabulary. --phobert-dir can separately verify
the real saved training vocabulary without redistributing upstream model assets.
"""
import argparse
from pathlib import Path
import tempfile
import numpy as np
import onnx
from onnx import helper, TensorProto, numpy_helper
from transformers import PhobertTokenizer, CanineTokenizer
from package_artifact import package


def generate(phobert_dir, output):
    with tempfile.TemporaryDirectory() as temp:
        root = Path(temp)
        if phobert_dir is None:
            phobert_dir = root / "synthetic-tokenizer"
            phobert_dir.mkdir()
            words = ["Xin", "chào", "bạn", "Tiếng", "Việt", "test", "https", "café", "trúng", "thưởng", "triệu", "đồng"]
            vocabulary = set()
            for char in "".join(words) + "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789😀𠀀":
                vocabulary.update([char, char + "@@"])
            codes = []
            for word in words:
                pieces = list(word[:-1]) + [word[-1] + "</w>"]
                while len(pieces) > 1:
                    pair = (pieces[0], pieces[1])
                    if pair not in codes: codes.append(pair)
                    pieces = [pieces[0] + pieces[1]] + pieces[2:]
                    vocabulary.add(pieces[0][:-4] if pieces[0].endswith("</w>") else pieces[0] + "@@")
                vocabulary.add(word)
            (phobert_dir / "vocab.txt").write_text("".join(word + " 1\n" for word in sorted(vocabulary)), encoding="utf-8")
            (phobert_dir / "bpe.codes").write_text("".join(a + " " + b + " 1\n" for a, b in codes), encoding="utf-8")
        for kind, count, length in [("text", 2, 256), ("url", 6, 128)]:
            stage = root / kind
            stage.mkdir()
            tokenizer = PhobertTokenizer(vocab_file=str(phobert_dir / "vocab.txt"), merges_file=str(phobert_dir / "bpe.codes")) if kind == "text" else CanineTokenizer()
            tokenizer.save_pretrained(stage)
            names = ["input_ids", "attention_mask"] + (["token_type_ids"] if kind == "url" else [])
            nodes = [helper.make_node("Cast", ["input_ids"], ["ids"], to=TensorProto.FLOAT),
                     helper.make_node("ReduceMean", ["ids"], ["mean"], axes=[1], keepdims=1),
                     helper.make_node("MatMul", ["mean", "weights"], ["logits"])]
            weights = np.linspace(-0.0001, 0.0001, count, dtype=np.float32).reshape(1, count)
            graph = helper.make_graph(nodes, "TEST_ONLY_INPUT_DEPENDENT", [helper.make_tensor_value_info(name, TensorProto.INT64, [1, length]) for name in names],
                                      [helper.make_tensor_value_info("logits", TensorProto.FLOAT, [1, count])], [numpy_helper.from_array(weights, "weights")])
            model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 14)], ir_version=10)
            onnx.save_model(model, stage / "fixture.onnx", save_as_external_data=(kind == "url"), all_tensors_to_one_file=True,
                            location="fixture.weights", size_threshold=0)
            package(kind, stage / "fixture.onnx", stage, output / kind, "TEST-FIXTURE")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--phobert-dir", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    generate(args.phobert_dir, args.output)
