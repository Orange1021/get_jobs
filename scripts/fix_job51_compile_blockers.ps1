$ErrorActionPreference = "Stop"

$path = "src/main/java/com/getjobs/worker/job51/Job51.java"
if (-not (Test-Path $path)) {
    throw "File not found: $path"
}

$raw = Get-Content -Raw -Path $path -Encoding UTF8

# 1) handleSeparateDeliveryDialog() is void, must not return int.
$raw = [Regex]::Replace(
    $raw,
    '(?s)(private\s+void\s+handleSeparateDeliveryDialog\s*\(\)\s*\{.*?catch\s*\(Exception\s+e\)\s*\{\s*log\.debug\(".*?",\s*e\.getMessage\(\)\);\s*)return\s+0;\s*(\}\s*\})',
    '$1$2'
)

# 2) closeAnyModalOverlays() is void, must not return int.
$raw = [Regex]::Replace(
    $raw,
    '(?s)(private\s+void\s+closeAnyModalOverlays\s*\(\)\s*\{.*?catch\s*\(Exception\s+e\)\s*\{\s*log\.debug\(".*?",\s*e\.getMessage\(\)\);\s*)return\s+0;\s*(\}\s*\})',
    '$1$2'
)

# 3) collectJobIdsOnPage() returns List<Long>, not int.
$raw = [Regex]::Replace(
    $raw,
    '(?s)(private\s+List<Long>\s+collectJobIdsOnPage\s*\(\)\s*\{.*?catch\s*\(Exception\s+e\)\s*\{\s*log\.debug\(".*?",\s*e\.getMessage\(\)\);\s*)return\s+0;\s*(\}\s*return\s+ids;\s*\})',
    '$1return ids;' + [Environment]::NewLine + '        $2'
)

$utf8NoBom = New-Object System.Text.UTF8Encoding($false)
[System.IO.File]::WriteAllText((Resolve-Path $path), $raw, $utf8NoBom)
Write-Host "Patched compile blockers in $path"
