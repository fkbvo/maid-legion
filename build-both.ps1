# 女仆军团 / Maid Legion —— 一次构建两个分支，成品统一收进 dist\
#
# 用法:
#   .\build-both.ps1              # 构建两个版本
#   .\build-both.ps1 -Version 1.20   # 只构建 Forge 1.20.1
#
# 背景: build\libs\ 会被 `clean` 和切分支冲掉，且从不进 git。
#       所以每个版本一构建完就立刻拷到 dist\，两份产物互不覆盖。

[CmdletBinding()]
param(
    [ValidateSet('all', '1.20', '1.21')]
    [string]$Version = 'all'
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$RepoRoot = $PSScriptRoot
$DistDir  = Join-Path $RepoRoot 'dist'

# 各分支的 JDK。可用环境变量覆盖，避免把本机路径写死进仓库。
$Jdk = @{
    '1.20' = if ($env:MAID_LEGION_JDK_17) { $env:MAID_LEGION_JDK_17 } else { 'D:\Java\jdk-17.0.20.1+1' }
    '1.21' = if ($env:MAID_LEGION_JDK_21) { $env:MAID_LEGION_JDK_21 } else { 'D:\Java\jdk-21.0.10' }
}

$Branches = if ($Version -eq 'all') { @('1.20', '1.21') } else { @($Version) }

# 记住进来时的分支，构建完切回去
Push-Location $RepoRoot
$OriginalBranch = (git rev-parse --abbrev-ref HEAD).Trim()

New-Item -ItemType Directory -Force -Path $DistDir | Out-Null

$Results = @()
try {
    foreach ($b in $Branches) {
        Write-Host "`n========== 构建分支 $b ==========" -ForegroundColor Cyan

        if (-not (Test-Path $Jdk[$b])) {
            Write-Host "  [跳过] 找不到 JDK: $($Jdk[$b])" -ForegroundColor Red
            $Results += [pscustomobject]@{ 分支=$b; 结果='跳过(无JDK)'; Jar='-' }
            continue
        }

        git checkout $b 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Host "  [失败] 切不到分支 $b" -ForegroundColor Red
            $Results += [pscustomobject]@{ 分支=$b; 结果='切换失败'; Jar='-' }
            continue
        }

        $env:JAVA_HOME = $Jdk[$b]
        # 本机需要代理才能访问 maven.neoforged.net；未设置时不影响直连环境
        if ($env:MAID_LEGION_PROXY) { Write-Host "  代理: $($env:MAID_LEGION_PROXY)" }

        # 1.21 的 runClient 资源下载在受限网络下会失败，构建产物本身不需要它
        $extra = if ($b -eq '1.21') { @('-x', 'neoFormJoined1.21.1-20240808.144430DownloadAssets') } else { @() }
        & (Join-Path $RepoRoot 'gradlew.bat') clean build --no-daemon @extra
        $ok = ($LASTEXITCODE -eq 0)

        # 测试统计
        $tests = 0; $fails = 0
        Get-ChildItem "build\test-results\test\*.xml" -ErrorAction SilentlyContinue | ForEach-Object {
            [xml]$d = Get-Content $_.FullName
            $tests += [int]$d.testsuite.tests
            $fails += [int]$d.testsuite.failures + [int]$d.testsuite.errors
        }

        $jar = Get-ChildItem "build\libs\touhou_maid_legion-*.jar" -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($ok -and $jar) {
            Copy-Item $jar.FullName $DistDir -Force
            Write-Host "  [成功] $($jar.Name)  测试 $tests/$tests 通过" -ForegroundColor Green
            $Results += [pscustomobject]@{ 分支=$b; 结果="成功 (测试 $($tests-$fails)/$tests)"; Jar=$jar.Name }
        } else {
            Write-Host "  [失败] 构建未通过" -ForegroundColor Red
            $Results += [pscustomobject]@{ 分支=$b; 结果='构建失败'; Jar='-' }
        }
    }
}
finally {
    git checkout $OriginalBranch 2>&1 | Out-Null
    Pop-Location
}

Write-Host "`n========== 汇总 ==========" -ForegroundColor Cyan
$Results | Format-Table -AutoSize
Write-Host "成品目录: $DistDir"
Get-ChildItem $DistDir -File | Select-Object Name,
    @{n='MB';e={[math]::Round($_.Length/1MB,2)}}, LastWriteTime | Format-Table -AutoSize
