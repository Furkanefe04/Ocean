# ==========================================================
# OCEAN COMPILER NEGATIVE TEST VERIFICATION SUITE
# ==========================================================
#
# Validates that invalid/erroneous Ocean source files are
# cleanly rejected by the compiler with a non-zero exit code
# and [ERROR] / Compilation Report output.
#
# ==========================================================

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "     OCEAN COMPILER NEGATIVE TEST VERIFICATION" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$negativeTests = @(
    "MapElementControlErrorTest",
    "ConstructorWithReturnTypeErrorTest",
    "MethodWithoutReturnTypeErrorTest",
    "DuplicateClassTest"
    "IncompatibleModifiersTest",
    "WithoutCastAssignErrorTest",
    "InvalidCatchTypeErrorTest",
    "InterfaceInvalidFieldErrorTest",
    "DuplicateSwitchCaseErrorTest",
    "SelfRefVarErrorTest",
    "ThisReassignmentErrorTest",
    "DuplicateEnumConstantErrorTest",
    "GenericArrayCreationErrorTest",
    "UntypedVarWithoutInitializerErrorTest",
    "UntypedValueWithoutInitializerErrorTest",
    "UntypedParameterErrorTest",
    "StringRelationalErrorTest",
    "OverrideErrorTest",
    "VoidReturnErrorTest",
    "ValuePostfixErrorTest",
    "ValueReassignmentErrorTest",
    "ClassNameMismatchErrorTest",
    "CheckedExceptionFailureTest",
    "NullSafetyFailureTest",
    "CovariantReturnFailureTest",
    "MediumPriorityAmbiguityErrorTest",
    "ArrayTypeMismatchTest",
    "MemberTypeMismatchTest",
    "SuperCtorTest",
    "SuperCtorSecondStmtTest",
    "ArraySizeMismatchTest",
    "StaticFieldAccessTest",
    "SealedClassErrorTest1",
    "SealedClassErrorTest2",
    "TestAbstractUnimplemented",
    "TestDuplicateMembers",
    "TestFinalExtend",
    "TestIncompatibleOverride",
    "PrimitiveBoundsErrorTest",
    "AsyncReturnTypeErrorTest",
    "GenericTypeErrorTest",
    "StaticContextErrorTest",
    "SemanticRemainingTest",
    "ShadowingComprehensiveTest",
    "ShadowingMethodScopeErrorTest",
    "ShadowingParamBlockErrorTest",
    "ShadowingLoopVarErrorTest",
    "ShadowingCatchVarErrorTest",
    "ShadowingLambdaParamErrorTest",
    "VarianceErrorTest",
    "VarianceAssignmentErrorTest",
    "AccessControlErrorTest",
    "NonStaticInnerFromStaticContextErrorTest",
    "CastTest",
    "LambdaCaptureFailureTest",
    "StaticMethodAccessTest",
    "AsyncErrorTest",
    "DefiniteAssignmentTest",
    "DefiniteAssignmentIfTest",
    "DefiniteAssignmentLoopTest",
    "VerifyNonBooleanConditionErrorTest",
    "MultiVariableDuplicateErrorTest",
    "NullSafetyInvalidAssignTest",
    "ObjectRelationalErrorTest",
    "EnumInheritanceTest",
    "ImplementsClassTest",
    "ExtendsInterfaceTest",
    "InterfaceExtendsClassTest"
)

Write-Host "Found $($negativeTests.Count) negative test files to verify..." -ForegroundColor Yellow

# ==========================================================
# RUNSPACE POOL
# ==========================================================

$maxThreads = 8

