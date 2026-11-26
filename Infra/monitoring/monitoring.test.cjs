const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const http=require('node:http');
const root=__dirname;
test('dashboard uses the provisioned datasource and distinguishes missing data',()=>{
  const d=JSON.parse(fs.readFileSync(path.join(root,'dashboards/baotin-overview.json')));
  assert.equal(d.uid,'baotin-overview');
  assert.equal(d.refresh,'30s');
  assert.equal(d.panels.length,9);
  for(const p of d.panels){
    assert.ok(p.title && p.description && p.fieldConfig.defaults.unit);
    assert.equal(p.datasource.uid,'baotin-prometheus');
    for(const q of p.targets)assert.ok(!q.expr.includes('or vector(1)'));
  }
});
test('launch uses loopback, bounded retention and external secret file',()=>{
  const s=fs.readFileSync(path.join(root,'Start-Monitoring.ps1'),'utf8');
  for(const flag of ['--web.listen-address=127.0.0.1:9090','--web.listen-address=127.0.0.1:9115','--storage.tsdb.retention.size=256MB']) assert.ok(s.includes(flag));
  assert.ok(s.includes("'$__file{'"));
  assert.ok(!s.includes('--web.enable-admin-api'));
  assert.ok(!s.includes('--web.enable-lifecycle'));
  const ini=fs.readFileSync(path.join(root,'grafana.ini'),'utf8');
  assert.ok(ini.includes('http_addr = 127.0.0.1'));
  assert.ok(ini.includes('allow_sign_up = false'));
  assert.ok(ini.includes('root_url = https://prometheus.rimiprod.me/'));
  assert.ok(ini.includes('cookie_secure = true'));
  assert.match(ini,/\[metrics\][\s\S]*?enabled = false/);
  const stop=fs.readFileSync(path.join(root,'Stop-Monitoring.ps1'),'utf8');
  assert.ok(stop.includes('startTicks') && stop.includes('$p.Path -ne $entry.path'));
  const hidden=fs.readFileSync(path.join(root,'HiddenProcess.ps1'),'utf8');
  assert.ok(hidden.includes('flags=0x101,show=0'));
  assert.ok(hidden.includes('0x10|0x200|0x400|0x80000|0x1000000'));
  assert.ok(!hidden.includes('0x8|0x200|0x400'));
  assert.ok(ini.includes('disable_plugins = cloudwatch,'));
});
test('live blackbox rejects HTTP 200 DOWN, malformed health, and HTTP 503', {skip:!process.env.BAOTIN_MONITORING_LIVE}, async()=>{
  let status=200,body='{"status":"UP"}';
  const server=http.createServer((req,res)=>{res.writeHead(status,{'Content-Type':'application/json'});res.end(body);});
  await new Promise(r=>server.listen(0,'127.0.0.1',r));
  try{
    const target=`http://127.0.0.1:${server.address().port}/health`;
    const cases=[
      [200,'{"status":"UP"}',1],
      [200,'{"components":{},"status":"UP"}',1],
      [200,'{"status":"DOWN"}',0],
      [200,'{"status":"DOWN","components":{"fixture":{"status":"UP"}}}',0],
      [200,'{"status":true}',0],
      [200,'{"status":"UP"',0],
      [200,'{}',0],
      [503,'{"status":"UP"}',0],
    ];
    for(const [code,text,expected] of cases){
      status=code;body=text;
      const response=await fetch('http://127.0.0.1:9115/probe?module=readiness&target='+encodeURIComponent(target),{signal:AbortSignal.timeout(10000)});
      assert.equal(response.status,200);
      assert.match(await response.text(),new RegExp(`^probe_success ${expected}$`,'m'));
    }
    status=200;body='<!doctype html><html><body>Fixture</body></html>';
    const nonTls=await fetch('http://127.0.0.1:9115/probe?module=website&target='+encodeURIComponent(target));
    assert.match(await nonTls.text(),/^probe_success 0$/m);
  }finally{await new Promise(r=>server.close(r));}
});
