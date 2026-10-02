# =============================================================================
#  Ocean Compiler Compile-Time Performance Benchmark
# =============================================================================

$tempDir = "temp_bench_sources"
if (Test-Path $tempDir) { Remove-Item -Recurse -Force $tempDir }
New-Item -ItemType Directory -Path $tempDir -Force | Out-Null

# Copy clean benchmark, stress, and integration files
Get-ChildItem "examples\*Stress*.ocean","examples\Grand*.ocean","examples\Masterpiece*.ocean","examples\Ultimate*.ocean","examples\Ultra*.ocean","examples\OceanBench*.ocean" | ForEach-Object {
    Copy-Item $_.FullName -Destination $tempDir
}

Write-Host "Starting Compile-Time Benchmark (5 iterations)..." -ForegroundColor Green
$times = @()

for ($i = 1; $i -le 5; $i++) {
    # Force clean build directory to avoid incremental compilation cache hit
    $binDir = ".ocean\bin"
    if (Test-Path $binDir) { Remove-Item -Recurse -Force $binDir }
    
    $start = [System.Diagnostics.Stopwatch]::StartNew()
    $out = .\ocean.bat -c $tempDir 2>&1
    $start.Stop()
    
    $ms = $start.ElapsedMilliseconds
    $times += $ms
    Write-Host "Iteration $i - $ms ms"
}

# Cleanup
Remove-Item -Recurse -Force $tempDir

# Calculate average
$sum = 0
foreach ($t in $times) { $sum += $t }
$avg = $sum / $times.Count

Write-Host "--------------------------------------" -ForegroundColor Yellow
Write-Host "Average Compilation Time: $avg ms" -ForegroundColor Green
Write-Host "--------------------------------------" -ForegroundColor Yellow
