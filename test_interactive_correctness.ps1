# ==========================================================
# OCEAN COMPILER INTERACTIVE TEST VERIFICATION SYSTEM
# ==========================================================

$totalSuiteStart = [System.Diagnostics.Stopwatch]::StartNew()

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "     OCEAN COMPILER INTERACTIVE TEST VERIFICATION        " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$interactiveTests = @(
    @{ Name = "BarcodeTest"; Input = "8691234`nexit`n" },
    @{ Name = "Benchmark"; Input = "Furkan`n100000`n0`n" },
    @{ Name = "Deneme"; Input = "Furkan`n1234`n" },
    @{ Name = "InputTest"; Input = "Furkan`n25`n10 Oca`n" },
    @{ Name = "PiSim"; Input = "50 0`n" },
    @{ Name = "NightmareInteractiveTest"; Input = "50 20 100"}
)

$maxThreads = 8

Write-Host "Found $($interactiveTests.Count) interactive tests to verify..." -ForegroundColor Yellow
Write-Host "Initializing parallel execution pool ($maxThreads threads)..." -ForegroundColor Yellow

# ==========================================================
# RUNSPACE POOL
# ==========================================================

$runspacePool = [runspacefactory]::CreateRunspacePool(
    1,
    $maxThreads
)

$runspacePool.Open()

$jobs = [System.Collections.Generic.List[object]]::new()

# ==========================================================
# TEST WORKER
# ==========================================================

$scriptBlock = {
    param(
        [string]$testName,
        [string]$inputData,
        [string]$workingDir
    )

    Set-Location $workingDir

    $tmpDir = Join-Path $env:TEMP "ocean_input"

    if (!(Test-Path $tmpDir)) {
        New-Item -ItemType Directory -Path $tmpDir -Force | Out-Null
    }

    $tmpFile = Join-Path $tmpDir "$testName.txt"

    [System.IO.File]::WriteAllText(
        $tmpFile,
        $inputData,
        [System.Text.Encoding]::ASCII
    )

    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    $output = cmd /c "(type `"$tmpFile`") | ocean.bat examples/$testName.ocean 2>&1"

    $exitCode = $LASTEXITCODE

    $sw.Stop()

    $outputStr = $output -join "`n"

    $hasFailure = $false
    $failReason = ""

    if ($exitCode -ne 0) {
        $hasFailure = $true
        $failReason = "Exit code non-zero ($exitCode)"
    }
    elseif (
        $outputStr -match "Exception in thread" -or
        $outputStr -match "VerifyError" -or
        $outputStr -match "ClassCastException"
    ) {
        $hasFailure = $true
        $failReason = "JVM Runtime Exception / ClassCastException"
    }
    elseif ($outputStr -match "Compilation failed:") {
        $hasFailure = $true
        $failReason = "Compilation failed"
    }

    @{
        TestName = $testName
        Status = if ($hasFailure) { "FAIL" } else { "PASS" }
        Duration = $sw.Elapsed.TotalSeconds
        Output = $outputStr
        Detail = if ($hasFailure) {
            $failReason
        } else {
            "Executed successfully."
        }
    }
}

# ==========================================================
# QUEUE TESTS
# ==========================================================

foreach ($test in $interactiveTests) {

    $powershell = [powershell]::Create()

    [void]$powershell.AddScript($scriptBlock)
    [void]$powershell.AddArgument($test.Name)
    [void]$powershell.AddArgument($test.Input)
    [void]$powershell.AddArgument($pwd.Path)

    $powershell.RunspacePool = $runspacePool

    $asyncResult = $powershell.BeginInvoke()

    $jobs.Add(@{
        Pipe = $powershell
        Async = $asyncResult
    })
}

# ==========================================================
# COLLECT RESULTS
# ==========================================================

$passedCount = 0
$failedCount = 0

$results = [System.Collections.Generic.List[PSCustomObject]]::new()

foreach ($job in $jobs) {

    try {

        $result = $job.Pipe.EndInvoke($job.Async)[0]

    }
    catch {

        $result = @{
            TestName = "Unknown"
            Status = "FAIL"
            ExitCode = -1
            Duration = 0
            Output = ""
            Detail = "Runspace error: $($_.Exception.Message)"
        }
    }

    $job.Pipe.Dispose()

    $testName = $result.TestName
    $status = $result.Status
    $duration = $result.Duration
    $detail = $result.Detail

    if ($status -eq "PASS") {

        $passedCount++

        Write-Host (
            "{0,-37} PASS   {1,5:N2}s    {2}" -f
            $testName,
            $duration,
            $detail
        ) -ForegroundColor Green

    }
    else {

        $failedCount++

        Write-Host (
            "{0,-37} FAIL   {1,5:N2}s    {2}" -f
            $testName,
            $duration,
            $detail
        ) -ForegroundColor Red

        Write-Host "--- Output Snippet ---" -ForegroundColor DarkRed
        Write-Host $result.Output -ForegroundColor Gray
    }

    $results.Add(
        [PSCustomObject]@{
            Test = $testName
            Status = $status
            Duration = "{0:N2}s" -f $duration
            Detail = $detail
        }
    )
}

# ==========================================================
# SHUTDOWN
# ==========================================================

$runspacePool.Close()
$runspacePool.Dispose()

$totalSuiteStart.Stop()

# ==========================================================
# SUMMARY
# ==========================================================

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "                    TEST SUMMARY" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

Write-Host ("Total Passed             : {0}" -f $passedCount) `
    -ForegroundColor Green

$color = if ($failedCount -gt 0) {
    "Red"
}
else {
    "Green"
}

Write-Host ("Total Failed             : {0}" -f $failedCount) `
    -ForegroundColor $color

Write-Host (
    "Total Execution Time     : {0:N2} seconds" -f
    $totalSuiteStart.Elapsed.TotalSeconds
) -ForegroundColor Yellow

Write-Host "==========================================================" -ForegroundColor Cyan

if ($failedCount -gt 0) {
    exit 1
}

exit 0