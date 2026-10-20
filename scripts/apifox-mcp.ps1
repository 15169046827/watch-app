param(
    [string]$Tool,
    [string]$ArgumentsJson = '{}',
    [switch]$ListTools,
    [switch]$AllowWrite
)

$ErrorActionPreference = 'Stop'
$projectId = '8948095'
$arguments = $ArgumentsJson | ConvertFrom-Json -AsHashtable -Depth 100
$operationsPath = Join-Path (Split-Path $PSScriptRoot -Parent) 'docs/api/apifox-operations.json'

function Assert-ProjectBinding {
    param([hashtable]$Values)
    $bindings = @($Values.projectId, $Values.pathParams.projectId,
        $Values.headers.'X-Project-Id') | Where-Object { $_ }
    if ($bindings | Where-Object { [string]$_ -ne $projectId }) {
        throw "Only Watch-App project $projectId is in scope."
    }
    if (($bindings | ForEach-Object { [string]$_ } | Select-Object -Unique).Count -gt 1) {
        throw 'Conflicting Apifox project bindings were provided.'
    }
}

if ($Tool -and $Tool -ne 'listAccessibleProjects') {
    Assert-ProjectBinding $arguments
    if ($Tool -eq 'executeOpenApi') {
        if (-not (Test-Path -LiteralPath $operationsPath)) {
            throw 'Reviewed Apifox operation inventory is unavailable.'
        }
        $inventory = Get-Content -LiteralPath $operationsPath -Raw |
            ConvertFrom-Json -AsHashtable -Depth 100
        $operation = @($inventory.operations | Where-Object { $_.id -eq $arguments.id })
        if ($operation.Count -ne 1) { throw 'OpenAPI operation has not been reviewed.' }
        if (-not $arguments.headers -or
            [string]$arguments.headers.'X-Project-Id' -ne $projectId) {
            throw 'An explicit Watch-App X-Project-Id binding is required.'
        }
        if ($operation.access -eq 'write' -and -not $AllowWrite) {
            throw 'Apifox writes require -AllowWrite.'
        }
    } elseif ($Tool -eq 'importData') {
        if (-not $AllowWrite) { throw 'Apifox imports require -AllowWrite.' }
        if ([string]$arguments.pathParams.projectId -ne $projectId -or
            [string]$arguments.headers.'X-Project-Id' -ne $projectId) {
            throw 'Apifox imports require an explicit Watch-App project binding.'
        }
    } elseif ($Tool -notin @(
            'getProjectSummary',
            'getStructureInfo',
            'readEntityDetails',
            'getHttpEndpoint',
            'listOpenApiEndpoints',
            'getOpenApiDetails'
        )) {
        throw 'This helper only supports reviewed Watch-App API maintenance tools.'
    }
}

$credential = [Environment]::GetEnvironmentVariable('ApiFox_MCP_TOKEN', 'Process')
if ([string]::IsNullOrWhiteSpace($credential)) {
    $credential = [Environment]::GetEnvironmentVariable('ApiFox_MCP_TOKEN', 'User')
}
if ([string]::IsNullOrWhiteSpace($credential)) { throw 'ApiFox_MCP_TOKEN is unavailable.' }
$headers = @{
    Authorization = 'Bearer ' + $credential
    'X-Apifox-Api-Version' = '2025-09-01'
    Accept = 'application/json, text/event-stream'
}

function Send-ApifoxMessage {
    param([hashtable]$Message)
    try {
        $response = Invoke-WebRequest -Uri 'https://api.apifox.com/mcp' -Method Post `
            -Headers $headers -ContentType 'application/json' `
            -Body ($Message | ConvertTo-Json -Depth 100 -Compress) `
            -MaximumRedirection 0 -TimeoutSec 45 -ErrorAction Stop
    } catch {
        $status = if ($_.Exception.Response) {
            [int]$_.Exception.Response.StatusCode
        } else {
            0
        }
        throw "Apifox MCP request failed (HTTP $status); credentials were not logged."
    }
    if ($response.Headers['Mcp-Session-Id']) {
        $headers['Mcp-Session-Id'] = [string]$response.Headers['Mcp-Session-Id']
    }
    $raw = [string]$response.Content
    if ([string]::IsNullOrWhiteSpace($raw)) { return $null }
    if ($raw -match '(?m)^data:') {
        $raw = (($raw -split "`n" | Where-Object { $_ -like 'data:*' }) |
                ForEach-Object { $_.Substring(5).Trim() }) -join "`n"
    }
    return $raw | ConvertFrom-Json -Depth 100
}

$initialize = Send-ApifoxMessage @{
    jsonrpc = '2.0'
    id = 1
    method = 'initialize'
    params = @{
        protocolVersion = '2025-06-18'
        capabilities = @{}
        clientInfo = @{ name = 'Watch-App-API-maintenance'; version = '1.0' }
    }
}
if ($initialize.error) { throw 'Apifox MCP initialization rejected.' }
$headers['MCP-Protocol-Version'] = $initialize.result.protocolVersion
$null = Send-ApifoxMessage @{
    jsonrpc = '2.0'
    method = 'notifications/initialized'
}
if ($ListTools) {
    Send-ApifoxMessage @{
        jsonrpc = '2.0'
        id = 2
        method = 'tools/list'
        params = @{}
    }
} elseif ($Tool) {
    Send-ApifoxMessage @{
        jsonrpc = '2.0'
        id = 2
        method = 'tools/call'
        params = @{ name = $Tool; arguments = $arguments }
    }
} else {
    $initialize
}
