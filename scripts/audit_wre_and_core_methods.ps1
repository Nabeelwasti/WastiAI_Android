# Targeted audit for all files in com/example/data
$files = Get-ChildItem -Path "app/src/main/java/com/example/data" -Filter "*.kt" -Recurse

$issues = @()
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    
    # Collect all functions declared in the file
    $declaredFunctions = [System.Collections.Generic.HashSet[string]]::new()
    $foundMatches = [regex]::Matches($clean, '\bfun\s+(?:<[^>]+>\s+)?([A-Za-z0-9_]+)\s*\(')
    foreach ($m in $foundMatches) {
        [void]$declaredFunctions.Add($m.Groups[1].Value)
    }
    
    # Check lines inside class methods for calls on this (or bare calls)
    # We look for: \b([a-zA-Z0-9_]+)\s*\(
    # where the call does NOT have a receiver: (?<![.\w])([a-z][A-Za-z0-9_]*)\s*\(
    $lines = $clean -split "`r?`n"
    for ($i = 0; $i -lt $lines.Length; $i++) {
        $line = $lines[$i]
        $callMatches = [regex]::Matches($line, '(?<![.\w])([a-z][A-Za-z0-9_]*)\s*\(')
        foreach ($cm in $callMatches) {
            $name = $cm.Groups[1].Value
            # If it looks like a private helper call in the same class
            if ($name -in @("feed", "execute", "process", "handle", "parse", "format", "validate", "convert", "build", "dispatch")) {
                if (-not $declaredFunctions.Contains($name)) {
                    $issues += "$($file.Name):$($i+1): Suspect helper call '$name()' not declared in file"
                }
            }
        }
    }
}

if ($issues.Count -eq 0) {
    Write-Host "SUCCESS: No suspect undeclared helper calls found in data package!" -ForegroundColor Green
} else {
    Write-Host "Found $($issues.Count) issues:" -ForegroundColor Yellow
    foreach ($issue in $issues) {
        Write-Host "  $issue"
    }
}
