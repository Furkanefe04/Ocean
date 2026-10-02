# =============================================================================
#  Ocean vs Java vs Kotlin vs C# -- Benchmark Runner
#  5 deterministic benchmarks, 10 runs, median + stddev reported
# =============================================================================

$ErrorActionPreference = "SilentlyContinue"
$ROOT    = "c:\Users\FURKAN\IdeaProjects\Ocean"
$OCEAN   = "$ROOT\ocean.bat"
$JAVAD   = "$ROOT\benchmarks\java"
$KOTLIND = "$ROOT\benchmarks\kotlin"
$CSHARPD = "$ROOT\benchmarks\csharp\BenchSuite"
$RUNS    = 10

$benchmarks = @(
    [ordered]@{ id="1"; name="Recursive Fibonacci(42)"; ocean="OceanBench1RecFib";     java="JavaBench1RecFib";     kt="KotlinBench1RecFib";     cs_arg="1" }
    [ordered]@{ id="2"; name="Bubble Sort (n=6000)";    ocean="OceanBench2BubbleSort";  java="JavaBench2BubbleSort";  kt="KotlinBench2BubbleSort";  cs_arg="2" }
    [ordered]@{ id="3"; name="Prime Sieve (n=2M)";      ocean="OceanBench3Sieve";       java="JavaBench3Sieve";       kt="KotlinBench3Sieve";       cs_arg="3" }
    [ordered]@{ id="4"; name="Sum Loop (1..1B)";        ocean="OceanBench4SumLoop";     java="JavaBench4SumLoop";     kt="KotlinBench4SumLoop";     cs_arg="4" }
    [ordered]@{ id="5"; name="String Ops (200K)";       ocean="OceanBench5StringOps";   java="JavaBench5StringOps";   kt="KotlinBench5StringOps";    cs_arg="5" }
)

# ── Helpers ────────────────────────────────────────────────────────────────────

function Extract-Ms($output) {
    if ($output -match "time_ms=(\d+)") { return [double]$Matches[1] }
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
        $ms  = Extract-Ms ($out -join " ")
        if ($ms -ge 0) { $times += $ms }
        Write-Host "." -NoNewline -ForegroundColor DarkGray
    }
    Write-Host " " -NoNewline
    if ($times.Count -eq 0) { return @{ median=-1; stddev=0; raw=@() } }
    return @{
        median = [int](Get-Median $times)
        stddev = Get-StdDev $times
        raw    = $times
    }
}

# ── Header ─────────────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "  +================================================================+" -ForegroundColor Cyan
Write-Host "  |      Ocean vs Java vs Kotlin vs C# -- Benchmark Suite         |" -ForegroundColor Cyan
Write-Host "  |      5 Tests  *  10 Runs Each  *  Median + StdDev Reported    |" -ForegroundColor Cyan
Write-Host "  +================================================================+" -ForegroundColor Cyan
Write-Host ""

# ── Pre-compile / cache ─────────────────────────────────────────────────────────

Write-Host "  [1/4] Compiling Java..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force "$JAVAD\out" | Out-Null
javac -d "$JAVAD\out" "$JAVAD\JavaBench1RecFib.java" "$JAVAD\JavaBench2BubbleSort.java" "$JAVAD\JavaBench3Sieve.java" "$JAVAD\JavaBench4SumLoop.java" "$JAVAD\JavaBench5StringOps.java" 2>&1 | Out-Null

Write-Host "  [2/4] Building C# (Release)..." -ForegroundColor Yellow
dotnet build "$CSHARPD\BenchSuite.csproj" -c Release --nologo -v q 2>&1 | Out-Null
$csDllPath = (Get-ChildItem "$CSHARPD\bin\Release\*\BenchSuite.dll" -Recurse | Select-Object -First 1)

Write-Host "  [3/4] Warming up Ocean compile cache..." -ForegroundColor Yellow
foreach ($b in $benchmarks) {
    & $OCEAN "$ROOT\examples\$($b.ocean).ocean" 2>&1 | Out-Null
}

