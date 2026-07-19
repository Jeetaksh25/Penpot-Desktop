@echo off
setlocal enabledelayedexpansion

cd /d D:\TestProjects\Penpot-desktop

REM Ensure local data directories exist
if not exist "data\postgres" mkdir "data\postgres"
if not exist "data\redis" mkdir "data\redis"

REM Initialize PostgreSQL if data directory is empty
if not exist "data\postgres\PG_VERSION" (
    echo Initializing PostgreSQL data directory...
    tools\postgres\bin\initdb.exe -D "D:\TestProjects\Penpot-desktop\data\postgres" --username=postgres --auth=trust --encoding=UTF8 --locale=C
    tools\postgres\bin\pg_ctl.exe -D "D:\TestProjects\Penpot-desktop\data\postgres" -l "D:\TestProjects\Penpot-desktop\data\postgres.log" start
    tools\postgres\bin\createuser.exe -U postgres -w penpot
    tools\postgres\bin\createdb.exe -U postgres -O penpot penpot
) else (
    echo Starting PostgreSQL...
    tools\postgres\bin\pg_ctl.exe -D "D:\TestProjects\Penpot-desktop\data\postgres" -l "D:\TestProjects\Penpot-desktop\data\postgres.log" start
)

echo Starting Redis...
tools\redis\Redis-8.8.0-Windows-x64-msys2\redis-server.exe --port 6379 --loglevel notice --dir "D:\TestProjects\Penpot-desktop\data\redis" --daemonize yes

echo.
echo Services should be ready in a few seconds.
echo PostgreSQL: localhost:5432
echo Redis:      localhost:6379
