// Trusted Jenkins service pipeline. Never deploys or exposes runtime secrets.
pipeline {
    agent { label 'baotin-docker' }
    options { timestamps(); disableConcurrentBuilds(); timeout(time: 60, unit: 'MINUTES'); buildDiscarder(logRotator(numToKeepStr: '20')) }
    environment { SERVICE = '__SERVICE__'; IMAGE = 'ghcr.io/__OWNER__/__SERVICE__' }
    stages {
        stage('Checkout') {
            steps {
                checkout scm
                script {
                    env.REVISION = sh(script: 'git rev-parse HEAD', returnStdout: true).trim()
                    env.ON_MAIN = sh(script: 'test "$(git rev-parse HEAD)" = "$(git rev-parse refs/remotes/origin/main)"', returnStatus: true) == 0 && !env.CHANGE_ID ? 'true' : 'false'
                    if (env.ON_MAIN != 'true') { error('Privileged Jenkins controller/dind lane accepts approved main commits only; PR verification runs on ephemeral GitHub runners') }
                }
            }
        }
        stage('Build daemon preflight') {
            steps { sh '''set -eu
                before=$(docker info --format '{{.ID}}')
                sleep 3
                test "$before" = "$(docker info --format '{{.ID}}')"
                docker info >/dev/null
            ''' }
        }
        stage('Build and integration tests') {
            steps { sh './gradlew --no-daemon clean test bootJar --max-workers=1 --console=plain' }
            post { always { junit allowEmptyResults: false, testResults: 'build/test-results/test/TEST-*.xml'; archiveArtifacts artifacts: 'build/reports/**,build/libs/*-SNAPSHOT.jar', allowEmptyArchive: false, fingerprint: true } }
        }
        stage('Security and SBOM gates') {
            steps { sh '''set -eu
                docker build -t "${IMAGE}:${REVISION}" .
                IMAGE_REFERENCE="${IMAGE}:${REVISION}" sh ci/security-gates.sh
            ''' }
            post { always { archiveArtifacts artifacts: 'build/security/**', allowEmptyArchive: true, fingerprint: true } }
        }
        stage('Publish trusted-main digest') {
            when { expression { env.ON_MAIN == 'true' } }
            steps {
                withCredentials([usernamePassword(credentialsId: 'ghcr-registry', usernameVariable: 'REGISTRY_USER', passwordVariable: 'REGISTRY_PASSWORD')]) {
                    sh '''set -eu
                        set +x
                        printf '%s' "$REGISTRY_PASSWORD" | docker login ghcr.io -u "$REGISTRY_USER" --password-stdin
                        trap 'docker logout ghcr.io >/dev/null 2>&1 || true' EXIT
                        docker push "${IMAGE}:${REVISION}"
                        ref=$(docker inspect --format '{{index .RepoDigests 0}}' "${IMAGE}:${REVISION}")
                        case "$ref" in "${IMAGE}"@sha256:*) ;; *) exit 2;; esac
                        printf '{"service":"%s","image":"%s","revision":"%s"}\n' "$SERVICE" "$ref" "$REVISION" > build/release-fragment.json
                    '''
                }
                archiveArtifacts artifacts: 'build/release-fragment.json', fingerprint: true
            }
        }
    }
}
