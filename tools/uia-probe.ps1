# UIA 可行性探测：桌面聊天软件的窗口控件树里能不能读到聊天文字？
#
# 这个答案决定桌面端的采集层架构：
#   能读到 -> UIA 监听为主（精确区分发送方、能实时监听新消息）
#   读不到 -> 截图 + OCR 为主（通用于任何客户端，但需按位置猜发送方）
#
# 用法（PowerShell，需要目标软件已打开）：
#   powershell -ExecutionPolicy Bypass -File tools\uia-probe.ps1

Add-Type -AssemblyName UIAutomationClient
Add-Type -AssemblyName UIAutomationTypes

$AE = [System.Windows.Automation.AutomationElement]
$walker = [System.Windows.Automation.TreeWalker]::ControlViewWalker

# 桌面聊天软件的进程名（微信 4.x 是 Weixin.exe，3.x 是 WeChat.exe；QQ 9.9 是 QQ.exe）
$targets = @("Weixin", "WeChat", "QQ", "QQNT", "TIM", "DingTalk", "Lark", "Feishu")

Write-Output "=== 1. 查找顶层窗口 ==="
$windows = New-Object System.Collections.Generic.List[object]
$root = $AE::RootElement
$child = $null
try { $child = $walker.GetFirstChild($root) } catch { }
$guard = 0
while ($null -ne $child -and $guard -lt 300) {
    $guard++
    try {
        $procId = $child.Current.ProcessId
        $procName = (Get-Process -Id $procId -ErrorAction SilentlyContinue).ProcessName
        if ($procName -and ($targets -contains $procName)) {
            $windows.Add([pscustomobject]@{
                Proc    = $procName
                Pid     = $procId
                Name    = $child.Current.Name
                Class   = $child.Current.ClassName
                Element = $child
            })
        }
    } catch { }
    try { $child = $walker.GetNextSibling($child) } catch { break }
}

if ($windows.Count -eq 0) {
    Write-Output "没有找到目标窗口。请先把微信/QQ 打开到聊天界面再运行本脚本。"
    exit 0
}
$windows | Select-Object Proc, Pid, Name, Class | Format-Table -AutoSize

function Walk-Tree($element, $maxDepth, $maxNodes) {
    $queue = New-Object System.Collections.Generic.List[object]
    $queue.Add([pscustomobject]@{ El = $element; Depth = 0 })
    $nodes = 0
    $texts = New-Object System.Collections.Generic.List[object]
    $i = 0
    while ($i -lt $queue.Count -and $nodes -lt $maxNodes) {
        $cur = $queue[$i]; $i++
        $nodes++
        try {
            $n = $cur.El.Current.Name
            $ct = $cur.El.Current.ControlType.ProgrammaticName -replace 'ControlType\.', ''
            if ($n -and $n.Trim().Length -gt 0) {
                $texts.Add([pscustomobject]@{ Depth = $cur.Depth; Type = $ct; Text = $n.Trim() })
            }
        } catch { }
        if ($cur.Depth -lt $maxDepth) {
            try {
                $c = $walker.GetFirstChild($cur.El)
                while ($null -ne $c) {
                    $queue.Add([pscustomobject]@{ El = $c; Depth = $cur.Depth + 1 })
                    $c = $walker.GetNextSibling($c)
                }
            } catch { }
        }
    }
    return [pscustomobject]@{ Nodes = $nodes; Texts = $texts }
}

foreach ($w in $windows) {
    Write-Output ""
    Write-Output ("=" * 60)
    Write-Output "目标: $($w.Proc) (pid=$($w.Pid)) 窗口类=$($w.Class) 标题=$($w.Name)"

    # Chromium/Electron 类客户端（微信 4.x、QQ 9.9）默认关闭无障碍树，
    # 只有检测到辅助技术查询后才开启。所以先探一次，等一秒再探第二次，
    # 第二次的结果才是真实的可用性。
    Write-Output "-- 第一次遍历（触发 Electron 开启无障碍树）--"
    $first = Walk-Tree $w.Element 8 1200
    Write-Output "   节点数=$($first.Nodes) 有文字=$($first.Texts.Count)"

    Start-Sleep -Seconds 2

    Write-Output "-- 第二次遍历（真实可用性）--"
    $second = Walk-Tree $w.Element 16 4000
    Write-Output "   节点数=$($second.Nodes) 有文字=$($second.Texts.Count)"
    Write-Output "   ---- 前 60 条文字节点 ----"
    $second.Texts | Select-Object -First 60 | ForEach-Object {
        $indent = "  " * [Math]::Min($_.Depth, 12)
        Write-Output ("{0}[{1}] {2}" -f $indent, $_.Type, $_.Text)
    }
}
