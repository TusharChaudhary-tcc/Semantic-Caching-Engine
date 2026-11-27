# One-command local backend launcher (loads .env and auto-falls back to In-Memory vector store if Qdrant is not running)
$ErrorActionPreference = "Stop"

$envFile = Join-Path $PSScriptRoot ".env"
if (Test-Path $envFile) {
    Write-Host "Loading environment variables from .env..." -ForegroundColor Cyan
    Get-Content $envFile | ForEach-Object {
        $line = $_.Trim()
        if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
            $parts = $line.Split("=", 2)
            $key = $parts[0].Trim()
            $val = $parts[1].Trim()
            [Environment]::SetEnvironmentVariable($key, $val, "Process")
        }
    }
} else {
    Write-Host "Warning: .env file not found at $envFile" -ForegroundColor Yellow
}

# Check if Qdrant gRPC (port 6334) is reachable
$qdrantUp = $false
try {
    $tcp = New-Object System.Net.Sockets.TcpClient
    $async = $tcp.BeginConnect("localhost", 6334, $null, $null)
    if ($async.AsyncWaitHandle.WaitOne(500, $false)) {
        $tcp.EndConnect($async)
        $qdrantUp = $true
    }
    $tcp.Close()
} catch {
    $qdrantUp = $false
}

if ($qdrantUp) {
    Write-Host "Qdrant detected on localhost:6334 -> Using Qdrant Vector Store" -ForegroundColor Green
    $env:CACHE_VECTOR_STORE = "qdrant"
} else {
    Write-Host "Qdrant not detected on localhost:6334 (no Docker needed) -> Using In-Memory Vector Store" -ForegroundColor Yellow
    $env:CACHE_VECTOR_STORE = "memory"
}

Write-Host "Starting Spring Boot backend on http://localhost:8080 ..." -ForegroundColor Cyan
Set-Location (Join-Path $PSScriptRoot "backend")
.\mvnw.cmd spring-boot:run
