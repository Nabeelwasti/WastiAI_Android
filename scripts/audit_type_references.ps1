# Repository-wide audit for all type references in Kotlin files
$files = Get-ChildItem -Path "app/src" -Filter "*.kt" -Recurse

# 1. Collect all declared types in the repository
$projectTypes = [System.Collections.Generic.HashSet[string]]::new()
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    
    # Matches: (class|interface|object|enum class|typealias) Name
    $matches = [regex]::Matches($clean, '\b(?:class|interface|object|typealias)\s+([A-Za-z0-9_]+)\b')
    foreach ($m in $matches) {
        [void]$projectTypes.Add($m.Groups[1].Value)
    }
}

Write-Host "Discovered $($projectTypes.Count) declared types across the project."

# 2. Add standard types
$standardTypes = [System.Collections.Generic.HashSet[string]]::new([string[]]@(
    "String", "Int", "Long", "Float", "Double", "Boolean", "Byte", "Short", "Char", "Unit", "Any", "Nothing",
    "List", "Map", "Set", "MutableList", "MutableMap", "MutableSet", "Collection", "Iterable", "Sequence", "Array",
    "ByteArray", "IntArray", "LongArray", "FloatArray", "DoubleArray", "BooleanArray", "CharArray",
    "Pair", "Triple", "Result", "Lazy", "Comparable", "CharSequence", "Number", "Throwable", "Exception", "Error",
    "Flow", "StateFlow", "SharedFlow", "MutableStateFlow", "MutableSharedFlow", "Job", "Deferred", "CoroutineScope", "CoroutineContext",
    "Context", "Intent", "Bundle", "Activity", "Service", "BroadcastReceiver", "IBinder", "SharedPreferences",
    "View", "Modifier", "Color", "TextStyle", "FontWeight", "Dp", "TextUnit", "PaddingValues", "Shape",
    "File", "InputStream", "OutputStream", "Reader", "Writer", "BufferedReader", "BufferedWriter", "UUID", "Date", "SimpleDateFormat",
    "JSONObject", "JSONArray", "Uri", "Bitmap", "Canvas", "Paint", "Rect", "Point",
    "AtomicBoolean", "AtomicInteger", "AtomicLong", "AtomicReference", "ConcurrentHashMap", "CopyOnWriteArrayList",
    "T", "R", "V", "K", "E", "N", "ID", "STATE", "EVENT"
))

# 3. Scan all type annotations in parameters, properties, and generic type arguments
$issues = @()
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    # Remove strings
    $cleanCode = [regex]::Replace($clean, '"(?:\\.|[^"\\])*"', '')
    
    $lines = $cleanCode -split "`r?`n"
    for ($lineIdx = 0; $lineIdx -lt $lines.Length; $lineIdx++) {
        $line = $lines[$lineIdx]
        
        # Match type after colon: :\s*([A-Za-z0-9_.]+)
        $typeMatches = [regex]::Matches($line, ':\s*(?:@\w+\s+)*([A-Za-z0-9_.]+)(?:<[^>]+>)?')
        foreach ($tm in $typeMatches) {
            $rawType = $tm.Groups[1].Value
            # Get the simple name
            $simpleName = ($rawType -split '\.')[-1]
            # Strip trailing ? or =
            $simpleName = $simpleName.TrimEnd('?', ' ', '=')
            
            if ($simpleName -match '^[A-Z][A-Za-z0-9_]*$') {
                if (-not $projectTypes.Contains($simpleName) -and -not $standardTypes.Contains($simpleName)) {
                    $issues += "$($file.FullName):$($lineIdx+1): Unknown type reference '$simpleName' (from '$rawType')"
                }
            }
        }
        
        # Match types inside generics: <\s*([A-Za-z0-9_.]+)\s*>
        $genMatches = [regex]::Matches($line, '<([A-Za-z0-9_.,\s?]+)>')
        foreach ($gm in $genMatches) {
            $inner = $gm.Groups[1].Value
            $tokens = $inner -split ','
            foreach ($tok in $tokens) {
                $cleaned = $tok.Trim().TrimEnd('?').Trim()
                $simpleName = ($cleaned -split '\.')[-1]
                $simpleName = ($simpleName -split '<')[0].Trim()
                $simpleName = ($simpleName -split '\s+')[0].Trim()
                if ($simpleName -match '^[A-Z][A-Za-z0-9_]*$') {
                    if (-not $projectTypes.Contains($simpleName) -and -not $standardTypes.Contains($simpleName)) {
                        $issues += "$($file.FullName):$($lineIdx+1): Unknown generic type reference '$simpleName'"
                    }
                }
            }
        }
    }
}

$uniqueIssues = $issues | Select-Object -Unique
Write-Host "Total unknown type references: $($uniqueIssues.Count)"
foreach ($issue in ($uniqueIssues | Select-Object -First 50)) {
    Write-Host "  $issue" -ForegroundColor Yellow
}
