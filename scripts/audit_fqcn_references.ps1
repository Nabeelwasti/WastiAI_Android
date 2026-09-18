# Audit script to check all fully-qualified com.example references across Kotlin files
$files = Get-ChildItem -Path "app/src" -Filter "*.kt" -Recurse

# Extract all declared classes, interfaces, objects in com.example
# Map: SimpleName -> List of FullNames (or Packages)
$declaredClasses = [System.Collections.Generic.HashSet[string]]::new()

foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    
    $pkgMatch = [regex]::Match($clean, 'package\s+([a-zA-Z0-9_.]+)')
    $pkg = if ($pkgMatch.Success) { $pkgMatch.Groups[1].Value } else { "" }
    
    $foundMatches = [regex]::Matches($clean, '\b(?:class|interface|object|typealias|fun|val)\s+([A-Za-z0-9_]+)\b')
    foreach ($m in $foundMatches) {
        $className = $m.Groups[1].Value
        if ($pkg -ne "") {
            [void]$declaredClasses.Add("$pkg.$className")
        } else {
            [void]$declaredClasses.Add($className)
        }
    }
}

Write-Host "Discovered $($declaredClasses.Count) fully-qualified declared classes/interfaces/typealiases."

# Scan all files for com.example.[package].[Class] usages
$issues = @()
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    $cleanCode = [regex]::Replace($clean, '"(?:\\.|[^"\\])*"', '')
    
    $lines = $cleanCode -split "`r?`n"
    for ($i = 0; $i -lt $lines.Length; $i++) {
        $l = $lines[$i]
        if ($l.Trim().StartsWith("package ")) { continue }
        
        # Match FQCN: com\.example(?:\.[a-zA-Z0-9_]+)+\.([A-Z][A-Za-z0-9_]*)
        $fqcnMatches = [regex]::Matches($l, '\b(com\.example(?:\.[a-zA-Z0-9_]+)*\.[A-Z][A-Za-z0-9_]*)\b')
        foreach ($fm in $fqcnMatches) {
            $fqcn = $fm.Groups[1].Value
            # Ignore Android R / BuildConfig
            if ($fqcn -match '\.R(\.[a-zA-Z0-9_]+)*$' -or $fqcn -match '\.BuildConfig(\.[a-zA-Z0-9_]+)*$') {
                continue
            }
            if (-not $declaredClasses.Contains($fqcn)) {
                $check = $fqcn
                $found = $false
                while ($check.Contains('.')) {
                    $lastDot = $check.LastIndexOf('.')
                    $check = $check.Substring(0, $lastDot)
                    if ($declaredClasses.Contains($check)) {
                        $found = $true
                        break
                    }
                }
                if (-not $found) {
                    $issues += "$($file.FullName):$($i+1): Unresolved FQCN '$fqcn'"
                }
            }
        }
    }
}

if ($issues.Count -eq 0) {
    Write-Host "SUCCESS: All fully-qualified com.example references exist and are valid!" -ForegroundColor Green
} else {
    Write-Host "FAILED: Found $($issues.Count) unresolved FQCN references:" -ForegroundColor Red
    foreach ($issue in ($issues | Select-Object -Unique)) {
        Write-Host "  $issue" -ForegroundColor Yellow
    }
}
