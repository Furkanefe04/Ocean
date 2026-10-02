# =============================================================================
#  Ocean vs Java vs Kotlin vs C# -- Differentiating Benchmark Suite
#  5 tests chosen to expose JVM vs CLR architectural differences
#  10 runs each, median + population stddev reported
# =============================================================================

$ErrorActionPreference = "SilentlyContinue"
$ROOT    = "c:\Users\FURKAN\IdeaProjects\Ocean"
$OCEAN   = "$ROOT\ocean.bat"
$JAVAD   = "$ROOT\benchmarks\java"
$KOTLIND = "$ROOT\benchmarks\kotlin"
$CSHARPD = "$ROOT\benchmarks\csharp\BenchSuite"
$RUNS    = 10

$benchmarks = @(
    [ordered]@{ id="6";  name="Exception Flood (100K)";      note="JVM fillInStackTrace vs CLR lightweight exceptions";  ocean="OceanBench6Exceptions";     java="JavaBench6Exceptions";     kt="KotlinBench6Exceptions";     cs_arg="6"  }
    [ordered]@{ id="7";  name="Functional Pipeline (3M)";    note="Ocean raw loop vs Java IntStream vs Kotlin sumOf vs C# LINQ"; ocean="OceanBench7Functional";      java="JavaBench7Functional";     kt="KotlinBench7Functional";     cs_arg="7"  }
    [ordered]@{ id="8";  name="Object Allocation (2M)";      note="JVM heap alloc + GC vs C# struct (stack, zero GC pressure)"; ocean="OceanBench8ObjAlloc";       java="JavaBench8ObjAlloc";       kt="KotlinBench8ObjAlloc";       cs_arg="8"  }
    [ordered]@{ id="9";  name="Megamorphic Dispatch (10M)";  note="4 concrete types exceed JVM bimorphic inline cache limit";   ocean="OceanBench9VirtualDispatch"; java="JavaBench9VirtualDispatch"; kt="KotlinBench9VirtualDispatch"; cs_arg="9"  }
    [ordered]@{ id="10"; name="StringBuilder (100K)";        note="Each language's native string builder idiom";                ocean="OceanBench10StringBuilder";  java="JavaBench10StringBuilder";  kt="KotlinBench10StringBuilder";  cs_arg="10" }
)

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
Write-Host "  +===================================================================+" -ForegroundColor Cyan
Write-Host "  |   Ocean vs Java vs Kotlin vs C# -- Differentiating Benchmarks    |" -ForegroundColor Cyan
Write-Host "  |   Tests chosen to expose JVM vs CLR architectural differences    |" -ForegroundColor Cyan
Write-Host "  |   10 Runs Each  *  Median + StdDev                               |" -ForegroundColor Cyan
Write-Host "  +===================================================================+" -ForegroundColor Cyan
Write-Host ""

# ── Pre-compile ────────────────────────────────────────────────────────────────

Write-Host "  [1/4] Compiling Java (all benchmarks)..." -ForegroundColor Yellow
New-Item -ItemType Directory -Force "$JAVAD\out" | Out-Null
$javaFiles = Get-ChildItem "$JAVAD\*.java" | ForEach-Object { $_.FullName }
javac -d "$JAVAD\out" @javaFiles 2>&1 | Out-Null
Write-Host "        Done." -ForegroundColor DarkGreen

Write-Host "  [2/4] Building C# Release..." -ForegroundColor Yellow
dotnet build "$CSHARPD\BenchSuite.csproj" -c Release --nologo -v q 2>&1 | Out-Null
$csDllPath = (Get-ChildItem "$CSHARPD\bin\Release\*\BenchSuite.dll" -Recurse | Select-Object -First 1)
Write-Host "        Done." -ForegroundColor DarkGreen

