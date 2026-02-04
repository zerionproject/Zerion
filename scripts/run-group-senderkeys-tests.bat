@echo off
REM ============================================================================
REM Zerion Group Sender Keys Test Runner (Windows)
REM ============================================================================
REM Runs all Sender Keys / Group PCS test suites and outputs a summary.
REM
REM Usage:
REM   scripts\run-group-senderkeys-tests.bat
REM
REM Exit codes:
REM   0 = All tests passed
REM   1 = One or more tests failed
REM ============================================================================

setlocal enabledelayedexpansion

set "SCRIPT_DIR=%~dp0"
set "PROJECT_ROOT=%SCRIPT_DIR%.."

cd /d "%PROJECT_ROOT%"

echo ==============================================
echo Zerion Group Sender Keys Test Suite
echo ==============================================
echo Date: %date% %time%
echo Project Root: %PROJECT_ROOT%
echo.

set TOTAL_TESTS=0
set PASSED_TESTS=0
set FAILED_TESTS=0

echo Running Sender Keys unit and integration tests...
echo.

REM Test class 1: SenderKeyCryptoTest
echo ----------------------------------------------
echo Running: SenderKeyCryptoTest
echo ----------------------------------------------
call gradlew.bat :briar-core:test --tests "org.briarproject.briar.privategroup.senderkeys.SenderKeyCryptoTest" --info
if %ERRORLEVEL% EQU 0 (
    echo √ PASSED: SenderKeyCryptoTest
    set /a PASSED_TESTS+=1
) else (
    echo X FAILED: SenderKeyCryptoTest
    set /a FAILED_TESTS+=1
)
set /a TOTAL_TESTS+=1
echo.

REM Test class 2: EpochRotationTest
echo ----------------------------------------------
echo Running: EpochRotationTest
echo ----------------------------------------------
call gradlew.bat :briar-core:test --tests "org.briarproject.briar.privategroup.senderkeys.EpochRotationTest" --info
if %ERRORLEVEL% EQU 0 (
    echo √ PASSED: EpochRotationTest
    set /a PASSED_TESTS+=1
) else (
    echo X FAILED: EpochRotationTest
    set /a FAILED_TESTS+=1
)
set /a TOTAL_TESTS+=1
echo.

REM Test class 3: SenderKeysIntegrationTest
echo ----------------------------------------------
echo Running: SenderKeysIntegrationTest
echo ----------------------------------------------
call gradlew.bat :briar-core:test --tests "org.briarproject.briar.privategroup.senderkeys.SenderKeysIntegrationTest" --info
if %ERRORLEVEL% EQU 0 (
    echo √ PASSED: SenderKeysIntegrationTest
    set /a PASSED_TESTS+=1
) else (
    echo X FAILED: SenderKeysIntegrationTest
    set /a FAILED_TESTS+=1
)
set /a TOTAL_TESTS+=1
echo.

echo ==============================================
echo TEST SUMMARY
echo ==============================================
echo Total test classes: %TOTAL_TESTS%
echo Passed: %PASSED_TESTS%
echo Failed: %FAILED_TESTS%
echo.

if %FAILED_TESTS% EQU 0 (
    echo √ ALL TESTS PASSED
    echo.
    echo HTML reports available at:
    echo   briar-core\build\reports\tests\test\index.html
    exit /b 0
) else (
    echo X SOME TESTS FAILED
    echo.
    echo Review failed tests in:
    echo   briar-core\build\reports\tests\test\index.html
    exit /b 1
)
