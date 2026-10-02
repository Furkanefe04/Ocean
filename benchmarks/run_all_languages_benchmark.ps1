Write-Host "================================================================================================================" -ForegroundColor Cyan
Write-Host "                MULTI-LANGUAGE MASSIVE BENCHMARK: C++, C#, Ocean, Kotlin, Java                                 " -ForegroundColor Cyan
Write-Host "                Workload: 10,000,000 Elements x 5 Rounds (Indexed Updates + Foreach Sum)                        " -ForegroundColor Cyan
Write-Host "================================================================================================================" -ForegroundColor Cyan

$results = [System.Collections.Generic.List[PSCustomObject]]::new()

# -------------------------------------------------------------
# 1. C++ (std::vector<int> - Native g++ -O3)
# -------------------------------------------------------------
Write-Host "`n[1/5] Compiling & Running C++ (g++ -O3)..." -ForegroundColor Yellow
$cppCompileSw = [System.Diagnostics.Stopwatch]::StartNew()
& "g++" -O3 "benchmarks/CppBenchmark.cpp" -o "benchmarks/cpp_bench.exe"
$cppCompileSw.Stop()
$cppCompileMs = $cppCompileSw.ElapsedMilliseconds

$cppRunSw = [System.Diagnostics.Stopwatch]::StartNew()
$cppOut = & ".\benchmarks\cpp_bench.exe"
$cppRunSw.Stop()
$cppWallMs = $cppRunSw.ElapsedMilliseconds
$cppOut | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

$cppInit = ($cppOut | Select-String "List Initialization Time: (\d+) ms").Matches.Groups[1].Value
$cppExec = ($cppOut | Select-String "Total Execution Time: (\d+) ms").Matches.Groups[1].Value
$cppAvg = ($cppOut | Select-String "Avg: (\d+) ms/round").Matches.Groups[1].Value

$results.Add([PSCustomObject]@{
    Language = "C++ (std::vector<int>)"
    Type = "Native (-O3)"
    CompileMs = [int]$cppCompileMs
    InitMs = [int]$cppInit
    ExecMs = [int]$cppExec
    TotalMs = [int]$cppCompileMs + [int]$cppExec
    AvgRoundMs = [int]$cppAvg
    WallMs = [int]$cppWallMs
})

# -------------------------------------------------------------
# 2. C# (List<int> - .NET 8.0 Release)
# -------------------------------------------------------------
Write-Host "`n[2/5] Compiling & Running C# (.NET Release)..." -ForegroundColor Yellow
$csCompileSw = [System.Diagnostics.Stopwatch]::StartNew()
& "dotnet" build "benchmarks/csharp/csharp.csproj" -c Release --verbosity quiet | Out-Null
$csCompileSw.Stop()
$csCompileMs = $csCompileSw.ElapsedMilliseconds

$csRunSw = [System.Diagnostics.Stopwatch]::StartNew()
$csOut = & "dotnet" run --project "benchmarks/csharp/csharp.csproj" -c Release
$csRunSw.Stop()
$csWallMs = $csRunSw.ElapsedMilliseconds
$csOut | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

$csInit = ($csOut | Select-String "List Initialization Time: (\d+) ms").Matches.Groups[1].Value
$csExec = ($csOut | Select-String "Total Execution Time: (\d+) ms").Matches.Groups[1].Value
$csAvg = ($csOut | Select-String "Avg: (\d+) ms/round").Matches.Groups[1].Value

$results.Add([PSCustomObject]@{
    Language = "C# (List<int>)"
    Type = ".NET 8 JIT"
    CompileMs = [int]$csCompileMs
    InitMs = [int]$csInit
    ExecMs = [int]$csExec
    TotalMs = [int]$csCompileMs + [int]$csExec
    AvgRoundMs = [int]$csAvg
    WallMs = [int]$csWallMs
})

# -------------------------------------------------------------
# 3. Ocean (OceanIntList - JVM Primitive Desugared)
# -------------------------------------------------------------
Write-Host "`n[3/5] Compiling & Running Ocean (OceanIntList)..." -ForegroundColor Yellow
$oceanCompileSw = [System.Diagnostics.Stopwatch]::StartNew()
& ".\ocean.bat" -c "benchmarks/OceanPrimitiveBenchmark.ocean" | Out-Null
$oceanCompileSw.Stop()
$oceanCompileMs = $oceanCompileSw.ElapsedMilliseconds

$oceanRunSw = [System.Diagnostics.Stopwatch]::StartNew()
$oceanOut = & ".\ocean.bat" "benchmarks/OceanPrimitiveBenchmark.ocean"
$oceanRunSw.Stop()
$oceanWallMs = $oceanRunSw.ElapsedMilliseconds
$oceanOut | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

$oceanInit = ($oceanOut | Select-String "List Initialization Time: (\d+) ms").Matches.Groups[1].Value
$oceanExec = ($oceanOut | Select-String "Total Execution Time: (\d+) ms").Matches.Groups[1].Value
$oceanAvg = ($oceanOut | Select-String "Avg: (\d+) ms/round").Matches.Groups[1].Value

$results.Add([PSCustomObject]@{
    Language = "Ocean (OceanIntList)"
    Type = "JVM Bytecode"
    CompileMs = [int]$oceanCompileMs
    InitMs = [int]$oceanInit
    ExecMs = [int]$oceanExec
    TotalMs = [int]$oceanCompileMs + [int]$oceanExec
    AvgRoundMs = [int]$oceanAvg
    WallMs = [int]$oceanWallMs
})