Write-Host "  [3/4] Warming up Ocean compile cache (one run per benchmark)..." -ForegroundColor Yellow
foreach ($b in $benchmarks) {
    & $OCEAN "$ROOT\examples\$($b.ocean).ocean" 2>&1 | Out-Null
    Write-Host "." -NoNewline -ForegroundColor DarkGray
}
Write-Host " Done." -ForegroundColor DarkGreen

Write-Host "  [4/4] Verifying Kotlin jars..." -ForegroundColor Yellow
$kotlinOk = Test-Path "$KOTLIND\out\KotlinBench6Exceptions.jar"
if ($kotlinOk) { Write-Host "        Found." -ForegroundColor DarkGreen }
else            { Write-Host "        Not found - Kotlin results will be N/A." -ForegroundColor Red }

Write-Host ""
Write-Host "  Starting timed runs ($RUNS iterations per benchmark per language)..." -ForegroundColor Green
Write-Host "  Each dot = 1 run" -ForegroundColor DarkGray
Write-Host ""

# ── Run ────────────────────────────────────────────────────────────────────────

$results = @{}

for ($bi = 0; $bi -lt $benchmarks.Count; $bi++) {
    $b = $benchmarks[$bi]

    Write-Host "  ---------------------------------------------------------------" -ForegroundColor DarkGray
    Write-Host "  Bench $($b.id): $($b.name)" -ForegroundColor White
    Write-Host "  WHY: $($b.note)" -ForegroundColor DarkGray
    Write-Host ""

    Write-Host "    [Ocean]  " -NoNewline -ForegroundColor Cyan
    $oRes = Run-Bench $OCEAN @("$ROOT\examples\$($b.ocean).ocean") $RUNS
    if ($oRes.median -ge 0) { Write-Host ("median={0,5} ms  stddev={1,5} ms" -f $oRes.median, $oRes.stddev) }
    else { Write-Host "FAILED" -ForegroundColor Red }

    Write-Host "    [Java]   " -NoNewline -ForegroundColor Blue
    $jRes = Run-Bench "java" @("-cp", "$JAVAD\out", $b.java) $RUNS
    if ($jRes.median -ge 0) { Write-Host ("median={0,5} ms  stddev={1,5} ms" -f $jRes.median, $jRes.stddev) }
    else { Write-Host "FAILED" -ForegroundColor Red }

    $ktJar = "$KOTLIND\out\$($b.kt).jar"
    Write-Host "    [Kotlin] " -NoNewline -ForegroundColor Magenta
    if ($kotlinOk -and (Test-Path $ktJar)) {
        $kRes = Run-Bench "java" @("-jar", $ktJar) $RUNS
        if ($kRes.median -ge 0) { Write-Host ("median={0,5} ms  stddev={1,5} ms" -f $kRes.median, $kRes.stddev) }
        else { Write-Host "FAILED" -ForegroundColor Red }
    } else {
        $kRes = @{ median=-1; stddev=0 }
        Write-Host "N/A"
    }

    Write-Host "    [C#]     " -NoNewline -ForegroundColor DarkGreen
    if ($null -ne $csDllPath) {
        $cRes = Run-Bench "dotnet" @($csDllPath.FullName, $b.cs_arg) $RUNS
        if ($cRes.median -ge 0) { Write-Host ("median={0,5} ms  stddev={1,5} ms" -f $cRes.median, $cRes.stddev) }
        else { Write-Host "FAILED" -ForegroundColor Red }
    } else {
        $cRes = @{ median=-1; stddev=0 }
        Write-Host "N/A"
    }

    $results[$b.id] = [ordered]@{ name=$b.name; note=$b.note; ocean=$oRes; java=$jRes; kotlin=$kRes; csharp=$cRes }
    Write-Host ""
}

# ── Summary Table ──────────────────────────────────────────────────────────────

