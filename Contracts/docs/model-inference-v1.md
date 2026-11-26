# Model inference v1

Internal API: `openapi/model-inference-service.json`. The Gateway must never expose `/internal/**` or proxy user tokens to inference. Threat-Service uses its own `MODEL_SERVICE_TOKEN` over the private network (TLS when crossing hosts).

Input is the exact training string, without lowercasing, word segmentation, Unicode normalization or URL canonicalization inside inference. PhoBERT pads/truncates right to 256 tokens; CANINE to 128 Unicode codepoints including special tokens. `/inference/sms` aliases `/inference/text`.

SMS class order: `HAM`, `SPAM`. URL class order: `benign`, `phishing_bank`, `phishing_ecommerce`, `phishing_gaming`, `phishing_gov`, `phishing_other`. These map directly to `vietluccuu/AI` training IDs 0..N. The benign class is ID 0. `confidence` is the winning probability; `riskProbability` sums all non-benign probabilities. A confident benign prediction must not become high risk.

Artifacts are immutable directories with `model.onnx`, any external weights, tokenizer files, `tokenizer-spec.json`, `metadata.json`, `labels.json`, `preprocessing.json`, `parity.json` and `sha256.json`. The manifest hashes every other file. Python packaging uses the real ONNX and saved training tokenizer; Java must pass exact tensor equality plus label equality and probability tolerance 1e-4 before marking a model READY. Missing weights are never replaced with generated or pretrained weights.

A model failure returns 503; capacity exhaustion returns 429. Threat-Service treats both as unavailable AI and explicitly returns fallback state. `/models` describes each slot; readiness requires both, liveness does not. Both services redact inputs from logs/errors. Response `X-Correlation-Id` is a bounded validated identifier.

Compatibility: all inference routes are new; `sms` remains an alias for integrations using the earlier service TODO. Additional classes or preprocessing need a new artifact version and new parity corpus.

`modelChecksum` is SHA-256 of the verified manifest file, so its identity binds external weights and preprocessing as well as the ONNX graph. It is not a graph-only checksum.
