@echo off
rem Switch this console to UTF-8 so Chinese/Unicode output is readable.
rem Must stay before any non-ASCII text; this file is ASCII on purpose.
chcp 65001 >nul
setlocal EnableExtensions

rem ============================================================
rem  HMCL RegionPatch launcher
rem
rem  Sets HMCL_JAVA_OPTS with "set", so it only affects this
rem  process tree. Nothing is written to the registry and the
rem  variable disappears when the window is closed.
rem
rem  Usage: put this file next to HMCL.exe and RegionPatch.jar,
rem         then double-click it. Adjust the paths below if needed.
rem ============================================================

rem ---- edit if needed -----------------------------------------
set "HMCL_EXE=%~dp0HMCL.exe"
set "AGENT_JAR=%~dp0RegionPatch.jar"

rem HMCL_JAVA_OPTS REPLACES the launcher defaults, so set the heap here.
set "JAVA_HEAP=-Xmx2G"

rem Make the JVM write UTF-8 as well, matching "chcp 65001" above.
rem Without this, JDK 17 on a Chinese system writes GBK into a UTF-8
rem console and the output is garbled. Clear this line if you prefer
rem the JVM's default encoding.
set "JAVA_ENCODING=-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8"

rem Optional: dump the rewritten RegionUtils.class for javap checks.
rem Leave empty to disable.
set "DUMP_DIR="
rem set "DUMP_DIR=%TEMP%\regionpatch-dump"

rem Optional: force the JDK used by HMCL
rem set "HMCL_JAVA_HOME=C:\Program Files\Java\jdk-21"

rem ---- no changes below this line ------------------------------
if not exist "%HMCL_EXE%" (
    echo [ERROR] HMCL.exe not found: "%HMCL_EXE%"
    echo         Edit HMCL_EXE at the top of this script.
    pause
    exit /b 1
)

if not exist "%AGENT_JAR%" (
    echo [ERROR] RegionPatch.jar not found: "%AGENT_JAR%"
    echo         Run "gradlew.bat shadowJar" first, or edit AGENT_JAR.
    pause
    exit /b 1
)

set "HMCL_JAVA_OPTS=%JAVA_HEAP% %JAVA_ENCODING% -javaagent:"%AGENT_JAR%""
if defined DUMP_DIR set "HMCL_JAVA_OPTS=%HMCL_JAVA_OPTS% -Dregionpatch.dumpDir="%DUMP_DIR%""

echo HMCL_EXE       = %HMCL_EXE%
echo AGENT_JAR      = %AGENT_JAR%
echo HMCL_JAVA_OPTS = %HMCL_JAVA_OPTS%
echo.
echo Notes:
echo   * HMCL_JAVA_OPTS is temporary: it only affects this window.
echo   * Started from a console, the launcher uses java.exe, so the
echo     agent log is printed right here.
echo   * If you see no log, check the status from another window:
echo       jcmd ^<pid^> VM.system_properties ^| findstr regionpatch
echo     Expected: regionpatch.agent.status=PATCHED
echo.

"%HMCL_EXE%"

if errorlevel 1 (
    echo.
    echo [INFO] HMCL exited with a non-zero code, check the output above.
    pause
)

endlocal
