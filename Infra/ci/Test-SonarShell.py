"""Execute old/new shell gates with synthetic Gradle/curl, never real credentials."""
import argparse, json, os, pathlib, subprocess, tempfile
p=argparse.ArgumentParser();p.add_argument('--bash',default='bash');a=p.parse_args()
root=pathlib.Path(__file__).resolve().parents[2]
before=root/'.changes/quality-deploy-20261013/before/Infra/ci/templates/sonar-quality-gate.sh'
after=root/'Infra/ci/templates/sonar-quality-gate.sh'
valid={'projectStatus':{'status':'OK','ignoredConditions':False,'conditions':[
 dict(metricKey=m,comparator=op,errorThreshold=str(limit),actualValue=str(value),status='OK')
 for m,op,limit,value in [('bugs','GT',6,6),('critical_violations','GT',35,35),('vulnerabilities','GT',0,0)]]}}
def unix(path):
 s=str(path).replace('\\','/')
 return '/'+s[0].lower()+s[2:] if len(s)>1 and s[1]==':' else s
count=0
with tempfile.TemporaryDirectory(prefix='baotin-sonar-shell-') as tmp:
 d=pathlib.Path(tmp);(d/'bin').mkdir();(d/'ci').mkdir()
 (d/'ci/sonar-validate-gate.pl').write_bytes((root/'Infra/ci/templates/sonar-validate-gate.pl').read_bytes())
 (d/'gradlew').write_text('''#!/bin/sh
mkdir -p build/reports/jacoco/test build/sonar
printf '<report/>\n' > build/reports/jacoco/test/jacocoTestReport.xml
printf 'ceTaskId=task_12345678\nprojectKey=baotin_auth-service\n' > build/sonar/report-task.txt
''')
 (d/'bin/curl').write_text('''#!/bin/sh
cat >/dev/null
out=''; url=''
while [ "$#" -gt 0 ]; do
 case "$1" in -o) shift; out=$1;; http://*) url=$1;; esac
 shift
done
case "$url" in */api/ce/task*) printf '{"task":{"status":"SUCCESS","analysisId":"analysis_12345678"}}' > "$out";; *) cp "$FAKE_BODY" "$out";; esac
''')
 cases=[(after,'valid',0),(after,'empty',5),(after,'missing',5),(after,'weakened',5)]
 if before.exists():cases.insert(0,(before,'empty',0))
 for script,mode,expected in cases:
  doc=json.loads(json.dumps(valid))
  if mode=='empty':doc['projectStatus']['conditions']=[]
  if mode=='missing':doc['projectStatus']['conditions'].pop()
  if mode=='weakened':doc['projectStatus']['conditions'][1]['errorThreshold']='999'
  (d/'body.json').write_text(json.dumps(doc));(d/'gate.sh').write_bytes(script.read_bytes())
  env=os.environ.copy();env.update(SONAR_HOST_URL='http://sonarqube:9000',SONAR_TOKEN='a'*40,SERVICE='auth-service',FAKE_BODY=unix(d/'body.json'))
  r=subprocess.run([a.bash,'-c','chmod +x gradlew bin/curl; export PATH="$PWD/bin:$PATH"; sh gate.sh'],cwd=d,env=env,capture_output=True,text=True,timeout=30)
  if r.returncode!=expected:raise SystemExit(f'{mode} expected={expected} observed={r.returncode}: {r.stderr}')
  count+=1
print(f'SONAR_SHELL PASS cases={count} baseline_available={before.exists()} modified_empty=exit5 missing=exit5 weakened=exit5 valid=exit0')
