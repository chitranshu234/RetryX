# ============================================================================
#  Retryx — Windows PowerShell One-Time Setup
# ============================================================================
#  Installs dashboard dependencies and compiles both Java apps, checking the
#  prerequisites first.
#
#      .\scripts\setup.ps1
# ============================================================================

$ErrorActionPreference = "Stop"
$ROOT = Split-Path -Parent $PSScriptRoot

function Write-Bold ($msg) { Write-Host "`n$msg" -ForegroundColor Cyan }
function Write-Ok ($msg)   { Write-Host "  [OK] $msg" -ForegroundColor Green }
function Write-Warn ($msg) { Write-Host "  [!] $msg" -ForegroundColor Yellow }
function Write-Fail ($msg) { Write-Host "  [X] $msg" -ForegroundColor Red }

$missing = $false

Write-Bold "Checking prerequisites..."

# 1. Node.js
if (Get-Command node -ErrorAction SilentlyContinue) {
    $nodeVer = (node -v).TrimStart("v")
    $nodeMajor = [int]($nodeVer.Split(".")[0])
    if ($nodeMajor -ge 20) {
        Write-Ok "node $(node -v)"
    } else {
        Write-Fail "node $(node -v) -- Vite requires Node 20 or newer"
        $missing = $true
    }
} else {
    Write-Fail "node is not installed -- https://nodejs.org (20 LTS or newer)"
    $missing = $true
}

# 2. npm
if (Get-Command npm -ErrorAction SilentlyContinue) {
    Write-Ok "npm $(npm -v)"
} else {
    Write-Fail "npm is not installed"
    $missing = $true
}

# 3. Java
if (Get-Command java -ErrorAction SilentlyContinue) {
    $oldEap = $ErrorActionPreference
    $ErrorActionPreference = "SilentlyContinue"
    $javaOutput = & java -version 2>&1
    $ErrorActionPreference = $oldEap
    $javaVerStr = ($javaOutput | Out-String).Split("`n")[0]
    if ($javaVerStr -match '"(\d+)') {
        $javaMajor = [int]$matches[1]
        if ($javaMajor -ge 21) {
            Write-Ok "java version $javaMajor"
        } else {
            Write-Fail "java version $javaMajor -- this project targets Java 21 or newer"
            $missing = $true
        }
    } else {
        Write-Ok "java is installed ($javaVerStr)"
    }
} else {
    Write-Fail "java is not installed -- https://adoptium.net (Java 21+)"
    $missing = $true
}

# 4. Maven
if (Get-Command mvn -ErrorAction SilentlyContinue) {
    $mvnVer = (mvn -v 2>&1 | Select-Object -First 1)
    Write-Ok "maven ($mvnVer)"
} else {
    Write-Fail "maven is not installed on PATH"
    $missing = $true
}

# 5. Docker / PostgreSQL check
if (Get-Command docker -ErrorAction SilentlyContinue) {
    $dockerInfo = docker info 2>&1
    if ($LASTEXITCODE -eq 0) {
        Write-Ok "docker is running"
    } else {
        Write-Warn "docker is installed but not running"
    }
} else {
    # Check if local postgres port 5432 is open
    $pgCheck = Test-NetConnection -ComputerName localhost -Port 5432 -InformationLevel Quiet -WarningAction SilentlyContinue
    if ($pgCheck) {
        Write-Ok "local PostgreSQL is listening on port 5432"
    } else {
        Write-Warn "Neither Docker nor local PostgreSQL (port 5432) detected. PostgreSQL is required to run the backend."
    }
}

if ($missing) {
    Write-Fail "Please install the missing tools above, then rerun setup."
    exit 1
}

Write-Bold "Installing frontend dependencies..."
Push-Location "$ROOT\frontend"
try {
    npm install
    Write-Ok "frontend node_modules ready"
    Write-Bold "Type-checking the frontend..."
    npm run typecheck
    Write-Ok "Frontend type-check passed"
} finally {
    Pop-Location
}

Write-Bold "Compiling backend..."
Push-Location "$ROOT\backend"
try {
    mvn -B -q clean package -DskipTests
    Write-Ok "backend package compiled successfully"
} finally {
    Pop-Location
}

Write-Bold "Compiling receiver..."
Push-Location "$ROOT\receiver"
try {
    mvn -B -q clean package -DskipTests
    Write-Ok "receiver package compiled successfully"
} finally {
    Pop-Location
}

Write-Bold "Setup complete!"
Write-Host @"

To run the components on Windows:

  1. Database:
     Make sure PostgreSQL is running on port 5432 with database 'retryx' (user: retryx, pass: retryx),
     or run: docker compose up -d postgres (if Docker is installed)

  2. Receiver (in a terminal):
     cd receiver; mvn spring-boot:run

  3. Backend (in another terminal):
     cd backend; mvn spring-boot:run

  4. Frontend (in another terminal):
     cd frontend; npm run dev

Then open http://localhost:5173 and click 'Use the demo key'.
"@ -ForegroundColor Yellow
