# _lib.ps1 — PowerShell library for AgentBridge hook scripts.
# Dot-source this at the top of every hook script:
#   . "$PSScriptRoot\_lib.ps1"
#
# Provides:
#   Hook-ReadPayload            — reads and caches stdin JSON payload
#   Hook-Get <field>            — extract a top-level value from the payload
#   Hook-GetArg <field>         — extract arguments.<field> (via HOOK_ARG_<field> env var)
#   Hook-JsonDeny <reason>      — emit deny decision JSON
#   Hook-JsonError <msg>        — emit pre-hook error JSON
#   Hook-JsonAppend <text>      — emit append output JSON
#   Hook-JsonArgs <hashtable>   — emit modified arguments JSON

$script:HookPayload = $null

function Hook-ReadPayload {
    $raw = [Console]::In.ReadToEnd()
    if ($raw -and $raw.Trim().Length -gt 0) {
        $script:HookPayload = $raw | ConvertFrom-Json
    }
}

function Hook-Get {
    param([string]$Field)
    if ($null -eq $script:HookPayload) { return $null }
    return $script:HookPayload.$Field
}

function Hook-GetArg {
    param([string]$Field)
    # Prefer the HOOK_ARG_<field> env var injected by HookExecutor (fast, reliable)
    $envVal = [System.Environment]::GetEnvironmentVariable("HOOK_ARG_$Field")
    if ($null -ne $envVal) { return $envVal }
    # Fall back to payload arguments object
    if ($null -eq $script:HookPayload) { return $null }
    return $script:HookPayload.arguments.$Field
}

function Hook-JsonDeny {
    param([string]$Reason)
    @{ decision = 'deny'; reason = $Reason } | ConvertTo-Json -Compress | Write-Output
}

function Hook-JsonError {
    param([string]$Message)
    @{ error = $Message } | ConvertTo-Json -Compress | Write-Output
}

function Hook-JsonAppend {
    param([string]$Text)
    @{ append = $Text } | ConvertTo-Json -Compress | Write-Output
}

function Hook-JsonArgs {
    param([hashtable]$Arguments)
    @{ arguments = $Arguments } | ConvertTo-Json -Compress | Write-Output
}
