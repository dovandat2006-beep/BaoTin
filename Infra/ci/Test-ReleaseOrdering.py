"""Release ordering contracts; no Docker/server/real credentials required."""
import argparse
import pathlib
import re

p=argparse.ArgumentParser();p.add_argument('--workspace',default=str(pathlib.Path(__file__).resolve().parents[2]));a=p.parse_args()
root=pathlib.Path(a.workspace);count=0
def retains_verification(text):
    return bool(re.search(r'gradlew\s+--no-daemon\s+test\s+(?:integrationTest\s+)?bootJar\b',text)) and not re.search(r'(?<!\S)-x\s+test\b|--exclude-task(?:=|\s+)test\b|SKIP_TESTS',text)

for fixture, expected in [('gradlew --no-daemon test bootJar',True),('gradlew --no-daemon test integrationTest bootJar --max-workers=1',True),('gradlew --no-daemon bootJar',False),('gradlew --no-daemon test bootJar -x test',False),('gradlew --no-daemon test bootJar --exclude-task=test',False)]:
    assert retains_verification(fixture)==expected
repos=['API-Gateway','Auth-Service','Threat-Service','Report-Service','Reputation-Service','Model-Inference-Service','Notification-Service','Search-Service','Campaign-Service','Analytics-Service']
for repo in repos:
    text=(root/repo/'Jenkinsfile').read_text(encoding='utf-8')
    gate=text.find("stage('SonarQube quality gate')")
    build=text.find("stage('Build release artifact')")
    security=text.find("stage('JAR security and SBOM gates')")
    assert 0<=gate<build<security, f'{repo}: Sonar must pass before JAR packaging/security'
    assert 'clean bootJar' not in text and not re.search(r'\bjunit\b|\bSKIP_TESTS\b',text), repo
    script=(root/repo/'ci/sonar-quality-gate.sh').read_text()
    commands=re.findall(r'^\./gradlew (.+)$',script,re.M)
    assert len(commands)==1 and 'clean classes sonar' in commands[0],repo
    assert not re.search(r'\b(test|testClasses|bootJar|jacocoTestReport)\b',commands[0]),repo
    assert './gradlew --no-daemon bootJar' in text,repo
    verify=(root/repo/'.github/workflows/ci-cd.yml').read_text()
    assert retains_verification(verify),repo
    count+=6
front=(root/'Frontend/Jenkinsfile').read_text()
assert front.index("stage('SonarQube quality gate')")<front.index("stage('Build web')")
web=(root/'Frontend/ci/build-web.sh').read_text()
assert 'npm run typecheck' in web and 'npm run build:web' in web
assert not re.search(r'npm (test|run test)|lcov|coverageReporters',web)
sonar=(root/'Frontend/ci/sonar-quality-gate.sh').read_text()
assert 'missing_lcov' not in sonar and '-Dsonar.javascript.lcov.reportPaths=' in sonar
assert not re.search(r'lcov\.reportPaths=\S',sonar)
docker=(root/'Frontend/Dockerfile').read_text()
assert 'npm test' not in docker and 'npm run typecheck' in docker
verify=(root/'Frontend/.github/workflows/ci.yml').read_text()
assert 'npm test' in verify and 'npm run test:e2e' in verify
assert 'sonar-token' in front and 'ci/security-gates.sh' in front
count+=7
print(f'RELEASE_ORDER PASS checks={count} backend=10 frontend=1 Sonar=before_packaging release_tests=absent verify_tests=retained')
