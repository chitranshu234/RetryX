@echo off
echo Starting Retryx services...

start "Retryx PostgreSQL (:5432)" cmd /k "cd /d %~dp0embedded-pg && node server.mjs"
timeout /t 3 /nobreak > nul

start "Retryx Receiver (:9090)" cmd /k "cd /d %~dp0..\receiver && mvn spring-boot:run"
start "Retryx Backend (:8080)" cmd /k "cd /d %~dp0..\backend && mvn spring-boot:run"
start "Retryx Frontend (:5173)" cmd /k "cd /d %~dp0..\frontend && npm run dev"

echo All services launched!
echo Dashboard will be available at: http://localhost:5173
echo Backend API available at: http://localhost:8080
echo Receiver available at: http://localhost:9090
pause
