Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "         OCEAN COMPILER ERROR TEST VERIFICATION           " -ForegroundColor Cyan
Write-Host "     (These tests MUST fail to compile or give exit 1)    " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

# Error tests: each entry is [filename, expected_error_fragment]
$errorTests = @(
    @{ Name = "AsyncErrorTest";       ExpectedFragment = "hata|error|await|async" },
    @{ Name = "SealedClassErrorTest1"; ExpectedFragment = "sealed|illegal|restricted" },
    @{ Name = "SealedClassErrorTest2"; ExpectedFragment = "sealed|illegal|restricted" },
    @{ Name = "OverrideErrorTest";    ExpectedFragment = "error|override|incompatible" },
    @{ Name = "VoidReturnErrorTest";  ExpectedFragment = "error|void|return" },
    @{ Name = "ValueReassignmentErrorTest"; ExpectedFragment = "error|value|reassign|final" },
    @{ Name = "CheckedExceptionFailureTest"; ExpectedFragment = "error|exception|checked" },
    @{ Name = "NullSafetyFailureTest"; ExpectedFragment = "error|null" },
    @{ Name = "NullSafetyInvalidAssignTest"; ExpectedFragment = "error|null|atanamaz" },
    @{ Name = "ArrayTypeMismatchTest"; ExpectedFragment = "error|type|mismatch|array" },
    @{ Name = "StringRelationalErrorTest"; ExpectedFragment = "error|kar|compare|String" },
    @{ Name = "ObjectRelationalErrorTest"; ExpectedFragment = "error|kar|compare|Object" },
    @{ Name = "VerifyNonBooleanConditionErrorTest"; ExpectedFragment = "verify|boolean|error" },
    @{ Name = "AmbiguousMethodOverloadErrorTest"; ExpectedFragment = "ambiguous|error|match" },
    @{ Name = "MultiVariableDuplicateErrorTest"; ExpectedFragment = "zaten|tanımlı|duplicate|defined" }
)

$passedCount = 0
$failedCount = 0
$results = @()

Write-Host "Found $($errorTests.Count) error test files to verify..." -ForegroundColor Yellow

foreach ($test in $errorTests) {
    $testName = $test.Name
    $fragment  = $test.ExpectedFragment
    $oceanFile = "examples/$testName.ocean"

    if (-not (Test-Path $oceanFile)) {
        Write-Host " [SKIP] $testName (file not found)" -ForegroundColor DarkGray
        continue
    }

    Write-Host "Running $testName..." -NoNewline

    $startTime = Get-Date
    $output = cmd /c "ocean.bat $oceanFile 2>&1"
    $exitCode = $LASTEXITCODE
    $endTime = Get-Date
    $duration = [math]::Round(($endTime - $startTime).TotalSeconds, 2)
    $outputStr = $output -join "`n"
    $durationStr = "{0:N2}s" -f $duration

    # Error tests pass when: exit code is non-zero (compilation error produced)
    $isCorrect = $exitCode -ne 0

    if ($isCorrect) {
        $passedCount++
        $results += [PSCustomObject]@{ Name = $testName; Status = "PASS"; Duration = $durationStr; Reason = "Correctly rejected (exit $exitCode)" }
        Write-Host " [PASS] $durationStr  Compiler correctly rejected the invalid code." -ForegroundColor Green
    } else {
        $failedCount++
        $results += [PSCustomObject]@{ Name = $testName; Status = "FAIL"; Duration = $durationStr; Reason = "Compiled successfully but should have failed!" }
        Write-Host " [FAIL] $durationStr  ERROR: Code compiled but it should have been rejected!" -ForegroundColor Red
        Write-Host "    Output: $($outputStr.Substring(0, [Math]::Min(200, $outputStr.Length)))" -ForegroundColor DarkRed
    }
}

Write-Host ""
Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host ""

$padName   = 40
$padStatus = 8
$padDur    = 8
$padReason = 50

$header = ("Name".PadRight($padName)) + ("Status".PadRight($padStatus)) + ("Time".PadRight($padDur)) + "Reason"
Write-Host $header -ForegroundColor White
Write-Host ("-" * ($padName + $padStatus + $padDur + $padReason)) -ForegroundColor DarkGray

foreach ($r in $results) {
    $color = if ($r.Status -eq "PASS") { "Green" } else { "Red" }
    $line = ($r.Name.PadRight($padName)) + ($r.Status.PadRight($padStatus)) + ($r.Duration.PadRight($padDur)) + $r.Reason
    Write-Host $line -ForegroundColor $color
}

Write-Host ""
Write-Host "Total Correct Rejections : $passedCount" -ForegroundColor Green
Write-Host "Total Incorrect Passes   : $failedCount" -ForegroundColor Red
Write-Host "==========================================================" -ForegroundColor Cyan

if ($failedCount -gt 0) {
    exit 1
}

exit 0
