// =============================================================================
//  BaoTin — Jenkins auto-configuration
//
//  Jenkins nạp tự động mọi file .groovy trong /var/jenkins_home/init.groovy.d/
//  mỗi lần khởi động, chạy với quyền SYSTEM nên không cần API token.
//
//  Việc làm:
//    1. Gắn label `baotin-docker` cho controller để Jenkinsfile tìm thấy
//    2. Set biến môi trường toàn cục (GITHUB_OWNER, DEPLOY_*)
//    3. Tạo credentials (baotin-github-scm, ghcr-registry, deploy-ssh-key, sonar-token)
//    4. Tạo folder `baotin` + 10 pipeline job lấy Jenkinsfile từ GitHub
//
//  Cấu hình lấy từ /var/jenkins_home/baotin-ci.properties
//    (sinh từ Infra/jenkins/init/baotin-ci.properties.example)
//
//  ---------------------------------------------------------------------------
//  LƯU Ý KỸ THUẬT — đừng "dọn dẹp" phần reflection này
//
//  Script hook chạy TRƯỚC khi Jenkins nạp xong plugin, và Groovy biên dịch
//  eager nên `import` class của plugin ở đầu file sẽ báo "unable to resolve".
//  Vì vậy mọi class của plugin đều được nạp động qua uber classloader rồi khởi
//  tạo bằng reflection. Chỉ class lõi (jenkins.*, hudson.model.*) mới import.
//
//  Toàn bộ thân script nằm trong try/catch: một lỗi cấu hình chỉ ghi log chứ
//  KHÔNG được làm Jenkins fail boot (nếu không container sẽ restart vô hạn).
// =============================================================================

import jenkins.model.Jenkins

def SCRIPT = '[baotin-ci]'

