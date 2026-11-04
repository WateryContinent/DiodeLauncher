<#
.SYNOPSIS
  Builds launcher.jar and packages it as a Windows installer with jpackage.

.DESCRIPTION
  1. Compiles the launcher and assembles dist\launcher.jar (same contents as
     the IntelliJ "launcher-release" artifact), unless -UseExistingJar.
  2. Uses jlink to make a small Java 21 runtime to bundle, so players don't
     need Java installed.
  3. Runs jpackage to produce the installer in dist\installer\.

  The installer is per-user (installs to %LOCALAPPDATA%, no admin prompt) so
  the launcher can keep updating its own launcher.jar. Installed copies keep
  game data in %APPDATA%\.diode; the portable app-image keeps it in its app
  folder.

  -Type exe / msi need WiX Toolset 3.14; JDK 21's jpackage doesn't support
  WiX 4 or newer. It is found on PATH, via -WixDir, or via the WIX
  environment variable (set by the WiX installer, or by hand to the folder
  the wix314-binaries.zip was extracted to).
  -Type app-image needs nothing extra and produces a portable folder + .zip.

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File installer\build-installer.ps1 -Type app-image

.EXAMPLE
  powershell -ExecutionPolicy Bypass -File installer\build-installer.ps1 -Type exe
#>
param(
   [ValidateSet('exe', 'msi', 'app-image')]
   [string]$Type = 'exe',
   # JDK 21 used to compile, jlink the bundled runtime and run jpackage.
   [string]$Jdk = $env:JAVA_HOME,
   # Folder containing WiX 3 candle.exe/light.exe, if not on PATH or in WIX.
   [string]$WixDir = '',
   # Package dist\launcher.jar as-is (e.g. built by IntelliJ) instead of compiling.
   [switch]$UseExistingJar
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$appName = 'Diode Launcher'
# Never change this: Windows uses it to recognise new installers as upgrades.
$upgradeUuid = 'b08dff95-e019-49ec-ab7a-948fd1df0012'
# Modules the launcher, its old libraries and the downloaded JavaFX WebView need.
$runtimeModules = @(
   'java.se', 'jdk.unsupported', 'jdk.unsupported.desktop', 'jdk.jsobject', 'jdk.xml.dom',
   'jdk.crypto.ec', 'jdk.crypto.cryptoki', 'jdk.charsets', 'jdk.localedata', 'jdk.zipfs',
   'jdk.net', 'jdk.management', 'jdk.accessibility', 'jdk.naming.dns'
) -join ','

$build = Join-Path $root 'build\installer'
$dist = Join-Path $root 'dist'
$launcherJar = Join-Path $dist 'launcher.jar'
$manifest = Join-Path $root 'src\launcher\src\main\resources\META-INF\MANIFEST.MF'

function Invoke-Tool([string]$exe, [string[]]$arguments) {
   # Native tools write progress to stderr; don't let PowerShell treat that as failure.
   $previous = $ErrorActionPreference
   $ErrorActionPreference = 'Continue'
   & $exe @arguments 2>&1 | ForEach-Object { "$_" }
   $code = $LASTEXITCODE
   $ErrorActionPreference = $previous
   if ($code -ne 0) {
      throw "$(Split-Path -Leaf $exe) failed with exit code $code"
   }
}

function Find-Jdk([string]$candidate) {
   if (-not $candidate) {
      $javac = Get-Command javac -ErrorAction SilentlyContinue
      if (-not $javac) {
         throw 'No JDK found. Pass -Jdk or set JAVA_HOME to a JDK 21 install.'
      }
      $candidate = Split-Path -Parent (Split-Path -Parent $javac.Source)
   }
   if (-not (Test-Path (Join-Path $candidate 'bin\jlink.exe'))) {
      throw "$candidate doesn't look like a JDK (no bin\jlink.exe)."
   }
   return $candidate
}

# Windows accepts PNG data inside an .ico, so wrap favicon.png instead of
# keeping a separate binary icon in the repo.
function New-IcoFromPng([string]$png, [string]$ico) {
   $bytes = [System.IO.File]::ReadAllBytes($png)
   $width = [byte]([System.Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($bytes, 16)) -band 0xFF)
   $height = [byte]([System.Net.IPAddress]::NetworkToHostOrder([BitConverter]::ToInt32($bytes, 20)) -band 0xFF)
   $stream = New-Object System.IO.MemoryStream
   $writer = New-Object System.IO.BinaryWriter($stream)
   $writer.Write([UInt16]0); $writer.Write([UInt16]1); $writer.Write([UInt16]1)
   $writer.Write($width); $writer.Write($height); $writer.Write([byte]0); $writer.Write([byte]0)
   $writer.Write([UInt16]1); $writer.Write([UInt16]32)
   $writer.Write([UInt32]$bytes.Length); $writer.Write([UInt32]22)
   $writer.Write($bytes)
   $writer.Flush()
   [System.IO.File]::WriteAllBytes($ico, $stream.ToArray())
}

function Add-WixToPath([string]$candidate) {
   if (Get-Command candle.exe -ErrorAction SilentlyContinue) {
      return
   }
   # Read WIX from the registry too: terminals opened before it was set won't have it.
   $wixHomes = @($env:WIX, [Environment]::GetEnvironmentVariable('WIX', 'User'), [Environment]::GetEnvironmentVariable('WIX', 'Machine')) |
      Where-Object { $_ }
   $folders = @($candidate) + @($wixHomes | ForEach-Object { $_; Join-Path $_ 'bin' }) +
      @(Get-ChildItem "${env:ProgramFiles(x86)}\WiX Toolset v3*\bin" -Directory -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
   foreach ($folder in $folders) {
      if ($folder -and (Test-Path (Join-Path $folder 'candle.exe'))) {
         Write-Host "  WiX: $folder"
         $env:PATH = "$folder;$env:PATH"
         return
      }
   }
}

$Jdk = Find-Jdk $Jdk
if ($Type -ne 'app-image') {
   Add-WixToPath $WixDir
}

$manifestText = Get-Content -Raw $manifest
if ($manifestText -notmatch 'Implementation-Version:[^\r\n]*?(\d+(\.\d+){0,2})') {
   throw "Couldn't read Implementation-Version from $manifest"
}
$version = $Matches[1]
Write-Host "Packaging $appName $version ($Type)"
Write-Host "  JDK: $Jdk"

if (Test-Path $build) {
   Remove-Item -Recurse -Force $build
}
New-Item -ItemType Directory -Force $build, $dist | Out-Null

if ($UseExistingJar) {
   if (-not (Test-Path $launcherJar)) {
      throw "-UseExistingJar was given but $launcherJar doesn't exist."
   }
   Write-Host "Using existing $launcherJar"
} else {
   Write-Host 'Compiling launcher...'
   $dependencies = Join-Path $root 'src\launcher\lib\original-launcher-dependencies.jar'
   $classes = Join-Path $build 'classes'
   $jarContents = Join-Path $build 'jar'
   New-Item -ItemType Directory -Force $classes, $jarContents | Out-Null

   # javac argument files treat backslashes as escapes, so use forward slashes.
   $sourceList = Join-Path $build 'sources.txt'
   Get-ChildItem -Recurse -Filter *.java (Join-Path $root 'src\launcher\src\main\java') |
      ForEach-Object { '"' + ($_.FullName -replace '\\', '/') + '"' } |
      Set-Content -Encoding ASCII $sourceList
   Invoke-Tool (Join-Path $Jdk 'bin\javac.exe') @('-nowarn', '-encoding', 'UTF-8', '-d', $classes, '-cp', $dependencies, "@$sourceList")

   Write-Host 'Assembling launcher.jar...'
   Push-Location $jarContents
   try {
      Invoke-Tool (Join-Path $Jdk 'bin\jar.exe') @('xf', $dependencies)
   } finally {
      Pop-Location
   }
   Copy-Item -Recurse -Force (Join-Path $classes '*') $jarContents
   Copy-Item -Recurse -Force (Join-Path $root 'src\launcher\src\main\resources\*') $jarContents
   Get-ChildItem (Join-Path $jarContents 'META-INF') -Include 'MANIFEST.MF', '*.SF', '*.RSA', '*.DSA' -Recurse |
      Remove-Item -Force
   if (Test-Path $launcherJar) {
      Remove-Item -Force $launcherJar
   }
   Invoke-Tool (Join-Path $Jdk 'bin\jar.exe') @('cfm', $launcherJar, $manifest, '-C', $jarContents, '.')
   Write-Host "Built $launcherJar"
}

Write-Host 'Creating bundled Java runtime...'
$runtime = Join-Path $build 'runtime'
# Native commands (bin\java.exe) are kept on purpose: the self-updater and
# older Minecraft versions without a Mojang runtime both need them.
Invoke-Tool (Join-Path $Jdk 'bin\jlink.exe') @(
   '--add-modules', $runtimeModules, '--strip-debug', '--no-man-pages', '--no-header-files',
   '--compress=zip-6', '--output', $runtime
)

$inputDir = Join-Path $build 'input'
New-Item -ItemType Directory -Force $inputDir | Out-Null
Copy-Item $launcherJar $inputDir
$icon = Join-Path $build 'diode.ico'
New-IcoFromPng (Join-Path $root 'src\launcher\src\main\resources\favicon.png') $icon

$output = Join-Path $dist 'installer'
New-Item -ItemType Directory -Force $output | Out-Null
$jpackageArgs = @(
   '--type', $Type,
   '--name', $appName,
   '--app-version', $version,
   '--vendor', 'WateryContinent',
   '--description', 'Diode - Minecraft Legacy Launcher',
   '--input', $inputDir,
   '--main-jar', 'launcher.jar',
   '--main-class', 'net.minecraft.launcher.Main',
   '--runtime-image', $runtime,
   '--icon', $icon,
   '--dest', $output
)
if ($Type -eq 'app-image') {
   $imageDir = Join-Path $output $appName
   if (Test-Path $imageDir) {
      Remove-Item -Recurse -Force $imageDir
   }
} else {
   $jpackageArgs += @(
      # The uninstaller deletes the whole install folder, so installed copies
      # keep game data in %APPDATA%\.diode (see Main.getWorkingDirectory).
      '--java-options', '-Ddiode.installed=true',
      '--win-per-user-install',
      '--win-menu', '--win-menu-group', 'Diode',
      '--win-shortcut', '--win-shortcut-prompt',
      '--win-upgrade-uuid', $upgradeUuid
   )
}

Write-Host 'Running jpackage...'
try {
   Invoke-Tool (Join-Path $Jdk 'bin\jpackage.exe') $jpackageArgs
} catch {
   if ($Type -ne 'app-image') {
      Write-Host ''
      Write-Host 'jpackage failed. If it mentions WiX, install WiX Toolset 3.14 from' -ForegroundColor Yellow
      Write-Host '  https://github.com/wixtoolset/wix3/releases and make sure its bin folder is on PATH.'
      Write-Host '  Or build a portable folder instead: -Type app-image'
   }
   throw
}

if ($Type -eq 'app-image') {
   $zip = Join-Path $output "$appName-$version-portable.zip"
   if (Test-Path $zip) {
      Remove-Item -Force $zip
   }
   Compress-Archive -Path $imageDir -DestinationPath $zip
   Write-Host "Done: $imageDir"
   Write-Host "      $zip"
} else {
   Write-Host "Done: $(Get-ChildItem $output -Filter "*.$Type" | Sort-Object LastWriteTime | Select-Object -Last 1 -ExpandProperty FullName)"
}
