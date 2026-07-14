# 鎶婃簮鐮佸寘鍚嶄粠鏃ф爣璇嗘敼鎴?RoboRumble 鍞竴鍖呭悕锛堜笉鍚笅鍒掔嚎锛夈€?
# 鐢ㄦ硶: .\scripts\rename-package.ps1 -NewPackage pc.Wavelet
# 浼氭敼: package 澹版槑銆佽剼鏈粯璁ゆ満鍣ㄤ汉鍚嶃€丷EADME 鎻愬強澶勶紱绫诲悕 Wavelet 鍙繚鐣欍€?
param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[a-z][a-z0-9]*(\.[a-zA-Z][a-zA-Z0-9]*)+$')]
    [string]$NewPackage,   # 渚? pc.Wavelet  鈫?鐩綍 src/pc/Wavelet.java锛宑lassname=pc.Wavelet
    [string]$OldPackage = "rcr.Wavelet",
    [string]$OldDir = "rcr"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$root = Split-Path -Parent $PSScriptRoot

# NewPackage = author.BotName 鈫?鐩綍 = author, 绫?= BotName
$parts = $NewPackage.Split(".")
if ($parts.Length -lt 2) { Write-Error "NewPackage 鑷冲皯涓ゆ锛屽 pc.Wavelet" }
$className = $parts[-1]
$pkgDir = ($parts[0..($parts.Length - 2)] -join "\")
$javaPackage = ($parts[0..($parts.Length - 2)] -join ".")

$oldSrc = Join-Path $root "src\$OldDir"
$newSrc = Join-Path $root "src\$pkgDir"
if (-not (Test-Path $oldSrc)) { Write-Error "鎵句笉鍒版棫婧愮爜鐩綍 $oldSrc" }
if ((Test-Path $newSrc) -and ($newSrc -ne $oldSrc)) {
    Write-Error "鐩爣鐩綍宸插瓨鍦? $newSrc"
}

Write-Host "Rename: $OldPackage  ->  $NewPackage"
Write-Host "  java package: $javaPackage"
Write-Host "  class:        $className"
Write-Host "  src dir:      src\$pkgDir"

# 1) 鎼洰褰?
if ($newSrc -ne $oldSrc) {
    New-Item -ItemType Directory -Force -Path (Split-Path $newSrc) | Out-Null
    Move-Item $oldSrc $newSrc
}

# 2) 鑻ョ被鍚嶅彉鏇达紝閲嶅懡鍚嶄富绫绘枃浠?
$oldClassFile = Join-Path $newSrc "Wavelet.java"
$newClassFile = Join-Path $newSrc "$className.java"
if ($className -ne "Wavelet") {
    if (-not (Test-Path $oldClassFile)) { Write-Error "鎵句笉鍒?Wavelet.java" }
    Move-Item $oldClassFile $newClassFile
}

# 3) 鏀规墍鏈?.java 鐨?package 澹版槑涓庣被鍚嶅紩鐢?
Get-ChildItem $newSrc -Filter *.java | ForEach-Object {
    $text = Get-Content $_.FullName -Raw -Encoding UTF8
    $text = $text -replace "package rcr;", "package $javaPackage;"
    $text = $text -replace "\brcr\.", "$javaPackage."
    if ($className -ne "Wavelet") {
        $text = $text -replace "\bclass Wavelet\b", "class $className"
        $text = $text -replace "\bWavelet\b", $className
    }
    [System.IO.File]::WriteAllText($_.FullName, $text, [System.Text.UTF8Encoding]::new($false))
    Write-Host "  updated $($_.Name)"
}

# 4) 鑴氭湰 / README 閲岀殑鏈哄櫒浜哄悕
$replacements = @{
    "rcr.Wavelet" = $NewPackage
    "rcr\.Wavelet" = [regex]::Escape($NewPackage) # for regex contexts we'll do carefully
}
$files = @(
    "scripts\build.ps1",
    "scripts\run-battle.ps1",
    "scripts\testbed.ps1",
    "scripts\hardbed.ps1",
    "scripts\datagen.ps1",
    "README.md"
)
foreach ($rel in $files) {
    $path = Join-Path $root $rel
    if (-not (Test-Path $path)) { continue }
    $text = Get-Content $path -Raw -Encoding UTF8
    $orig = $text
    $text = $text.Replace("rcr.Wavelet", $NewPackage)
    $text = $text.Replace("rcr\Wavelet", ($pkgDir + "\" + $className))
    $text = $text.Replace("package rcr", "package $javaPackage")
    # testbed 瑙ｆ瀽姝ｅ垯
    $text = $text.Replace("rcr\.Wavelet", ($NewPackage -replace '\.', '\.'))
    if ($text -ne $orig) {
        [System.IO.File]::WriteAllText($path, $text, [System.Text.UTF8Encoding]::new($true))
        Write-Host "  patched $rel"
    }
}

Write-Host ""
Write-Host "瀹屾垚銆備笅涓€姝?"
Write-Host "  1. .\scripts\build.ps1"
Write-Host "  2. .\scripts\package-upload.ps1 -Version 1.0 -RobocodeHome C:\robocode-1.9.4.2"
Write-Host "  3. 鍦?1.9.4.2 涓婅窇 testbed / hardbed 鍥炲綊"
