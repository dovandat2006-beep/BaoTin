// Direct static-web runtime: loopback only, no request logging or directory listing.
const http = require('node:http');
const fs = require('node:fs');
const path = require('node:path');
const {randomUUID} = require('node:crypto');
const root = fs.realpathSync(process.argv[2]);
const port = Number(process.argv[3]);
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('Invalid web port');
let publicWeb;
try {
  const value = process.env.BAOTIN_WEB_PUBLIC_ORIGIN || 'https://baotin.rimiprod.me';
  publicWeb = new URL(value);
  if (publicWeb.protocol !== 'https:' || publicWeb.origin !== value || publicWeb.username || publicWeb.password) throw new Error();
} catch (_) {throw new Error('Web public origin must be an exact HTTPS origin');}
function originalScheme(req) {
  try {
    // The visitor scheme survives an inner proxy rewriting its own hop's proto.
    const scheme = JSON.parse(req.headers['cf-visitor'] || '{}').scheme;
    if (scheme === 'http' || scheme === 'https') return scheme;
  } catch (_) { /* Unrecognized metadata never selects a redirect destination. */ }
  const forwarded = req.headers['x-forwarded-proto'];
  if (forwarded === 'http' || forwarded === 'https') return forwarded;
  return null;
}
const types = {'.html':'text/html; charset=utf-8','.js':'text/javascript; charset=utf-8',
  '.css':'text/css; charset=utf-8','.json':'application/json','.png':'image/png',
  '.jpg':'image/jpeg','.svg':'image/svg+xml','.ttf':'font/ttf','.woff2':'font/woff2','.ico':'image/x-icon',
  '.apk':'application/vnd.android.package-archive'};
http.createServer((req, res) => {
  res.setHeader('X-Content-Type-Options','nosniff');
  res.setHeader('Referrer-Policy','no-referrer');
  res.setHeader('Cache-Control','no-store');
  const fail = status => {res.writeHead(status, {'Content-Type':'application/problem+json'});
    res.end(JSON.stringify({title:'Request rejected', status, correlationId:randomUUID()}));};
  const host = (req.headers.host || '').toLowerCase();
  const publicHost = host === publicWeb.host || (!publicWeb.port && host === publicWeb.host + ':80');
  // This server binds loopback only. The existing edge/tunnel supplies the original
  // scheme; direct loopback health/dev hosts and unrelated hosts stay unchanged.
  if (publicHost && originalScheme(req) === 'http') {
    let target;
    try {target = new URL(req.url, 'http://localhost');} catch (_) {return fail(400);}
    res.writeHead(308, {Location:publicWeb.origin + target.pathname + target.search});
    return res.end();
  }
  if (publicHost && originalScheme(req) === 'https') res.setHeader('Strict-Transport-Security','max-age=31536000');
  if (!['GET','HEAD'].includes(req.method)) return fail(405);
  let uri;
  try {uri = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);} catch (_) {return fail(400);}
  if (uri === '/healthz') {res.writeHead(200, {'Content-Type':'application/json'});return res.end(req.method === 'HEAD' ? '' : '{"status":"UP"}');}
  if (/^\/(internal|actuator)(\/|$)/.test(uri)) return fail(404);
  if (uri.split('/').some(s => s.startsWith('.') || s.includes('\\')) || uri.includes('\0')) return fail(403);
  let file = path.join(root, uri === '/' ? 'index.html' : uri);
  if (!fs.existsSync(file)) {
    if (path.extname(uri)) return fail(404);
    file = path.join(root, 'index.html');
  }
  try {
    file = fs.realpathSync(file);
    if (!file.startsWith(root + path.sep) || !fs.statSync(file).isFile()) return fail(403);
    res.setHeader('Content-Type',types[path.extname(file)] || 'application/octet-stream');
    res.setHeader('Content-Length',fs.statSync(file).size);
    if (uri === '/downloads/android.json') res.setHeader('Cache-Control','no-store');
    else if (path.extname(file) === '.apk') {
      res.setHeader('Content-Disposition',`attachment; filename="${path.basename(file)}"`);
      res.setHeader('Cache-Control','public, max-age=31536000, immutable');
    } else if (path.extname(file) !== '.html') res.setHeader('Cache-Control','public, max-age=3600');
    if (req.method === 'HEAD') return res.end();
    fs.createReadStream(file).on('error',() => res.destroy()).pipe(res);
  } catch (_) {fail(404);}
}).listen(port,'127.0.0.1',() => console.log('BAOTIN_WEB_READY loopback=true logs=requests_disabled'));
