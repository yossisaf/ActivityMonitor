@echo off
set SCRIPT_DIR=%~dp0
java -classpath "%SCRIPT_DIR%gradle\wrapper\gradle-wrapper.jar" org.gradle.wrapper.WrapperMain %*
exit /b %ERRORLEVEL%
