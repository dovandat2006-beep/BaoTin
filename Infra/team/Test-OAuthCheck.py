"""Synthetic receiver transport/privacy regression; not live provider proof."""
import contextlib
import http.client
import importlib.util
import io
import json
import sys
from pathlib import Path
import tempfile
import threading


def main():
    sys.dont_write_bytecode = True
    here = Path(__file__).resolve().parent
    spec = importlib.util.spec_from_file_location("oauth_receiver", here / "oauth-check-server.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    checks = 0
    with tempfile.TemporaryDirectory(prefix="baotin-oauth-receiver-") as temp:
        report = Path(temp) / "report.json"
        server = module.Receiver(here / "oauth-check.html", "https://fixture.example:9444",
                                 "https://fixture.example:9443", "synthetic-run-nonce", report)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        logs = io.StringIO()

        def request(method, path, body=None, extra=None):
            connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
            headers = {"Origin": server.origin, "X-Check-Nonce": server.nonce,
                       "Content-Type": "application/json"}
            headers.update(extra or {})
            connection.request(method, path, json.dumps(body) if body is not None else None, headers)
            response = connection.getresponse()
            data, status, returned_headers = response.read(), response.status, dict(response.getheaders())
            connection.close()
            return status, data, returned_headers

        try:
            with contextlib.redirect_stderr(logs), contextlib.redirect_stdout(logs):
                status, page, headers = request("GET", "/auth/callback?code=synthetic-sensitive-code")
                assert status == 200 and b"synthetic-sensitive-code" not in page
                assert headers["Cache-Control"] == "no-store" and headers["Referrer-Policy"] == "no-referrer"
                assert "sha256-" in headers["Content-Security-Policy"] and "unsafe-inline" not in headers["Content-Security-Policy"]
                assert b"__OAUTH_CHECK_CONFIG__" not in page
                checks += 4
                assert request("GET", "/health")[0] == 200
                assert request("GET", "/../../secrets")[0] == 404
                assert request("GET", "/health", extra={"Host":"evil.example"})[0] == 421
                assert request("POST", "/auth/callback", {})[0] == 404
                checks += 4
                result = {"provider":"GOOGLE", "passed":True, "phase":"complete", "exchange":200,
                          "wrongPkce":401, "wrongTransport":401, "replay":401, "profile":200,
                          "admin":403, "missingCsrf":403, "refresh":200, "logout":204, "revoked":401}
                assert request("POST", "/result", result, {"Origin":"https://evil.example"})[0] == 403
                assert request("POST", "/result", result, {"X-Check-Nonce":"wrong"})[0] == 403
                assert request("POST", "/result", result, {"Host":"evil.example"})[0] == 421
                checks += 3
                bad = result | {"accessToken":"synthetic-sensitive-token"}
                assert request("POST", "/result", bad)[0] == 400 and not report.exists()
                assert request("POST", "/result", result | {"admin":200})[0] == 400
                assert request("POST", "/result", result | {"phase":"sensitive-message"})[0] == 400
                assert request("POST", "/result", result | {"profile":True})[0] == 400
                assert request("POST", "/result", result, {"Content-Type":"text/plain"})[0] == 415
                assert request("POST", "/result", {"oversize":"x"*4096})[0] == 413
                checks += 6
                assert request("POST", "/result", result)[0] == 204
                assert json.loads(report.read_text())["GOOGLE"] == result
                assert "synthetic-sensitive" not in report.read_text()
                assert not logs.getvalue()
                checks += 4
        finally:
            server.shutdown()
            server.server_close()
            thread.join(timeout=3)
    print(f"OAUTH_RECEIVER_TESTS PASS checks={checks} host_origin_nonce=required arbitrary_fields=denied callback_query=not_logged reports=status_only")


if __name__ == "__main__":
    main()
