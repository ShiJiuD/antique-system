<#
    tunnels.ps1 — 一键建立到云端中间件的 SSH 隧道（断线自动重连）

    为什么用脚本而不是 MobaXterm 的图形界面：
      · 一条命令同时建好全部隧道，不用逐个点
      · PowerShell 的 while 循环实现"断了自动重连"，图形界面没有这个能力
      · 不依赖 GUI 进程，MobaXterm 关掉了隧道也照样在

    用法：
        pwsh -File tunnels.ps1           前台运行（Ctrl+C 结束）
        pwsh -File tunnels.ps1 -Check    只检查隧道是否通，不建立连接

    前置：配置免密登录（只需做一次，见下方"首次配置"）
         Get-Content "$env:USERPROFILE\.ssh\id_ed25519.pub" |
             ssh root@101.132.106.91 "mkdir -p ~/.ssh && chmod 700 ~/.ssh && cat >> ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys"
#>
param(
    [switch]$Check
)

$Server = 'root@101.132.106.91'

# 本机端口 -> 云端地址（键是字符串，便于 [int] 转换）
$Forwards = [ordered]@{
    '13306' = '127.0.0.1:3306'    # MySQL
    '16379' = '127.0.0.1:6379'    # Redis
    '25672' = '127.0.0.1:5672'    # RabbitMQ（AMQP，应用连接用这个）
    '15673' = '127.0.0.1:15672'   # RabbitMQ 管理台（浏览器 http://localhost:15673）
}

function Test-Tunnels {
    Write-Host '隧道状态：' -ForegroundColor Cyan
    $allOk = $true
    foreach ($local in $Forwards.Keys) {
        $ok = Test-NetConnection 127.0.0.1 -Port ([int]$local) -InformationLevel Quiet -WarningAction SilentlyContinue
        if (-not $ok) { $allOk = $false }
        $mark = if ($ok) { '通' } else { '不通' }
        Write-Host ("  {0,-6} -> {1,-18} [{2}]" -f $local, $Forwards[$local], $mark)
    }
    return $allOk
}

if ($Check) {
    $null = Test-Tunnels
    exit
}

$sshArgs = @(
    '-N',
    '-o', 'ServerAliveInterval=30',      # 每 30 秒发心跳，防中间设备掐断空闲连接
    '-o', 'ServerAliveCountMax=3',       # 3 次心跳无响应即判定断开
    '-o', 'ExitOnForwardFailure=yes',    # 端口被占用时直接失败，而不是假装成功
    '-o', 'TCPKeepAlive=yes',
    '-o', 'StrictHostKeyChecking=accept-new'
)
foreach ($local in $Forwards.Keys) {
    $sshArgs += @('-L', "${local}:$($Forwards[$local])")
}
$sshArgs += $Server

Write-Host '建立隧道（保持本窗口开着，Ctrl+C 结束）：' -ForegroundColor Cyan
foreach ($local in $Forwards.Keys) {
    Write-Host ("  本机 {0,-6} -> 云端 {1}" -f $local, $Forwards[$local])
}
Write-Host ''

while ($true) {
    & ssh @sshArgs
    Write-Host '隧道断开，5 秒后自动重连…（Ctrl+C 退出）' -ForegroundColor Yellow
    Start-Sleep -Seconds 5
}
