@echo off
setlocal enabledelayedexpansion

title Zerodha Automated Trading System
color 0A

:: Get current date and time
for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value') do set datetime=%%I
set current_date=%datetime:~0,4%-%datetime:~4,2%-%datetime:~6,2%
set current_time=%datetime:~8,2%:%datetime:~10,2%:%datetime:~12,2%

echo ========================================
echo    ZERODHA AUTOMATED TRADING SYSTEM
echo ========================================
echo.
echo [%current_date% %current_time%] Starting...
echo.

:: Set Java 17
set JAVA_HOME=C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot
set PATH=%JAVA_HOME%\bin;%PATH%

:: Set classpath
set CP=target\classes;target\dependency\*

:: Check if environment variables are set
if "%ZERODHA_USER_ID%"=="" (
    echo [ERROR] ZERODHA_USER_ID not set!
    echo Please run: setx ZERODHA_USER_ID "7019211971" /M
    pause
    exit /b 1
)

:: Parse command line arguments
if "%1"=="token" goto token
if "%1"=="trading" goto trading
if "%1"=="test" goto test
if "%1"=="settime" goto settime

echo Usage:
echo   run_trading.bat token     - Refresh access token (runs immediately)
echo   run_trading.bat trading   - Start trading application
echo   run_trading.bat test      - Test login immediately
echo   run_trading.bat settime   - Configure schedule times
echo.
pause
exit /b

:token
echo [%date% %time%] Refreshing access token...
echo [%date% %time%] Starting token refresh... >> logs\token_%datetime:~0,8%.log

:: Run login helper (this only refreshes token, does NOT start trading)
java -cp "%CP%" com.trading.util.AutoZerodhaLoginHelper >> logs\token_%datetime:~0,8%.log 2>&1

if errorlevel 1 (
    echo [ERROR] Token refresh failed! Check logs\token_%datetime:~0,8%.log
    exit /b 1
) else (
    echo [SUCCESS] Token refreshed successfully at %date% %time%
    echo [SUCCESS] Token refreshed successfully >> logs\token_%datetime:~0,8%.log

    :: Wait 5 seconds for file to be written
    echo [INFO] Waiting 5 seconds for token to be saved...
    timeout /t 5 /nobreak >nul

    :: Verify token was saved
    echo [INFO] Verifying token in properties file...
    findstr "zerodha.access.token" src\main\resources\application.properties
)
exit /b 0

:trading
echo [%date% %time%] Starting trading application...

:: Check if token exists
if not exist src\main\resources\application.properties (
    echo [ERROR] application.properties not found!
    exit /b 1
)

:: Read the current token for logging
for /f "tokens=1,* delims==" %%a in ('findstr "zerodha.access.token" src\main\resources\application.properties') do (
    set TOKEN_VALUE=%%b
)
echo [INFO] Using token: !TOKEN_VALUE:~0,15!...

:: Start trading application ONLY (token should already be refreshed)
java -cp "%CP%" com.trading.TradingApplication >> logs\trading_%datetime:~0,8%.log 2>&1

echo [%date% %time%] Trading application stopped
exit /b 0

:test
echo [%date% %time%] Testing login immediately...
java -cp "%CP%" com.trading.util.AutoZerodhaLoginHelper test
pause
exit /b 0

:settime
echo Current schedule: Monday to Friday
echo   Token Refresh: 09:10 AM
echo   Trading Start: 09:15 AM
echo.
echo To change schedule, use Task Scheduler or run:
echo   powershell -ExecutionPolicy Bypass -File setup_scheduled_tasks.ps1 -TokenTime "HH:MM" -TradingTime "HH:MM"
pause
exit /b 0