# XS Chat 版本自动递增 + 构建 + APK 输出（dist 目录中旧 APK 自动替换）
# 用法: powershell -ExecutionPolicy Bypass -File bump-version.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path
$gradle = Join-Path $root "app\build.gradle.kts"
$content = Get-Content -LiteralPath $gradle -Raw

$vcMatch = [regex]::Match($content, 'versionCode = (\d+)')
$vnMatch = [regex]::Match($content, 'versionName = "([^"]+)"')
if (-not $vcMatch.Success -or -not $vnMatch.Success) { throw "未找到版本字段" }

$oldCode = [int]$vcMatch.Groups[1].Value
$oldName = $vnMatch.Groups[1].Value
$versionCode = $oldCode + 1
$parts = $oldName -split '\.'
$parts[-1] = ([int]$parts[-1] + 1).ToString()
$versionName = $parts -join '.'

$content = $content.Replace("versionCode = $oldCode", "versionCode = $versionCode")
$content = $content.Replace('versionName = "' + $oldName + '"', 'versionName = "' + $versionName + '"')
Set-Content -LiteralPath $gradle -Value $content -Encoding UTF8
Write-Host "[1/3] 版本: $oldName ($oldCode) -> $versionName ($versionCode)"

$env:JAVA_HOME = "C:\Users\Administrator\.jdks\jbr-17.0.14"
Push-Location $root
Write-Host "[2/3] 构建中..."
& .\gradlew.bat :app:assembleDebug --console=plain
$code = $LASTEXITCODE
Pop-Location
if ($code -ne 0) { throw "构建失败 (exit $code)" }

$apk = Join-Path $root "app\build\outputs\apk\debug\app-debug.apk"
$dist = Join-Path $root "dist"
New-Item -ItemType Directory -Force -Path $dist | Out-Null
Get-ChildItem -LiteralPath $dist -Filter "XS-Chat-*.apk" -ErrorAction SilentlyContinue | Remove-Item -Force
$out = Join-Path $dist "XS-Chat-$versionName.apk"
Copy-Item -LiteralPath $apk -Destination $out -Force
Write-Host "[3/3] 已输出（旧 APK 已替换）: $out"