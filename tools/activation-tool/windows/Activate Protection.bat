@echo off
setlocal enabledelayedexpansion
title NexLock - Device Protection Activation
cd /d "%~dp0"

set ADB=platform-tools\adb.exe
set ADMIN_COMPONENT=com.nexlock.agent/.service.NexLockDeviceAdminReceiver
set PACKAGE=com.nexlock.agent

cls
echo.
echo ==========================================
echo    NexLock - Device Protection Activation
echo ==========================================
echo.

REM --- Step 1: wait for the phone to show up and be trusted ---
echo Step 1 of 3: Looking for your phone...
echo (Make sure it's plugged in with a USB cable.)
echo.

set FOUND=
for /L %%i in (1,1,30) do (
    for /f "delims=" %%s in ('"%ADB%" get-state 2^>nul') do set STATE=%%s
    if "!STATE!"=="device" (
        set FOUND=yes
        goto :found
    )
    if "!STATE!"=="unauthorized" (
        echo A message appeared on the phone's screen asking to trust this computer.
        echo Please tap 'Allow' on the phone now, then wait...
    )
    set STATE=
    timeout /t 1 /nobreak >nul
)

:found
if not defined FOUND (
    echo.
    echo ----------------------------------------------------
    echo COULD NOT FIND THE PHONE.
    echo.
    echo Please check:
    echo   1. The USB cable is properly plugged in on both ends
    echo   2. On the phone, go to Settings and make sure
    echo      'USB debugging' is turned ON under Developer Options
    echo   3. If a popup appeared on the phone, tap 'Allow'
    echo ----------------------------------------------------
    echo.
    pause
    exit /b 1
)

echo Phone found!
echo.

REM --- Step 2: confirm the NexLock app is installed ---
echo Step 2 of 3: Checking the NexLock app is installed...
"%ADB%" shell pm list packages > "%TEMP%\nexlock_packages.txt" 2>nul
findstr /C:"%PACKAGE%" "%TEMP%\nexlock_packages.txt" >nul
if errorlevel 1 (
    echo.
    echo ----------------------------------------------------
    echo THE NEXLOCK APP ISN'T INSTALLED ON THIS PHONE YET.
    echo.
    echo Please install the NexLock app on the phone first
    echo (open the phone's browser and download it from the
    echo link your admin gave you^), then run this tool again.
    echo ----------------------------------------------------
    echo.
    del "%TEMP%\nexlock_packages.txt" >nul 2>nul
    pause
    exit /b 1
)
del "%TEMP%\nexlock_packages.txt" >nul 2>nul

echo App found!
echo.

REM --- Step 3: activate protection ---
echo Step 3 of 3: Activating protection...
echo.
"%ADB%" shell dpm set-device-owner "%ADMIN_COMPONENT%" > "%TEMP%\nexlock_result.txt" 2>&1

findstr /I "Success" "%TEMP%\nexlock_result.txt" >nul
if not errorlevel 1 (
    echo ==========================================
    echo    PROTECTION ACTIVATED SUCCESSFULLY
    echo ==========================================
    echo.
    echo Next step: open the NexLock app on the phone
    echo and enter the enrollment OTP to finish setup.
    goto :cleanup
)

findstr /I "already" "%TEMP%\nexlock_result.txt" >nul
if not errorlevel 1 (
    echo ==========================================
    echo    This phone is already protected.
    echo ==========================================
    echo.
    echo No action needed - open the NexLock app to check its status.
    goto :cleanup
)

findstr /I /C:"not allowed" /C:"provisioning" "%TEMP%\nexlock_result.txt" >nul
if not errorlevel 1 (
    echo ==========================================
    echo    COULDN'T ACTIVATE PROTECTION
    echo ==========================================
    echo.
    echo This usually means the phone was NOT freshly factory reset,
    echo or a Google/Samsung account was already added to it.
    echo.
    echo Please factory reset the phone, skip adding any account
    echo when it restarts, install the NexLock app, then try this
    echo tool again.
    goto :cleanup
)

echo ==========================================
echo    COULDN'T ACTIVATE PROTECTION
echo ==========================================
echo.
echo Something unexpected happened. Please contact support
echo and share this message:
echo.
type "%TEMP%\nexlock_result.txt"

:cleanup
del "%TEMP%\nexlock_result.txt" >nul 2>nul
echo.
pause
