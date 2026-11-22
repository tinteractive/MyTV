# SPDX-License-Identifier: GPL-3.0-only
# MyTV Launcher installer for Windows. Run in PowerShell:
#   irm https://raw.githubusercontent.com/tinteractive/MyTV/main/install/windows.ps1 | iex
# It downloads Google's adb (into a temporary folder), finds the TV on the home network, installs the newest
# MyTV Launcher, gives it its extra permission and opens it. Nothing is installed on this computer.
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'
$Pkg = 'com.tinteractive.mytv'
$ApkUrl = 'https://github.com/tinteractive/MyTV/releases/latest/download/MyTV-Launcher.apk'
$ToolsUrl = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
$Work = Join-Path $env:TEMP 'mytv-install'

function Step($n, $t) { Write-Host ""; Write-Host "[$n/5] $t" -ForegroundColor Cyan }
function Say($t) { Write-Host "      $t" }
function Ask($t) { Read-Host "      $t" }
# Stops the installer with a message. No exit: under 'irm | iex' that would close the user's PowerShell window.
function Fail($t) { throw [System.OperationCanceledException]::new($t) }
# adb writes normal messages to its error stream; Windows PowerShell would stop on those. Returns all output as text,
# with adb's exit code in $LASTEXITCODE.
function Adb { $old = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { (& $Adb @args 2>&1 | ForEach-Object { "$_" }) -join "`n" } finally { $ErrorActionPreference = $old } }

