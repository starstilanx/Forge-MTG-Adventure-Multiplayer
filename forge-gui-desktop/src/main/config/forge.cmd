@echo off

pushd %~dp0

java -version 1>nul 2>nul || (
   echo no java installed
   popd
   exit /b 2
)
for /f tokens^=2^ delims^=.-_^+^" %%j in ('java -fullversion 2^>^&1') do set "jver=%%j"

if %jver% LEQ 16 (
   echo unsupported java
   popd
   exit /b 2
)

if %jver% GEQ 17 (
  rem set GEMINI_API_KEY=your-vertex-ai-api-key-here
  rem set GEMINI_PROJECT_ID=gen-lang-client-0610286057
  rem set GEMINI_LOCATION=us-central1
  rem set GEMINI_MODEL=gemini-3.1-flash-lite
  java $mandatory.java.args$ -jar $project.build.finalName$
  popd
  exit /b 0
)

popd