const {test,before,after}=require('node:test');
const assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),http=require('node:http'),net=require('node:net'),{spawn}=require('node:child_process');
const script=process.env.BAOTIN_WEB_SERVER_UNDER_TEST||path.join(__dirname,'web-server.cjs');
let root,server;
async function launch(origin){
 const probe=net.createServer();await new Promise(r=>probe.listen(0,'127.0.0.1',r));const port=probe.address().port;await new Promise(r=>probe.close(r));
 const child=spawn(process.execPath,[script,root,String(port)],{windowsHide:true,env:{...process.env,BAOTIN_WEB_PUBLIC_ORIGIN:origin},stdio:['ignore','pipe','pipe']});
 await new Promise((resolve,reject)=>{const timer=setTimeout(()=>reject(Error('Owned server startup timeout')),10000);child.stdout.on('data',data=>{if(String(data).includes('BAOTIN_WEB_READY')){clearTimeout(timer);resolve();}});child.once('exit',()=>{clearTimeout(timer);reject(Error('Owned server exited'));});});
 return {child,port};
}
function request(target,url='/',headers={},method='GET'){
 return new Promise((resolve,reject)=>{const req=http.request({hostname:'127.0.0.1',port:target.port,path:url,method,headers},r=>{const chunks=[];r.on('data',c=>chunks.push(c));r.on('end',()=>resolve({status:r.statusCode,headers:r.headers,body:Buffer.concat(chunks).toString()}));});req.on('error',reject);req.end();});
}
before(async()=>{root=fs.mkdtempSync(path.join(os.tmpdir(),'baotin-https-entry-'));fs.writeFileSync(path.join(root,'index.html'),'<!doctype html><title>fixture</title>');server=await launch('https://baotin.rimiprod.me');});
after(async()=>{if(server&&server.child.exitCode===null){server.child.kill();await new Promise(r=>server.child.once('exit',r));}if(root)fs.rmSync(root,{recursive:true});});
test('known public HTTP entry redirects to the exact HTTPS origin and preserves path/query',async()=>{
 const r=await request(server,'/auth/callback?code=fixture',{Host:'baotin.rimiprod.me','X-Forwarded-Proto':'http'});
 assert.equal(r.status,308);assert.equal(r.headers.location,'https://baotin.rimiprod.me/auth/callback?code=fixture');assert.equal(r.body,'');assert.equal(r.headers['referrer-policy'],'no-referrer');assert.equal(r.headers['cache-control'],'no-store');
});
test('HTTPS public entry remains usable and sets host-only HSTS',async()=>{
 const r=await request(server,'/',{Host:'baotin.rimiprod.me','X-Forwarded-Proto':'https'});assert.equal(r.status,200);assert.equal(r.headers.location,undefined);assert.equal(r.headers['strict-transport-security'],'max-age=31536000');
});
test('loopback health is never redirected or given a public HSTS policy',async()=>{
 const r=await request(server,'/healthz',{'X-Forwarded-Proto':'http'});assert.equal(r.status,200);assert.equal(JSON.parse(r.body).status,'UP');assert.equal(r.headers.location,undefined);assert.equal(r.headers['strict-transport-security'],undefined);
});
test('unrelated hosts cannot select a redirect destination',async()=>{
 const r=await request(server,'/',{Host:'evil.example','X-Forwarded-Proto':'http'});assert.equal(r.status,200);assert.equal(r.headers.location,undefined);assert.equal(r.headers['strict-transport-security'],undefined);
});
test('absolute and protocol-relative targets never redirect off the configured origin',async()=>{
 for(const url of ['//evil.example/path?q=fixture','http://evil.example/path?q=fixture']){const r=await request(server,url,{Host:'baotin.rimiprod.me','X-Forwarded-Proto':'http'});assert.equal(r.status,308);assert.equal(r.headers.location,'https://baotin.rimiprod.me/path?q=fixture');}
});
test('malformed redirect targets fail with bounded Problem Details and no reflection',async()=>{
 const r=await request(server,'http://[',{Host:'baotin.rimiprod.me','X-Forwarded-Proto':'http'});assert.equal(r.status,400);assert.match(r.headers['content-type'],/application\/problem\+json/);const body=JSON.parse(r.body);assert.equal(body.status,400);assert.ok(!r.body.includes('http://['));
});
test('HTTP HEAD and explicit default HTTP port keep the same canonical target',async()=>{
 const r=await request(server,'/?entry=fixture',{Host:'BAOTIN.RIMIPROD.ME:80','X-Forwarded-Proto':'http'},'HEAD');assert.equal(r.status,308);assert.equal(r.headers.location,'https://baotin.rimiprod.me/?entry=fixture');assert.equal(r.body,'');
});
test('Cloudflare visitor metadata handles a missing forwarded-proto header',async()=>{
 const r=await request(server,'/',{Host:'baotin.rimiprod.me','CF-Visitor':'{"scheme":"http"}'});assert.equal(r.status,308);assert.equal(r.headers.location,'https://baotin.rimiprod.me/');
});
test('an HTTPS visitor is not redirected when an inner hop reports HTTP',async()=>{
 const r=await request(server,'/',{Host:'baotin.rimiprod.me','CF-Visitor':'{"scheme":"https"}','X-Forwarded-Proto':'http'});assert.equal(r.status,200);assert.equal(r.headers.location,undefined);
});
test('an HTTP visitor redirects even when an inner hop reports HTTPS',async()=>{
 const r=await request(server,'/',{Host:'baotin.rimiprod.me','CF-Visitor':'{"scheme":"http"}','X-Forwarded-Proto':'https'});assert.equal(r.status,308);assert.equal(r.headers.location,'https://baotin.rimiprod.me/');
});
test('malformed proxy metadata cannot crash the server or choose another host',async()=>{
 const r=await request(server,'/',{Host:'baotin.rimiprod.me','CF-Visitor':'not-json'});assert.equal(r.status,200);assert.equal(r.headers.location,undefined);
});
test('HTTPS private paths still fail closed instead of becoming SPA HTML',async()=>{
 for(const url of ['/internal/v1/models','/actuator/env']){const r=await request(server,url,{Host:'baotin.rimiprod.me','X-Forwarded-Proto':'https'});assert.equal(r.status,404);assert.match(r.headers['content-type'],/application\/problem\+json/);}
});
test('a reviewed alternative HTTPS web origin is independently configurable',async()=>{
 const other=await launch('https://app.example');try{const r=await request(other,'/',{Host:'app.example','X-Forwarded-Proto':'http'});assert.equal(r.status,308);assert.equal(r.headers.location,'https://app.example/');}finally{other.child.kill();await new Promise(r=>other.child.once('exit',r));}
});
test('invalid public origins fail before listening without echoing configured values',async()=>{
 for(const origin of ['http://app.example','https://user:pass@app.example','https://app.example/','https://app.example/path','https://app.example?next=other','https://app.example#secret','https://bảo-tin.example','not-a-uri']){
  const probe=net.createServer();await new Promise(r=>probe.listen(0,'127.0.0.1',r));const port=probe.address().port;await new Promise(r=>probe.close(r));
  const child=spawn(process.execPath,[script,root,String(port)],{windowsHide:true,env:{...process.env,BAOTIN_WEB_PUBLIC_ORIGIN:origin},stdio:['ignore','pipe','pipe']});let errors='',ready=false;
  child.stderr.on('data',data=>{errors+=data;});child.stdout.on('data',data=>{if(String(data).includes('BAOTIN_WEB_READY')){ready=true;child.kill();}});
  await new Promise(resolve=>{const timer=setTimeout(()=>child.kill(),2000);child.once('exit',()=>{clearTimeout(timer);resolve();});});
  assert.equal(ready,false);assert.match(errors,/Web public origin must be an exact HTTPS origin/);assert.ok(!errors.includes(origin));
 }
});
test('static APK attachment has correct MIME/cache/hash bytes, manifest stays fresh and missing APK is404',async()=>{
 const downloads=path.join(root,'downloads');fs.mkdirSync(downloads,{recursive:true});
 const name='baotin-'+('a'.repeat(64))+'.apk',bytes=Buffer.from('PK\x03\x04http-transport-test-not-installable');fs.writeFileSync(path.join(downloads,name),bytes);fs.writeFileSync(path.join(downloads,'android.json'),'{}');
 const base=`http://127.0.0.1:${server.port}`,apk=await fetch(base+'/downloads/'+name);
 assert.equal(apk.status,200);assert.equal(apk.headers.get('content-type'),'application/vnd.android.package-archive');assert(apk.headers.get('content-disposition').startsWith('attachment;'));assert(apk.headers.get('cache-control').includes('immutable'));assert(Buffer.from(await apk.arrayBuffer()).equals(bytes));
 const manifest=await fetch(base+'/downloads/android.json');assert.equal(manifest.headers.get('cache-control'),'no-store');assert.equal((await fetch(base+'/downloads/missing.apk')).status,404);assert.equal((await fetch(base+'/downloads/'+name,{method:'HEAD'})).headers.get('content-length'),String(bytes.length));
});
