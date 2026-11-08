# The learning environment's servers on a Windows machine (the laptop): start, stop, status, busy.
# Run by tools/rl/launch_envs.py over ssh; everything lives under -Root (C:\Videos\aiguide-promo\rl by default):
# template\ (Forge, the server jar, libraries), mods\ (the two jars), servers\env-PORT\ (one server each).
# A server is started by WMI (Win32_Process.Create: it outlives the ssh session), at BelowNormal priority.
# Nothing starts while League of Legends or another Minecraft runs (the owner's machine comes first).
param(
    [ValidateSet('start', 'stop', 'status', 'busy')][string]$Action = 'status',
    [string]$Root = 'C:\Videos\aiguide-promo\rl',
    [string]$Ports = '26100',
    [string]$Xmx = '1200M',
    [string]$Fast = 'true',
    [string]$Seed = '5802634',
    [string]$Java = 'C:\Program Files\Java\jre1.8.0_481\bin\java.exe'
)
$ErrorActionPreference = 'Stop'

function Ours() {
    Get-CimInstance Win32_Process -Filter "Name='java.exe'" | Where-Object { $_.CommandLine -match '-Daiguide\.rl=' -and $_.CommandLine -match [regex]::Escape($Root) }
}

function Busy() {
    if (Get-Process -Name 'League of Legends', 'LeagueClient', 'LeagueClientUx' -ErrorAction SilentlyContinue) { return 'League of Legends is running' }
    $mc = Get-CimInstance Win32_Process -Filter "Name='javaw.exe' OR Name='java.exe'" | Where-Object { $_.CommandLine -match 'minecraft|forge|launchwrapper' -and $_.CommandLine -notmatch [regex]::Escape($Root) }
    if ($mc) { return 'another Minecraft is running: pid ' + (($mc | ForEach-Object ProcessId) -join ',') }
    return ''
}

switch ($Action) {
    'busy' { $b = Busy; if ($b) { Write-Output "BUSY $b" } else { Write-Output 'FREE' } }
    'status' {
        $b = Busy
        Write-Output ("machine: " + $(if ($b) { "BUSY $b" } else { 'FREE' }))
        foreach ($p in Ours) {
            $port = if ($p.CommandLine -match '-Daiguide\.rl=(\d+)') { $Matches[1] } else { '?' }
            $proc = Get-Process -Id $p.ProcessId -ErrorAction SilentlyContinue
            Write-Output ("env port {0} pid {1} priority {2} ws {3} MB cpu {4} s" -f $port, $p.ProcessId, $proc.PriorityClass, [math]::Round($proc.WorkingSet64 / 1MB), [math]::Round($proc.CPU))
        }
    }
    'stop' {
        foreach ($port in $Ports.Split(',')) {
            foreach ($p in Ours | Where-Object { $_.CommandLine -match "-Daiguide\.rl=$port\b" }) {
                Stop-Process -Id $p.ProcessId -Force
                Write-Output "stopped env $port pid $($p.ProcessId)"
            }
        }
    }
    'start' {
        $b = Busy
        if ($b) { Write-Output "NOT STARTED: $b"; exit 2 }
        foreach ($port in $Ports.Split(',')) {
            $port = [int]$port
            if (Ours | Where-Object { $_.CommandLine -match "-Daiguide\.rl=$port\b" }) { Write-Output "env $port already runs"; continue }
            $dir = Join-Path $Root "servers\env-$port"
            if (-not (Test-Path $dir)) {
                New-Item -ItemType Directory -Force $dir | Out-Null
                Copy-Item -Recurse (Join-Path $Root 'template\*') $dir
            }
            New-Item -ItemType Directory -Force (Join-Path $dir 'mods') | Out-Null
            Copy-Item (Join-Path $Root 'mods\*.jar') (Join-Path $dir 'mods') -Force
            Set-Content (Join-Path $dir 'eula.txt') 'eula=true' -Encoding ASCII
            $mcPort = $port + 1000
            @"
online-mode=false
server-port=$mcPort
server-ip=127.0.0.1
level-name=world
level-seed=$Seed
level-type=DEFAULT
generate-structures=false
spawn-monsters=true
spawn-animals=true
spawn-npcs=false
difficulty=2
gamemode=0
pvp=true
max-players=1
view-distance=4
snooper-enabled=false
enable-query=false
enable-rcon=false
motd=aiguide rl $port
"@ | Set-Content (Join-Path $dir 'server.properties') -Encoding ASCII
            $jargs = "-Xms256M -Xmx$Xmx -XX:+UseG1GC -Dfile.encoding=UTF-8 -Daiguide.rl=$port -Daiguide.rl.fast=$Fast -Daiguide.rl.dir=$dir -Daiguide.places=false -Daiguide.record=false -jar forge-1.7.10-10.13.4.1614-1.7.10-universal.jar nogui"
            $cmd = "cmd.exe /c `"`"$Java`" $jargs > stdout.txt 2> stderr.txt`""
            $r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = $cmd; CurrentDirectory = $dir }
            Start-Sleep -Seconds 2
            $j = Ours | Where-Object { $_.CommandLine -match "-Daiguide\.rl=$port\b" }
            foreach ($p in $j) { Invoke-CimMethod -InputObject $p -MethodName SetPriority -Arguments @{ Priority = 16384 } | Out-Null }
            Write-Output ("started env {0} (minecraft port {1}) cmd pid {2} java pid {3}" -f $port, $mcPort, $r.ProcessId, (($j | ForEach-Object ProcessId) -join ','))
        }
    }
}
