# Audit script for singleton and companion object calls across all Kotlin files
$files = Get-ChildItem -Path "app/src" -Filter "*.kt" -Recurse

$objects = @(
    "MemoryManager",
    "UnifiedExecutionFabric",
    "LargeDatasetEngine",
    "WastiDeviceController",
    "WastiEmbeddingRuntime",
    "UnifiedBrain",
    "CapabilityRealityRegistry",
    "WastiOSRuntime",
    "WastiNodeManager",
    "WastiAppActionBus",
    "ExecutionMemoryRecorder",
    "DynamicWreToolSynthesizer",
    "AutonomousCapabilityOrchestrator",
    "AutonomousHardwareOffloader",
    "UnifiedWorkflowEngine",
    "CostIntelligenceEngine",
    "CapabilityEvolutionPipeline",
    "CapabilityCivilizationRegistry",
    "WastiSecurityPolicyEngine",
    "RealityAuditEngine",
    "HardwareCapabilityDetector",
    "ModelArtifactManager",
    "WastiModelDownloader",
    "WastiMeshTransportEngine",
    "WastiProactiveAutonomousEngine",
    "ProductionReadinessGate"
)

# Extract declared functions/properties for each object
$objectMembers = @{}

foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    foreach ($obj in $objects) {
        if ($content -match "(?:object\s+$obj\b|class\s+$obj\b[^{]*\{(?s:.*?companion\s+object\b))") {
            if (-not $objectMembers.ContainsKey($obj)) {
                $objectMembers[$obj] = [System.Collections.Generic.HashSet[string]]::new()
            }
            # Collect fun, val, var names
            $memberMatches = [regex]::Matches($content, '(?:fun|val|var)\s+([A-Za-z0-9_]+)\b')
            foreach ($mm in $memberMatches) {
                [void]$objectMembers[$obj].Add($mm.Groups[1].Value)
            }
        }
    }
}

Write-Host "Extracted members for $($objectMembers.Keys.Count) target singletons/classes."

# Scan all files for Object.member calls
$issues = @()
foreach ($file in $files) {
    $content = [System.IO.File]::ReadAllText($file.FullName)
    $lines = $content -split "`r?`n"
    for ($i = 0; $i -lt $lines.Length; $i++) {
        $line = $lines[$i]
        $trimmed = $line.Trim()
        if ($trimmed.StartsWith("//") -or $trimmed.StartsWith("/*") -or $trimmed.StartsWith("*") -or $trimmed.StartsWith("import ") -or $trimmed.StartsWith("package ")) {
            continue
        }
        
        foreach ($obj in $objects) {
            if ($objectMembers.ContainsKey($obj)) {
                $regex = [regex]("\b" + $obj + "(?:\.Companion|\.instance)?\.([A-Za-z0-9_]+)\b")
                $matches = $regex.Matches($line)
                foreach ($m in $matches) {
                    $call = $m.Groups[1].Value
                    # Skip common standard Kotlin/Java methods and class reference
                    if ($call -in @("class", "java", "toString", "hashCode", "equals", "Companion", "instance", "getInstance")) {
                        continue
                    }
                    if (-not $objectMembers[$obj].Contains($call)) {
                        $issues += "Line $($i+1) in $($file.FullName): Unknown call '$call' on '$obj'"
                    }
                }
            }
        }
    }
}

if ($issues.Count -eq 0) {
    Write-Host "SUCCESS: All object/singleton calls are valid!" -ForegroundColor Green
} else {
    Write-Host "FAILED: Found $($issues.Count) invalid calls:" -ForegroundColor Red
    foreach ($issue in $issues) {
        Write-Host "  $issue" -ForegroundColor Yellow
    }
}
