"""Synthetic Jenkins artifact transport; real JAR/runtime/DB tests run outside it."""
import argparse
import base64
import hmac
import hashlib
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass

    def do_GET(self):
        cfg = self.server.config
        if self.path == '/login':
            self.send_response(200)
            self.send_header('X-Jenkins', '2.568.3')
            self.end_headers()
            return
        expected = 'Basic ' + base64.b64encode(('fixture-user:' + cfg['token']).encode()).decode()
        if not hmac.compare_digest(self.headers.get('Authorization', ''), expected):
            self.send_error(401)
            return
        if self.path == '/whoAmI/api/json':
            data = b'{"authenticated":true,"name":"fixture-user"}'
        elif self.path.startswith('/job/baotin/job/'):
            parts = self.path.split('/')
            if len(parts) < 7 or parts[4] not in cfg['artifacts'] or parts[5] not in ('1', '2', '3', '4'):
                self.send_error(404)
                return
            if parts[6].startswith('api'):
                data = b'{"result":"SUCCESS","building":false}'
            elif '/artifact/' in self.path:
                file = Path(cfg['artifacts'][parts[4]])
                invalid = (parts[5] == '3' and parts[4] == 'Auth-Service') or (parts[5] == '4' and parts[4] == 'API-Gateway')
                if self.path.endswith('/artifact/build/release-evidence.json'):
                    if invalid:
                        digest = hashlib.sha256(b'invalid Java archive').hexdigest()
                    else:
                        with file.open('rb') as stream:
                            digest = hashlib.file_digest(stream, 'sha256').hexdigest()
                    data = json.dumps({'jarSha256': digest}).encode()
                elif invalid:
                    data = b'invalid Java archive'
                else:
                    self.send_response(200)
                    self.send_header('Content-Length', str(file.stat().st_size))
                    self.end_headers()
                    with file.open('rb') as stream:
                        while chunk := stream.read(65536):
                            self.wfile.write(chunk)
                    return
            else:
                self.send_error(404)
                return
        else:
            self.send_error(404)
            return
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('config')
    args = parser.parse_args()
    cfg = json.loads(Path(args.config).read_text(encoding='utf-8-sig'))
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.config = cfg
    Path(cfg['portFile']).write_text(str(server.server_port), encoding='ascii')
    server.serve_forever()
