@echo off
chcp 65001 >nul
setlocal EnableExtensions

rem ============================================================
rem  HMCL RegionPatch 启动脚本
rem
rem  用 set 临时设置 HMCL_JAVA_OPTS，只影响本脚本启动的进程树，
rem  不写注册表、不改系统环境变量，关闭窗口即失效。
rem
rem  用法：把本文件与 RegionPatch.jar 放到 HMCL.exe 所在目录，
rem        双击运行即可。路径不同时改下面两行。
rem ============================================================

rem ---- 按需修改 ------------------------------------------------
set "HMCL_EXE=%~dp0HMCL.exe"
set "AGENT_JAR=%~dp0RegionPatch.jar"

rem HMCL_JAVA_OPTS 会“替换”启动器的默认参数，所以堆参数要自己写全
set "JAVA_HEAP=-Xmx2G"

rem 可选：把改写后的 RegionUtils.class dump 出来，便于 javap 验证。
rem 不需要就保持为空。
set "DUMP_DIR="
rem set "DUMP_DIR=%TEMP%\regionpatch-dump"

rem 可选：指定 HMCL 使用的 JDK（不设置则用启动器自动探测的 JDK）
rem set "HMCL_JAVA_HOME=C:\Program Files\Java\jdk-21"

rem ---- 以下一般不用改 ------------------------------------------
if not exist "%HMCL_EXE%" (
    echo [错误] 找不到 HMCL.exe: "%HMCL_EXE%"
    echo        请用记事本修改本脚本顶部的 HMCL_EXE。
    pause
    exit /b 1
)

if not exist "%AGENT_JAR%" (
    echo [错误] 找不到 RegionPatch.jar: "%AGENT_JAR%"
    echo        请先运行 gradlew.bat shadowJar，或修改脚本顶部的 AGENT_JAR。
    pause
    exit /b 1
)

set "HMCL_JAVA_OPTS=%JAVA_HEAP% -javaagent:"%AGENT_JAR%""
if defined DUMP_DIR set "HMCL_JAVA_OPTS=%HMCL_JAVA_OPTS% -Dregionpatch.dumpDir="%DUMP_DIR%""

echo HMCL_EXE        = %HMCL_EXE%
echo AGENT_JAR       = %AGENT_JAR%
echo HMCL_JAVA_OPTS  = %HMCL_JAVA_OPTS%
echo.
echo 说明：
echo   * 这里的 HMCL_JAVA_OPTS 只在本窗口内有效，不会写入系统环境变量。
echo   * 从控制台启动时启动器会使用 java.exe，Agent 日志直接显示在本窗口。
echo   * 若看不到日志，可在另一个窗口执行：
echo       jcmd ^<pid^> VM.system_properties ^| findstr regionpatch
echo     期望看到 regionpatch.agent.status=PATCHED
echo.

"%HMCL_EXE%"

if errorlevel 1 (
    echo.
    echo [提示] HMCL 退出码非 0，请检查上面的输出。
    pause
)

endlocal
