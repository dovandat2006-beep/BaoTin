# Manual startup and compatible recovery

## Bounded runtime startup — 2026-10-28

The primary launcher now waits for the exact PID/start-time-owned process to be
readiness UP before starting the next service. This avoids ten JVMs consuming
their startup native-memory peaks simultaneously. A partial startup failure stops
only the exact owned processes in that attempt; rollback uses the same ordered
startup and cleans up its own partial attempt on failure. Keep PID, loopback,
readiness and compatible-reader/key checks intact. Regression:
`direct/Test-SequentialRuntimeStartup.ps1`. Low-memory VM initialization errors
are operational failures, not a successful deployment or successful rollback.

Current accepted deployment: FullStack#12,2026-10-19. Backend/web origins, domain
route table and host checks: [HOST_READY.vi.md](HOST_READY.vi.md). CI now uses the
four-container `ci-stack/compose.yml` with Sonar9001 and the existing private
manifest. Restart that Compose installation, not the legacy standalone Sonar stack.
No data archive was created in the approved adoption; existing volumes were reused.

No Task Scheduler/autostart was installed. Jenkins JAR + Expo web is primary;
Docker alternative is opt-in. After a reboot, native JAR/web and remoting processes
need manual startup. This runbook describes the accepted reader2/schema10 release;
old FullStack#6 recovery scripts and reader1 images are not compatible rollback targets.

## This host

1. Start Docker Desktop manually. Start the existing dependencies without deleting
   volumes or reprovisioning passwords (from the Infra repository):

   ```powershell
   docker compose --project-name baotin-deps --env-file C:\Users\ngoan\.baotin\deps.env -f direct/dependencies/docker-compose.yml up -d --wait --wait-timeout 120
   ```

2. Reconnect the existing deployment node from a normal, non-elevated PowerShell7
   session using `jenkins/windows-agent/Start-OwnedDeployAgent.ps1`, the existing
   `agent.jar` recorded SHA256, `agent.secret`, `jar.env` and
   `deploy-agent.state.json`. Follow that directory's README. If its old PID is reused,
   inspect PID/start-time ownership first; preserve unrelated processes. Do not call
   initial Connect again or recreate the controller/node.

3. Before recovery, check the current `RUNTIME_ROOT/active.json`, keys, dependencies
   and all listeners. If the accepted eleven processes are already owned/live/UP,
   leave them running. For stale **pre-reboot** ownership only, the following guarded
   metadata retirement leaves the accepted release directory and keys intact:

   ```powershell
   $ErrorActionPreference='Stop'
   $path='M:\BaoTin-runtime\active.json'
   $original=[IO.File]::ReadAllText($path)
   $state=$original|ConvertFrom-Json -AsHashtable
   $boot=(Get-CimInstance Win32_OperatingSystem).LastBootUpTime.ToUniversalTime().Ticks
   if($state.processes.Count -ne 11){throw 'Inspect incomplete ownership record'}
   foreach($entry in $state.processes){
     if([long]$entry.startTicks -ge $boot){throw 'Record is not pre-reboot'}
     $p=Get-Process -Id $entry.pid -ErrorAction SilentlyContinue
     if($p -and $p.StartTime.ToUniversalTime().Ticks -eq [long]$entry.startTicks){throw 'Owned process remains alive'}
   }
   foreach($listener in [Net.NetworkInformation.IPGlobalProperties]::GetIPGlobalProperties().GetActiveTcpListeners()){
     if($listener.Port -in $state.ports.Values){throw 'Preserve occupied runtime port'}
   }
   # No process is signalled; only proven stale ownership is retired.
   $backup=$path+'.preboot-'+[guid]::NewGuid().ToString('N')+'.json'
   [IO.File]::WriteAllText($backup,$original)
   $state.processes=@()
   $pending=$path+'.pending'
   if(Test-Path $pending){throw 'Inspect pending deployment state first'}
   [IO.File]::WriteAllText($pending,($state|ConvertTo-Json -Depth 8))
   Move-Item -LiteralPath $pending -Destination $path -Force
   ```

4. Run the already SHA-pinned Jenkins FullStack job with `DEPLOY_RELEASE=true`,
   `BUILD_DOCKER_IMAGES=false`, then approve promotion. It rechecks successful immutable
   child artifacts, current DB reader/schema/cipher floors, key identity, ports and all
   eleven readiness endpoints. After schema10, use reader2 or newer compatible Auth.
   A new doc commit does not automatically advance any approved job pin.

5. Require terminal Jenkins SUCCESS **and** post-job PID/start-time/readiness checks.
   Preserve the accepted current reader2 release as rollback; do not select the historical
   reader1 entry from `active.json.previous`, down-migrate or restore old key files.

## Docker alternative

Use an external manifest/config containing the accepted immutable reader2 images:

```powershell
docker compose --project-name baotin-alt --env-file C:\Users\ngoan\.baotin\alternative-email.env -f alternative/docker-compose.yml up -d --pull never --wait --wait-timeout 180
# Stop only alternative applications; images, volumes and dependencies remain.
docker compose --project-name baotin-alt --env-file C:\Users\ngoan\.baotin\alternative-email.env -f alternative/docker-compose.yml stop
```

Do not enable both lanes automatically. On another machine use its external paths,
separately prepared secrets/DB, available immutable images (`save/load` or registry
digest), and its deployment node. The Windows JAR launcher is Windows-specific;
Linux-container images/Compose are the cross-OS alternative. Domain/tunnel rollout
and frontend business integration remain later work.