Write-Host ""
Write-Host "  +=========================================================================================+" -ForegroundColor Cyan
Write-Host "  |               FINAL RESULTS  (median ms +- stddev, n=10)                              |" -ForegroundColor Cyan
Write-Host "  +--------------------------+-----------------+-----------------+-----------------+--------+" -ForegroundColor Cyan
Write-Host "  | Benchmark                |     Ocean       |      Java       |     Kotlin      |   C#   | Best" -ForegroundColor Cyan
Write-Host "  +--------------------------+-----------------+-----------------+-----------------+--------+" -ForegroundColor Cyan

foreach ($bid in @("6","7","8","9","10")) {
    $r = $results[$bid]

    $candidates = @{}
    if ($r.ocean.median  -ge 0) { $candidates["Ocean"]  = $r.ocean.median  }
    if ($r.java.median   -ge 0) { $candidates["Java"]   = $r.java.median   }
    if ($r.kotlin.median -ge 0) { $candidates["Kotlin"] = $r.kotlin.median }
    if ($r.csharp.median -ge 0) { $candidates["C#"]     = $r.csharp.median }
    $best = ($candidates.GetEnumerator() | Sort-Object Value | Select-Object -First 1).Key

    function FC($res, $winner) {
        if ($res.median -lt 0) { return "      N/A      " }
        $cell = "{0,4} ms+-{1,4} ms" -f $res.median, $res.stddev
        return $cell
    }

    $oCell = FC $r.ocean  $best
    $jCell = FC $r.java   $best
    $kCell = FC $r.kotlin $best
    $cCell = FC $r.csharp $best

    $namePad = "{0,-24}" -f $r.name
    $oColor  = if ($best -eq "Ocean")  { "Cyan"    } else { "Gray" }
    $jColor  = if ($best -eq "Java")   { "Yellow"  } else { "Gray" }
    $kColor  = if ($best -eq "Kotlin") { "Magenta" } else { "Gray" }
    $cColor  = if ($best -eq "C#")     { "Green"   } else { "Gray" }

    Write-Host "  | $namePad | " -NoNewline -ForegroundColor Cyan
    Write-Host $oCell -NoNewline -ForegroundColor $oColor
    Write-Host " | " -NoNewline -ForegroundColor Cyan
    Write-Host $jCell -NoNewline -ForegroundColor $jColor
    Write-Host " | " -NoNewline -ForegroundColor Cyan
    Write-Host $kCell -NoNewline -ForegroundColor $kColor
    Write-Host " |" -NoNewline -ForegroundColor Cyan
    Write-Host (" {0,-6}" -f $cCell) -NoNewline -ForegroundColor $cColor
    Write-Host "| $best" -ForegroundColor White
}

Write-Host "  +--------------------------+-----------------+-----------------+-----------------+--------+" -ForegroundColor Cyan
Write-Host ""
Write-Host "  WHY each test differentiates:" -ForegroundColor White
Write-Host "    Bench 6 - Exception Flood:      JVM throws capture full stack trace (fillInStackTrace) -- CLR exceptions are lightweight." -ForegroundColor DarkGray
Write-Host "    Bench 7 - Functional Pipeline:  Ocean bare loop vs Java IntStream overhead vs Kotlin inline sumOf vs C# LINQ lazy chain." -ForegroundColor DarkGray
Write-Host "    Bench 8 - Object Allocation:    C# struct = STACK allocated, zero GC. JVM/Ocean/Kotlin = HEAP, triggers GC." -ForegroundColor DarkGray
Write-Host "    Bench 9 - Megamorphic Dispatch: 4 concrete types exceed JVM inline cache (bimorphic limit) -> megamorphic fallback." -ForegroundColor DarkGray
Write-Host "    Bench 10- StringBuilder:        All use native builder but JIT inlining depth and allocation strategy differs." -ForegroundColor DarkGray
Write-Host ""
Write-Host "  Methodology: 10 runs, median reported, stddev shows measurement stability." -ForegroundColor DarkGray
Write-Host "  Ocean compile cache pre-warmed. All JVM benchmarks use Corretto 22. C# uses .NET 9 Release." -ForegroundColor DarkGray
Write-Host ""
