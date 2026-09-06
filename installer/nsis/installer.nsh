; Custom NSIS steps for the WorkloadHub Forecast installer (electron-builder `nsis.include`).
;
; customInstall runs inside the install section, after the application files and shortcuts are in
; place and before the finish page offers to launch the app. It loads the sample data with the bundled
; frozen service. `--if-empty` makes the call a no-op when %LOCALAPPDATA%\WorkloadHubForecast\whf.db
; already holds data (upgrade, reinstall), so stored runs and narratives are never replaced.
; A failure here never fails the installation: it is written to the details log with the manual command.

; Numeric locale IDs (1033 English, 1036 French) because this include is prepended before
; installer.nsi loads MUI2 and defines LANG_ENGLISH / LANG_FRENCH.
LangString whfSeedStart 1033 "Loading the sample data (this takes a moment)..."
LangString whfSeedStart 1036 "Chargement des données d'exemple (cela prend un instant)..."
LangString whfSeedDone 1033 "Sample data ready."
LangString whfSeedDone 1036 "Données d'exemple prêtes."
LangString whfSeedFailed 1033 "Could not load the sample data (code $0). The app will start empty; run this in PowerShell, then restart the app: & '$INSTDIR\resources\service\whf\whf.exe' data generate"
LangString whfSeedFailed 1036 "Impossible de charger les données d'exemple (code $0). L'application démarrera vide ; exécutez ceci dans PowerShell, puis redémarrez l'application : & '$INSTDIR\resources\service\whf\whf.exe' data generate"

!macro customInstall
  DetailPrint "$(whfSeedStart)"
  nsExec::ExecToLog '"$INSTDIR\resources\service\whf\whf.exe" data generate --if-empty'
  Pop $0
  ${If} $0 == "0"
    DetailPrint "$(whfSeedDone)"
  ${Else}
    DetailPrint "$(whfSeedFailed)"
  ${EndIf}
!macroend
