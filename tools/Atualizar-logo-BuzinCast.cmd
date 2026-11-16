@echo off
title Atualizar logo BuzinCast
powershell.exe -NoProfile -File "%~dp0Atualizar-logo-BuzinCast.ps1" %*
exit /b %errorlevel%