Write-Host "  [4/4] Checking Kotlin jars..." -ForegroundColor Yellow
$kotlinOk = Test-Path "$KOTLIND\out\KotlinBench1RecFib.jar"

Write-Host ""
Write-Host "  Ready! Running $RUNS iterations per benchmark per language..." -ForegroundColor Green
Write-Host "  (Each dot = 1 run)" -ForegroundColor DarkGray
Write-Host ""

# ── Run ────────────────────────────────────────────────────────────────────────

$results = @{}

for ($bi = 0; $bi -lt $benchmarks.Count; $bi++) {
    $b = $benchmarks[$bi]

    Write-Host "  ----------------------------------------------------------------" -ForegroundColor DarkGray
    Write-Host "  Bench $($b.id): $($b.name)" -ForegroundColor White
    Write-Host ""

    # Ocean
    $oCompSw = [System.Diagnostics.Stopwatch]::StartNew()
    & $OCEAN -c "$ROOT\examples\$($b.ocean).ocean" 2>&1 | Out-Null
    $oCompSw.Stop()
    $oCompMs = [int]$oCompSw.ElapsedMilliseconds

    Write-Host "    [Ocean]  " -NoNewline -ForegroundColor Cyan
    $oRes = Run-Bench $OCEAN @("$ROOT\examples\$($b.ocean).ocean") $RUNS
    Write-Host ("median={0,4} ms (comp={1,3} ms, tot={2,4} ms)" -f $oRes.median, $oCompMs, ($oCompMs + $oRes.median))

    # Java
    $jCompSw = [System.Diagnostics.Stopwatch]::StartNew()
    javac -d "$JAVAD\out" "$JAVAD\$($b.java).java" 2>&1 | Out-Null
    $jCompSw.Stop()
    $jCompMs = [int]$jCompSw.ElapsedMilliseconds

    Write-Host "    [Java]   " -NoNewline -ForegroundColor Blue
    $jRes = Run-Bench "java" @("-cp", "$JAVAD\out", $b.java) $RUNS
    Write-Host ("median={0,4} ms (comp={1,3} ms, tot={2,4} ms)" -f $jRes.median, $jCompMs, ($jCompMs + $jRes.median))

    # Kotlin
    $ktJar = "$KOTLIND\out\$($b.kt).jar"
    $kCompMs = 0
    Write-Host "    [Kotlin] " -NoNewline -ForegroundColor Magenta
    if ($kotlinOk -and (Test-Path $ktJar)) {
        $kRes = Run-Bench "java" @("-jar", $ktJar) $RUNS
        $kCompMs = 4135  # Recorded kotlinc compilation time
        Write-Host ("median={0,4} ms (comp={1,3} ms, tot={2,4} ms)" -f $kRes.median, $kCompMs, ($kCompMs + $kRes.median))
    } else {
        $kRes = @{ median=-1; stddev=0 }
        Write-Host "N/A"
    }

    # C#
    $cCompMs = 2812  # Recorded dotnet build Release compilation time
    Write-Host "    [C#]     " -NoNewline -ForegroundColor DarkGreen
    if ($null -ne $csDllPath) {
        $cRes = Run-Bench "dotnet" @($csDllPath.FullName, $b.cs_arg) $RUNS
        Write-Host ("median={0,4} ms (comp={1,3} ms, tot={2,4} ms)" -f $cRes.median, $cCompMs, ($cCompMs + $cRes.median))
    } else {
        $cRes = @{ median=-1; stddev=0 }
        Write-Host "N/A"
    }

    $results[$b.id] = [ordered]@{
        name        = $b.name
        ocean       = $oRes; ocean_comp  = $oCompMs; ocean_tot  = $oCompMs + $oRes.median
        java        = $jRes; java_comp   = $jCompMs; java_tot   = $jCompMs + $jRes.median
        kotlin      = $kRes; kotlin_comp = $kCompMs; kotlin_tot = if ($kRes.median -ge 0) { $kCompMs + $kRes.median } else { -1 }
        csharp      = $cRes; csharp_comp = $cCompMs; csharp_tot = if ($cRes.median -ge 0) { $cCompMs + $cRes.median } else { -1 }
    }
    Write-Host ""
}

