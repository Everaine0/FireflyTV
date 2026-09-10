# Offline build entry point for this machine.
#
# Why: services.gradle.org is unreachable here, so the Gradle Wrapper cannot
# download its distribution, but Gradle 8.11.1 already exists in the local
# cache. This script builds with that copy and needs no network.
# The standard `gradlew` still works on any machine with internet access.
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Tasks = @('assembleDebug')
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot

$candidates = @(
    "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.11.1-bin\*\gradle-8.11.1\bin\gradle.bat",
    "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.11.1-all\*\gradle-8.11.1\bin\gradle.bat"
)
$gradleBat = $null
foreach ($pattern in $candidates) {
    $hit = Get-ChildItem $pattern -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($hit) { $gradleBat = $hit.FullName; break }
}

if (-not $gradleBat) {
    Write-Error "Gradle 8.11.1 not found in the local wrapper cache. Install Gradle or use gradlew with network access."
}

if (-not $env:JAVA_HOME) { $env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-17.0.18.8-hotspot' }
if (-not $env:ANDROID_HOME) { $env:ANDROID_HOME = '<Android SDK>' }
if (-not $env:ANDROID_SDK_ROOT) { $env:ANDROID_SDK_ROOT = $env:ANDROID_HOME }

Write-Host "gradle : $gradleBat"
Write-Host "tasks  : $($Tasks -join ' ')"
& $gradleBat -p $root @Tasks
exit $LASTEXITCODE
