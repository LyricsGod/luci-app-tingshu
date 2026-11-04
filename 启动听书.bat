@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem 优先用 python，找不到再试 Windows 自带的 py 启动器
set "PY=python"
where python >nul 2>nul || set "PY=py -3"
%PY% -c "import sys" >nul 2>nul || (
  echo.
  echo   没有找到 Python，请先安装 Python 3：https://www.python.org/downloads/
  echo.
  pause
  exit /b 1
)

echo.
echo   听书 · 本地离线书架
echo   正在启动本地服务 http://127.0.0.1:8765
echo   关闭这个窗口即可停止服务。
echo.
start "" /min cmd /c "%PY% -m http.server 8765 --bind 127.0.0.1"
timeout /t 2 >nul
start "" "http://127.0.0.1:8765/index.html"
