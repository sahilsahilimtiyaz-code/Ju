$WshShell = New-Object -ComObject WScript.Shell
$DesktopPath = [Environment]::GetFolderPath('Desktop')
$Shortcut = $WshShell.CreateShortcut((Join-Path $DesktopPath "Brahma Evo.lnk"))

$RootDir = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$PythonExe = Join-Path $RootDir ".venv\Scripts\pythonw.exe"
if (-not (Test-Path $PythonExe)) {
    $PythonExe = "pythonw.exe"
}

$Shortcut.TargetPath = $PythonExe
$Shortcut.Arguments = ('"{0}"' -f (Join-Path $RootDir "main.py"))
$Shortcut.WorkingDirectory = $RootDir
$Shortcut.WindowStyle = 7
$Shortcut.Description = 'Launch Brahma Evo'

$IconPath = Join-Path $RootDir "assets\Brahma_Lite_Logo.ico"
if (Test-Path $IconPath) {
    $Shortcut.IconLocation = "$IconPath,0"
}
$Shortcut.Save()
Write-Host "Created desktop shortcut at: $($Shortcut.FullName)"