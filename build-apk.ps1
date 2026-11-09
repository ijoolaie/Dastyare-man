[CmdletBinding()]
param(
    [ValidateSet('debug','release')]
    [string]$BuildType = 'debug',
    [switch]$SkipSdkInstall
)

$ErrorActionPreference = 'Stop'
$ProjectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$ToolsRoot = Join-Path $ProjectRoot '.tools'
$GradleVersion = '8.7'
$GradleHome = Join-Path $ToolsRoot "gradle-$GradleVersion"
$GradleZip = Join-Path $ToolsRoot "gradle-$GradleVersion-bin.zip"

function Fail([string]$Message) {
    Write-Host "ERROR: $Message" -ForegroundColor Red
    exit 1
}

# This project uses Android Gradle Plugin 8.5.2 and Gradle 8.7; use JDK 17 or 21.
$java = Get-Command java -ErrorAction SilentlyContinue
if (-not $java) { Fail 'Java was not found. Install JDK 17 or 21 and configure JAVA_HOME/PATH.' }
# Read both output streams with .NET Process to avoid PowerShell 5.1 treating
# java.exe stderr output from `java -version` as a terminating NativeCommandError.
try {
    $javaInfo = New-Object System.Diagnostics.ProcessStartInfo
    $javaInfo.FileName = $java.Source
    $javaInfo.Arguments = '-version'
    $javaInfo.UseShellExecute = $false
    $javaInfo.RedirectStandardOutput = $true
    $javaInfo.RedirectStandardError = $true
    $javaInfo.CreateNoWindow = $true
    $javaProcess = New-Object System.Diagnostics.Process
    $javaProcess.StartInfo = $javaInfo
    [void]$javaProcess.Start()
    $javaStdOut = $javaProcess.StandardOutput.ReadToEnd()
    $javaStdErr = $javaProcess.StandardError.ReadToEnd()
    $javaProcess.WaitForExit()
    $javaVersionText = $javaStdOut + "`n" + $javaStdErr
    $javaExitCode = $javaProcess.ExitCode
    $javaProcess.Dispose()
} catch {
    Fail "Could not execute Java. Check JAVA_HOME and PATH. Details: $_"
}
if ($javaExitCode -ne 0) { Fail 'Java execution failed. Check JAVA_HOME and PATH.' }
if ($javaVersionText -notmatch 'version "(?:1\.)?(\d+)') { Fail 'Could not detect Java version. Install JDK 17 or 21.' }
$javaMajor = [int]$Matches[1]
# This project pins Gradle 8.7 / Android Gradle Plugin 8.5.2. JDK 25 is too new
# to run this Gradle version; use a supported JDK (17 or 21).
if ($javaMajor -lt 17 -or $javaMajor -gt 21) { Fail "Detected Java $javaMajor. This project requires JDK 17 or 21 (Java 25 is not supported by the pinned Gradle 8.7)." }

# Find Android SDK.
$sdk = $env:ANDROID_SDK_ROOT
if (-not $sdk) { $sdk = $env:ANDROID_HOME }
if (-not $sdk -and $env:LOCALAPPDATA) {
    $candidate = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
    if (Test-Path $candidate) { $sdk = $candidate }
}
if (-not $sdk -or -not (Test-Path $sdk)) {
    Fail 'Android SDK was not found. Install Android SDK and set ANDROID_SDK_ROOT. See BUILD-APK.md.'
}
$sdk = (Resolve-Path $sdk).Path

# Install required SDK packages when sdkmanager is available.
$platformJar = Join-Path $sdk 'platforms\android-34\android.jar'
$buildTools = Join-Path $sdk 'build-tools\34.0.0'
if ((-not (Test-Path $platformJar) -or -not (Test-Path $buildTools)) -and -not $SkipSdkInstall) {
    $sdkManagerCandidates = @(
        (Join-Path $sdk 'cmdline-tools\latest\bin\sdkmanager.bat'),
        (Join-Path $sdk 'cmdline-tools\bin\sdkmanager.bat'),
        (Join-Path $sdk 'tools\bin\sdkmanager.bat')
    )
    $sdkManager = $sdkManagerCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if ($sdkManager) {
        Write-Host 'Installing Android SDK Platform 34 and Build Tools 34.0.0...' -ForegroundColor Cyan
        & $sdkManager "--sdk_root=$sdk" 'platforms;android-34' 'build-tools;34.0.0'
        if ($LASTEXITCODE -ne 0) { Fail 'Android SDK component installation failed. Accept licenses in sdkmanager and retry.' }
    }
}
if (-not (Test-Path $platformJar)) { Fail "Android SDK Platform 34 is missing: $platformJar`nInstall platforms;android-34 using SDK Manager." }
if (-not (Test-Path $buildTools)) { Fail "Android Build Tools 34.0.0 is missing: $buildTools`nInstall build-tools;34.0.0 using SDK Manager." }

# Persist the SDK path for Gradle without overwriting a user-managed local.properties.
$localProperties = Join-Path $ProjectRoot 'local.properties'
if (-not (Test-Path $localProperties)) {
    $sdkForGradle = $sdk.Replace('\','/')
    Set-Content -Path $localProperties -Value "sdk.dir=$sdkForGradle" -Encoding ASCII
}

# Use a project-local Gradle distribution; no global Gradle installation required.
New-Item -ItemType Directory -Force -Path $ToolsRoot | Out-Null
$gradleExe = Join-Path $GradleHome 'bin\gradle.bat'
if (-not (Test-Path $gradleExe)) {
    if (-not (Test-Path $GradleZip)) {
        $url = "https://services.gradle.org/distributions/gradle-$GradleVersion-bin.zip"
        Write-Host "Downloading Gradle $GradleVersion..." -ForegroundColor Cyan
        try { Invoke-WebRequest -Uri $url -OutFile $GradleZip -UseBasicParsing }
        catch { Fail "Gradle download failed. Check internet/proxy and retry. Details: $_" }
    }
    Write-Host 'Extracting Gradle...' -ForegroundColor Cyan
    Expand-Archive -Path $GradleZip -DestinationPath $ToolsRoot -Force
}
if (-not (Test-Path $gradleExe)) { Fail 'Gradle executable was not found after extraction.' }

Write-Host "Building $BuildType APK..." -ForegroundColor Cyan
Push-Location $ProjectRoot
try {
    & $gradleExe --no-daemon "assemble$($BuildType.Substring(0,1).ToUpper())$($BuildType.Substring(1))"
    if ($LASTEXITCODE -ne 0) { Fail 'Gradle build failed. Review the error output above.' }
} finally {
    Pop-Location
}

$apkSource = Join-Path $ProjectRoot "app\build\outputs\apk\$BuildType\app-$BuildType.apk"
if (-not (Test-Path $apkSource)) { Fail "Build completed but APK was not found: $apkSource" }
$dist = Join-Path $ProjectRoot 'dist'
New-Item -ItemType Directory -Force -Path $dist | Out-Null
$apkTarget = Join-Path $dist "dastyar-$BuildType.apk"
Copy-Item $apkSource $apkTarget -Force
Write-Host ''
Write-Host 'BUILD SUCCESSFUL' -ForegroundColor Green
Write-Host "APK: $apkTarget" -ForegroundColor Green
