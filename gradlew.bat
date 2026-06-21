@echo off
setlocal

set APP_HOME=%~dp0

if "%JAVA_HOME%"=="" (
  set JAVACMD=java
) else (
  set JAVACMD=%JAVA_HOME%\bin\java.exe
)

"%JAVACMD%" %JAVA_OPTS% -classpath "%APP_HOME%gradle\\wrapper\\gradle-wrapper.jar" org.gradle.wrapper.GradleWrapperMain %*
endlocal
