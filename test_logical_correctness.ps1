$totalSuiteStart = Get-Date
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "         OCEAN COMPILER TEST VERIFICATION SYSTEM          " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# List of interactive/helper files to skip
$skips = @(
    "BarcodeTest", "LiveBarcodeSearch", "InputTest", "Deneme", "PiSim", "Benchmark","NightmareInteractiveTest" # Interactive
    "InfinityLoop", "StrictLoopTest", "BarcodeMapTest", # Infinite loops / Extremely long loops
    "HelperClass", "PriceEngine", "FuncInterface", "Runnablex", "SecretClass", "Wallet", "OrderBook", "ConflictClassA", "ConflictClassB", # Helpers / Non-main
    "OverrideErrorTest", "VoidReturnErrorTest", "ValuePostfixErrorTest", "ValueReassignmentErrorTest", "ClassNameMismatchErrorTest", "IncompatibleModifiersTest", "InvalidCatchTypeErrorTest", "InterfaceInvalidFieldErrorTest", "DuplicateSwitchCaseErrorTest", "SelfRefVarErrorTest", "ThisReassignmentErrorTest", "DuplicateEnumConstantErrorTest", "GenericArrayCreationErrorTest", "UntypedVarWithoutInitializerErrorTest", "UntypedValueWithoutInitializerErrorTest", "UntypedParameterErrorTest", "WithoutCastAssignErrorTest",
    "CheckedExceptionFailureTest", "NullSafetyFailureTest", "NullSafetyInvalidAssignTest","PrimitiveBoundsErrorTest", "CovariantReturnFailureTest", "MediumPriorityAmbiguityErrorTest", "ArrayTypeMismatchTest", "MemberTypeMismatchTest", "SuperCtorTest", "SuperCtorSecondStmtTest", "ArraySizeMismatchTest", "NullSafetyParamTest", "StaticFieldAccessTest", "StaticMethodAccessTest" # Fail tests
    "StringRelationalErrorTest", "ObjectRelationalErrorTest","AsyncReturnTypeErrorTest","GenericTypeErrorTest","StaticContextErrorTest","ShadowingComprehensiveTest","ShadowingMethodScopeErrorTest","ShadowingParamBlockErrorTest","ShadowingLoopVarErrorTest","ShadowingCatchVarErrorTest","ShadowingLambdaParamErrorTest","SemanticRemainingTest", "VarianceErrorTest", "VarianceAssignmentErrorTest", "AccessControlErrorTest","ConstructorWithReturnTypeErrorTest","MethodWithoutReturnTypeErrorTest" # Relational comparison error tests
    "InlineValueClassArrayBenchmark", "NoInlineValueClassArrayBenchmark", # Parallel benchmark collisions
    "DefiniteAssignmentIfTest", "DefiniteAssignmentLoopTest", "DefiniteAssignmentTest","NonStaticInnerFromStaticContextErrorTest", # Definite assignment error tests
    "TestFinalExtend", "TestAbstractUnimplemented", "TestIncompatibleOverride", "TestDuplicateMembers", "AbstractClassObjectTest", # Faulty JVM-safety tests
    "EnumInheritanceTest", "ImplementsClassTest", "ExtendsInterfaceTest", "InterfaceExtendsClassTest", "CastTest", "LambdaCaptureFailureTest", "newExceptionTest", "UnimplementedExternalAbstractTest", "JavaNullSafetyInteropTest","MapElementControlErrorTest"
    # Error/Failure tests — designed to trigger compiler errors, NOT to output STATUS: PASSED
    "AsyncErrorTest", "SealedClassErrorTest1", "SealedClassErrorTest2", "IRSemanticAnalyzerControlTest", "VerifyNonBooleanConditionErrorTest", "AmbiguousMethodOverloadErrorTest", "MultiVariableDuplicateErrorTest","DuplicateClassTest", "DuplicateNestedClassErrorTest"
    # Benchmark-only files that do NOT output STATUS: PASSED
    "OceanBench1RecFib", "OceanBench2BubbleSort", "OceanBench3Sieve", "OceanBench4SumLoop", "OceanBench5StringOps",
    "OceanBench6Exceptions", "OceanBench7Functional", "OceanBench8ObjAlloc", "OceanBench9VirtualDispatch",
    "OceanBench10StringBuilder", "OceanBench11ReusedBuilder", "BytecodeBenchmark",
    "IOBench", "JsonBench", "MandelbrotBench", "NodesBench", "CallsBench",
    "MathBench", "HeavyBenchmark",
    # Showcase examples & standalone samples that do NOT output STATUS: PASSED
    "Hello", "Fibonacci", "DataClassPattern", "Concurrency", "CheckedExceptionSuccessTest", "NullSafetySuccessTest"
)

$resourceDir = "examples"
$testFiles = Get-ChildItem -Path $resourceDir -Filter "*.ocean" | Where-Object { $skips -notcontains $_.BaseName }

$passedCount = 0
$failedCount = 0
$results = [System.Collections.Generic.List[PSCustomObject]]::new()

$maxThreads = 8