try {
    def log = { String msg -> println "${SCRIPT} ${msg}" }

    def jenkins = Jenkins.get()

    // -------------------------------------------------------------------------
    // Tiện ích reflection
    // -------------------------------------------------------------------------

    def uber = jenkins.pluginManager.uberClassLoader

    /** Nạp class của plugin theo tên đầy đủ. */
    def clazz = { String fqcn -> uber.loadClass(fqcn) }

    /**
     * Tạo đối tượng bằng reflection.
     * argLists là DANH SÁCH các bộ tham số — thử lần lượt cho tới khi khớp,
     * nên không phụ thuộc chính xác thứ tự tham số giữa các phiên bản plugin.
     * Trả về đối tượng (không phải danh sách).
     */
    def newInstance = { String fqcn, List argLists ->
        def c = clazz(fqcn)
        Exception last = null
        for (def args : argLists) {
            for (def ctor : c.constructors) {
                if (ctor.parameterTypes.length != args.size()) { continue }
                try {
                    // GString (chuỗi nội suy) KHÔNG phải java.lang.String nên
                    // reflection sẽ báo "argument type mismatch" — ép về String.
                    def normalized = args.collect { it instanceof GString ? it.toString() : it }
                    return ctor.newInstance(normalized.toArray())
                } catch (Exception e) {
                    last = e
                }
            }
        }
        throw new IllegalStateException(
            "${fqcn}: không khởi tạo được — ${last?.message ?: 'không có constructor khớp'}",
            last)
    }

    /**
     * Gọi static method của plugin.
     * Dùng InvokerHelper vì Groovy chặn `Class.getMethod(...)` khi gọi trực tiếp.
     */
    def staticCall = { String fqcn, String method, List args ->
        org.codehaus.groovy.runtime.InvokerHelper.invokeStaticMethod(
            clazz(fqcn), method, args.toArray())
    }

    // -------------------------------------------------------------------------
    // Đọc cấu hình
    // -------------------------------------------------------------------------

    def PROPS_FILE = new File('/var/jenkins_home/baotin-ci.properties')
    def props = new Properties()
    if (PROPS_FILE.exists()) {
        PROPS_FILE.withInputStream { props.load(it) }
    } else {
        log "KHÔNG THẤY ${PROPS_FILE} — dùng toàn bộ giá trị mặc định"
    }

    def cfg = { String key, String fallback = '' ->
        def v = props.getProperty(key)
        (v == null || v.trim().isEmpty()) ? fallback : v.trim()
    }

    // Giá trị mẫu chưa thay -> bỏ qua thay vì tạo credential hỏng
    def PLACEHOLDERS = ~/(?i)^(REPLACE_WITH_.*|CHANGE_ME.*)$/
    def isReal = { String v ->
        v != null && !v.trim().isEmpty() && !PLACEHOLDERS.matcher(v.trim()).matches()
    }

    def owner   = 'vietluccuu' // Confirmed workspace SCM owner, independent of legacy properties.
    def ghUser  = cfg('GITHUB_USER', 'x-access-token')
    def ghToken = cfg('GITHUB_TOKEN')
    def sshUser = cfg('DEPLOY_USER', 'deploy')
    def sshKey  = cfg('DEPLOY_SSH_KEY')
    // Quality gate của Jenkins trỏ tới SonarQube nội bộ, không ra Internet.
    def sonarToken = cfg('SONAR_TOKEN')
    def sonarHost  = cfg('SONAR_HOST_URL', 'http://sonarqube:9000')

    def SERVICES = [
        [repo: 'API-Gateway',             port: '8080', desc: 'Edge gateway — routing, rate limit, JWT'],
        [repo: 'Auth-Service',            port: '8081', desc: 'Auth — user, login, RBAC, JWT'],
        [repo: 'Threat-Service',          port: '8082', desc: 'Threat — SMS/URL inspection'],
        [repo: 'Report-Service',          port: '8083', desc: 'Report — report, evidence, moderation'],
        [repo: 'Reputation-Service',      port: '8084', desc: 'Reputation — risk scoring'],
        [repo: 'Model-Inference-Service', port: '8085', desc: 'Model inference — ONNX'],
        [repo: 'Notification-Service',    port: '8086', desc: 'Notification — push / email'],
        [repo: 'Search-Service',          port: '8087', desc: 'Search — FTS projection'],
        [repo: 'Campaign-Service',        port: '8088', desc: 'Campaign — scam grouping'],
        [repo: 'Analytics-Service',       port: '8089', desc: 'Analytics — statistics'],
        [repo: 'Frontend',                port: '8091', desc: 'Expo web artifact build'],
        [repo: 'Infra', job: 'FullStack',   port: '8090', desc: 'Full-stack JAR + web orchestration'],
    ]

    // Source approval is operator-owned and persisted beside Jenkins config, not
    // in a moving repository branch. Missing/invalid approval must never unpin jobs.
    def approvalFile = new File(jenkins.rootDir, 'baotin-approved-main.json')
    if (!approvalFile.isFile() || approvalFile.length() > 32768) {
        log 'SCM approvals missing/invalid; existing job definitions retained'
        return
    }
    def approved = new groovy.json.JsonSlurperClassic().parseText(approvalFile.getText('UTF-8'))
    def approvedPins = approved.repositories
    if (approved.schemaVersion != 1 || !(approvedPins instanceof Map) ||
        approvedPins.keySet() != SERVICES.collect { it.repo }.toSet() ||
        approvedPins.values().any { !(it instanceof String) || !(it ==~ /[a-f0-9]{40}/) || it == '0' * 40 }) {
        log 'SCM approval scope invalid; existing job definitions retained'
        return
    }

    // -------------------------------------------------------------------------
    // 1. Label cho controller
    //
    //    Controller đã có JDK 21 + docker CLI + buildx nên build trực tiếp ở
    //    đây, không cần agent riêng. Muốn tách agent sau chỉ cần đổi label
    //    trong Jenkinsfile và khai báo node mới — không phải sửa file này.
    // -------------------------------------------------------------------------

    try {
        def current = jenkins.getLabelString() ?: ''
        def atoms = current ? (current.trim().split(/\s+/) as List) : []
        ['baotin-docker', 'java21', 'gradle', 'docker'].each {
            if (!atoms.contains(it)) { atoms << it }
        }
        jenkins.setLabelString(atoms.unique().join(' '))
        log "label controller = '${jenkins.getLabelString()}'"
    } catch (Throwable t) {
        log "WARN: không set được label — ${t.message}"
    }

    // -------------------------------------------------------------------------
    // 2. Biến môi trường toàn cục cho pipeline
    // -------------------------------------------------------------------------

    try {
        def globals = jenkins.globalNodeProperties
        // Entry là lớp lồng bên trong EnvironmentVariablesNodeProperty
        def entryFqcn = 'hudson.slaves.EnvironmentVariablesNodeProperty$Entry'
        def propClass = clazz('hudson.slaves.EnvironmentVariablesNodeProperty')

        // Bỏ bản cũ để không nhân bản mỗi lần restart
        globals.removeAll { propClass.isInstance(it) }

        def values = [
            ['GITHUB_OWNER', owner],
            ['DEPLOY_HOST',  cfg('DEPLOY_HOST')],
            ['DEPLOY_USER',  sshUser],
            ['DEPLOY_PATH',  cfg('DEPLOY_PATH', '/opt/baotin')],
            ['SONAR_HOST_URL', sonarHost],
            ['TZ',           'Asia/Ho_Chi_Minh'],
        ].findAll { it[1] }   // bỏ biến rỗng

        def entries = values.collect { pair ->
            newInstance(entryFqcn, [[pair[0], pair[1]]])
        }
        globals.add(newInstance('hudson.slaves.EnvironmentVariablesNodeProperty', [[entries]]))
        log "biến môi trường toàn cục: ${values.collect { it[0] }.join(', ')}"
    } catch (Throwable t) {
        log "WARN: không set env vars — ${t.message}"
    }

    // -------------------------------------------------------------------------
    // 3. Credentials
    // -------------------------------------------------------------------------

    def credStore = staticCall(
        'com.cloudbees.plugins.credentials.SystemCredentialsProvider', 'getInstance', [])
            .getStore()
    def credDomain = staticCall('com.cloudbees.plugins.credentials.domains.Domain', 'global', [])
    def scopeGlobal = clazz('com.cloudbees.plugins.credentials.CredentialsScope').GLOBAL

    // Secret ở dạng so sánh được; null khi credential không có property 'secret'.
    // Với plain-credentials (ví dụ sonar-token), class + description không đổi khi
    // token bị xoay, nên phải so cả giá trị secret — nếu không, apply-config sẽ giữ
    // lại token cũ và pipeline fail gate sau khi token cũ bị thu hồi.
    def secretOf = { c ->
        try {
            def s = c.hasProperty('secret') ? c.secret : null
            return s == null ? null : s.plainText
        } catch (Throwable ignored) {
            return null
        }
    }

    def upsert = { String id, String desc, credential ->
        def existing = credStore.getCredentials(credDomain).find { it.id == id }
        if (existing) {
            if (existing.getClass() == credential.getClass() && existing.description == desc &&
                    secretOf(existing) == secretOf(credential)) {
                log "credential '${id}': đã có, giữ nguyên"
                return
            }
            credStore.removeCredentials(credDomain, existing)
            log "credential '${id}': cập nhật"
        } else {
            log "credential '${id}': tạo mới"
        }
        credStore.addCredentials(credDomain, credential)
    }

    if (isReal(ghToken)) {
        // (scope, id, description, username, password)
        def fqcn = 'com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl'

        upsert('ghcr-registry',
               'ghcr.io — GitHub user + PAT (read:packages, write:packages)',
               newInstance(fqcn, [[scopeGlobal, 'ghcr-registry',
                                   'ghcr.io — GitHub user + PAT (read:packages, write:packages)',
                                   ghUser, ghToken]]))

        upsert('baotin-github-scm',
               'GitHub SCM — checkout source từ repo backend',
               newInstance(fqcn, [[scopeGlobal, 'baotin-github-scm',
                                   'GitHub SCM — checkout source từ repo backend',
                                   ghUser, ghToken]]))
    } else {
        log 'BỎ QUA credentials: GITHUB_TOKEN chưa được điền trong baotin-ci.properties'
    }

    if (isReal(sonarToken)) {
        // (scope, id, description, secret) — token của SonarQube, không phải mật khẩu.
        // plain-credentials ≥ 199 chuyển lớp sang package org.jenkinsci.plugins;
        // giữ nhánh cũ để không vỡ nếu plugin bị hạ cấp.
        def sonarDesc = 'SonarQube — analysis token cho quality gate'
        def sonarFqcn = 'org.jenkinsci.plugins.plaincredentials.impl.StringCredentialsImpl'
        try { clazz(sonarFqcn) } catch (Throwable ignored) {
            sonarFqcn = 'com.cloudbees.plugins.credentials.impl.StringCredentialsImpl'
        }
        upsert('sonar-token', sonarDesc,
               newInstance(sonarFqcn,
                    [[scopeGlobal, 'sonar-token', sonarDesc,
                      staticCall('hudson.util.Secret', 'fromString', [sonarToken])]]))
    } else {
        log 'BỎ QUA credential sonar-token: SONAR_TOKEN chưa được điền'
    }

    if (isReal(sshKey)) {
        // Properties file ghi \n theo nghĩa đen -> khôi phục thành xuống dòng thật
        def pem = sshKey.replace('\\n', '\n')

        def keySource = newInstance(
            'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey$DirectEntryPrivateKeySource',
            [[pem]])

        // (scope, id, description, privateKeySource, username, passphrase)
        upsert('deploy-ssh-key',
               "SSH deploy — user ${sshUser}",
               newInstance(
                   'com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey',
                   [[scopeGlobal, 'deploy-ssh-key', "SSH deploy — user ${sshUser}",
                     keySource, sshUser, null]]))
    } else {
        log 'BỎ QUA credential deploy-ssh-key: DEPLOY_SSH_KEY chưa được điền'
    }

    try {
        credStore.save()
    } catch (Throwable t) {
        log "WARN: không save được credential store — ${t.message}"
    }

    // -------------------------------------------------------------------------
    // 4. Job cho từng service — pipeline lấy Jenkinsfile từ repo GitHub
    // -------------------------------------------------------------------------

    def folderClass = clazz('com.cloudbees.hudson.plugins.folder.Folder')
    def jobClass    = clazz('org.jenkinsci.plugins.workflow.job.WorkflowJob')

    def folder = jenkins.getItem('baotin')
    if (!folderClass.isInstance(folder)) {
        folder = jenkins.createProject(folderClass, 'baotin')
        folder.description = 'BaoTin backend microservices'
        log 'tạo folder baotin'
    }

    int created = 0, updated = 0, failed = 0

    SERVICES.each { svc ->
        def name = svc.job ?: svc.repo
        try {
            def url = "https://github.com/${owner}/${svc.repo}.git"

            // (url, name, refspec, credentialsId)
            def remote = newInstance('hudson.plugins.git.UserRemoteConfig',
                                     [[url, 'origin', null, 'baotin-github-scm']])
            // Pin the reviewed commit BEFORE retrieving/executing Jenkinsfile.
            def branches = [newInstance('hudson.plugins.git.BranchSpec', [[approvedPins[svc.repo]]])]

            def extensions = [newInstance('hudson.plugins.git.extensions.impl.CleanCheckout', [[]])]

            // (userRemoteConfigs, branches, browser, gitTool, extensions)
            def scm = newInstance('hudson.plugins.git.GitSCM',
                                  [[[remote], branches, null, null, extensions]])

            // (scm, scriptPath) + setLightweight(false)
            def definition = newInstance(
                'org.jenkinsci.plugins.workflow.cps.CpsScmFlowDefinition',
                [[scm, 'Jenkinsfile']])
            definition.setLightweight(false)

            def description = "${svc.desc} — port ${svc.port}\nRepo: ${owner}/${name}"

            def job = folder.getItem(name)
            if (jobClass.isInstance(job)) {
                updated++
            } else {
                job = folder.createProject(jobClass, name)
                created++
            }

            job.setDefinition(definition)
            job.setDescription(description)

            // Parameter khởi tạo — người dùng chỉnh được trong UI.
            // Phải bọc trong ParametersDefinitionProperty rồi mới addProperty,
            // vì addProperty chỉ nhận JobProperty chứ không nhận ParameterDefinition.
            def params = svc.repo == 'Infra' ? [
                newInstance('hudson.model.BooleanParameterDefinition',
                    [['DEPLOY_RELEASE', false, 'Promote reviewed successful JAR/web artifacts after approval']]),
                newInstance('hudson.model.BooleanParameterDefinition',
                    [['BUILD_DOCKER_IMAGES', false, 'Build alternative Docker images']]),
            ] : [
                newInstance('hudson.model.BooleanParameterDefinition',
                    [['BUILD_DOCKER_IMAGE', false, 'Build alternative Docker image']]),
            ]
            def paramProp = newInstance('hudson.model.ParametersDefinitionProperty', [[params]])

            // Preserve parameters already owned by a validated declarative pipeline.
            def oldProp = job.getProperty(paramProp.getClass())
            if (oldProp == null) { job.addProperty(paramProp) }
        } catch (Throwable t) {
            failed++
            log "LỖI khi tạo job '${name}': ${t.message}"
        }
    }

    log "job: ${created} tạo mới, ${updated} cập nhật, ${failed} lỗi (tổng ${SERVICES.size()})"

    try {
        jenkins.save()
    } catch (Throwable t) {
        log "WARN: không save được cấu hình Jenkins — ${t.message}"
    }

    log 'HOÀN TẤT cấu hình BaoTin CI/CD'
} catch (Throwable t) {
    println "${SCRIPT} LỖI: ${t}"
    t.printStackTrace()
}
