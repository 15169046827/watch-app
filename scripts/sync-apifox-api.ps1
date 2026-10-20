param([switch]$Apply, [switch]$RecoverImportedHttp)

$ErrorActionPreference = 'Stop'
$repository = Split-Path $PSScriptRoot -Parent
$layoutPath = Join-Path $repository 'docs/api/apifox-layout.json'
$manifestPath = Join-Path $repository 'docs/api/apifox-manifest.json'
$helper = Join-Path $PSScriptRoot 'apifox-mcp.ps1'
$projectId = 8948095
$branchId = 8751743
$moduleId = 8741710

$layout = Get-Content -LiteralPath $layoutPath -Raw | ConvertFrom-Json -AsHashtable -Depth 100
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json -AsHashtable -Depth 100
if ($manifest.projectId -ne $projectId -or $manifest.branchId -ne $branchId -or
    $manifest.moduleId -ne $moduleId) { throw 'Unexpected Watch-App Apifox binding.' }

function Get-Hash([string]$Content) {
    $bytes = [Text.Encoding]::UTF8.GetBytes($Content.Replace("`r`n", "`n"))
    return [Convert]::ToHexString([Security.Cryptography.SHA256]::HashData($bytes)).ToLowerInvariant()
}

function Get-DefinitionText($Definition) {
    if ($Definition -is [string]) { return $Definition }
    return ($Definition | ConvertTo-Json -Depth 100 -Compress)
}

function Save-Manifest {
    $pending = "$manifestPath.pending"
    [IO.File]::WriteAllText($pending, ($manifest | ConvertTo-Json -Depth 100) + "`n",
        [Text.UTF8Encoding]::new($false))
    [IO.File]::Move($pending, $manifestPath, $true)
}

function Call-Apifox([string]$Tool, [hashtable]$Arguments, [bool]$Write = $false) {
    $response = & $helper -Tool $Tool `
        -ArgumentsJson ($Arguments | ConvertTo-Json -Depth 100 -Compress) `
        -AllowWrite:$Write
    if ($response.error -or $response.result.isError) {
        throw "Apifox $Tool rejected the request; no automatic retry was made."
    }
    $text = ($response.result.content | Where-Object type -EQ 'text' |
            ForEach-Object text) -join "`n"
    if ([string]::IsNullOrWhiteSpace($text)) { return @{} }
    $parsed = $text | ConvertFrom-Json -AsHashtable -Depth 100
    if ($parsed.ContainsKey('success') -and -not $parsed.success) {
        throw "Apifox $Tool returned a business error; no automatic retry was made."
    }
    return $parsed
}

function Call-Dynamic([string]$Id, [hashtable]$Path, $Body, [bool]$Write = $false) {
    $arguments = @{
        id = $Id
        pathParams = $Path
        queryParams = @{}
        headers = @{ 'X-Project-Id' = [string]$projectId }
    }
    if ($null -ne $Body) { $arguments.body = $Body }
    return Call-Apifox 'executeOpenApi' $arguments $Write
}

function Get-Folders {
    return @((Call-Dynamic '00a468815ec90da0' @{} $null).data)
}

function Find-RemoteFolder($Folders, [string]$Name, $ParentId, [bool]$Root) {
    return @($Folders | Where-Object {
        $_.name -eq $Name -and (
            ($Root -and [string]$_.parentId -eq '0') -or
            (-not $Root -and [string]$_.parentId -eq [string]$ParentId)
        )
    })
}

# Folder writes: POST /api/v1/api-detail-folders, bound by X-Project-Id.
$remoteFolders = Get-Folders
foreach ($folder in $layout.folders) {
    $isRoot = -not $folder.parent
    $parentId = if ($isRoot) { 0 } else { $manifest.folders[$folder.parent].id }
    if (-not $isRoot -and -not $parentId) {
        if ($Apply) { throw "Missing verified parent folder: $($folder.parent)" }
        Write-Output "PLAN folder $($folder.key) (after parent)"
        continue
    }
    $state = $manifest.folders[$folder.key]
    if ($state) {
        $actual = @($remoteFolders | Where-Object { [string]$_.id -eq [string]$state.id })
        $expectedParent = if ($isRoot) { '0' } else { [string]$parentId }
        if ($actual.Count -ne 1 -or $actual[0].name -ne $folder.name -or
            [string]$actual[0].parentId -ne $expectedParent) {
            throw "Managed Apifox folder changed: $($folder.key)"
        }
        Write-Output "UNCHANGED folder $($folder.key)"
        continue
    }
    $collision = Find-RemoteFolder $remoteFolders $folder.name $parentId $isRoot
    if ($collision.Count) { throw "Unmanaged Apifox folder collision: $($folder.name)" }
    if (-not $Apply) { Write-Output "PLAN folder $($folder.key)"; continue }
    $result = Call-Dynamic 'a669aaa8a7136b83' @{} @{
        name = $folder.name; parentId = [string]$parentId; type = 'api'
    } $true
    $id = $result.data.id
    if (-not $id) { throw 'Folder creation outcome is unclear; inspect remote state before retrying.' }
    $manifest.folders[$folder.key] = @{ id = $id; name = $folder.name; parentId = $parentId }
    if ($isRoot) { $manifest.rootFolderId = $id }
    Save-Manifest
    $remoteFolders = Get-Folders
    $read = @($remoteFolders | Where-Object { [string]$_.id -eq [string]$id })
    $expectedParent = if ($isRoot) { '0' } else { [string]$parentId }
    if ($read.Count -ne 1 -or $read[0].name -ne $folder.name -or
        [string]$read[0].parentId -ne $expectedParent) {
        throw "Folder readback mismatch: $($folder.key)"
    }
    Write-Output "VERIFIED folder $($folder.key) id=$id"
}

