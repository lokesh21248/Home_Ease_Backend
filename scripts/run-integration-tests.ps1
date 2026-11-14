<#
.SYNOPSIS
  Runs BookingKafkaIntegrationTest (real Spring context, Flyway migrations, JPA, embedded Kafka, in-process Redis)
  against a DISPOSABLE LOCAL PostgreSQL + PostGIS database that you provide.

.DESCRIPTION
  Prerequisite you must create yourself (nothing here installs or modifies PostgreSQL):
    1. A local PostgreSQL server with the PostGIS extension available (the installed PostgreSQL 18 on this machine
       does NOT ship PostGIS; install it with EDB Stack Builder, or use any local PostGIS build).
    2. An EMPTY database whose name contains "test", "disposable" or "_it", e.g. homeease_it_test.
       The test inserts and deletes users/services/bookings and Flyway creates the whole schema in it.

  Safety: the URL is refused unless it is jdbc:postgresql:// on localhost/127.0.0.1/::1 and the database name looks
  disposable (same rules as DisposableDatabaseGuard, which the test enforces again). Supabase or any remote host is
  refused before anything runs. The password is read with hidden input and only lives in this process's environment.

.EXAMPLE
  .\scripts\run-integration-tests.ps1 -JdbcUrl jdbc:postgresql://localhost:5432/homeease_it_test -Username postgres
#>
[CmdletBinding()]
param(
    [string]$JdbcUrl,
    [string]$Username = 'postgres',
    [string]$MavenBin = 'C:\Users\Admin\maven\apache-maven-3.9.6\bin'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$bstr = [IntPtr]::Zero
$saved = @{}
foreach ($n in 'TEST_POSTGIS_JDBC_URL', 'TEST_POSTGIS_USER', 'TEST_POSTGIS_PASSWORD') { $saved[$n] = [Environment]::GetEnvironmentVariable($n, 'Process') }

function Assert-DisposableDatabase([string]$url) {
    if ($url -notmatch '^jdbc:postgresql://') { throw 'Refused: the URL must start with jdbc:postgresql://' }
    $rest = $url.Substring('jdbc:postgresql://'.Length)
    $hostPort, $path = $rest -split '/', 2
    $hostName = ($hostPort -replace '^\[', '[' -replace ':\d+$', '').ToLower()
    $db = (($path -split '\?')[0]).ToLower()
    if ($hostName -match 'supabase|pooler') { throw 'Refused: the host looks like a managed/production database.' }
    if ($hostName -notin @('localhost', '127.0.0.1', '[::1]')) { throw 'Refused: the host must be localhost, 127.0.0.1 or ::1 (a disposable LOCAL database).' }
    if ([string]::IsNullOrEmpty($db) -or $db -eq 'postgres' -or $db -notmatch 'test|disposable|_it|-it') {
        throw "Refused: the database name must contain 'test', 'disposable' or '_it' and must not be 'postgres'."
    }
}

try {
    if ([string]::IsNullOrWhiteSpace($JdbcUrl)) { $JdbcUrl = Read-Host 'JDBC URL of the disposable local database (e.g. jdbc:postgresql://localhost:5432/homeease_it_test)' }
    Assert-DisposableDatabase $JdbcUrl
    $secure = Read-Host -AsSecureString 'Password for that database user (hidden; leave empty if none)'
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    $env:TEST_POSTGIS_JDBC_URL = $JdbcUrl
    $env:TEST_POSTGIS_USER = $Username
    $env:TEST_POSTGIS_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    $env:PATH = "$MavenBin;$env:PATH"
    Write-Host "Database: $JdbcUrl (user $Username). Redis: in-process. Kafka: embedded."

    $reports = Join-Path $repo 'target\surefire-reports'
    Get-ChildItem $reports -Filter '*BookingKafkaIntegrationTest*' -ErrorAction SilentlyContinue | Remove-Item -Force
    Push-Location $repo
    try { & mvn -B -q test '-Dtest=BookingKafkaIntegrationTest' | Out-Null } finally { Pop-Location }

    $xml = Get-ChildItem $reports -Filter 'TEST-*BookingKafkaIntegrationTest.xml' -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $xml) { Write-Host 'RESULT: NO RESULTS - the test did not run' -ForegroundColor Red; exit 2 }
    [xml]$x = Get-Content $xml.FullName -Raw
    $tests = [int]$x.testsuite.tests; $fail = [int]$x.testsuite.failures + [int]$x.testsuite.errors; $skip = [int]$x.testsuite.skipped
    if ($fail -gt 0)     { Write-Host "RESULT: FAILED ($fail of $tests). See target\surefire-reports\*BookingKafkaIntegrationTest.txt" -ForegroundColor Red; exit 2 }
    elseif ($skip -gt 0) { Write-Host "RESULT: SKIPPED ($skip of $tests): not a pass" -ForegroundColor Yellow; exit 1 }
    else                 { Write-Host "RESULT: PASSED ($tests test)" -ForegroundColor Green; exit 0 }
}
finally {
    if ($bstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    foreach ($n in $saved.Keys) { [Environment]::SetEnvironmentVariable($n, $saved[$n], 'Process') }
}
