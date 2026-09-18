# Audit script for named arguments in constructor calls in test files
$mainFiles = Get-ChildItem -Path "app/src/main" -Filter "*.kt" -Recurse
$testFiles = Get-ChildItem -Path "app/src/test" -Filter "*.kt" -Recurse

# Extract constructors from main files
# Key: ClassName -> List of Sets of parameter names
$constructors = @{}

foreach ($file in $mainFiles) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    
    # Match: (data\s+)?class\s+([A-Za-z0-9_]+)\s*(?:<[^>]+>)?\s*\((.*?)\)
    $matches = [regex]::Matches($clean, '(?:data\s+)?class\s+([A-Za-z0-9_]+)\s*(?:<[^>]+>)?\s*\((.*?)\)', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    foreach ($m in $matches) {
        $className = $m.Groups[1].Value
        $paramsText = $m.Groups[2].Value
        
        # Split paramsText on commas (ignoring inside generics or parentheses)
        $paramNames = [System.Collections.Generic.HashSet[string]]::new()
        $paramMatches = [regex]::Matches($paramsText, '(?:val|var)?\s*([A-Za-z0-9_]+)\s*:\s*')
        foreach ($pm in $paramMatches) {
            [void]$paramNames.Add($pm.Groups[1].Value)
        }
        
        if ($paramNames.Count -gt 0) {
            if (-not $constructors.ContainsKey($className)) {
                $constructors[$className] = @()
            }
            $constructors[$className] += $paramNames
        }
    }
}

Write-Host "Indexed constructors for $($constructors.Keys.Count) classes."

# Check named argument invocations in test files
$issues = @()
foreach ($file in $testFiles) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    
    # Match ClassName(arg1 = ..., arg2 = ...)
    $invocations = [regex]::Matches($clean, '\b([A-Z][A-Za-z0-9_]+)\s*\(\s*([a-zA-Z0-9_]+)\s*=', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    foreach ($inv in $invocations) {
        $className = $inv.Groups[1].Value
        $firstArg = $inv.Groups[2].Value
        
        if ($constructors.ContainsKey($className)) {
            $matched = $false
            foreach ($paramSet in $constructors[$className]) {
                if ($paramSet.Contains($firstArg)) {
                    $matched = $true
                    break
                }
            }
            if (-not $matched) {
                $known = ($constructors[$className] | ForEach-Object { $_ -join ', ' }) -join ' | '
                $issues += "$($file.Name): Call to '$className($firstArg = ...)' does not match constructor params: ($known)"
            }
        }
    }
}

if ($issues.Count -eq 0) {
    Write-Host "SUCCESS: All named argument constructor calls match!" -ForegroundColor Green
} else {
    Write-Host "FAILED: Found $($issues.Count) issues:" -ForegroundColor Red
    $unique = $issues | Select-Object -Unique
    foreach ($issue in $unique) {
        Write-Host "  $issue" -ForegroundColor Yellow
    }
}