Write-Host "Initializing parallel execution pool ($maxThreads threads)..." -ForegroundColor Yellow

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
        [string]$workingDir
    )

    Set-Location $workingDir

    $filePath = "examples/$testName.ocean"

    # ------------------------------------------------------
    # File existence
    # ------------------------------------------------------

    if (!(Test-Path $filePath)) {
        return @{
            TestName = $testName
            Status = "SKIP"
            ExitCode = -1
            Duration = 0
            Detail = "File not found"
        }
    }

    # ------------------------------------------------------
    # Start compiler
    # ------------------------------------------------------

    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    $pinfo = New-Object System.Diagnostics.ProcessStartInfo

    $pinfo.FileName = "cmd.exe"
    $pinfo.Arguments = "/c ocean.bat `"$filePath`""

    $pinfo.RedirectStandardOutput = $true
    $pinfo.RedirectStandardError = $true
    $pinfo.UseShellExecute = $false
    $pinfo.CreateNoWindow = $true

    try {

        $process = [System.Diagnostics.Process]::Start($pinfo)

        $stdoutTask = $process.StandardOutput.ReadToEndAsync()
        $stderrTask = $process.StandardError.ReadToEndAsync()

        $process.WaitForExit()

        $stdout = $stdoutTask.Result
        $stderr = $stderrTask.Result

        $exitCode = $process.ExitCode

    }
    catch {

        $sw.Stop()

        return @{
            TestName = $testName
            Status = "FAIL"
            ExitCode = -1
            Duration = $sw.Elapsed.TotalSeconds
            Detail = "Failed to start compiler: $($_.Exception.Message)"
        }
    }

    $sw.Stop()

    # ------------------------------------------------------
    # Analyze output
    # ------------------------------------------------------

    $combinedOutput = $stdout + "`n" + $stderr

    $passed =
        ($exitCode -ne 0) -and
        (
            ($combinedOutput -match "\[ERROR\]") -or
            ($combinedOutput -match "Compilation Report")
        )

    if ($passed) {

        return @{
            TestName = $testName
            Status = "PASS"
            ExitCode = $exitCode
            Duration = $sw.Elapsed.TotalSeconds
            Detail = "Rejected cleanly as expected."
        }

    }
    else {

        return @{
            TestName = $testName
            Status = "FAIL"
            ExitCode = $exitCode
            Duration = $sw.Elapsed.TotalSeconds
            Detail = "Failed to reject invalid file."
        }
    }
}

# ==========================================================
# QUEUE ALL TESTS
# ==========================================================

$totalSuiteStart = [System.Diagnostics.Stopwatch]::StartNew()

foreach ($testName in $negativeTests) {

    $powershell = [powershell]::Create()

    [void]$powershell.AddScript($scriptBlock)
    [void]$powershell.AddArgument($testName)
    [void]$powershell.AddArgument($pwd.Path)

    $powershell.RunspacePool = $runspacePool

    # IMPORTANT:
    # BeginInvoke actually submits the work to the runspace pool.

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
$skippedCount = 0

$results = [System.Collections.Generic.List[object]]::new()

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
            Detail = "Runspace error: $($_.Exception.Message)"
        }
    }

    $job.Pipe.Dispose()

    $testName = $result.TestName
    $status = $result.Status
    $duration = $result.Duration
    $detail = $result.Detail

    switch ($status) {

        "PASS" {

            $passedCount++

            Write-Host (
                "{0,-38} {1,-6} {2,8:N2}s    {3}" -f
                $testName,
                "PASS",
                $duration,
                $detail
            ) -ForegroundColor Green
        }

        "FAIL" {

            $failedCount++

            Write-Host (
                "{0,-38} {1,-6} {2,8:N2}s    {3}" -f
                $testName,
                "FAIL",
                $duration,
                $detail
            ) -ForegroundColor Red
        }

        "SKIP" {

            $skippedCount++

            Write-Host (
                "{0,-38} {1,-6}          {2}" -f
                $testName,
                "SKIP",
                $detail
            ) -ForegroundColor Yellow
        }
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
# SHUTDOWN RUNSPACE POOL
# ==========================================================

$runspacePool.Close()
$runspacePool.Dispose()

$totalSuiteStart.Stop()

# ==========================================================
# SUMMARY
# ==========================================================

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "                    TEST SUMMARY" -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan


Write-Host "Total Passed             : $passedCount" -ForegroundColor Green

if ($failedCount -gt 0) {
    Write-Host "Total Failed             : $failedCount" -ForegroundColor Red
}
else {
    Write-Host "Total Failed             : 0" -ForegroundColor Green
}

if ($skippedCount -gt 0) {
    Write-Host "Total Skipped            : $skippedCount" -ForegroundColor Yellow
}

Write-Host (
    "Total Execution Time     : {0:N2} seconds" -f
    $totalSuiteStart.Elapsed.TotalSeconds
) -ForegroundColor Yellow

Write-Host "==========================================================" -ForegroundColor Cyan

# ==========================================================
# EXIT CODE
# ==========================================================

if ($failedCount -gt 0) {
    exit 1
}

exit 0