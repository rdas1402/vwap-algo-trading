# setup_scheduled_tasks.ps1
# Run this script as Administrator to set up weekday-only tasks

param(
    [string]$TokenTime = "09:10",
    [string]$TradingTime = "09:15"
)

$projectPath = "D:\aPPLICATION_i_DEVELOP\vwap-algo-trading"
$batchFile = "$projectPath\run_trading.bat"

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   Setting up Automated Trading Tasks" -ForegroundColor Cyan
Write-Host "   (Monday to Friday Only)" -ForegroundColor Yellow
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Schedule Configuration:" -ForegroundColor Yellow
Write-Host "  Token Refresh Time: $TokenTime (Weekdays)" -ForegroundColor White
Write-Host "  Trading Start Time: $TradingTime (Weekdays)" -ForegroundColor White
Write-Host ""
Write-Host "IMPORTANT: Trading start time should be at least 2 minutes after token refresh" -ForegroundColor Red
Write-Host ""

# Confirm with user
$confirm = Read-Host "Do you want to create these tasks? (y/n)"
if ($confirm -ne 'y') {
    Write-Host "Cancelled." -ForegroundColor Red
    exit 0
}

# Check if batch file exists
if (!(Test-Path $batchFile)) {
    Write-Host "ERROR: run_trading.bat not found at $batchFile" -ForegroundColor Red
    exit 1
}

# Remove existing tasks if they exist
$taskNames = @("Zerodha_Token_Refresh", "Zerodha_Trading_Start")
foreach ($taskName in $taskNames) {
    try {
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false -ErrorAction SilentlyContinue
        Write-Host "Removed existing task: $taskName" -ForegroundColor Yellow
    } catch {
        # Task doesn't exist, ignore
    }
}

# Create weekday trigger for Monday to Friday
$weekdayDaysOfWeek = "Monday", "Tuesday", "Wednesday", "Thursday", "Friday"

# Task 1: Token Refresh (Monday to Friday only)
$taskNameToken = "Zerodha_Token_Refresh"
$actionToken = New-ScheduledTaskAction -Execute $batchFile -Argument "token" -WorkingDirectory $projectPath

# Create a trigger for each weekday
$triggersToken = @()
foreach ($day in $weekdayDaysOfWeek) {
    $trigger = New-ScheduledTaskTrigger -Weekly -DaysOfWeek $day -At $TokenTime
    $triggersToken += $trigger
}

$settingsToken = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -RunOnlyIfNetworkAvailable `
    -MultipleInstances IgnoreNew `
    -WakeToRun

# Register token refresh task
try {
    Register-ScheduledTask -TaskName $taskNameToken `
        -Action $actionToken `
        -Trigger $triggersToken `
        -Settings $settingsToken `
        -Description "Zerodha Token Refresh (Runs at $TokenTime on Mon-Fri)" `
        -RunLevel Highest `
        -Force

    Write-Host "Task '$taskNameToken' created for $TokenTime (Mon-Fri)" -ForegroundColor Green
} catch {
    Write-Host "Failed to create token refresh task: $_" -ForegroundColor Red
}

# Task 2: Trading Start (Monday to Friday only)
$taskNameTrading = "Zerodha_Trading_Start"
$actionTrading = New-ScheduledTaskAction -Execute $batchFile -Argument "trading" -WorkingDirectory $projectPath

# Create a trigger for each weekday
$triggersTrading = @()
foreach ($day in $weekdayDaysOfWeek) {
    $trigger = New-ScheduledTaskTrigger -Weekly -DaysOfWeek $day -At $TradingTime
    $triggersTrading += $trigger
}

$settingsTrading = New-ScheduledTaskSettingsSet `
    -AllowStartIfOnBatteries `
    -DontStopIfGoingOnBatteries `
    -StartWhenAvailable `
    -RunOnlyIfNetworkAvailable `
    -MultipleInstances IgnoreNew

# Register trading start task
try {
    Register-ScheduledTask -TaskName $taskNameTrading `
        -Action $actionTrading `
        -Trigger $triggersTrading `
        -Settings $settingsTrading `
        -Description "Zerodha Trading (Starts at $TradingTime on Mon-Fri)" `
        -RunLevel Highest `
        -Force

    Write-Host "Task '$taskNameTrading' created for $TradingTime (Mon-Fri)" -ForegroundColor Green
} catch {
    Write-Host "Failed to create trading start task: $_" -ForegroundColor Red
}

Write-Host ""
Write-Host "========================================" -ForegroundColor Cyan
Write-Host "   Tasks Created Successfully!" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "Schedule (Monday to Friday only):" -ForegroundColor Yellow
Write-Host "  Token Refresh: $TokenTime" -ForegroundColor White
Write-Host "  Trading Start: $TradingTime" -ForegroundColor White
Write-Host ""
Write-Host "IMPORTANT: Ensure Trading Start time is at least 2 minutes after Token Refresh" -ForegroundColor Red
Write-Host ""
Write-Host "Weekends: No tasks will run" -ForegroundColor Cyan
Write-Host ""
Write-Host "To test tasks immediately:" -ForegroundColor Yellow
Write-Host "  Start-ScheduledTask -TaskName 'Zerodha_Token_Refresh'" -ForegroundColor White
Write-Host "  Start-ScheduledTask -TaskName 'Zerodha_Trading_Start'" -ForegroundColor White
Write-Host ""
Write-Host "To view scheduled tasks:" -ForegroundColor Yellow
Write-Host "  Get-ScheduledTask | Where-Object {`$_.TaskName -like 'Zerodha_*'}" -ForegroundColor White
Write-Host ""
Write-Host "To remove tasks:" -ForegroundColor Red
Write-Host "  Unregister-ScheduledTask -TaskName 'Zerodha_Token_Refresh' -Confirm:`$false" -ForegroundColor White
Write-Host "  Unregister-ScheduledTask -TaskName 'Zerodha_Trading_Start' -Confirm:`$false" -ForegroundColor White