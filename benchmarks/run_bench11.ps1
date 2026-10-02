# =============================================================================
#  Ocean vs Java vs Kotlin vs C# -- Reused Builder Bench 11
#  5 Million iterations, 10 runs each, median + stddev reported
# =============================================================================

$ErrorActionPreference = "SilentlyContinue"
$ROOT    = "c:\Users\FURKAN\IdeaProjects\Ocean"
$OCEAN   = "$ROOT\ocean.bat"
$JAVAD   = "$ROOT\benchmarks\java"
$KOTLIND = "$ROOT\benchmarks\kotlin"
$CSHARPD = "$ROOT\benchmarks\csharp\BenchSuite"
$RUNS    = 10

function Extract-Ms($output) {
    if ($output -join " " -match "time_ms=(\d+)") { return [double]$Matches[1] }
    return -1
}

function Get-Median([double[]]$values) {
    $s = $values | Sort-Object
    $n = $s.Count
    if ($n -eq 0) { return -1 }
    if ($n % 2 -eq 1) { return $s[[int]($n / 2)] }
    return ($s[$n/2 - 1] + $s[$n/2]) / 2.0
}

function Get-StdDev([double[]]$values) {
    if ($values.Count -lt 2) { return 0.0 }
    $mean = ($values | Measure-Object -Average).Average
    $variance = ($values | ForEach-Object { ($_ - $mean) * ($_ - $mean) } | Measure-Object -Average).Average
    return [Math]::Round([Math]::Sqrt($variance), 1)
}

function Run-Bench($cmd, $argList, $runs) {
    $times = @()
    for ($i = 0; $i -lt $runs; $i++) {
        $out = & $cmd @argList 2>&1
        $ms  = Extract-Ms $out
        if ($ms -ge 0) { $times += $ms }
        Write-Host "." -NoNewline -ForegroundColor DarkGray
    }
    Write-Host " " -NoNewline
    if ($times.Count -eq 0) { return @{ median=-1; stddev=0 } }
    return @{
        median = [int](Get-Median $times)
        stddev = Get-StdDev $times
    }
}

# --- Compiling ---
Write-Host "Compiling Java Bench 11 (javac)..." -ForegroundColor Yellow
$jCompSw = [System.Diagnostics.Stopwatch]::StartNew()
javac -d "$JAVAD\out" "$JAVAD\JavaBench11ReusedBuilder.java"
$jCompSw.Stop()
$jCompMs = $jCompSw.ElapsedMilliseconds

Write-Host "Compiling Kotlin Bench 11 (kotlinc)..." -ForegroundColor Yellow
$kCompSw = [System.Diagnostics.Stopwatch]::StartNew()
kotlinc "$KOTLIND\KotlinBench11ReusedBuilder.kt" -include-runtime -d "$KOTLIND\out\KotlinBench11ReusedBuilder.jar"
$kCompSw.Stop()
$kCompMs = $kCompSw.ElapsedMilliseconds

Write-Host "Building C# Bench 11 (dotnet build Release)..." -ForegroundColor Yellow
$cCompSw = [System.Diagnostics.Stopwatch]::StartNew()
dotnet build "$CSHARPD\BenchSuite.csproj" -c Release --nologo -v q
$cCompSw.Stop()
$cCompMs = $cCompSw.ElapsedMilliseconds
$csDll = (Get-ChildItem "$CSHARPD\bin\Release\*\BenchSuite.dll" -Recurse | Select-Object -First 1).FullName

Write-Host "Compiling Ocean Bench 11 (ocean.bat -c)..." -ForegroundColor Yellow
$oCompSw = [System.Diagnostics.Stopwatch]::StartNew()
& $OCEAN -c "$ROOT\examples\OceanBench11ReusedBuilder.ocean" 2>&1 | Out-Null
$oCompSw.Stop()
$oCompMs = $oCompSw.ElapsedMilliseconds

Write-Host ""
Write-Host "Running 5,000,000 Reused Builder cycles (10 runs each)..." -ForegroundColor Green
Write-Host ""

Write-Host "[Ocean]  " -NoNewline -ForegroundColor Cyan
$oRes = Run-Bench $OCEAN @("$ROOT\examples\OceanBench11ReusedBuilder.ocean") $RUNS
Write-Host ("median={0,4} ms  stddev={1,4} ms" -f $oRes.median, $oRes.stddev)

Write-Host "[Java]   " -NoNewline -ForegroundColor Blue
$jRes = Run-Bench "java" @("-cp", "$JAVAD\out", "JavaBench11ReusedBuilder") $RUNS
Write-Host ("median={0,4} ms  stddev={1,4} ms" -f $jRes.median, $jRes.stddev)

Write-Host "[Kotlin] " -NoNewline -ForegroundColor Magenta
$kRes = Run-Bench "java" @("-jar", "$KOTLIND\out\KotlinBench11ReusedBuilder.jar") $RUNS
Write-Host ("median={0,4} ms  stddev={1,4} ms" -f $kRes.median, $kRes.stddev)

Write-Host "[C#]     " -NoNewline -ForegroundColor DarkGreen
$cRes = Run-Bench "dotnet" @($csDll, "11") $RUNS
Write-Host ("median={0,4} ms  stddev={1,4} ms" -f $cRes.median, $cRes.stddev)

# --- Leaderboard by Total Turnaround Time (Compile + Exec) ---
$bench11Results = @(
    [PSCustomObject]@{ Language = "Ocean";  CompileMs = $oCompMs; ExecMs = $oRes.median; TotalMs = $oCompMs + $oRes.median; StdDev = $oRes.stddev },
    [PSCustomObject]@{ Language = "Java";   CompileMs = $jCompMs; ExecMs = $jRes.median; TotalMs = $jCompMs + $jRes.median; StdDev = $jRes.stddev },
    [PSCustomObject]@{ Language = "Kotlin"; CompileMs = $kCompMs; ExecMs = $kRes.median; TotalMs = $kCompMs + $kRes.median; StdDev = $kRes.stddev },
    [PSCustomObject]@{ Language = "C#";     CompileMs = $cCompMs; ExecMs = $cRes.median; TotalMs = $cCompMs + $cRes.median; StdDev = $cRes.stddev }
) | Sort-Object TotalMs

Write-Host "`n==========================================================================================" -ForegroundColor Cyan
Write-Host "  REUSED BUILDER BENCHMARK (5M CYCLES) — TOTAL TURNAROUND TIME LEADERBOARD (Compile + Run)" -ForegroundColor Cyan
Write-Host "==========================================================================================" -ForegroundColor Cyan
Write-Host ("{0,-5} | {1,-10} | {2,-14} | {3,-18} | {4,-22}" -f "Rank", "Language", "Compile Time", "Exec Time (Median)", "TOTAL TURNAROUND") -ForegroundColor White
Write-Host ("-" * 82)
$rank = 1
foreach ($r in $bench11Results) {
    $color = if ($rank -eq 1) { "Green" } elseif ($rank -eq 2) { "Yellow" } else { "Gray" }
    Write-Host ("{0,-5} | {1,-10} | {2,11} ms | {3,15} ms | {4,19} ms" -f "$rank.", $r.Language, $r.CompileMs, $r.ExecMs, $r.TotalMs) -ForegroundColor $color
    $rank++
}
Write-Host ("-" * 82)
Write-Host "All tests finished!" -ForegroundColor Green
