# 发布到 GitHub（需先登录：gh auth login 或 gh auth login --with-token < token.txt）
# 用法: powershell -ExecutionPolicy Bypass -File scripts/publish-github.ps1
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Push-Location $root
if (-not (gh auth status 2>$null)) {
    Write-Host "请先运行: gh auth login"
    exit 1
}
# 仓库名：GitHub 不支持空格，取主名 Android-Codex（别名 Android Agent / Android Chat 见 README）
gh repo create AndroidCodexAndroidAgentAndroidChat --public --source . --remote origin --push --description "AndroidCodex AndroidAgentAndroidChat - local AI chat client"
Pop-Location
Write-Host "已发布: https://github.com/<你的用户名>/AndroidCodexAndroidAgentAndroidChat"
