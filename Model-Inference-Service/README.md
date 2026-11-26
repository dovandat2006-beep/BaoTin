# Model Inference Service

Spring Boot 4 / Java 21 + ONNX Runtime Java. Internal-only SMS (PhoBERT) and URL (CANINE) inference, following the training/export scripts in `vietluccuu/AI`.

## Current delivery

Implemented: load-once native sessions; checksum validation of every artifact file including external ONNX weights; exact single-sequence tokenizer; Python tensor/label/probability parity gate at startup; warm-up; bounded concurrency; native execution deadline; versioned probabilities; internal bearer authorization; input size limits; correlation IDs; latency metrics; independent liveness/readiness. No database.

The repository does **not** contain trained weights. Without mounted artifacts, the application starts, liveness is UP, readiness is DOWN, `/models` reports MISSING, and inference returns 503. This is intentional. It never returns fake classifications. Native ONNX, Python parity and HTTP/security integration tests are included. Fixture graphs and synthetic vocabularies live only in test resources; they are input-dependent and explicitly versioned TEST-FIXTURE. They are never mounted in normal deployments.

## Package existing AI exports

Use the tokenizer saved beside the ONNX by the AI export scripts. Python tooling never downloads models or trains replacements.

```sh
python3 -m venv .venv
.venv/bin/pip install -r scripts/requirements.txt
.venv/bin/python scripts/package_artifact.py --kind text \
  --onnx ../AI/sms/output/onnx/phobert_sms_spam.onnx \
  --tokenizer ../AI/sms/output/onnx --version 1.0.0 --output /models/text-1.0.0
.venv/bin/python scripts/package_artifact.py --kind url \
  --onnx ../AI/url/output/onnx/canine_url_phishing.onnx \
  --tokenizer ../AI/url/output/onnx --version 1.0.0 --output /models/url-1.0.0
```

Set `MODEL_TEXT_PATH` and `MODEL_URL_PATH` to those directories. Set `MODEL_SERVICE_TOKEN` to a random value of at least 32 characters, also configured in Threat-Service. Mount artifacts read-only. Restart to change versions; sessions are never loaded per request. Tokenizer differences, malformed artifacts and probability drift fail closed. Do not put private messages in parity corpora.

## Run / API

```sh
sh ./gradlew test bootJar
# Supply environment from an external secret store / shell. Do not commit tokens.
sh ./gradlew bootRun
curl http://localhost:8080/actuator/health/liveness
curl http://localhost:8080/actuator/health/readiness
```

Authenticated internal endpoints: `GET /internal/v1/models`, `POST /internal/v1/inference/text` (`{"content":"..."}`), `POST /internal/v1/inference/url` (`{"url":"..."}`). `/inference/sms` aliases text. Send `Authorization: Bearer <MODEL_SERVICE_TOKEN>`. Requests are limited to 64 KiB, text to 10000 characters and URL to 4096.

Responses contain modelName, modelVersion, modelChecksum, label, confidence, riskProbability, per-class probabilities and latencyMs. **Confidence is not risk**: riskProbability sums non-benign classes. 401 = invalid credential, 400 = invalid input, 413 = oversized body, 429 = model busy, 503 = unavailable/failed model. No public route or CORS. Contract: `../Contracts/openapi/model-inference-service.json`.

Runtime settings are in `.env.example`. Keep inference on a private network; use TLS for cross-host traffic. Liveness never depends on external services. Readiness requires both verified, warmed models and a configured service credential. Logs never contain raw input, paths or exception bodies. Metrics: `baotin_model_requests_total`, `baotin_model_latency_seconds`.

The existing Jenkins release/verify lanes remain separate; tests run locally and in the PR verify lane. Deploy only reviewed, pinned images. Roll back by restoring the prior image and immutable artifact directories together.

## Verification and tokenizer provenance

`sh ./gradlew test bootJar` exercises native ONNX execution, input-dependent outputs, exact Python-generated tensor/probability parity, checksum/path safety, authenticated endpoints, validation, and missing-model readiness/fallback semantics. `scripts/generate_test_fixtures.py --output <new-directory>` reproduces test-only graphs with a synthetic BPE vocabulary; supply `--phobert-dir <saved-training-tokenizer>` to verify the full real vocabulary separately. Tool dependencies are in `scripts/requirements.txt`.

PhoBERT BPE follows Hugging Face transformers v4.57.1 `tokenization_phobert.py` (VinAI/Hugging Face, Apache-2.0). CANINE follows its Unicode codepoint and special-token semantics. Every production artifact is independently checked against the Python tokenizer; unsupported settings are rejected rather than approximated.

The runtime Docker stage uses pinned Ubuntu/glibc (not musl), because ONNX Runtime native libraries require glibc; `libgomp1` is installed. The builder can remain Alpine because it only compiles/packages Java.

Still requires deployment inputs: both trained ONNX exports with external weights and saved tokenizers. Real-model accuracy/parity and CPU/RAM/p50/p95 benchmarks cannot be asserted without those files. No model or production-accuracy claims are inferred from the synthetic tests.

`modelChecksum` is SHA-256 of the verified `sha256.json` manifest, binding the graph, external weights, tokenizer/preprocessing and reference corpus as one artifact identity. Hashing only model.onnx would miss external-weight-only changes.
