"""Synthetic HTTP fixture only; never validates actual Jenkins declarative semantics."""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import base64
import json
import sys
from urllib.parse import parse_qs, urlparse

token = Path(sys.argv[1]).read_text().strip()
expected = "Basic " + base64.b64encode(("fixture-user:" + token).encode()).decode()
mode = "success"
stats = {"validated": 0, "credentialValid": True, "tokenInPipeline": False, "crumbValid": True,
         "credentialsToUnidentifiedService": False}


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def reply(self, status, body, content="application/json", location=None):
        self.send_response(status)
        self.send_header("Content-Type", content)
        if location:
            self.send_header("Location", location)
        self.end_headers()
        self.wfile.write(body.encode())

    def do_GET(self):
        global mode
        parsed = urlparse(self.path)
        if parsed.path == "/fixture-mode":
            mode = parse_qs(parsed.query)["value"][0]
            self.reply(200, "{}")
            return
        if parsed.path == "/fixture-stats":
            self.reply(200, json.dumps(stats))
            return
        if mode == "not-jenkins" and self.headers.get("Authorization"):
            stats["credentialsToUnidentifiedService"] = True
        if parsed.path == "/login":
            self.send_response(200)
            if mode != "not-jenkins":
                self.send_header("X-Jenkins", "2.568.3")
            self.end_headers()
            self.wfile.write(b"fixture login")
            return
        if self.headers.get("Authorization") != expected or mode == "unauthorized":
            stats["credentialValid"] = False
            self.reply(401, token)  # Deliberate secret-bearing diagnostic must be withheld.
            return
        if mode == "redirect":
            self.reply(302, "", location="http://example.test/")
        elif parsed.path == "/whoAmI/api/json":
            name = "wrong-user" if mode == "wrong-identity" else "fixture-user"
            self.reply(200, json.dumps({"authenticated": True, "name": name}))
        elif parsed.path == "/crumbIssuer/api/json":
            if mode == "no-crumb":
                self.reply(404, "{}")
            else:
                self.reply(200, json.dumps({"crumbRequestField": "Jenkins-Crumb", "crumb": "fixture-crumb"}))
        else:
            self.reply(404, "{}")

    def do_POST(self):
        if self.headers.get("Authorization") != expected:
            self.reply(401, token)
            return
        source = parse_qs(self.rfile.read(int(self.headers.get("Content-Length", "0"))).decode()).get("jenkinsfile", [""])[0]
        stats["tokenInPipeline"] |= token in source
        if mode != "no-crumb" and self.headers.get("Jenkins-Crumb") != "fixture-crumb":
            stats["crumbValid"] = False
            self.reply(403, token)
            return
        if mode == "invalid" or "BROKEN_FIXTURE_PIPELINE" in source:
            self.reply(200, "Errors: " + token, "text/plain")
        else:
            stats["validated"] += 1
            self.reply(200, "Jenkinsfile successfully validated.", "text/plain")


server = HTTPServer(("127.0.0.1", 0), Handler)
Path(sys.argv[2]).write_text(str(server.server_port))
server.serve_forever()
