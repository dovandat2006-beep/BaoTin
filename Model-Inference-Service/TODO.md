# Model Inference delivery

- [x] Internal text/url API and sms compatibility alias; Contracts v1.
- [x] Separate service credential, input/body limits, no public routes.
- [x] ONNX sessions load once and warm up with parity samples.
- [x] PhoBERT/CANINE single-sequence tokenizer, labels and preprocessing matching AI export scripts.
- [x] Manifest covers ONNX external weights, tokenizer, metadata and Python golden reference.
- [x] Exact input tensor equality; labels equal; probabilities within 1e-4.
- [x] Model version/checksum, bounded concurrency, native deadline, latency counters.
- [x] Missing/invalid model state and independent liveness/readiness.
- [x] Native fixture, malformed artifact, HTTP/auth and missing-model tests.
- [x] Glibc-compatible pinned Docker runtime.
- [ ] Supply actual trained exports; repository contains training/export code only.
- [ ] Run artifact packaging and parity acceptance with both trained models.
- [ ] Benchmark p50/p95/CPU/RAM on deployment host with actual weights before resource sizing.
