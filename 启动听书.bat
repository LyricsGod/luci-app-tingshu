@echo off
chcp 65001 >nul
cd /d "%~dp0"
echo.
echo   听书 · 本地离线书架
echo   正在启动本地服务 http://127.0.0.1:8765
echo   关闭这个窗口即可停止服务。
echo.
start "" /min cmd /c "python -m http.server 8765 --bind 127.0.0.1"
timeout /t 2 >nul
start "" "http://127.0.0.1:8765/index.html"
