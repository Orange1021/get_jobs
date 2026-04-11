param(
    [string]$BaseUrl = "http://localhost:8888",
    [int]$StartTimeoutSec = 20,
    [switch]$SkipStatusCheck
)

$ErrorActionPreference = "Stop"

$platforms = @(
    @{ Name = "boss";    Start = "/api/boss/start";    Status = "/api/boss/status" },
    @{ Name = "51job";   Start = "/api/51job/start";   Status = "/api/51job/status" },
    @{ Name = "liepin";  Start = "/api/liepin/start";  Status = "/api/liepin/status" },
    @{ Name = "zhilian"; Start = "/api/zhilian/start"; Status = "/api/zhilian/status" }
)

Write-Host "Starting 4 platforms in parallel via $BaseUrl ..."

$jobs = @()
foreach ($p in $platforms) {
    $jobs += Start-Job -Name ("start-" + $p.Name) -ScriptBlock {
        param($base, $name, $startPath, $timeout)
        try {
            $uri = $base.TrimEnd("/") + $startPath
            $res = Invoke-RestMethod -Uri $uri -Method Post -TimeoutSec $timeout
            [PSCustomObject]@{
                platform = $name
                ok       = $true
                uri      = $uri
                success  = $res.success
                status   = $res.status
                message  = $res.message
            }
        } catch {
            [PSCustomObject]@{
                platform = $name
                ok       = $false
                uri      = $base.TrimEnd("/") + $startPath
                success  = $false
                status   = "request_failed"
                message  = $_.Exception.Message
            }
        }
    } -ArgumentList $BaseUrl, $p.Name, $p.Start, $StartTimeoutSec
}

Wait-Job -Job $jobs | Out-Null
$startResults = @()
foreach ($j in $jobs) {
    $startResults += Receive-Job -Job $j
    Remove-Job -Job $j -Force
}

Write-Host ""
Write-Host "Start results:"
$startResults | Sort-Object platform | Format-Table -AutoSize

if ($SkipStatusCheck) {
    exit 0
}

Start-Sleep -Seconds 2
Write-Host ""
Write-Host "Runtime status:"

$statusResults = @()
foreach ($p in $platforms) {
    try {
        $uri = $BaseUrl.TrimEnd("/") + $p.Status
        $res = Invoke-RestMethod -Uri $uri -Method Get -TimeoutSec 8
        $statusResults += [PSCustomObject]@{
            platform   = $p.Name
            isRunning  = $res.isRunning
            isLoggedIn = $res.isLoggedIn
            success    = if ($null -ne $res.success) { $res.success } else { $true }
            rawStatus  = ($res | ConvertTo-Json -Compress)
        }
    } catch {
        $statusResults += [PSCustomObject]@{
            platform   = $p.Name
            isRunning  = $null
            isLoggedIn = $null
            success    = $false
            rawStatus  = $_.Exception.Message
        }
    }
}

$statusResults | Sort-Object platform | Format-Table -AutoSize
