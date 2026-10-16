# =============================================================================
#  run_E5_file.ps1  --  E5 文件侧四种布局，依次测量
#
#  用法（在 PowerShell 里执行）：
#     powershell -ExecutionPolicy Bypass -File "E:\deepseek harness\database proj1\deliverables\run_E5_file.ps1"
# =============================================================================

$ErrorActionPreference = 'Continue'
Set-Location 'E:\deepseek harness\database proj1\deliverables'
$env:MOVIEBENCH_CSV = 'E:\DBMS-Project-Data\csv'

$CP     = '.'
$JAVA   = 'java'
$ARGS   = @('-Xmx6g', '-cp', $CP, 'MovieBench', 'file', 'layout')
$KEY    = 'love'

$layouts = @(
    @{ name = 'csv';    desc = '① 普通 TSV 顺序扫描' },
    @{ name = 'fixed';  desc = '② 定长二进制 256 字节/条 + seek' },
    @{ name = 'mmap';   desc = '③ 内存映射 mmap' },
    @{ name = 'invidx'; desc = '④ TSV + 反转索引' }
)

$total = $layouts.Count
$i = 0
foreach ($l in $layouts) {
    $i++
    Write-Host ''
    Write-Host ('#' * 78)
    Write-Host ("#  [{0}/{1}]  {2}" -f $i, $total, $l.desc)
    Write-Host ('#' * 78)
    $t0 = Get-Date
    & $JAVA @ARGS $l.name $KEY 2>&1
    $sec = ((Get-Date) - $t0).TotalSeconds
    Write-Host ("---- [{0}] 总耗时 {1:N1} 秒 ----" -f $l.name, $sec)
}

Write-Host ''
Write-Host ('#' * 78)
Write-Host '#  四种布局全部跑完'
Write-Host ('#' * 78)
Write-Host ''
Write-Host '=== 生成的中间文件 ==='
Get-ChildItem 'E:\DBMS-Project-Data\csv\_work' -File -ErrorAction SilentlyContinue |
    Sort-Object Length -Descending |
    Select-Object Name, @{ n = 'MB'; e = { [math]::Round($_.Length / 1MB, 1) } } |
    Format-Table -AutoSize
