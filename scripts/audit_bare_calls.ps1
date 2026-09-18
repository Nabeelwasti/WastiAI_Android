# Audit script to detect undefined bare function calls within classes in Kotlin files
$files = Get-ChildItem -Path "app/src" -Filter "*.kt" -Recurse

# Standard Kotlin/Java/Android globals that can be called bare
$knownGlobals = [System.Collections.Generic.HashSet[string]]::new([string[]]@(
    "listOf", "mutableListOf", "mapOf", "mutableMapOf", "setOf", "mutableSetOf", "emptyList", "emptyMap", "emptySet",
    "arrayOf", "byteArrayOf", "intArrayOf", "floatArrayOf", "doubleArrayOf", "booleanArrayOf", "longArrayOf",
    "println", "print", "require", "requireNotNull", "check", "checkNotNull", "error",
    "runBlocking", "launch", "async", "withContext", "withTimeout", "withTimeoutOrNull",
    "flow", "flowOf", "channelFlow", "callbackFlow", "coroutineScope", "supervisorScope",
    "minOf", "maxOf", "synchronized", "repeat", "buildString", "sequence",
    "assertEquals", "assertNotEquals", "assertTrue", "assertFalse", "assertNull", "assertNotNull", "assertSame", "assertNotSame", "fail",
    "coEvery", "coVerify", "every", "verify", "mockk", "spyk", "slot",
    "remember", "mutableStateOf", "derivedStateOf", "LaunchedEffect", "DisposableEffect", "SideEffect",
    "Text", "Box", "Column", "Row", "Spacer", "Button", "Card", "Surface", "Scaffold", "Icon", "Divider", "LazyColumn", "LazyRow",
    "items", "item", "stringResource", "colorResource"
))

# Collect top-level functions across all files
$topLevelFunctions = [System.Collections.Generic.HashSet[string]]::new()
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    
    # Top level: fun <Name> before any class
    $topPart = ($clean -split '\b(?:class|object|interface)\b')[0]
    $matches = [regex]::Matches($topPart, '\bfun\s+(?:<[^>]+>\s+)?([A-Za-z0-9_]+)\s*\(')
    foreach ($m in $matches) {
        [void]$topLevelFunctions.Add($m.Groups[1].Value)
    }
}

Write-Host "Found $($topLevelFunctions.Count) top-level functions."

$issues = @()
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    
    # Collect all functions and properties declared in this file
    $fileMembers = [System.Collections.Generic.HashSet[string]]::new()
    $declMatches = [regex]::Matches($clean, '\b(?:fun|val|var)\s+(?:<[^>]+>\s+)?([A-Za-z0-9_]+)\b')
    foreach ($dm in $declMatches) {
        [void]$fileMembers.Add($dm.Groups[1].Value)
    }
    
    # Collect all imported names
    $importedNames = [System.Collections.Generic.HashSet[string]]::new()
    $importMatches = [regex]::Matches($clean, 'import\s+[a-zA-Z0-9_.]+\.([A-Za-z0-9_]+)\b')
    foreach ($im in $importMatches) {
        [void]$importedNames.Add($im.Groups[1].Value)
    }
    
    # Scan for bare function calls: (?<!\.)\b([a-z][A-Za-z0-9_]*)\s*\(
    # Look for calls where preceding character is not '.' and not 'fun' and not 'val' and not 'var'
    $lines = $clean -split "`r?`n"
    for ($lineIdx = 0; $lineIdx -lt $lines.Length; $lineIdx++) {
        $line = $lines[$lineIdx]
        $callMatches = [regex]::Matches($line, '(?<![.\w])([a-z][A-Za-z0-9_]*)\s*\(')
        foreach ($cm in $callMatches) {
            $funcName = $cm.Groups[1].Value
            # Skip control structures and keywords
            if ($funcName -in @("if", "when", "while", "for", "catch", "return", "throw", "also", "let", "apply", "run", "takeIf", "takeUnless", "use")) {
                continue
            }
            if (-not $fileMembers.Contains($funcName) -and 
                -not $topLevelFunctions.Contains($funcName) -and 
                -not $importedNames.Contains($funcName) -and 
                -not $knownGlobals.Contains($funcName)) {
                $issues += "$($file.FullName):$($lineIdx + 1): Potential bare unresolved call '$funcName()'"
            }
        }
    }
}

Write-Host "Found $($issues.Count) candidate bare calls."
$filtered = $issues | Where-Object { 
    $_ -notmatch "invoke\(\)" -and 
    $_ -notmatch "copy\(\)" -and 
    $_ -notmatch "get\(\)" -and 
    $_ -notmatch "set\(\)" -and
    $_ -notmatch "toString\(\)" -and
    $_ -notmatch "equals\(\)"
}

Write-Host "Filtered candidates: $($filtered.Count)"
foreach ($item in ($filtered | Select-Object -First 30)) {
    Write-Host "  $item" -ForegroundColor Yellow
}
