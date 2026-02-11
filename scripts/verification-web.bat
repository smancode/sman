@echo off
REM ##############################################################################
REM # SmanAgent 验证服务启动脚本 (Windows)
REM #
REM # 用途: 启动 Web 验证服务，用于验证分析结果的正确性
REM # 端口: 8080 (可通过环境变量 VERIFICATION_PORT 覆盖)
REM #
REM # 环境变量:
REM #   VERIFICATION_PORT - 服务端口（默认 8080）
REM #   LLM_API_KEY - LLM API 密钥
REM #   LLM_BASE_URL - LLM 基础 URL
REM #   LLM_MODEL_NAME - LLM 模型名称
REM #   BGE_ENABLED - 是否启用 BGE（默认 false）
REM #   BGE_ENDPOINT - BGE 端点
REM #   RERANKER_ENABLED - 是否启用 Reranker（默认 false）
REM #   RERANKER_BASE_URL - Reranker 基础 URL
REM #   RERANKER_API_KEY - Reranker API 密钥
REM ##############################################################################

SETLOCAL EnableDelayedExpansion

REM 设置基础目录
set "SCRIPT_DIR=%~dp0"
set "PROJECT_DIR=%SCRIPT_DIR%.."
cd /d "%PROJECT_DIR%" || exit /b 1

REM 配置
if "%VERIFICATION_PORT%"=="" set "PORT=8080" else set "PORT=%VERIFICATION_PORT%"
set "LOG_DIR=%PROJECT_DIR%\logs\verification"
set "LOG_FILE=%LOG_DIR%\verification-web.log"
set "PID_FILE=%LOG_DIR%\verification-web.pid"

REM 创建日志目录
if not exist "%LOG_DIR%" mkdir "%LOG_DIR%"

echo [INFO] ========================================
echo [INFO] SmanAgent 验证服务 (Windows)
echo [INFO] ========================================
echo [INFO] 端口: %PORT%
echo [INFO] 日志: %LOG_FILE%
echo [INFO] ========================================
echo.

REM 检查 Java
echo [STEP] 检查 Java 版本...
where java >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] 未找到 Java，请安装 Java 17 或更高版本
    exit /b 1
)

java -version >nul 2>&1
for /f "tokens=3" %%i in ('java -version 2^>^&1 ^| findstr /i "version"') do set "JAVA_VERSION=%%i"
set "JAVA_VERSION=%JAVA_VERSION:"=%"
for /f "tokens=1 delims=." %%i in ("%JAVA_VERSION%") do set "MAJOR_VERSION=%%i"

if %MAJOR_VERSION% lss 17 (
    echo [ERROR] Java 版本过低: %MAJOR_VERSION%，需要 Java 17 或更高版本
    exit /b 1
)

echo [INFO] Java 版本: %MAJOR_VERSION%
echo.

REM 检查端口占用
echo [STEP] 检查端口 %PORT%...
netstat -ano | findstr ":%PORT%" | findstr "LISTENING" >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo [WARN] 端口 %PORT% 已被占用
    set /p "KILL=是否杀死占用进程并继续？(y/n): "
    if /i "!KILL!"=="y" (
        for /f "tokens=5" %%a in ('netstat -ano ^| findstr ":%PORT%" ^| findstr "LISTENING"') do (
            taskkill /F /PID %%a >nul 2>&1
        )
        echo [INFO] 已杀死占用进程
        timeout /t 1 /nobreak >nul
    ) else (
        echo [ERROR] 用户取消操作
        exit /b 1
    )
)
echo.

REM 检查环境变量
echo [STEP] 检查环境变量...
if "%LLM_API_KEY%"=="" (
    echo [WARN] 未设置 LLM_API_KEY 环境变量
    echo [WARN] 专家咨询功能可能无法正常工作
)
echo.

REM 构建 JAR
echo [STEP] 构建 JAR...
call gradlew.bat clean build -x test >nul 2>&1
if %ERRORLEVEL% neq 0 (
    echo [ERROR] 构建失败，请检查错误
    echo 运行 'gradlew.bat clean build' 查看详细日志
    exit /b 1
)

echo [INFO] 构建成功
echo.

REM 查找 JAR 文件
set "JAR_FILE="
for /f "delims=" %%f in ('dir /b /s "%PROJECT_DIR%\build\libs\*.jar" 2^>nul ^| findstr /v "sources.jar ^| javadoc.jar"') do (
    set "JAR_FILE=%%f"
    goto :found_jar
)

:found_jar
if "%JAR_FILE%"=="" (
    echo [ERROR] 未找到 JAR 文件
    exit /b 1
)

echo [INFO] JAR 文件: %JAR_FILE%
echo.

REM 启动服务
echo [STEP] 启动验证服务...
echo.
echo ==========================================
echo   SmanAgent 验证服务
echo ==========================================
echo 端口: %PORT%
echo 日志: %LOG_FILE%
echo.
echo API 端点:
echo   - POST http://localhost:%PORT%/api/verify/expert_consult
echo   - POST http://localhost:%PORT%/api/verify/semantic_search
echo   - POST http://localhost:%PORT%/api/verify/analysis_results
echo   - POST http://localhost:%PORT%/api/verify/h2_query
echo.
echo 健康检查:
echo   curl http://localhost:%PORT%/actuator/health
echo   或
echo   Invoke-WebRequest http://localhost:%PORT%/actuator/health
echo.
echo 按 Ctrl+C 停止服务
echo ==========================================
echo.

REM 启动服务（前台运行，以便用户可以看到日志）
start /B java -Dserver.port=%PORT% -Dlogging.file.name="%LOG_FILE%" -Dlogging.level.com.smancode.smanagent=INFO -jar "%JAR_FILE%" --spring.main.web-application-type=SERVLET > "%LOG_DIR%\verification-web.stdout" 2>&1

set "JAVA_PID=%ERRORLEVEL%"
echo %JAVA_PID% > "%PID_FILE%"

REM 等待服务启动
echo [INFO] 等待服务启动...
timeout /t 3 /nobreak >nul

REM 健康检查
set "MAX_RETRIES=30"
set "RETRY_COUNT=0"

:health_check
if %RETRY_COUNT% geq %MAX_RETRIES% (
    echo.
    echo [ERROR] 服务启动超时
    echo [ERROR] 请查看日志: %LOG_FILE%
    exit /b 1
)

curl -s http://localhost:%PORT%/actuator/health >nul 2>&1
if %ERRORLEVEL% equ 0 (
    echo.
    echo [INFO] 服务启动成功！
    echo.
    echo 测试命令:
    echo   curl -X POST http://localhost:%PORT%/api/verify/expert_consult ^
    echo     -H "Content-Type: application/json" ^
    echo     -d "{\"question\": \"测试\", \"projectKey\": \"test\"}"
    echo.
    goto :service_running
)

set /a "RETRY_COUNT+=1"
set /a "REMAINDER=%RETRY_COUNT%%%5"
if %REMAINDER% equ 0 echo.
echo|set /p="."
timeout /t 1 /nobreak >nul
goto :health_check

:service_running
echo.
echo [INFO] 服务运行中，按 Ctrl+C 停止...

REM 保持脚本运行
:wait_loop
timeout /t 5 /nobreak >nul
goto :wait_loop

REM 清理函数
:cleanup
echo.
echo [STEP] 停止服务...
if exist "%PID_FILE%" (
    set /p "PID=" < "%PID_FILE%"
    taskkill /F /PID !PID! >nul 2>&1
    del "%PID_FILE%" >nul 2>&1
    echo [INFO] 服务已停止 (PID: !PID!)
)
exit /b 0
