; Custom NSIS steps for the WorkloadHub Forecast installer (electron-builder `nsis.include`).
;
; customInstall runs inside the install section, after the application files and shortcuts are in
; place and before the finish page offers to launch the app. It loads the sample data with the bundled
; frozen service. `--if-empty` makes the call a no-op when %LOCALAPPDATA%\WorkloadHubForecast\whf.db
; already holds data (upgrade, reinstall), so stored runs and narratives are never replaced.
; electron-builder's templates suppress DetailPrint output (SetDetailsPrint none) for the whole
; install section, so the loading line is shown through the status text above the progress bar
; instead, and a failure never fails the installation: it shows a message box with the manual
; command (skipped for silent installs). Choosing "for all users" (or running the setup elevated)
; installs into the administrator's profile: %LOCALAPPDATA% there is not the invoking user's, so
; seeding would fill the wrong database. That path is skipped entirely and shown its own message box
; with the manual command to run afterwards, as themselves.

; Numeric locale IDs (1033 English, 1036 French) because this include is prepended before
; installer.nsi loads MUI2 and defines LANG_ENGLISH / LANG_FRENCH. Every language listed in
; `installerLanguages` in installer/electron-builder.yml must have LangStrings here, or the compile
; fails under -WX; keep that list exactly `[en_US, fr_FR]` with en_US first (the fallback language for
; other system locales).
LangString whfSeedStart 1033 "Loading the sample data (this takes a moment)..."
LangString whfSeedStart 1036 "Chargement des données d'exemple (cela prend un instant)..."
LangString whfSeedFailed 1033 "Could not load the sample data (code $0). The app will start empty; run this in PowerShell, then restart the app: & '$INSTDIR\resources\service\whf\whf.exe' data generate"
LangString whfSeedFailed 1036 "Impossible de charger les données d'exemple (code $0). L'application démarrera vide ; exécutez ceci dans PowerShell, puis redémarrez l'application : & '$INSTDIR\resources\service\whf\whf.exe' data generate"
LangString whfSeedSkippedAllUsers 1033 "Installed for all users, so the sample data was not loaded (it would have gone to the administrator's profile). After the app starts, run this in PowerShell as yourself, then restart the app: & '$INSTDIR\resources\service\whf\whf.exe' data generate"
LangString whfSeedSkippedAllUsers 1036 "Installation pour tous les utilisateurs : les données d'exemple n'ont pas été chargées (elles seraient allées dans le profil de l'administrateur). Une fois l'application démarrée, exécutez ceci dans PowerShell avec votre propre compte, puis redémarrez l'application : & '$INSTDIR\resources\service\whf\whf.exe' data generate"

!macro customInstall
  ${If} $installMode == "all"
    ; Elevated (all-users) install: %LOCALAPPDATA% is the administrator's profile, not the user's,
    ; so seeding here would fill the wrong database. Leave it to the manual command instead.
    ${IfNot} ${Silent}
      MessageBox MB_OK|MB_ICONINFORMATION "$(whfSeedSkippedAllUsers)"
    ${EndIf}
  ${Else}
    SetDetailsPrint textonly
    DetailPrint "$(whfSeedStart)"
    SetDetailsPrint lastused
    nsExec::ExecToLog '"$INSTDIR\resources\service\whf\whf.exe" data generate --if-empty'
    Pop $0
    ${If} $0 != "0"
      ${IfNot} ${Silent}
        MessageBox MB_OK|MB_ICONEXCLAMATION "$(whfSeedFailed)"
      ${EndIf}
    ${EndIf}
  ${EndIf}
!macroend
