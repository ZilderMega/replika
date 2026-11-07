param([Parameter(Mandatory)][string]$Serial, [string]$StopLog='.private-validation/long-phone-run.log', [string]$Output='.private-validation/device-samples.csv')
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
$clock=[Diagnostics.Stopwatch]::StartNew()
while($clock.Elapsed.TotalHours -lt 2) {
    if((Test-Path -LiteralPath $StopLog) -and (Select-String -LiteralPath $StopLog -Pattern '^INSTRUMENTATION_CODE:' -Quiet)){break}
    $battery=(& $adb -s $Serial shell dumpsys battery) -join "`n"
    $memory=(& $adb -s $Serial shell dumpsys meminfo team.replika) -join "`n"
    $temperature=[regex]::Match($battery,'(?m)^\s+temperature:\s+(\d+)\s*$')
    $level=[regex]::Match($battery,'(?m)^\s+level:\s+(\d+)\s*$')
    $pss=[regex]::Match($memory,'TOTAL PSS:\s+(\d+)')
    $copyActive=@(Get-CimInstance Win32_Process -Filter "Name='adb.exe'" | Where-Object {$_.CommandLine -like '*push artifacts/replika-0.6-bundled-debug.apk*'}).Count -gt 0
    $row=[pscustomobject]@{utc=[DateTime]::UtcNow.ToString('o'); sampled_seconds=[math]::Round($clock.Elapsed.TotalSeconds,1);
        battery_c=if($temperature.Success){[int]$temperature.Groups[1].Value/10}else{$null};
        battery_percent=if($level.Success){[int]$level.Groups[1].Value}else{$null};
        process_pss_kib=if($pss.Success){[int]$pss.Groups[1].Value}else{$null}; adb_apk_transfer=$copyActive}
    $row | Export-Csv -LiteralPath $Output -Append -NoTypeInformation -Encoding utf8NoBOM
    Write-Output "Sample: battery $($row.battery_c) C, app PSS $($row.process_pss_kib) KiB, ADB file transfer: $copyActive"
    Start-Sleep -Seconds 15
}
Write-Output 'Validation monitoring stopped.'