# Native interfaces: GET/POST/PUT /api/v1/projects/{projectId}/custom-endpoints.
$remoteInterfaces = @((Call-Dynamic 'aa85350f4c6df684' @{ projectId = [string]$projectId } $null).data)
foreach ($item in $layout.interfaces) {
    $folderId = $manifest.folders[$item.group].id
    if (-not $folderId) {
        if ($Apply) { throw "Missing destination folder for $($item.key)" }
        Write-Output "PLAN interface $($item.key) (after folders)"
        continue
    }
    $hash = Get-Hash $item.content
    $state = $manifest.interfaces[$item.key]
    $actual = $null
    if ($state) {
        $actual = (Call-Dynamic 'd2430159b28e4551' @{
            projectId = [string]$projectId; id = [string]$state.id
        } $null).data
        if ($state.sha256 -and (Get-Hash ([string]$actual.content)) -ne $state.sha256) {
            throw "Remote interface was edited: $($item.key)"
        }
        if ((Get-Hash ([string]$actual.content)) -eq $hash -and
            [string]$actual.folderId -eq [string]$folderId -and
            $actual.protocol -eq $item.protocol -and $actual.path -eq $item.path -and
            $actual.name -eq $item.name) {
            Write-Output "UNCHANGED interface $($item.key)"
            continue
        }
    } elseif ($remoteInterfaces | Where-Object { $_.path -eq $item.path -or $_.name -eq $item.name }) {
        throw "Unmanaged interface collision: $($item.path)"
    }
    if (-not $Apply) { Write-Output "PLAN interface $($item.key)"; continue }
    $path = @{ projectId = [string]$projectId }
    $operation = '1cbdd18b600ccea1'
    if ($state) { $path.id = [string]$state.id; $operation = '0a1b56c981b3e8fc' }
    $result = Call-Dynamic $operation $path @{
        name = $item.name; protocol = $item.protocol; path = $item.path
        folderId = [int]$folderId; content = $item.content; status = 'released'
        tags = @('Watch-App', $item.protocol)
    } $true
    $id = if ($state) { $state.id } else { $result.data.id }
    if (-not $id) { throw 'Interface write outcome is unclear; inspect remote state before retrying.' }
    $manifest.interfaces[$item.key] = @{
        id = $id; name = $item.name; folderId = $folderId; sha256 = $null
    }
    Save-Manifest
    $read = (Call-Dynamic 'd2430159b28e4551' @{
        projectId = [string]$projectId; id = [string]$id
    } $null).data
    if ((Get-Hash ([string]$read.content)) -ne $hash -or
        [string]$read.folderId -ne [string]$folderId -or
        $read.protocol -ne $item.protocol -or $read.path -ne $item.path -or
        $read.name -ne $item.name) { throw "Interface readback mismatch: $($item.key)" }
    $manifest.interfaces[$item.key].sha256 = $hash
    Save-Manifest
    Write-Output "VERIFIED interface $($item.key) id=$id"
}

