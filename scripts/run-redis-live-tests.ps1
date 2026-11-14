<#
.SYNOPSIS
  Runs the authenticated Redis (Upstash) live tests for all three HomeEase services and writes a sanitized
  PASS/FAIL/SKIPPED report, without ever echoing, logging or persisting the Redis URL or password.

.DESCRIPTION
  * The URL is read with Read-Host -AsSecureString (nothing is shown while typing) unless REDIS_URL is already set in
    this shell, held only in this process's REDIS_URL environment variable, and restored/removed in a finally block.
  * It must be a rediss:// (TLS) URL. A plain redis:// URL is refused.
  * Only isolated keys are used (test:connectivity:<uuid>:, test:compat:<uuid>:). Nothing is flushed.
  * Tests that touch the SHARED keys (active_workers_geo, worker:heartbeat:*) run ONLY with -AllowSharedKeys. They add
    one uniquely named worker far from any real location and remove only that worker.
  * Stale results are deleted before each run, so a report can never show output from an earlier run.
  * The report (reports\redis-live-test-report-<timestamp>.md) is built from the surefire XML. Every string written to
    it is scrubbed: the full URL, the password, and (unless -IncludeHost) the host name. After writing, the script
    scans the file for any leak and deletes it if one is found.
  * Skipped tests are reported as SKIPPED, never as passed. Exit code: 0 = all passed, 1 = incomplete (skips),
    2 = failures.

.EXAMPLE
  .\scripts\run-redis-live-tests.ps1
  .\scripts\run-redis-live-tests.ps1 -AllowSharedKeys
