$files = Get-ChildItem -Path "app/src" -Filter "*.kt" -Recurse

# 1. Collect all enum class names
$enumNames = [System.Collections.Generic.HashSet[string]]::new()
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    
    $regexMatches = [regex]::Matches($clean, '\benum\s+class\s+([A-Za-z0-9_]+)\b')
    foreach ($m in $regexMatches) {
        [void]$enumNames.Add($m.Groups[1].Value)
    }
}

# Exclude any that also have data class declaration
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    
    $dcMatches = [regex]::Matches($clean, '\bdata\s+class\s+([A-Za-z0-9_]+)\b')
    foreach ($dm in $dcMatches) {
        [void]$enumNames.Remove($dm.Groups[1].Value)
    }
}

Write-Host "Found $($enumNames.Count) strict enum classes."

# 2. Search for EnumName(...) usages
$issues = @()
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    $cleanCode = [regex]::Replace($clean, '"(?:\\.|[^"\\])*"', '')
    
    $lines = $cleanCode -split "`r?`n"
    for ($i = 0; $i -lt $lines.Length; $i++) {
        $line = $lines[$i]
        # Ignore lines declaring enum class itself
        if ($line -match '\benum\s+class\b') { continue }
        
        foreach ($enumName in $enumNames) {
            # Match: EnumName(...) but not EnumName.valueOf(...) or EnumName.entries or EnumName.MEMBER
            # Regex: \b$enumName\s*\(
            if ($line -match "\b$enumName\s*\(") {
                # Check if it's EnumName::class or similar
                if ($line -notmatch "\b$enumName::class") {
                    $issues += "$($file.FullName):$($i+1): Enum instantiation of '$enumName': $($line.Trim())"
                }
            }
        }
    }
}

if ($issues.Count -eq 0) {
    Write-Host "SUCCESS: No invalid enum instantiations found!" -ForegroundColor Green
} else {
    Write-Host "FAILED: Found $($issues.Count) invalid enum instantiations:" -ForegroundColor Red
    foreach ($issue in $issues) {
        Write-Host "  $issue" -ForegroundColor Yellow
    }
}