# Reference documents: GET/POST/PUT /api/v1/docs.
$remoteDocuments = @((Call-Dynamic '9738c601d6498b0a' @{} $null).data)
foreach ($document in $layout.documents) {
    $folderId = $manifest.folders[$document.group].id
    if (-not $folderId) {
        if ($Apply) { throw "Missing destination folder for document $($document.key)" }
        Write-Output "PLAN document $($document.key) (after folders)"
        continue
    }
    $hash = Get-Hash $document.content
    $state = $manifest.documents[$document.key]
    if ($state) {
        $actual = (Call-Dynamic '2c83ef72f52ed483' @{ docId = [string]$state.id } $null).data
        if ($state.sha256 -and (Get-Hash ([string]$actual.content)) -ne $state.sha256) {
            throw "Remote document was edited: $($document.key)"
        }
        if ((Get-Hash ([string]$actual.content)) -eq $hash -and
            [string]$actual.folderId -eq [string]$folderId -and $actual.name -eq $document.name) {
            Write-Output "UNCHANGED document $($document.key)"
            continue
        }
    } elseif ($remoteDocuments | Where-Object name -EQ $document.name) {
        throw "Unmanaged document collision: $($document.name)"
    }
    if (-not $Apply) { Write-Output "PLAN document $($document.key)"; continue }
    $path = @{}
    $operation = '12efa5c42b296c7c'
    if ($state) { $path.doc_id = [string]$state.id; $operation = 'fa8695f8e5037106' }
    $result = Call-Dynamic $operation $path @{
        name = $document.name; content = $document.content; folderId = [string]$folderId
    } $true
    $id = if ($state) { $state.id } else { $result.data.id }
    if (-not $id) { throw 'Document write outcome is unclear; inspect remote state before retrying.' }
    $manifest.documents[$document.key] = @{
        id = $id; name = $document.name; folderId = $folderId; sha256 = $null
    }
    Save-Manifest
    $read = (Call-Dynamic '2c83ef72f52ed483' @{ docId = [string]$id } $null).data
    if ((Get-Hash ([string]$read.content)) -ne $hash -or
        [string]$read.folderId -ne [string]$folderId -or $read.name -ne $document.name) {
        throw "Document readback mismatch: $($document.key)"
    }
    $manifest.documents[$document.key].sha256 = $hash
    Save-Manifest
    Write-Output "VERIFIED document $($document.key) id=$id"
}

