# Deploy-only Windows WebSocket node

Keep existing controller/dind unchanged. Create node `baotin-deploy-desktop` in Jenkins,
1 executor, EXCLUSIVE mode, inbound WebSocket launcher, separate workdir such as
`C:\Users\USER\.baotin\jenkins-deploy-agent`. Restrict node/job permissions to maintainers;
labels alone are not an authorization boundary. Build/PR jobs must not execute here.

Default controller URL: `http://[::1]:8080/`. The script checks the anonymous Jenkins login
version header before connecting the remoting agent. This host currently has a different
proxy on IPv4 loopback8080; explicit IPv6 avoids passing node secrets to that endpoint or
relying on JVM localhost address-family ordering. Other local loopback URLs must also pass
identification; no redirect/proxy is followed and no existing port mapping is changed.

Download agent.jar from the authenticated local controller and verify its SHA-256 recorded
by the operator. Put its node secret in an ACL-restricted file outside Git. Start with the
script below; no Docker TCP endpoint or SSH server is installed. The node uses Docker
Desktop's local named pipe, and only release scripts issue explicit `--context desktop-linux`.

Configure only the trusted `baotin/FullStack` job from Infra `Jenkinsfile` on this node. Set
`BAOTIN_JAR_ENV_FILE` to external public JAR/web config, protect SCM revision/job configuration,
and require maintainer approval. Runtime/operator/migration secrets live in host files,
never in build-job credentials or controller-global environment.

```powershell
.\Start-DeployAgent.ps1 -AgentJar C:\Users\USER\.baotin\agent.jar `
  -AgentJarSha256 EXPECTED_SHA256 -SecretFile C:\Users\USER\.baotin\agent.secret `
  -WorkDir C:\Users\USER\.baotin\jenkins-deploy-agent -JavaHome PATH_TO_JDK21
```

Use Windows Task Scheduler only after manual verification, hidden/non-elevated under the
Docker Desktop user's session. Reboot/logout behavior must be verified; Desktop is not
assumed to provide production HA. Stop/disable only this new agent to roll back setup;
do not restart the controller or recreate dind from project scripts.

`Start-OwnedDeployAgent.ps1` uses the WMI-created `Start-AgentDetached.ps1`
launcher to start the JVM outside Task Scheduler's restrictive job object. The
launcher rejects elevation; the JVM retains the ordinary user's token. This lets
the existing detached-runtime launcher work without access-denied errors and lets
runtime children survive the Jenkins step. The bounded temporary launch spec
contains file paths, not credential contents, and is removed after PID reporting.
After reconnecting, require `Test-AgentJobIsolation.ps1` to pass before promotion.

`Start-DeployAgent.ps1 -ValidateOnly` validates JDK21, recorded jar hash, local named pipe
and secret-file ACL without connecting or creating an agent workdir. ACL checks use SIDs,
not localized group names; only the current operator, SYSTEM and local Administrators
may hold Allow entries. `Test-DeployAgent.ps1 -AgentJar PATH -JavaHome JDK21` exercises
restricted ACLs, broad groups, tampered jar and controller URL rejection with synthetic
data; it never starts an agent or reads a real node secret.

## Reviewed deploy-node queue boundary

`DeployNodeGuard.groovy` denies all work on `baotin-deploy-desktop` except a running
Pipeline node task owned by `baotin/FullStack`, from Infra's exact approved SHA.
It requires the expected SCM URL/credential/Jenkinsfile path, non-lightweight SCM,
and actual loaded Git BuildData matching the external approval. Replay and declarative
restart causes are denied. Other nodes are unchanged; a matching label is not sufficient.
The controller's existing single operator account/signup-off settings are preserved.
This controls scheduling, not isolation from a controller administrator or compromised
approved pipeline/controller OS account. The approved Infra code still holds host privileges.

`Install-DeployNodeGuard.ps1` compiles/runs 25 policy/dispatcher checks on the real
controller, installs a SHA-pinned init loader and registers the guard without restart.
The loader uses the plugin uberClassLoader; failed loading disables this node's executors.
Its two fixed files use root-owned 0644 permissions, not changed directory ownership.
`Connect-DeployAgent.ps1` creates the approved one-executor EXCLUSIVE WebSocket node,
writes the generated secret to an external restricted file and starts one hidden JDK21
process with PID/start-time ownership recorded. It connects only; no deployment runs.
Initial setup preserves existing node/files rather than silently recreating/replacing them.

```powershell
.\Test-DeployNodeGuard.ps1 -Username rimiprod -TokenFile C:\Users\ngoan\.baotin\jenkins-api.token
.\Test-DeployNodeLive.ps1 -Username rimiprod -TokenFile C:\Users\ngoan\.baotin\jenkins-api.token
.\Test-DeployNodeFailClosed.ps1 -Username rimiprod -TokenFile C:\Users\ngoan\.baotin\jenkins-api.token
.\Test-AgentRollback.ps1 -JavaHome PATH_TO_JDK21
```

The live test temporarily queues one freestyle and one sandboxed Pipeline job targeting
the deploy label; both are denied before commands execute, then canceled/deleted. It does
not execute the real FullStack promotion. Loader re-evaluation is not controller-restart proof.
The fail-closed test evaluates an invalid-hash loader copy on the idle owned node,
checks zero executors, then restores one executor and re-evaluates the unchanged
persisted loader in finally. It leaves the node online and the controller running.

Manual connection rollback stops only the recorded Java PID with matching start time:

```powershell
.\Stop-OwnedDeployAgent.ps1 -StateFile C:\Users\ngoan\.baotin\deploy-agent.state.json
```

The node then disconnects. Controller, dind, runtime/config files and guard remain.
Delete the now-idle node via Jenkins only if decommissioning; preserve its restricted logs.
The rollback test uses a separate synthetic Java sleeper/ownership-record copy and checks
wrong-start-time denial plus idempotent stop; it does not stop the actual deploy agent.
Do not run initial Connect again over existing ownership files. Reconnect with
`Start-OwnedDeployAgent.ps1` from a normal non-elevated PowerShell session, supplying
the existing JDK/JAR/hash/secret/env/state paths; it checks the old PID is absent,
verifies the actual new Java token using Win32 TokenElevation and atomically updates
the restricted ownership record. Initial Connect and direct Start also reject elevated
launches (ValidateOnly remains usable for read-only preflight).
An elevated operator can invoke the normal user's existing Explorer desktop
ShellExecute with window style0; a newly created Shell.Application dispatcher may
inherit elevation, so the launcher itself verifies and rejects that case.
Reconnection preserves the node, guard and secret. No autorun/task is installed.
# Dedicated temporary/cache directories — 2026-10-28

`Start-DeployAgent.ps1` accepts optional `-TempRoot` and `-CacheRoot` absolute,
dedicated paths outside the source workspace. The user approved
`L:/BaoTin-agent-state/temp` and `L:/BaoTin-agent-state/cache` for this host.
TEMP/TMP and npm/Gradle caches are set only for the agent process/children; no
Windows global environment, monitor threshold, secret, volume or existing cache
is deleted/replaced. Launch from an ordinary non-elevated session and verify
PID/start-time, job isolation and actual temp monitor before promotion.
`Test-AgentTemporaryPaths.ps1` checks invalid paths and mutation-free validation.
