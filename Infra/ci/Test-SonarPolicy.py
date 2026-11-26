"""Offline verdict/threshold regression; no Sonar credentials or server changes."""
import argparse
import copy
import json
import pathlib
import subprocess
import tempfile

p = argparse.ArgumentParser()
p.add_argument('--perl', default='perl')
p.add_argument('--baseline', action='store_true')
a = p.parse_args()
root = pathlib.Path(__file__).resolve().parent
if a.baseline:
    source = (root.parent.parent / '.changes/quality-deploy-20261013/before/Infra/ci/templates/sonar-quality-gate.sh').read_text()
    assert 'sonar-validate-gate.pl' not in source and 'if [ "$gate" != OK ]' in source
    print('SONAR_POLICY BASELINE empty_OK=accepted missing_metrics=accepted')
    raise SystemExit(0)
policy = root / 'templates/sonar-validate-gate.pl'
conditions = [dict(metricKey=m, comparator=op, errorThreshold=str(limit), actualValue=str(value), status='OK')
              for m, op, limit, value in [('bugs','GT',6,6),('vulnerabilities','GT',0,0),('critical_violations','GT',35,35)]]
good = {'projectStatus': dict(status='OK', conditions=conditions, ignoredConditions=False)}
cases = [('valid',good,0,'baotin_auth-service')]
for name, mutate in [
    ('empty',lambda d:d['projectStatus'].update(conditions=[])),
    ('ignored',lambda d:d['projectStatus'].update(ignoredConditions=True)),
    ('missing',lambda d:d['projectStatus']['conditions'].pop()),
    ('missing_value',lambda d:d['projectStatus']['conditions'][0].pop('actualValue')),
    ('nan',lambda d:d['projectStatus']['conditions'][0].update(actualValue='NaN')),
    ('weakened_critical',lambda d:d['projectStatus']['conditions'][2].update(errorThreshold='999')),
    ('weakened_bugs',lambda d:d['projectStatus']['conditions'][1].update(errorThreshold='99')),
    ('lying_OK',lambda d:d['projectStatus']['conditions'][0].update(actualValue='10')),
    ('duplicate',lambda d:d['projectStatus']['conditions'].append(copy.deepcopy(d['projectStatus']['conditions'][0]))),
    ('failed',lambda d:d['projectStatus'].update(status='ERROR')),
    ('missing_ignored_flag',lambda d:d['projectStatus'].pop('ignoredConditions')),
]:
    doc=copy.deepcopy(good); mutate(doc); cases.append((name,doc,5,'baotin_auth-service'))
cases.extend([('invalid_json','not JSON',5,'baotin_auth-service'),('wrong_project',good,5,'baotin_unknown')])
frontend={'projectStatus':dict(status='OK',ignoredConditions=False,conditions=[
    dict(metricKey=metric,comparator='GT',errorThreshold='0',actualValue='0',status='OK')
    for metric in ('bugs','vulnerabilities')])}
cases.append(('frontend_without_coverage',frontend,0,'baotin_frontend'))
missing_front=copy.deepcopy(frontend);missing_front['projectStatus']['conditions'].pop()
cases.append(('frontend_missing_security_metric',missing_front,5,'baotin_frontend'))
with tempfile.TemporaryDirectory(prefix='baotin-sonar-policy-') as temp:
    for name, doc, expected, project in cases:
        f=pathlib.Path(temp)/'gate.json'; f.write_text(json.dumps(doc) if isinstance(doc,dict) else doc)
        r=subprocess.run([a.perl,str(policy),str(f),project],capture_output=True,text=True,check=False)
        if r.returncode != expected: raise SystemExit(f'case={name} expected={expected} actual={r.returncode}')
print(f'SONAR_POLICY MODIFIED PASS cases={len(cases)} empty=denied missing=denied weaker_threshold=denied lying_OK=denied')
