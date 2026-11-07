<#
Reversible install of a pinned AI Guide JAR pair into the normal TLauncher profile.
    tools\install_normal_profile.ps1 -Client <jar> -Server <jar> -ClientSha <sha256> -ServerSha <sha256>
    tools\install_normal_profile.ps1 -Rollback <backup dir>
Refuses a running game, a hash mismatch and an unexpected current JAR. Worlds are not touched.
#>
param([string]$Client,[string]$Server,[string]$ClientSha,[string]$ServerSha,[string]$Rollback,
      [string]$Mods="$env:APPDATA\.minecraft\mods",
      [string]$ExpectClient='327A3C3D1EC65D84619AA0E68647FB01F08D672A99FB5D6FB35F72DB310ADF30',
      [string]$ExpectServer='49453BD39665E38C0E57AC925B841113B941AABEC621A5926EFBDF0EC1820B94')
$ErrorActionPreference='Stop'
function Sha($p){(Get-FileHash -LiteralPath $p -Algorithm SHA256).Hash}
if(Get-Process java,javaw -ErrorAction SilentlyContinue | Where-Object {$_.MainWindowTitle -match 'Minecraft'}){throw 'Minecraft is running'}
$c=Join-Path $Mods 'AIGuideClient-0.2.jar'; $s=Join-Path $Mods 'AIGuide-0.4.jar'
if($Rollback){
  foreach($n in 'AIGuideClient-0.2.jar','AIGuide-0.4.jar'){Copy-Item -LiteralPath (Join-Path $Rollback $n) -Destination (Join-Path $Mods $n) -Force}
  "rolled back: client $(Sha $c) server $(Sha $s)"; return
}
if((Sha $Client) -ine $ClientSha -or (Sha $Server) -ine $ServerSha){throw 'new JAR hash mismatch'}
if((Sha $c) -ine $ExpectClient -or (Sha $s) -ine $ExpectServer){throw 'current JARs are not the expected ones; pass -ExpectClient/-ExpectServer after looking at them'}
$b=Join-Path (Split-Path $Mods) ('mods-backup-aiguide-'+(Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $b|Out-Null
Copy-Item -LiteralPath $c,$s -Destination $b
Copy-Item -LiteralPath $Client -Destination $c -Force; Copy-Item -LiteralPath $Server -Destination $s -Force
if((Sha $c) -ine $ClientSha -or (Sha $s) -ine $ServerSha){throw "copy mismatch; roll back from $b"}
"installed; rollback: tools\install_normal_profile.ps1 -Rollback '$b'"
