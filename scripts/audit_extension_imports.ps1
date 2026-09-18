$files = Get-ChildItem -Path "app/src/main" -Filter "*.kt" -Recurse

# Collect all top-level public/internal extension functions and properties
$extensions = @{}

foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    
    $pkgMatch = [regex]::Match($clean, 'package\s+([a-zA-Z0-9_.]+)')
    $pkg = if ($pkgMatch.Success) { $pkgMatch.Groups[1].Value } else { "" }
    
    $lines = $clean -split "`r?`n"
    $braceDepth = 0
    foreach ($line in $lines) {
        $trimmed = $line.Trim()
        if ($braceDepth -eq 0) {
            # Skip private extensions
            if ($trimmed -match '^\s*private\b') {
                # continue
            } else {
                $extMatch = [regex]::Match($trimmed, '^(?:(?:public|internal)\s+)?(?:suspend\s+|inline\s+)*(?:fun|val)\s+(?:<[^>]+>\s+)?([A-Za-z0-9_<>.,\s?]+)\.([A-Za-z0-9_]+)\b')
                if ($extMatch.Success) {
                    $extName = $extMatch.Groups[2].Value
                    $receiver = $extMatch.Groups[1].Value.Trim()
                    if (-not $extensions.ContainsKey($extName)) {
                        $extensions[$extName] = @()
                    }
                    $extensions[$extName] += [PSCustomObject]@{
                        Package = $pkg
                        Name = $extName
                        Receiver = $receiver
                        File = $file.FullName
                    }
                }
            }
        }
        $braceDepth += ([regex]::Matches($line, '\{')).Count
        $braceDepth -= ([regex]::Matches($line, '\}')).Count
        if ($braceDepth -lt 0) { $braceDepth = 0 }
    }
}

Write-Host "Discovered $($extensions.Keys.Count) unique non-private top-level extension names across the codebase."

$allFiles = Get-ChildItem -Path "app/src" -Filter "*.kt" -Recurse
$issues = @()

foreach ($file in $allFiles) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    $cleanCode = [regex]::Replace($clean, '"(?:\\.|[^"\\])*"', '')
    
    $pkgMatch = [regex]::Match($clean, 'package\s+([a-zA-Z0-9_.]+)')
    $filePkg = if ($pkgMatch.Success) { $pkgMatch.Groups[1].Value } else { "" }
    
    $imports = [System.Collections.Generic.HashSet[string]]::new()
    $importMatches = [regex]::Matches($clean, 'import\s+([a-zA-Z0-9_.]+)')
    foreach ($im in $importMatches) {
        [void]$imports.Add($im.Groups[1].Value)
    }
    
    foreach ($extName in $extensions.Keys) {
        if ($cleanCode -match "\.$extName\b") {
            foreach ($ext in $extensions[$extName]) {
                if ($file.FullName -eq $ext.File) { continue }
                if ($filePkg -eq $ext.Package) { continue }
                
                $expectedFqcn = "$($ext.Package).$extName"
                $wildcardImport = "$($ext.Package).*"
                if (-not $imports.Contains($expectedFqcn) -and -not $imports.Contains($wildcardImport)) {
                    $issues += [PSCustomObject]@{
                        File = $file.FullName
                        Extension = $extName
                        Receiver = $ext.Receiver
                        DeclaringPackage = $ext.Package
                        DeclaringFile = $ext.File
                    }
                }
            }
        }
    }
}

if ($issues.Count -eq 0) {
    Write-Host "SUCCESS: All top-level extension usages are properly imported!" -ForegroundColor Green
} else {
    Write-Host "Found $($issues.Count) candidate extension usages without import:" -ForegroundColor Yellow
    foreach ($issue in $issues) {
        Write-Host "  $($issue.File) uses '.$($issue.Extension)' on $($issue.Receiver) declared in $($issue.DeclaringPackage) ($($issue.DeclaringFile))"
    }
}
