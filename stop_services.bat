@echo off
setlocal enabledelayedexpansion

cd /d D:\TestProjects\Penpot-desktop

echo Stopping Redis...
tools\redis\Redis-8.8.0-Windows-x64-msys2\redis-cli.exe shutdown nosave 2>nul

echo Stopping PostgreSQL...
tools\postgres\bin\pg_ctl.exe -D "D:\TestProjects\Penpot-desktop\data\postgres" stop -m fast 2>nul

echo Services stopped.
