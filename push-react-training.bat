@echo off
setlocal EnableExtensions EnableDelayedExpansion

cd /d "%~dp0"

set "REMOTE=origin"
set "BRANCH=codex/react-training"
set "EXPECTED_REMOTE=https://github.com/cixingji/interview.git"
set "MAX_RETRIES=3"

echo.
echo [1/4] Checking repository...
git rev-parse --is-inside-work-tree >nul 2>&1
if errorlevel 1 (
  echo ERROR: This script is not inside a Git repository.
  goto :failed
)

for /f "delims=" %%U in ('git remote get-url --push "%REMOTE%" 2^>nul') do (
  set "REMOTE_URL=%%U"
)
if not defined REMOTE_URL (
  echo ERROR: Git remote "%REMOTE%" does not exist.
  goto :failed
)
if /I not "!REMOTE_URL!"=="%EXPECTED_REMOTE%" (
  echo ERROR: Refusing to push to an unexpected repository.
  echo Expected: %EXPECTED_REMOTE%
  echo Actual:   !REMOTE_URL!
  goto :failed
)

for /f "delims=" %%B in ('git branch --show-current') do set "CURRENT_BRANCH=%%B"
if /I not "!CURRENT_BRANCH!"=="%BRANCH%" (
  echo ERROR: Current branch is "!CURRENT_BRANCH!", expected "%BRANCH%".
  echo Run: git switch %BRANCH%
  goto :failed
)

for /f "delims=" %%H in ('git rev-parse HEAD') do set "LOCAL_SHA=%%H"
echo Repository: !REMOTE_URL!
echo Branch:     %BRANCH%
echo Commit:     !LOCAL_SHA!

echo.
echo [2/4] Pushing branch...
for /L %%R in (1,1,%MAX_RETRIES%) do (
  echo Attempt %%R of %MAX_RETRIES%...
  git ^
    -c http.version=HTTP/1.1 ^
    -c http.postBuffer=524288000 ^
    -c http.lowSpeedLimit=0 ^
    -c http.lowSpeedTime=999999 ^
    push --no-thin --set-upstream "%REMOTE%" "%BRANCH%"

  echo.
  echo [3/4] Verifying the remote commit...
  set "REMOTE_SHA="
  for /f "tokens=1" %%H in ('git ls-remote --heads "%REMOTE%" "%BRANCH%" 2^>nul') do (
    set "REMOTE_SHA=%%H"
  )

  if /I "!REMOTE_SHA!"=="!LOCAL_SHA!" (
    echo.
    echo [4/4] Push completed and verified.
    echo Remote branch: %REMOTE%/%BRANCH%
    echo Commit:        !REMOTE_SHA!
    echo.
    pause
    exit /b 0
  )

  echo Remote verification did not match yet.
  echo Local:  !LOCAL_SHA!
  echo Remote: !REMOTE_SHA!
  if not "%%R"=="%MAX_RETRIES%" (
    echo Waiting 5 seconds before retry...
    timeout /t 5 /nobreak >nul
  )
)

:failed
echo.
echo Push was not verified. Review the error above, then run this script again.
echo.
pause
exit /b 1
