"""Ephemeral, loopback-only OAuth smoke receiver; never record request URLs/bodies."""
import argparse
import base64
import hashlib
import hmac
import json
import re
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlsplit


class Receiver(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, template, origin, api, nonce, report):
        super().__init__(("127.0.0.1", 0), Handler)
        self.origin, self.nonce, self.report = origin, nonce, Path(report)
        self.authorities = {urlsplit(origin).netloc, f"127.0.0.1:{self.server_port}"}
        html = Path(template).read_text(encoding="utf-8")
        config = json.dumps({"api": api, "origin": origin, "nonce": nonce}, separators=(",", ":"))
        self.page = html.replace("__OAUTH_CHECK_CONFIG__", config).encode()
        script = re.search(rb"<script>(.*?)</script>", self.page, re.S).group(1)
        digest = base64.b64encode(hashlib.sha256(script).digest()).decode()
        self.csp = f"default-src 'none'; script-src 'sha256-{digest}'; connect-src 'self' {api}; base-uri 'none'; form-action 'none'; frame-ancestors 'none'"
        self.results = {}


class Handler(BaseHTTPRequestHandler):
    # Base handler otherwise logs the full request URI, including the exchange ticket.
    def log_message(self, *_):
        pass

    def reply(self, status, data=b"", content_type="application/json"):
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Cache-Control", "no-store")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Content-Security-Policy", self.server.csp)
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.headers.get("Host") not in self.server.authorities:
            return self.reply(421)
        path = urlsplit(self.path).path
        if path in ("/", "/auth/callback"):
            return self.reply(200, self.server.page, "text/html; charset=utf-8")
        if path == "/health" and not urlsplit(self.path).query:
            return self.reply(200, b'{"status":"UP"}')
        self.reply(404)

    def do_POST(self):
        if self.path != "/result":
            return self.reply(404)
        if self.headers.get("Host") not in self.server.authorities:
            return self.reply(421)
        if self.headers.get("Origin") != self.server.origin:
            return self.reply(403)
        if not hmac.compare_digest(self.headers.get("X-Check-Nonce", ""), self.server.nonce):
            return self.reply(403)
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if not 0 < size <= 2048 or self.headers.get("Transfer-Encoding"):
                return self.reply(413)
            if self.headers.get("Content-Type") != "application/json":
                return self.reply(415)
            result = json.loads(self.rfile.read(size))
            # Accept only bounded, non-sensitive status fields, never arbitrary errors/PII.
            expected = {"provider", "passed", "phase", "exchange", "wrongPkce", "wrongTransport",
                        "replay", "profile", "admin", "missingCsrf", "refresh", "logout", "revoked"}
            if set(result) != expected or result["provider"] not in ("GOOGLE", "FACEBOOK"):
                return self.reply(400)
            if type(result["passed"]) is not bool or result["phase"] not in (
                "complete", "denied", "exchange", "onboarding", "mfa", "session", "transport"):
                return self.reply(400)
            if any(type(result[k]) is not int or not 0 <= result[k] <= 599
                   for k in expected - {"provider", "passed", "phase"}):
                return self.reply(400)
            success = {"exchange": 200, "wrongPkce": 401, "wrongTransport": 401, "replay": 401,
                       "profile": 200, "admin": 403, "missingCsrf": 403, "refresh": 200,
                       "logout": 204, "revoked": 401}
            if result["passed"] and (result["phase"] != "complete" or
                                     any(result[k] != v for k, v in success.items())):
                return self.reply(400)
            self.server.results[result["provider"]] = result
            # Only sanitized status results are persisted, in an operator ACL-restricted dir.
            temporary = self.server.report.with_suffix(".tmp")
            temporary.write_text(json.dumps(self.server.results, indent=2), encoding="utf-8")
            temporary.replace(self.server.report)
            self.reply(204)
        except (ValueError, TypeError, KeyError):
            self.reply(400)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True)
    args = parser.parse_args()
    config = json.loads(Path(args.config).read_text(encoding="utf-8-sig"))
    server = Receiver(**{k: config[k] for k in ("template", "origin", "api", "nonce", "report")})
    Path(config["portFile"]).write_text(str(server.server_port), encoding="ascii")
    server.serve_forever()