Write-Host "Found $($testFiles.Count) test files to verify..." -ForegroundColor Yellow
Write-Host "Initializing parallel execution pool ($maxThreads threads)..." -ForegroundColor Yellow

$runspacePool = [runspacefactory]::CreateRunspacePool(1, $maxThreads)
$runspacePool.Open()

$jobs = [System.Collections.Generic.List[object]]::new()

foreach ($file in $testFiles) {
    $testName = $file.BaseName
    $scriptBlock = {
        param($testName, $workingDir)
        Set-Location $workingDir
        $startTime = Get-Date
        $output = & "$workingDir\ocean.bat" "-Docean.test.id=$testName" "examples/$testName.ocean" 2>&1
        $endTime = Get-Date
        $duration = ($endTime - $startTime).TotalSeconds
        $exitCode = $LASTEXITCODE
        # Değişiklik 3: Test sonrası izolasyon dizinini temizle
        $isoDir = "$workingDir\build\test-isolation\$testName"
        if (Test-Path $isoDir) { Remove-Item $isoDir -Recurse -Force -ErrorAction SilentlyContinue }
        return @{
            TestName = $testName
            Output = ($output -join "`n")
            ExitCode = $exitCode
            Duration = $duration
        }
    }
    
    $powershell = [powershell]::Create().AddScript($scriptBlock).AddArgument($testName).AddArgument($pwd.Path)
    $powershell.RunspacePool = $runspacePool
    
    $jobs.Add(@{
        Pipe = $powershell
        Async = $powershell.BeginInvoke()
    })
}

$completed = 0
foreach ($job in $jobs) {
    $res = $job.Pipe.EndInvoke($job.Async)[0]
    $job.Pipe.Dispose()
    
    $completed++
    $testName = $res.TestName
    $outputStr = $res.Output
    $exitCode = $res.ExitCode
    $duration = $res.Duration
    
    $hasFailure = $false
    $failReason = ""
    
    if ($exitCode -ne 0) {
        $hasFailure = $true
        $failReason = "Exit code non-zero ($exitCode)"
    } elseif ($outputStr -match "Exception in thread" -or $outputStr -match "VerifyError") {
        $hasFailure = $true
        $failReason = "JVM Runtime Exception / VerifyError"
    } elseif ($outputStr -match "Compilation failed:") {
        $hasFailure = $true
        $failReason = "Compilation failed"
    } elseif ($outputStr -match "HATALI" -or $outputStr -match "STATUS: FAILED" -or $outputStr -match "STATUS FAILED") {
        $hasFailure = $true
        $failReason = "Test reported verification failure"
    } elseif ($outputStr -notmatch "STATUS: PASSED" -and $outputStr -notmatch "STATUS PASSED" -and $outputStr -notmatch "Passed" -and $outputStr -notmatch "BASARILI") {
        $hasFailure = $true
        $failReason = "Test output did not contain success marker (STATUS: PASSED)"
    }
    
    if ($hasFailure) {
        $failedCount++
        Write-Host "Running $testName... [FAIL] -> $failReason" -ForegroundColor Red
        $results.Add([PSCustomObject]@{
            Test     = $testName
            Status   = "FAIL"
            Duration = "$($duration.ToString('F2'))s"
            Detail   = $failReason
        })
    } else {
        $passedCount++
        Write-Host "Running $testName... [PASS]" -ForegroundColor Green
        $results.Add([PSCustomObject]@{
            Test     = $testName
            Status   = "PASS"
            Duration = "$($duration.ToString('F2'))s"
            Detail   = "Executed successfully."
        })
    }
}

$runspacePool.Close()
$runspacePool.Dispose()

Write-Host "`n==========================================================" -ForegroundColor Cyan
Write-Host "                    SUMMARY OF RESULTS                    " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan
$results | Format-Table -AutoSize

$totalSuiteEnd = Get-Date
$totalDuration = ($totalSuiteEnd - $totalSuiteStart).TotalSeconds

Write-Host "Total Passed             : $passedCount" -ForegroundColor Green
if ($failedCount -gt 0) {
    Write-Host "Total Failed             : $failedCount" -ForegroundColor Red
} else {
    Write-Host "Total Failed             : 0" -ForegroundColor Green
    Write-Host "`n★ ALL TESTS PASSED LOGICALLY AND SUCCESSFULLY! ★" -ForegroundColor Green
}
Write-Host "Total Suite Execution Time: $($totalDuration.ToString('F2')) seconds" -ForegroundColor Yellow
Write-Host "==========================================================" -ForegroundColor Cyan

# Run interactive test suite as well
& "$PSScriptRoot\test_interactive_correctness.ps1"
$interactiveExitCode = $LASTEXITCODE

# Run negative test suite as well
& "$PSScriptRoot\test_negative_correctness.ps1"
$negativeExitCode = $LASTEXITCODE

if ($failedCount -gt 0 -or $interactiveExitCode -ne 0 -or $negativeExitCode -ne 0) {
    Write-Host "`n[ERROR] Test suite failed! (Logical Failures: $failedCount, Interactive ExitCode: $interactiveExitCode, Negative ExitCode: $negativeExitCode)" -ForegroundColor Red
    exit 1
}

exit 0
