Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  BENCHMARK: Java (ArrayList<Integer>) vs Ocean (OceanIntList)   " -ForegroundColor Cyan
Write-Host "  Workload: 200,000 Elements x 20 Rounds Updates + Foreach Sum   " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# 1. Java Benchmark Compilation
Write-Host "`n[1/4] Compiling Java Benchmark (javac)..." -ForegroundColor Yellow
$javaCompileStart = [System.Diagnostics.Stopwatch]::StartNew()
javac -d "build/classes/java/test" "benchmarks/JavaArrayListBenchmark.java"
$javaCompileStart.Stop()
$javaCompileMs = $javaCompileStart.ElapsedMilliseconds
Write-Host "Java Compile Time: $javaCompileMs ms" -ForegroundColor Green

# 2. Java Benchmark Execution
Write-Host "`n[2/4] Executing Java Benchmark..." -ForegroundColor Yellow
$javaRunStart = [System.Diagnostics.Stopwatch]::StartNew()
$javaOut = java -cp "build/classes/java/test" benchmarks.JavaArrayListBenchmark
$javaRunStart.Stop()
$javaTotalWallMs = $javaRunStart.ElapsedMilliseconds
$javaOut | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

# 3. Ocean Benchmark Compilation
Write-Host "`n[3/4] Compiling Ocean Benchmark (ocean.bat -c)..." -ForegroundColor Yellow
$oceanCompileStart = [System.Diagnostics.Stopwatch]::StartNew()
.\ocean.bat -c "benchmarks/OceanPrimitiveBenchmark.ocean"
$oceanCompileStart.Stop()
$oceanCompileMs = $oceanCompileStart.ElapsedMilliseconds
Write-Host "Ocean Compile Time: $oceanCompileMs ms" -ForegroundColor Green

# 4. Ocean Benchmark Execution
Write-Host "`n[4/4] Executing Ocean Benchmark..." -ForegroundColor Yellow
$oceanRunStart = [System.Diagnostics.Stopwatch]::StartNew()
$oceanOut = .\ocean.bat "benchmarks/OceanPrimitiveBenchmark.ocean"
$oceanRunStart.Stop()
$oceanTotalWallMs = $oceanRunStart.ElapsedMilliseconds
$oceanOut | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

Write-Host "`n=================================================================" -ForegroundColor Cyan
Write-Host "                    BENCHMARK RESULTS SUMMARY                   " -ForegroundColor Cyan
Write-Host "=================================================================" -ForegroundColor Cyan

# Parse internal execution timings
$javaInit = ($javaOut | Select-String "List Initialization Time: (\d+) ms").Matches.Groups[1].Value
$javaExec = ($javaOut | Select-String "Total Execution Time: (\d+) ms").Matches.Groups[1].Value
$javaAvg = ($javaOut | Select-String "Avg: (\d+) ms/round").Matches.Groups[1].Value

$oceanInit = ($oceanOut | Select-String "List Initialization Time: (\d+) ms").Matches.Groups[1].Value
$oceanExec = ($oceanOut | Select-String "Total Execution Time: (\d+) ms").Matches.Groups[1].Value
$oceanAvg = ($oceanOut | Select-String "Avg: (\d+) ms/round").Matches.Groups[1].Value

$javaTotalTurnaround = [int]$javaCompileMs + [int]$javaExec
$oceanTotalTurnaround = [int]$oceanCompileMs + [int]$oceanExec

Write-Host ("{0,-30} | {1,-15} | {2,-15} | {3,-15}" -f "Metric", "Java (ArrayList)", "Ocean (IntList)", "Speedup / Ratio") -ForegroundColor White
Write-Host ("-" * 82)
Write-Host ("{0,-30} | {1,-15} | {2,-15} | {3,-15}" -f "TOTAL Turnaround (Derl+Run)", "$javaTotalTurnaround ms", "$oceanTotalTurnaround ms", "$([math]::Round([double]$javaTotalTurnaround / [math]::Max([double]$oceanTotalTurnaround, 1), 2))x hizli") -ForegroundColor Green
Write-Host ("{0,-30} | {1,-15} | {2,-15} | {3,-15}" -f "Compilation Time", "$javaCompileMs ms", "$oceanCompileMs ms", "$([math]::Round([double]$javaCompileMs / [math]::Max([double]$oceanCompileMs, 1), 2))x hizli")
Write-Host ("{0,-30} | {1,-15} | {2,-15} | {3,-15}" -f "Execution Time (5 Rounds)", "$javaExec ms", "$oceanExec ms", "$([math]::Round([double]$javaExec / [math]::Max([double]$oceanExec, 1), 2))x hizli")
Write-Host ("{0,-30} | {1,-15} | {2,-15} | {3,-15}" -f "Avg Time / Round", "$javaAvg ms", "$oceanAvg ms", "$([math]::Round([double]$javaAvg / [math]::Max([double]$oceanAvg, 1), 2))x hizli")
Write-Host ("{0,-30} | {1,-15} | {2,-15} | {3,-15}" -f "10M List Init Time", "$javaInit ms", "$oceanInit ms", "$([math]::Round([double]$javaInit / [math]::Max([double]$oceanInit, 1), 2))x hizli")
Write-Host ("{0,-30} | {1,-15} | {2,-15} | {3,-15}" -f "Process Wall-Clock Time", "$javaTotalWallMs ms", "$oceanTotalWallMs ms", "$([math]::Round([double]$javaTotalWallMs / [math]::Max([double]$oceanTotalWallMs, 1), 2))x hizli")
Write-Host ("-" * 82)