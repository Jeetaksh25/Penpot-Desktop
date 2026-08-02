; NSIS installer hooks for Penpot Desktop.
;
; Wired into Tauri's installer.nsi via bundle.windows.nsis.installerHooks in
; tauri.conf.json. Tauri's default template already provides: a license page
; (when bundle.licenseFile is set), an install-directory page, a Start Menu
; folder page, a Start Menu shortcut, and a "Launch app" finish page. These
; hooks add the two things the template does NOT do:
;
;   1. A guaranteed desktop shortcut (the template only makes one when the
;      user ticks the finish-page "Show Readme" box, which is easy to miss).
;   2. Install-time PostgreSQL initdb — the one slow, one-time part of the
;      first boot — so the first launch is fast instead of doing it then.
;
; The initdb step is deliberately NON-FATAL: if anything goes wrong (the user
; installed to a read-only location, a DLL is missing, etc.) the installer
; still succeeds and the app's own first-launch logic in src/lib.rs will run
; initdb itself (it checks for PG_VERSION). Worst case = same as before.

!macro NSIS_HOOK_POSTINSTALL
  ; ── Desktop shortcut ───────────────────────────────────────────────────
  ; Uses the exe's embedded icon (the real Penpot icon set via bundle.icon),
  ; so no separate icon file is needed.
  CreateShortcut "$DESKTOP\Oriole Desktop.lnk" "$INSTDIR\${MAINBINARYNAME}.exe"
  DetailPrint "Created desktop shortcut."

  ; ── Install-time PostgreSQL initdb ─────────────────────────────────────
  CreateDirectory "$INSTDIR\data"
  DetailPrint "Initializing local PostgreSQL data directory (this takes a few seconds)..."
  nsExec::ExecToLog '"$INSTDIR\tools\postgres\bin\initdb.exe" -D "$INSTDIR\data\postgres" --username=postgres --auth=trust --encoding=UTF8 --locale=C'
  Pop $0
  ${If} $0 == 0
    DetailPrint "PostgreSQL data directory initialized."
  ${Else}
    DetailPrint "initdb exited with code $0 — the app will initialize it on first launch."
  ${EndIf}
!macroend

!macro NSIS_HOOK_PREUNINSTALL
  ; Stop PostgreSQL if it is running so the data directory is not locked
  ; (a locked postmaster.pid would prevent file removal). Best-effort.
  IfFileExists "$INSTDIR\data\postgres\PG_VERSION" 0 +3
    nsExec::ExecToLog '"$INSTDIR\tools\postgres\bin\pg_ctl.exe" -D "$INSTDIR\data\postgres" -m fast -w -t 10 stop'
    Pop $0
!macroend

!macro NSIS_HOOK_POSTUNINSTALL
!macroend