# Real HTTP endpoints: import each reviewed OpenAPI document into its verified product folder.
$httpTotal = 0
foreach ($catalog in $layout.httpCatalogs) {
    $httpImportFolderId = $manifest.folders[$catalog.importFolder].id
    $httpEndpointFolderId = $manifest.folders[$catalog.endpointFolder].id
    $openApiPath = Join-Path (Join-Path $repository 'docs/api') $catalog.file
    $openApiText = Get-Content -LiteralPath $openApiPath -Raw
    $openApi = $openApiText | ConvertFrom-Json -AsHashtable -Depth 100
    $sourceHash = Get-Hash $openApiText
    $expectedHttp = @()
    foreach ($path in $openApi.paths.Keys) {
        foreach ($method in @($openApi.paths[$path].Keys | Where-Object { $_ -in @('get','post','put','patch','delete','head','options') })) {
            $expectedHttp += @{
                key = "$($method.ToUpperInvariant()) $path"
                method = $method.ToUpperInvariant()
                path = $path
                name = [string]$openApi.paths[$path][$method].summary
            }
        }
    }
    $httpTotal += $expectedHttp.Count
    $structure = Call-Apifox 'getStructureInfo' @{
        projectId = $projectId; branchId = $branchId; entityType = 'endpoint'
    }
    $needImport = $false
    foreach ($endpoint in $expectedHttp) {
        $state = $manifest.httpEndpoints[$endpoint.key]
        if (-not $state) {
            $collision = @($structure.data.entities | Where-Object {
                $_.path -eq $endpoint.path -and ([string]$_.method).ToUpperInvariant() -eq $endpoint.method
            })
            if ($collision.Count) {
                if (-not $RecoverImportedHttp -or $collision.Count -ne 1) {
                    throw "Unmanaged HTTP endpoint collision: $($endpoint.key)"
                }
                $candidateId = $collision[0].entityId
                $actual = (Call-Apifox 'readEntityDetails' @{
                    projectId = $projectId; branchId = $branchId
                    entityType = 'endpoint'; entityId = $candidateId
                }).data
                $methodKey = $endpoint.method.ToLowerInvariant()
                $hasContract = $actual.definition.paths.ContainsKey($endpoint.path) -and
                    $actual.definition.paths[$endpoint.path].ContainsKey($methodKey)
                if ($hasContract) {
                    $remoteOperation = $actual.definition.paths[$endpoint.path][$methodKey]
                    $localOperation = $openApi.paths[$endpoint.path][$methodKey]
                    $remoteServers = @($remoteOperation.servers | ForEach-Object url) -join '|'
                    $localServers = @($localOperation.servers | ForEach-Object url) -join '|'
                    $hasContract = $remoteOperation.operationId -eq $localOperation.operationId -and
                        $remoteOperation.summary -eq $localOperation.summary -and
                        $remoteServers -eq $localServers
                }
                $detail = (Call-Apifox 'getHttpEndpoint' @{
                    pathParams = @{ projectId = $projectId; httpApiId = $candidateId }
                    queryParams = @{}; headers = @{ 'X-Project-Id' = [string]$projectId }
                }).data
                if (-not $candidateId -or -not $hasContract -or
                    $collision[0].name -ne $endpoint.name -or
                    [string]$detail.folderId -ne [string]$httpEndpointFolderId) {
                    throw "HTTP recovery verification failed: $($endpoint.key)"
                }
                $manifest.httpEndpoints[$endpoint.key] = @{
                    id = $candidateId; folderId = $httpEndpointFolderId
                    sourceSha256 = $sourceHash
                    remoteSha256 = Get-Hash (Get-DefinitionText $actual.definition)
                }
                Save-Manifest
                Write-Output "RECOVERED HTTP $($catalog.key) $($endpoint.key) id=$candidateId"
                continue
            }
            $needImport = $true
            continue
        }
        $actual = (Call-Apifox 'readEntityDetails' @{
            projectId = $projectId; branchId = $branchId; entityType = 'endpoint'; entityId = $state.id
        }).data
        $remoteHash = Get-Hash (Get-DefinitionText $actual.definition)
        if ($state.remoteSha256 -and $remoteHash -ne $state.remoteSha256) {
            throw "Remote HTTP endpoint was edited: $($endpoint.key)"
        }
        $detail = (Call-Apifox 'getHttpEndpoint' @{
            pathParams = @{ projectId = $projectId; httpApiId = $state.id }
            queryParams = @{}; headers = @{ 'X-Project-Id' = [string]$projectId }
        }).data
        if ([string]$detail.folderId -ne [string]$httpEndpointFolderId) {
            throw "Remote HTTP endpoint moved: $($endpoint.key)"
        }
        if ($state.sourceSha256 -ne $sourceHash) { $needImport = $true }
    }
    if ($needImport) {
        if (-not $httpImportFolderId -or -not $httpEndpointFolderId) {
            if ($Apply) { throw "Missing verified HTTP folder for catalog $($catalog.key)." }
            Write-Output "PLAN HTTP $($catalog.key) import (after folders)"
        } elseif (-not $Apply) {
            Write-Output "PLAN HTTP $($catalog.key) import ($($expectedHttp.Count) endpoints)"
        } else {
            # Direct importData POST: fixed Watch-App binding and reviewed local OpenAPI only.
            $null = Call-Apifox 'importData' @{
                pathParams = @{ projectId = [string]$projectId }
                queryParams = @{}
                headers = @{ 'X-Project-Id' = [string]$projectId }
                body = @{
                    importFormat = 'openapi'; data = $openApiText
                    apiOverwriteMode = 'methodAndPath'; schemaOverwriteMode = 'ignore'
                    syncApiFolder = $false; apiFolderId = [int]$httpImportFolderId; importBasePath = $false
                }
            } $true
            $structure = Call-Apifox 'getStructureInfo' @{
                projectId = $projectId; branchId = $branchId; entityType = 'endpoint'
            }
            foreach ($endpoint in $expectedHttp) {
                $matches = @($structure.data.entities | Where-Object {
                    $_.path -eq $endpoint.path -and ([string]$_.method).ToUpperInvariant() -eq $endpoint.method
                })
                if ($matches.Count -ne 1) { throw "HTTP import readback mismatch: $($endpoint.key)" }
                $actual = (Call-Apifox 'readEntityDetails' @{
                    projectId = $projectId; branchId = $branchId; entityType = 'endpoint'; entityId = $matches[0].entityId
                }).data
                $detail = (Call-Apifox 'getHttpEndpoint' @{
                    pathParams = @{ projectId = $projectId; httpApiId = $matches[0].entityId }
                    queryParams = @{}; headers = @{ 'X-Project-Id' = [string]$projectId }
                }).data
                if ([string]$detail.folderId -ne [string]$httpEndpointFolderId) {
                    throw "HTTP endpoint folder mismatch: $($endpoint.key)"
                }
                $manifest.httpEndpoints[$endpoint.key] = @{
                    id = $matches[0].entityId; folderId = $httpEndpointFolderId
                    sourceSha256 = $sourceHash
                    remoteSha256 = Get-Hash (Get-DefinitionText $actual.definition)
                }
                Save-Manifest
                Write-Output "VERIFIED HTTP $($catalog.key) $($endpoint.key) id=$($matches[0].entityId)"
            }
        }
    } else {
        foreach ($endpoint in $expectedHttp) {
            Write-Output "UNCHANGED HTTP $($catalog.key) $($endpoint.key)"
        }
    }
}

Write-Output "SUMMARY folders=$($layout.folders.Count) interfaces=$($layout.interfaces.Count) documents=$($layout.documents.Count) http=$httpTotal apply=$Apply recover=$RecoverImportedHttp"
