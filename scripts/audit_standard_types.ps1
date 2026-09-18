# Audit script for common Java/Android/Kotlin standard library types
$files = Get-ChildItem -Path "app/src" -Filter "*.kt" -Recurse

$standardTypes = @{
    "ConcurrentHashMap" = "java.util.concurrent.ConcurrentHashMap"
    "AtomicBoolean" = "java.util.concurrent.atomic.AtomicBoolean"
    "AtomicInteger" = "java.util.concurrent.atomic.AtomicInteger"
    "AtomicLong" = "java.util.concurrent.atomic.AtomicLong"
    "AtomicReference" = "java.util.concurrent.atomic.AtomicReference"
    "CopyOnWriteArrayList" = "java.util.concurrent.CopyOnWriteArrayList"
    "UUID" = "java.util.UUID"
    "SimpleDateFormat" = "java.text.SimpleDateFormat"
    "Date" = "java.util.Date"
    "File" = "java.io.File"
    "InputStream" = "java.io.InputStream"
    "OutputStream" = "java.io.OutputStream"
    "FileInputStream" = "java.io.FileInputStream"
    "FileOutputStream" = "java.io.FileOutputStream"
    "ByteArrayInputStream" = "java.io.ByteArrayInputStream"
    "ByteArrayOutputStream" = "java.io.ByteArrayOutputStream"
    "BufferedReader" = "java.io.BufferedReader"
    "StandardCharsets" = "java.nio.charset.StandardCharsets"
    "JSONObject" = "org.json.JSONObject"
    "JSONArray" = "org.json.JSONArray"
    "Context" = "android.content.Context"
    "Intent" = "android.content.Intent"
    "Log" = "android.util.Log"
    "Base64" = "android.util.Base64|java.util.Base64"
    "MessageDigest" = "java.security.MessageDigest"
    "KeyStore" = "java.security.KeyStore"
}

$issues = @()

foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $clean = [regex]::Replace($content, '/\*.*?\*/', '', [System.Text.RegularExpressions.RegexOptions]::Singleline)
    $clean = [regex]::Replace($clean, '//[^\r\n]*', '')
    
    $imports = [regex]::Matches($clean, 'import\s+([a-zA-Z0-9_.*]+)') | ForEach-Object { $_.Groups[1].Value }
    
    foreach ($type in $standardTypes.Keys) {
        $expectedPackagePattern = $standardTypes[$type]
        
        # Check if the type name is used as an identifier: (?<![.\w])Type(?![.\w])
        if ($clean -match "(?<![.\w])$type(?![.\w])") {
            # Check if imported
            $hasImport = $false
            foreach ($expPkg in ($expectedPackagePattern -split '\|')) {
                $pkgOnly = $expPkg.Substring(0, $expPkg.LastIndexOf('.'))
                if ($imports -contains $expPkg -or $imports -contains "$pkgOnly.*") {
                    $hasImport = $true
                    break
                }
            }
            
            # Also check if declared in the file itself
            if (-not $hasImport) {
                if ($clean -match "(?:class|interface|object|enum\s+class)\s+$type\b") {
                    $hasImport = $true
                }
            }
            
            # If not imported, check each usage line to see if it was fully qualified in place e.g. java.util.UUID
            if (-not $hasImport) {
                $lines = $clean -split "`r?`n"
                for ($i = 0; $i -lt $lines.Length; $i++) {
                    $l = $lines[$i]
                    if ($l.Trim().StartsWith("package ") -or $l.Trim().StartsWith("import ")) {
                        continue
                    }
                    if ($l -match "(?<![.\w])$type(?![.\w])") {
                        # Make sure not preceded by a dot
                        if ($l -notmatch "\.$type\b") {
                            $issues += "$($file.FullName):$($i+1): Type '$type' referenced without import (expected $expectedPackagePattern)"
                        }
                    }
                }
            }
        }
    }
}

if ($issues.Count -eq 0) {
    Write-Host "SUCCESS: All standard types are properly imported or qualified!" -ForegroundColor Green
} else {
    Write-Host "FAILED: Found $($issues.Count) missing standard type imports:" -ForegroundColor Red
    foreach ($issue in $issues) {
        Write-Host "  $issue" -ForegroundColor Yellow
    }
}
