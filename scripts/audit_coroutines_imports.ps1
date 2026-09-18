# Audit script to check all coroutine functions across all Kotlin files
$files = Get-ChildItem -Path "app/src" -Filter "*.kt" -Recurse

$coroutineFunctions = @("withTimeout", "withTimeoutOrNull", "joinAll", "awaitAll", "cancelAndJoin", "delay", "yield")

$issues = @()
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    # Strip string literals so string contents aren't mistaken for code
    $cleanCode = [regex]::Replace($clean, '"(?:\\.|[^"\\])*"', '')
    
    $imports = [regex]::Matches($clean, 'import\s+([a-zA-Z0-9_.*]+)') | ForEach-Object { $_.Groups[1].Value }
    
    # 1. Check coroutineContext.isActive
    if ($cleanCode -match 'coroutineContext\.isActive') {
        if (-not ($imports -contains 'kotlinx.coroutines.isActive' -or $imports -contains 'kotlinx.coroutines.*')) {
            $issues += "$($file.FullName): coroutineContext.isActive used without import kotlinx.coroutines.isActive"
        }
    }
    
    # 2. Check each coroutine function
    foreach ($fn in $coroutineFunctions) {
        if ($cleanCode -match "(?<![.\w])$fn\s*\(") {
            if (-not ($imports -contains "kotlinx.coroutines.$fn" -or $imports -contains "kotlinx.coroutines.*")) {
                # Check if declared in file
                if ($cleanCode -notmatch "\bfun\s+(?:<[^>]+>\s+)?$fn\s*\(") {
                    $issues += "$($file.FullName): $fn() used without import kotlinx.coroutines.$fn"
                }
            }
        }
    }
}

if ($issues.Count -eq 0) {
    Write-Host "SUCCESS: All coroutine extension functions and properties are properly imported!" -ForegroundColor Green
} else {
    Write-Host "FAILED: Found $($issues.Count) coroutine import issues:" -ForegroundColor Red
    foreach ($issue in $issues) {
        Write-Host "  $issue" -ForegroundColor Yellow
    }
}