try {
Write-Host "MyTV Launcher - installation" -ForegroundColor White

Step 1 "Prepare the TV"
Say "On the TV, with the remote:"
Say "  1. Settings > Device preferences (or System) > About > press OK on 'Build' seven times."
Say "  2. Settings > Device preferences > Developer options > switch on 'USB debugging'"
Say "     (newer Google TVs: 'Wireless debugging')."
Say "Keep the TV on, on the same network as this computer."
Ask "Press Enter when that is done" | Out-Null

Step 2 "Getting Google's adb (temporary, nothing is installed)"
New-Item -ItemType Directory -Force -Path $Work | Out-Null
$Adb = Join-Path $Work 'platform-tools\adb.exe'
if (-not (Test-Path $Adb)) {
    $zip = Join-Path $Work 'platform-tools.zip'
    Invoke-WebRequest -Uri $ToolsUrl -OutFile $zip -UseBasicParsing
    Expand-Archive -Path $zip -DestinationPath $Work -Force
}
Adb start-server | Out-Null
Say "Ready."

Step 3 "Looking for the TV"
# TVs with Wireless debugging announce themselves on the network; adb lists them (address:port).
$mdns = Adb mdns services
$wireless = @($mdns -split "`n" | Where-Object { $_ -match '_adb-tls-connect' } | ForEach-Object { ($_ -split '\s+')[-1] } | Sort-Object -Unique)
$pairing = @($mdns -split "`n" | Where-Object { $_ -match '_adb-tls-pairing' } | ForEach-Object { ($_ -split '\s+')[-1] } | Sort-Object -Unique)
# TVs with network debugging often announce themselves too (_adb._tcp), also from another part of the network.
$announced = @($mdns -split "`n" | Where-Object { $_ -match '\s_adb\._tcp\s' } | ForEach-Object { ($_ -split '\s+')[-1] })
# TVs with USB or network debugging listen on port 5555: look in this computer's part of the network (x.y.z.1-254).
$found = @()
$me = Get-NetIPAddress -AddressFamily IPv4 -ErrorAction SilentlyContinue |
    Where-Object { $_.IPAddress -notmatch '^(127|169\.254)\.' -and $_.PrefixOrigin -in 'Dhcp', 'Manual' } |
    Select-Object -First 1 -ExpandProperty IPAddress
if ($me) {
    $net = $me.Substring(0, $me.LastIndexOf('.'))
    $tries = 1..254 | ForEach-Object { $c = New-Object System.Net.Sockets.TcpClient; @{ Ip = "$net.$_"; Client = $c; Task = $c.ConnectAsync("$net.$_", 5555) } }
    Start-Sleep -Milliseconds 2500
    foreach ($t in $tries) {
        if ($t.Task.Status -eq 'RanToCompletion' -and $t.Client.Connected) { $found += "$($t.Ip):5555" }
        $t.Client.Dispose()
    }
}
$found = @($announced + $found | Where-Object { $_ } | Sort-Object -Unique)
$choices = @()
foreach ($w in $wireless) { if ($w) { $choices += [pscustomobject]@{ Target = $w; Wireless = $true } } }
foreach ($f in $found) { $choices += [pscustomobject]@{ Target = $f; Wireless = $false } }
if ($choices.Count -eq 0) {
    Say "No TV found by itself. Its address is under Settings > Network & Internet > your network."
    $ip = (Ask "TV address (for example 192.168.1.23)").Trim()
    if ($ip -notmatch '^[0-9]{1,3}(\.[0-9]{1,3}){3}$') { Fail "That is not an address like 192.168.1.23." }
    $choices = @([pscustomobject]@{ Target = "$ip`:5555"; Wireless = $false })
}
if ($choices.Count -eq 1) { $pick = $choices[0]; Say "Found: $($pick.Target)" }
else {
    Say "Found these devices:"
    for ($i = 0; $i -lt $choices.Count; $i++) {
        $extra = if ($choices[$i].Wireless) { '  (wireless debugging)' } else { '' }
        Say "  $($i + 1)) $($choices[$i].Target)$extra"
    }
    $k = (Ask "Which one is the TV? (number)").Trim()
    if ($k -notmatch '^[0-9]+$' -or [int]$k -lt 1 -or [int]$k -gt $choices.Count) { Fail "Please run this again and type one of the numbers." }
    $pick = $choices[[int]$k - 1]
}
$Target = $pick.Target

Step 4 "Connecting"
if ($pick.Wireless) {
    # A computer the TV does not know yet must pair once, with the code from the TV's own screen.
    if ((Adb connect $Target) -notmatch 'connected to') {
        Say "On the TV: Wireless debugging > Pair device with pairing code."
        $host_ = $Target.Substring(0, $Target.LastIndexOf(':'))
        $pair = $pairing | Where-Object { $_ -like "$host_`:*" } | Select-Object -First 1
        if (-not $pair) { $pair = "$host_`:" + (Ask "Port shown on the pairing screen (after the colon)").Trim() }
        $code = (Ask "Pairing code shown on the TV").Trim()
        Adb pair $pair $code | Out-Null
        if ($LASTEXITCODE -ne 0) { Fail "Pairing did not work. Check the code, and run this again." }
        Adb connect $Target | Out-Null
    }
} else {
    Say "The TV asks 'Allow USB debugging?': choose 'Always allow from this computer' and OK."
    Adb connect $Target | Out-Null
}
$state = ''
for ($i = 0; $i -lt 60; $i++) {
    $state = (Adb -s $Target get-state).Trim()
    if ($state -eq 'device') { break }
    Start-Sleep -Seconds 1
}
if ($state -ne 'device') { Fail "No connection. Check that debugging is on. If the TV never asked 'Allow USB debugging?': go to the TV's home screen, wait 10 seconds and run this again, or switch USB debugging off and on." }
$model = (Adb -s $Target shell getprop ro.product.model).Trim()
Say "Connected to $(if ($model) { $model } else { 'the TV' })."

Step 5 "Installing MyTV Launcher"
$Apk = Join-Path $Work 'MyTV-Launcher.apk'
Invoke-WebRequest -Uri $ApkUrl -OutFile $Apk -UseBasicParsing
$out = Adb -s $Target install -r $Apk
if ($LASTEXITCODE -ne 0) {
    if ($out -match 'INSUFFICIENT_STORAGE') { Fail "The TV is short of space. Remove apps you don't use (or old downloads), then run this again." }
    if ($out -match 'UPDATE_INCOMPATIBLE|SIGNATURE') { Fail "A MyTV signed with another key is installed. Remove that first, then run this again." }
    Fail "Installing failed: $($out.Trim())"
}
# The extra permission lets MyTV switch its Home-button helper on itself and manage its screensaver.
Adb -s $Target shell pm grant $Pkg android.permission.WRITE_SECURE_SETTINGS | Out-Null
Adb -s $Target shell am start -n "$Pkg/com.mytv.launcher.MainActivity" | Out-Null
Adb disconnect $Target | Out-Null
Adb kill-server | Out-Null

Write-Host ""
Write-Host "Done! MyTV Launcher is open on the TV; its welcome screen does the rest." -ForegroundColor Green
Say "You can switch debugging off again on the TV (Developer options)."
} catch [System.OperationCanceledException] {
    Write-Host ""; Write-Host $_.Exception.Message -ForegroundColor Yellow
} catch {
    Write-Host ""; Write-Host "Something went wrong: $($_.Exception.Message)" -ForegroundColor Yellow
    Write-Host "Check the internet connection and run this again." -ForegroundColor Yellow
}