# -------------------------------------------------------------
# 4. Kotlin (ArrayList<Int> - JVM)
# -------------------------------------------------------------
Write-Host "`n[4/5] Compiling & Running Kotlin (ArrayList<Int>)..." -ForegroundColor Yellow
$ktCompileSw = [System.Diagnostics.Stopwatch]::StartNew()
& "kotlinc" "benchmarks/KotlinBenchmark.kt" -include-runtime -d "benchmarks/kotlin_bench.jar"
$ktCompileSw.Stop()
$ktCompileMs = $ktCompileSw.ElapsedMilliseconds

$ktRunSw = [System.Diagnostics.Stopwatch]::StartNew()
$ktOut = java -Xmx4g -jar "benchmarks/kotlin_bench.jar"
$ktRunSw.Stop()
$ktWallMs = $ktRunSw.ElapsedMilliseconds
$ktOut | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

$ktInit = ($ktOut | Select-String "List Initialization Time: (\d+) ms").Matches.Groups[1].Value
$ktExec = ($ktOut | Select-String "Total Execution Time: (\d+) ms").Matches.Groups[1].Value
$ktAvg = ($ktOut | Select-String "Avg: (\d+) ms/round").Matches.Groups[1].Value

$results.Add([PSCustomObject]@{
    Language = "Kotlin (ArrayList<Int>)"
    Type = "JVM Bytecode"
    CompileMs = [int]$ktCompileMs
    InitMs = [int]$ktInit
    ExecMs = [int]$ktExec
    TotalMs = [int]$ktCompileMs + [int]$ktExec
    AvgRoundMs = [int]$ktAvg
    WallMs = [int]$ktWallMs
})

# -------------------------------------------------------------
# 5. Java (ArrayList<Integer> - JVM)
# -------------------------------------------------------------
Write-Host "`n[5/5] Compiling & Running Java (ArrayList<Integer>)..." -ForegroundColor Yellow
$javaCompileSw = [System.Diagnostics.Stopwatch]::StartNew()
& "javac" -d "build/classes/java/test" "benchmarks/JavaArrayListBenchmark.java"
$javaCompileSw.Stop()
$javaCompileMs = $javaCompileSw.ElapsedMilliseconds

$javaRunSw = [System.Diagnostics.Stopwatch]::StartNew()
$javaOut = java -Xmx4g -cp "build/classes/java/test" benchmarks.JavaArrayListBenchmark
$javaRunSw.Stop()
$javaWallMs = $javaRunSw.ElapsedMilliseconds
$javaOut | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

$javaInit = ($javaOut | Select-String "List Initialization Time: (\d+) ms").Matches.Groups[1].Value
$javaExec = ($javaOut | Select-String "Total Execution Time: (\d+) ms").Matches.Groups[1].Value
$javaAvg = ($javaOut | Select-String "Avg: (\d+) ms/round").Matches.Groups[1].Value

$results.Add([PSCustomObject]@{
    Language = "Java (ArrayList<Integer>)"
    Type = "JVM Bytecode"
    CompileMs = [int]$javaCompileMs
    InitMs = [int]$javaInit
    ExecMs = [int]$javaExec
    TotalMs = [int]$javaCompileMs + [int]$javaExec
    AvgRoundMs = [int]$javaAvg
    WallMs = [int]$javaWallMs
})

# -------------------------------------------------------------
# Sıralama: Derleme + Runtime Toplamına Göre (Küçükten Büyüğe)
# -------------------------------------------------------------
$sortedResults = $results | Sort-Object TotalMs

Write-Host "`n================================================================================================================" -ForegroundColor Cyan
Write-Host "           DERLEME + RUNTIME TOPLAM SÜRESİNE GÖRE SIRALANMIŞ LİDERLİK TABLOSU (En Hızlıdan En Yavaşa)            " -ForegroundColor Cyan
Write-Host "================================================================================================================" -ForegroundColor Cyan

Write-Host ("{0,-5} | {1,-26} | {2,-12} | {3,-12} | {4,-12} | {5,-16} | {6,-10} | {7,-10}" -f "Sıra", "Dil & Koleksiyon", "Ortam", "Derleme", "5R Runtime", "TOPLAM (Derl+Run)", "10M Init", "Avg/Tur") -ForegroundColor White
Write-Host ("-" * 120)

$rank = 1
$medals = @("1.", "2.", "3.", "4.", "5.")
foreach ($r in $sortedResults) {
    $medal = if ($rank -le $medals.Count) { $medals[$rank - 1] } else { "   $rank." }
    $color = if ($rank -eq 1) { "Green" } elseif ($rank -eq 2) { "Yellow" } elseif ($rank -eq 3) { "Cyan" } else { "Gray" }
    
    Write-Host ("{0,-5} | {1,-26} | {2,-12} | {3,9} ms | {4,9} ms | {5,13} ms | {6,7} ms | {7,7} ms" -f `
        $medal, $r.Language, $r.Type, $r.CompileMs, $r.ExecMs, $r.TotalMs, $r.InitMs, $r.AvgRoundMs) -ForegroundColor $color
    $rank++
}
Write-Host ("-" * 120)