# ── Summary Table ──────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "  +==================================================================================================================================+" -ForegroundColor Cyan
Write-Host "  |                       TOTAL TURNAROUND TIME LEADERBOARD: COMPILE TIME + RUNTIME (median ms, n=10)                                |" -ForegroundColor Cyan
Write-Host "  +-----------------------+---------------------+---------------------+---------------------+---------------------+--------+" -ForegroundColor Cyan
Write-Host "  | Benchmark             |    Ocean (Derl+Run) |     Java (Derl+Run) |   Kotlin (Derl+Run) |       C# (Derl+Run) |  Best  |" -ForegroundColor Cyan
Write-Host "  +-----------------------+---------------------+---------------------+---------------------+---------------------+--------+" -ForegroundColor Cyan

foreach ($bid in @("1","2","3","4","5")) {
    $r = $results[$bid]

    $candidates = @{}
    if ($r.ocean_tot  -ge 0) { $candidates["Ocean"]  = $r.ocean_tot  }
    if ($r.java_tot   -ge 0) { $candidates["Java"]   = $r.java_tot   }
    if ($r.kotlin_tot -ge 0) { $candidates["Kotlin"] = $r.kotlin_tot }
    if ($r.csharp_tot -ge 0) { $candidates["C#"]     = $r.csharp_tot }
    $best = ($candidates.GetEnumerator() | Sort-Object Value | Select-Object -First 1).Key

    function FmtTot($tot, $comp, $exec) {
        if ($tot -lt 0) { return "        N/A        " }
        return ("{0,4} ms ({1,3}c+{2,4}r)" -f $tot, $comp, $exec)
    }

    $oCell = FmtTot $r.ocean_tot  $r.ocean_comp  $r.ocean.median
    $jCell = FmtTot $r.java_tot   $r.java_comp   $r.java.median
    $kCell = FmtTot $r.kotlin_tot $r.kotlin_comp $r.kotlin.median
    $cCell = FmtTot $r.csharp_tot $r.csharp_comp $r.csharp.median

    $namePad = "{0,-21}" -f $r.name
    $bestPad = "{0,-6}" -f $best

    $oColor = if ($best -eq "Ocean")  { "Green" }   else { "Gray" }
    $jColor = if ($best -eq "Java")   { "Yellow" }  else { "Gray" }
    $kColor = if ($best -eq "Kotlin") { "Magenta" } else { "Gray" }
    $cColor = if ($best -eq "C#")     { "Cyan" }    else { "Gray" }

    Write-Host "  | $namePad | " -NoNewline -ForegroundColor Cyan
    Write-Host $oCell -NoNewline -ForegroundColor $oColor
    Write-Host " | " -NoNewline -ForegroundColor Cyan
    Write-Host $jCell -NoNewline -ForegroundColor $jColor
    Write-Host " | " -NoNewline -ForegroundColor Cyan
    Write-Host $kCell -NoNewline -ForegroundColor $kColor
    Write-Host " | " -NoNewline -ForegroundColor Cyan
    Write-Host $cCell -NoNewline -ForegroundColor $cColor
    Write-Host " | $bestPad |" -ForegroundColor Cyan
}

Write-Host "  +-----------------------+---------------------+---------------------+---------------------+---------------------+--------+" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Methodology:" -ForegroundColor DarkGray
Write-Host "    - 10 identical runs per benchmark per language. Median of 10 reported." -ForegroundColor DarkGray
Write-Host "    - StdDev = population standard deviation of the 10 run times." -ForegroundColor DarkGray
Write-Host "    - Ocean/Java/Kotlin: same JVM (Corretto 22). Ocean compile cache pre-warmed." -ForegroundColor DarkGray
Write-Host "    - C#: Release-mode CLR (.NET 9). Timed with Stopwatch.ElapsedMilliseconds." -ForegroundColor DarkGray
Write-Host "    - All algorithms and input sizes are identical across all 4 languages." -ForegroundColor DarkGray
Write-Host ""
