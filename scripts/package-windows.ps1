[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

if ([Environment]::OSVersion.Platform -ne [PlatformID]::Win32NT) {
    throw 'PikaDesk Windows app-image packaging must run on Windows.'
}

$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$maven = Join-Path $repoRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $maven -PathType Leaf)) {
    throw 'Maven Wrapper is missing.'
}

$jdkBin = if ([string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
    Split-Path -Parent (Get-Command javac.exe -ErrorAction Stop).Source
} else {
    Join-Path $env:JAVA_HOME 'bin'
}
$java = Join-Path $jdkBin 'java.exe'
$javac = Join-Path $jdkBin 'javac.exe'
$jlink = Join-Path $jdkBin 'jlink.exe'
$jpackage = Join-Path $jdkBin 'jpackage.exe'
foreach ($tool in @($java, $javac, $jlink, $jpackage)) {
    if (-not (Test-Path -LiteralPath $tool -PathType Leaf)) {
        throw "Required JDK tool is missing: $tool"
    }
}
$javacVersion = (& $javac -version 2>&1 | Out-String).Trim()
if ($LASTEXITCODE -ne 0 -or $javacVersion -notmatch '^javac 21(?:\.|$)') {
    throw "JDK 21 is required; detected: $javacVersion"
}

function Assert-LastExitCode([string]$operation) {
    if ($LASTEXITCODE -ne 0) {
        throw "$operation failed with exit code $LASTEXITCODE"
    }
}

Push-Location $repoRoot
try {
    & $maven --batch-mode clean verify
    Assert-LastExitCode 'Maven clean verify'

    $target = Join-Path $repoRoot 'target'
    $packageInput = Join-Path $target 'windows-package-input'
    New-Item -ItemType Directory -Path $packageInput | Out-Null

    & $maven --batch-mode dependency:copy-dependencies `
        '-DincludeScope=runtime' `
        "-DoutputDirectory=$packageInput"
    Assert-LastExitCode 'Runtime dependency staging'

    $applicationJars = @(Get-ChildItem -LiteralPath $target -File `
        -Filter 'pikadesk-*.jar')
    if ($applicationJars.Count -ne 1) {
        throw "Expected exactly one PikaDesk application JAR; found $($applicationJars.Count)."
    }
    Copy-Item -LiteralPath $applicationJars[0].FullName `
        -Destination (Join-Path $packageInput 'pikadesk.jar')

    Copy-Item -LiteralPath (Join-Path $repoRoot 'target\classes\model') `
        -Destination (Join-Path $packageInput 'model') -Recurse
    Copy-Item -LiteralPath (Join-Path $repoRoot 'target\classes\sound') `
        -Destination (Join-Path $packageInput 'sound') -Recurse
    Copy-Item -LiteralPath (Join-Path $repoRoot 'target\classes\ui') `
        -Destination (Join-Path $packageInput 'ui') -Recurse

    $developmentNotice = Join-Path $target 'DEVELOPMENT-ONLY.txt'
    @'
PikaDesk development app-image only.

This artifact is for local, offline validation. Do not publish or redistribute
it while docs/third-party.md lists the bundled recognition model as a release
blocker. It is not an installer and does not bypass any commercial license.
'@ | Set-Content -LiteralPath $developmentNotice -Encoding utf8

    $runtimeImage = Join-Path $target 'windows-runtime'
    & $jlink `
        --add-modules 'java.desktop,java.sql,java.sql.rowset,java.scripting,java.logging,jdk.unsupported,jdk.unsupported.desktop,jdk.jfr' `
        --output $runtimeImage `
        --strip-debug `
        --no-man-pages `
        --no-header-files
    Assert-LastExitCode 'JDK runtime image creation'

    $destination = Join-Path $target 'windows-app-image'
    $appContent = @(
        (Join-Path $repoRoot 'LICENSE'),
        (Join-Path $repoRoot 'NOTICE.md'),
        (Join-Path $repoRoot 'docs\third-party.md'),
        $developmentNotice
    ) -join ','
    & $jpackage `
        --type app-image `
        --name PikaDesk `
        --module-path $packageInput `
        --module 'Xiangqi/com.sojourners.chess.Main' `
        --runtime-image $runtimeImage `
        --dest $destination `
        --app-version '0.1.0' `
        --vendor 'PikaDesk' `
        --description 'Local-first Chinese chess workstation' `
        --app-content $appContent
    Assert-LastExitCode 'Windows app-image creation'

    $image = Join-Path $destination 'PikaDesk'
    $required = @(
        (Join-Path $image 'PikaDesk.exe'),
        (Join-Path $image 'runtime\bin\java.exe'),
        (Join-Path $image 'app\mods\pikadesk.jar'),
        (Join-Path $image 'app\mods\model\yolov11.onnx'),
        (Join-Path $image 'app\mods\ui\board.png'),
        (Join-Path $image 'app\mods\sound\click.wav'),
        (Join-Path $image 'DEVELOPMENT-ONLY.txt')
    )
    foreach ($path in $required) {
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
            throw "Packaged artifact is incomplete: $path"
        }
    }

    $hashManifest = Join-Path $image 'SHA256SUMS.txt'
    $imagePrefix = $image.TrimEnd('\') + '\'
    $hashLines = Get-ChildItem -LiteralPath $image -Recurse -File |
        Where-Object { $_.FullName -ne $hashManifest } |
        Sort-Object FullName |
        ForEach-Object {
            if (-not $_.FullName.StartsWith(
                    $imagePrefix, [StringComparison]::OrdinalIgnoreCase)) {
                throw "Packaged file escaped the image directory: $($_.FullName)"
            }
            $relative = $_.FullName.Substring($imagePrefix.Length).Replace('\', '/')
            $hash = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
            "$hash  $relative"
        }
    $hashLines | Set-Content -LiteralPath $hashManifest -Encoding ascii

    $files = @(Get-ChildItem -LiteralPath $image -Recurse -File)
    $bytes = ($files | Measure-Object -Property Length -Sum).Sum
    Write-Host "PikaDesk development app-image created: $image"
    Write-Host "Files: $($files.Count); bytes: $bytes"
    Write-Warning 'Development-only: the bundled recognition model still blocks public redistribution.'
} finally {
    Pop-Location
}
