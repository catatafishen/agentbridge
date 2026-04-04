$env:JAVA_HOME = "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\jbr"; $env:PATH = "$env:JAVA_HOME\bin;C:\Program Files\nodejs;$env:PATH"; Set-Location "d:\Projects\ide-agent-for-copilot"; .\gradlew.bat :plugin-core:buildPlugin -x buildSearchableOptions -x verifyPluginConfiguration -x buildChatUi 2>&1 | Select-Object -Last 20

$env:JAVA_HOME = "C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\jbr"; $env:PATH = "$env:JAVA_HOME\bin;C:\Program Files\nodejs;$env:PATH"; Set-Location "d:\Projects\ide-agent-for-copilot"; .\gradlew.bat :plugin-core:buildPlugin -x buildSearchableOptions -x verifyPluginConfiguration -x buildChatUi --stacktrace 2>&1 | Select-Object -Last 15

$zip = Get-ChildItem plugin-core\build\distributions -Filter "*.zip" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Get-ChildItem "$env:APPDATA\JetBrains\Rider2026.1\plugins" | Where-Object { $_.Name -like "*agentbridge*" } | Remove-Item -Recurse -Force
Expand-Archive $zip.FullName "$env:APPDATA\JetBrains\Rider2026.1\plugins" -Force