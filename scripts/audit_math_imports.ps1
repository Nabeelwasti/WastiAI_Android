# Audit script for math functions across all Kotlin files
$files = Get-ChildItem -Path "app/src" -Filter "*.kt" -Recurse

$mathFunctions = @("abs", "pow", "sqrt", "round", "ceil", "floor", "sin", "cos", "tan", "atan2", "exp", "ln", "log2", "log10")

$issues = @()
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    
    # Check imports
    $imports = [regex]::Matches($clean, 'import\s+([a-zA-Z0-9_.*]+)') | ForEach-Object { $_.Groups[1].Value }
    
    # Also check if declared inside file
    $declared = [regex]::Matches($clean, '\bfun\s+(?:<[^>]+>\s+)?([A-Za-z0-9_]+)\s*\(') | ForEach-Object { $_.Groups[1].Value }
    
    foreach ($fn in $mathFunctions) {
        # Check if function is called: (?<![.\w])fn\s*\( or \.pow\(
        $isUsed = $false
        if ($fn -eq "pow") {
            $isUsed = ($clean -match '\.pow\s*\(')
        } else {
            $isUsed = ($clean -match "(?<![.\w])$fn\s*\(")
        }
        
        if ($isUsed) {
            # Check if imported from kotlin.math or java.lang.Math or Math.fn or declared in file
            $hasImport = $imports -contains "kotlin.math.$fn" -or 
                         $imports -contains "kotlin.math.*" -or 
                         $imports -contains "java.lang.Math.$fn" -or
                         $declared -contains $fn
            
            # If not imported, check if called as Math.fn or kotlin.math.fn
            if (-not $hasImport) {
                # Check each matching line to see if it's qualified with Math. or kotlin.math.
                $lines = $clean -split "`r?`n"
                for ($i = 0; $i -lt $lines.Length; $i++) {
                    $l = $lines[$i]
                    if ($fn -eq "pow" -and $l -match '\.pow\s*\(') {
                        $issues += "$($file.FullName):$($i+1): Missing import for 'kotlin.math.pow'"
                    } elseif ($fn -ne "pow" -and $l -match "(?<![.\w])$fn\s*\(") {
                        # Make sure not qualified
                        if ($l -notmatch "Math\.$fn" -and $l -notmatch "kotlin\.math\.$fn" -and $l -notmatch "\.$fn\(") {
                            $issues += "$($file.FullName):$($i+1): Missing import for 'kotlin.math.$fn'"
                        }
                    }
                }
            }
        }
    }
}

if ($issues.Count -eq 0) {
    Write-Host "SUCCESS: All math function calls are properly imported!" -ForegroundColor Green
} else {
    Write-Host "FAILED: Found $($issues.Count) missing math imports:" -ForegroundColor Red
    foreach ($issue in $issues) {
        Write-Host "  $issue" -ForegroundColor Yellow
    }
}
