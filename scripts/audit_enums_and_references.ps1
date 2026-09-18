# Audit script to extract all enum declarations and check EnumName.MEMBER references across the codebase
$files = Get-ChildItem -Path "app/src" -Filter "*.kt" -Recurse

$enumDefinitions = @{}

# Phase 1: Extract all enum classes and their entries
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    # Remove block comments
    $contentClean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    # Remove line comments
    $contentClean = [regex]::Replace($contentClean, '//[^\r\n]*', '')

    # Match: enum class <Name> ... { <entries> }
    $regex = [regex]'enum\s+class\s+([A-Za-z0-9_]+)\s*(?:\([^)]*\))?\s*(?::\s*[^{]+)?\{([^}]*)\}'
    $matches = $regex.Matches($contentClean)
    foreach ($m in $matches) {
        $enumName = $m.Groups[1].Value
        $body = $m.Groups[2].Value
        # Split by semicolon or end of entries
        $entriesPart = $body.Split(';')[0]
        
        # Tokenize by comma
        $entries = @()
        $tokens = $entriesPart -split ','
        foreach ($tok in $tokens) {
            $cleaned = ($tok -split '\(')[0].Trim()
            # Clean extra whitespace
            $cleaned = [regex]::Replace($cleaned, '\s+', ' ').Trim()
            $words = $cleaned -split ' '
            foreach ($w in $words) {
                $w = $w.Trim()
                if ($w -match '^[A-Za-z0-9_]+$') {
                    $entries += $w
                }
            }
        }
        if ($entries.Count -gt 0) {
            # Support multiple enums with same name in different files
            if (-not $enumDefinitions.ContainsKey($enumName)) {
                $enumDefinitions[$enumName] = @()
            }
            $enumDefinitions[$enumName] += @{
                File = $file.FullName
                Entries = [System.Collections.Generic.HashSet[string]]$entries
            }
        }
    }
}

Write-Host "Discovered $($enumDefinitions.Keys.Count) unique enum class names."

# Phase 2: Scan all files for EnumName.MEMBER usages
$issues = @()
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $lines = $content -split "`r?`n"
    for ($i = 0; $i -lt $lines.Length; $i++) {
        $line = $lines[$i]
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith("//") -or $trimmed.StartsWith("/*") -or $trimmed.StartsWith("*")) {
            continue
        }
        
        $matchUsage = [regex]::Matches($line, '\b([A-Z][A-Za-z0-9_]+)\.([A-Z0-9_]+)\b')
        foreach ($mu in $matchUsage) {
            $targetEnum = $mu.Groups[1].Value
            $targetMember = $mu.Groups[2].Value
            
            if ($enumDefinitions.ContainsKey($targetEnum)) {
                $allowedSpecial = @("name", "ordinal", "values", "valueOf", "entries")
                if ($allowedSpecial.Contains($targetMember)) {
                    continue
                }
                
                # Check if ANY enum with this name has this entry
                $foundInAny = $false
                foreach ($enumDef in $enumDefinitions[$targetEnum]) {
                    if ($enumDef.Entries.Contains($targetMember)) {
                        $foundInAny = $true
                        break
                    }
                    # Also check companion object in that file
                    $enumFileContent = [System.IO.File]::ReadAllText($enumDef.File)
                    if ($enumFileContent -match "companion\s+object[^{]*\{[^}]*\b$targetMember\b") {
                        $foundInAny = $true
                        break
                    }
                }
                
                if (-not $foundInAny) {
                    $allValid = ($enumDefinitions[$targetEnum] | ForEach-Object { $_.Entries }) -join ', '
                    $issues += "Line $($i+1) in $($file.FullName): Unknown enum entry '$targetMember' on '$targetEnum' (Valid: $allValid)"
                }
            }
        }
    }
}

if ($issues.Count -eq 0) {
    Write-Host "SUCCESS: All EnumName.MEMBER usages are strictly valid!" -ForegroundColor Green
} else {
    Write-Host "FAILED: Found $($issues.Count) invalid enum entry usages:" -ForegroundColor Red
    foreach ($issue in $issues) {
        Write-Host "  $issue" -ForegroundColor Yellow
    }
}