#>
[CmdletBinding()]
param(
    [switch]$AllowSharedKeys,
    [switch]$IncludeHost,
    [string]$MavenBin = 'C:\Users\Admin\maven\apache-maven-3.9.6\bin'
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
$bstr = [IntPtr]::Zero
$previousUrl = $env:REDIS_URL
$previousShared = $env:REDIS_LIVE_ALLOW_SHARED_KEYS
$script:secrets = @()   # strings that must never appear in the report

function Mask([string]$u) { $u -replace '://[^@/]*@', '://***@' }

function Scrub([string]$text) {
    if ([string]::IsNullOrEmpty($text)) { return $text }
    $t = $text
    foreach ($s in $script:secrets) { if ($s.Length -ge 3) { $t = $t.Replace($s, '***') } }
    $t = [regex]::Replace($t, 'rediss?://[^\s"''<>]+', 'rediss://***')
    return $t
}

function Get-TestResults([string]$reportsDir) {
    $rows = @()
    Get-ChildItem $reportsDir -Filter 'TEST-*LiveTest*.xml' -ErrorAction SilentlyContinue | ForEach-Object {
        [xml]$x = Get-Content $_.FullName -Raw
        $suite = $x.testsuite
        foreach ($tc in @($suite.testcase)) {
            if ($null -eq $tc) { continue }
            $status = 'PASSED'; $detail = ''
            if ($tc.skipped -ne $null) { $status = 'SKIPPED'; $detail = [string]$tc.skipped.message }
            elseif ($tc.failure -ne $null) { $status = 'FAILED'; $detail = [string]$tc.failure.message }
            elseif ($tc.error -ne $null) { $status = 'FAILED'; $detail = [string]$tc.error.message }
            $rows += [pscustomobject]@{
                Class = ($tc.classname -split '\.')[-1]; Test = $tc.name; Status = $status
                Seconds = [string]$tc.time; Detail = (Scrub $detail)
            }
        }
        $out = [string]$suite.'system-out'
        if ($out) {
            $lines = $out -split "`n" | Where-Object { $_ -match '^\[(redis-live|compat|dispatch-live)\]' } | ForEach-Object { Scrub $_.Trim() }
            if ($lines) { $script:evidence += [pscustomobject]@{ Class = ($suite.name -split '\.')[-1]; Lines = $lines } }
        }
    }
    return $rows
}

$script:evidence = @()
try {
    if ([string]::IsNullOrWhiteSpace($env:REDIS_URL)) {
        $secure = Read-Host -AsSecureString 'Paste REDIS_URL (rediss://default:<password>@host:6379) - input is hidden'
        $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        $env:REDIS_URL = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr)
    }
    if ($env:REDIS_URL -notmatch '^rediss://') {
        throw "REDIS_URL must start with rediss:// (TLS). Refusing to continue."
    }
    # Remember what must never be written anywhere
    $script:secrets += $env:REDIS_URL
    if ($env:REDIS_URL -match '^rediss://[^:]*:([^@]*)@([^:/]+)') {
        try { $script:secrets += [uri]::UnescapeDataString($Matches[1]) } catch { }
        $script:secrets += $Matches[1]
        if (-not $IncludeHost) { $script:secrets += $Matches[2] }
    }
    Write-Host ("Using " + (Mask $env:REDIS_URL))
    if ($AllowSharedKeys) {
        $env:REDIS_LIVE_ALLOW_SHARED_KEYS = 'true'
        Write-Host 'Shared-key tests ENABLED (unique member, removed afterwards).'
    } else {
        Remove-Item Env:REDIS_LIVE_ALLOW_SHARED_KEYS -ErrorAction SilentlyContinue
        Write-Host 'Shared-key tests disabled (they will be reported as SKIPPED).'
    }
    $env:PATH = "$MavenBin;$env:PATH"

    $runs = @(
        @{ Name = 'core';     Dir = $repo;                                         Filter = 'Redis*LiveTest' },
        @{ Name = 'tracking'; Dir = Join-Path $repo 'homeease-tracking-service';   Filter = 'TrackingRedisLiveTest' },
        @{ Name = 'dispatch'; Dir = Join-Path $repo 'homeease-dispatch-service';   Filter = 'DispatchRedisLiveTest' }
    )
    $all = @()
    foreach ($r in $runs) {
        Write-Host "`n=== $($r.Name) ===" -ForegroundColor Cyan
        $reports = Join-Path $r.Dir 'target\surefire-reports'
        Get-ChildItem $reports -Filter '*LiveTest*' -ErrorAction SilentlyContinue | Remove-Item -Force   # no stale results
        Push-Location $r.Dir
        try {
            & mvn -B -q test "-Dtest=$($r.Filter)" "-Dsurefire.failIfNoSpecifiedTests=false" | Out-Null
        } finally { Pop-Location }
        $rows = Get-TestResults $reports
        if (-not $rows) { Write-Host '  (no results were produced: the run did not execute any live test)' -ForegroundColor Yellow }
        foreach ($row in $rows) {
            '{0,-34} {1,-62} {2,-8}' -f $row.Class, $row.Test, $row.Status
        }
        $all += $rows | ForEach-Object { $_ | Add-Member -NotePropertyName Module -NotePropertyValue $r.Name -PassThru }
    }

    $passed  = ($all | Where-Object Status -eq 'PASSED'  | Measure-Object).Count
    $failed  = ($all | Where-Object Status -eq 'FAILED'  | Measure-Object).Count
    $skipped = ($all | Where-Object Status -eq 'SKIPPED' | Measure-Object).Count
    $total   = ($all | Measure-Object).Count
    $verdict = if ($total -eq 0) { 'NO RESULTS' } elseif ($failed -gt 0) { 'FAILED' } elseif ($skipped -gt 0) { 'INCOMPLETE (skipped tests are not passes)' } else { 'ALL LIVE TESTS PASSED' }

    # ------------------------------------------------------------------ sanitized report
    $reportDir = Join-Path $repo 'reports'
    New-Item -ItemType Directory -Force -Path $reportDir | Out-Null
    $stamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $reportPath = Join-Path $reportDir "redis-live-test-report-$stamp.md"
    $md = New-Object System.Collections.Generic.List[string]
    $md.Add("# Redis live test report ($stamp)")
    $md.Add('')
    $md.Add("**Verdict: $verdict** | passed $passed | failed $failed | skipped $skipped | total $total")
    $md.Add('')
    $md.Add("Endpoint: scheme rediss (TLS); host $(if ($IncludeHost) { 'included' } else { 'redacted' }); credentials never recorded.")
    $md.Add("Shared-key tests: $(if ($AllowSharedKeys) { 'enabled (unique member, removed afterwards)' } else { 'disabled (reported as SKIPPED)' }).")
    $md.Add('')
    $md.Add('| Module | Class | Test | Status | Seconds | Note |')
    $md.Add('|---|---|---|---|---|---|')
    foreach ($row in $all) {
        $note = ($row.Detail -replace '\s+', ' '); if ($note.Length -gt 160) { $note = $note.Substring(0, 160) + '...' }
        $md.Add("| $($row.Module) | $($row.Class) | $($row.Test) | **$($row.Status)** | $($row.Seconds) | $($note -replace '\|','/') |")
    }
    if ($script:evidence) {
        $md.Add('')
        $md.Add('## Evidence lines printed by the tests (scrubbed)')
        foreach ($e in $script:evidence) {
            $md.Add(''); $md.Add("**$($e.Class)**"); $md.Add('```')
            foreach ($l in $e.Lines) { $md.Add($l) }
            $md.Add('```')
        }
    }
    Set-Content -Path $reportPath -Value ($md -join "`n") -Encoding utf8

    # Leak self-check: the finished file must contain none of the secrets and no redis URL with credentials.
    $written = Get-Content $reportPath -Raw
    $leak = ($script:secrets | Where-Object { $_.Length -ge 3 -and $written.Contains($_) }) -or ($written -match 'rediss?://[^*\s][^\s]*@')
    if ($leak) {
        Remove-Item $reportPath -Force
        throw 'Report deleted: the leak self-check found sensitive text in it. Nothing was kept.'
    }
    Write-Host "`nSanitized report written: $reportPath"

    Write-Host ''
    if ($failed -gt 0)       { Write-Host "RESULT: FAILED ($failed failed, $skipped skipped, $passed passed)" -ForegroundColor Red; exit 2 }
    elseif ($total -eq 0)    { Write-Host 'RESULT: NO RESULTS - nothing ran' -ForegroundColor Red; exit 2 }
    elseif ($skipped -gt 0)  { Write-Host "RESULT: INCOMPLETE - $skipped test(s) SKIPPED (not passes), $passed passed. Use -AllowSharedKeys for the shared-key tests." -ForegroundColor Yellow; exit 1 }
    else                     { Write-Host "RESULT: ALL $passed LIVE TESTS PASSED" -ForegroundColor Green; exit 0 }
}
finally {
    if ($bstr -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    if ($null -ne $previousUrl) { $env:REDIS_URL = $previousUrl } else { Remove-Item Env:REDIS_URL -ErrorAction SilentlyContinue }
    if ($null -ne $previousShared) { $env:REDIS_LIVE_ALLOW_SHARED_KEYS = $previousShared } else { Remove-Item Env:REDIS_LIVE_ALLOW_SHARED_KEYS -ErrorAction SilentlyContinue }
    $script:secrets = @()
}
