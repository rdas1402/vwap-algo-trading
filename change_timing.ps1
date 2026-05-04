# change_timing.ps1
# Run this to change the schedule times

param(
    [string]$MidnightTime = "00:20",
    [string]$MorningTime = "09:10"
)

$projectPath = "D:\aPPLICATION_i_DEVELOP\vwap-algo-trading"
$batchFile = "$projectPath\run_trading.bat"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   Changing Task Timings" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Parse times
$midnightHour = [int]($MidnightTime.Split(':')[0])
$midnightMinute = [int]($MidnightTime.Split(':')[1])
$morningHour = [int]($MorningTime.Split(':')[0])
$morningMinute = [int]($MorningTime.Split(':')[1])

Write-Host "New Schedule:" -ForegroundColor Yellow
Write-Host "  Midnight Token Refresh: $MidnightTime" -ForegroundColor White
Write-Host "  Morning Trading Start: $MorningTime" -ForegroundColor White
Write-Host ""

# Update Midnight Task
$taskNameMidnight = "Zerodha_Midnight_Token_Refresh"
$actionMidnight = New-ScheduledTaskAction -Execute $batchFile -Argument "midnight" -WorkingDirectory $projectPath
$triggerMidnight = New-ScheduledTaskTrigger -Daily -At $MidnightTime
$settingsMidnight = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -RunOnlyIfNetworkAvailable -MultipleInstances IgnoreNew -WakeToRun

try {
    Register-ScheduledTask -TaskName $taskNameMidnight `
        -Action $actionMidnight `
        -Trigger $triggerMidnight `
        -Settings $settingsMidnight `
        -Description "Zerodha Login Token Refresh" `
        -RunLevel Highest `
        -Force
    
    Write-Host "✅ Midnight task updated to $MidnightTime" -ForegroundColor Green
} catch {
    Write-Host "❌ Failed to update midnight task: $_" -ForegroundColor Red
}

# Update Morning Task
$taskNameMorning = "Zerodha_Morning_Trading_Start"
$actionMorning = New-ScheduledTaskAction -Execute $batchFile -Argument "morning" -WorkingDirectory $projectPath
$triggerMorning = New-ScheduledTaskTrigger -Daily -At $MorningTime
$settingsMorning = New-ScheduledTaskSettingsSet -AllowStartIfOnBatteries -DontStopIfGoingOnBatteries -StartWhenAvailable -RunOnlyIfNetworkAvailable -MultipleInstances IgnoreNew

try {
    Register-ScheduledTask -TaskName $taskNameMorning `
        -Action $actionMorning `
        -Trigger $triggerMorning `
        -Settings $settingsMorning `
        -Description "Zerodha Trading Application" `
        -RunLevel Highest `
        -Force
    
    Write-Host "✅ Morning task updated to $MorningTime" -ForegroundColor Green
} catch {
    Write-Host "❌ Failed to update morning task: $_" -ForegroundColor Red
}

Write-Host ""
Write-Host "Schedule updated successfully!" -ForegroundColor Green