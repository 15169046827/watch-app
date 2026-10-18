param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$resolvedRoot = (Resolve-Path -LiteralPath $Root).Path
$configPath = Join-Path $resolvedRoot 'config\release\wars-1.0.json'
$documentPath = Join-Path $resolvedRoot 'docs\release\WARS_1_0.md'
$checklistPath = Join-Path $resolvedRoot 'docs\release\PRODUCTION_ACCEPTANCE_CHECKLIST.md'
$readmePath = Join-Path $resolvedRoot 'README.md'
$securityPath = Join-Path $resolvedRoot 'SECURITY.md'
$contributingPath = Join-Path $resolvedRoot 'CONTRIBUTING.md'
$sourceStatusPath = Join-Path $resolvedRoot 'docs\release\PUBLIC_SOURCE_STATUS.md'
$requiredAssets = @(
    $configPath,
    $documentPath,
    $checklistPath,
    $readmePath,
    $securityPath,
    $contributingPath,
    $sourceStatusPath,
    (Join-Path $resolvedRoot 'gradle\shared-release-signing-v1.gradle'),
    (Join-Path $resolvedRoot 'scripts\verify-release-signatures.ps1'),
    (Join-Path $resolvedRoot 'scripts\verify-authenticode.ps1'),
    (Join-Path $resolvedRoot 'X3OfflineReader\builder\package-windows.ps1'),
    (Join-Path $resolvedRoot 'X3OfflineReader\builder\package-windows-preview.ps1')
)
foreach ($path in $requiredAssets) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required WARS asset is missing: $path"
    }
}

$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
if ($config.standardId -ne 'WARS' -or $config.version -ne '1.1.0' -or
        $config.status -ne 'approved_in_progress') {
    throw 'WARS identity, version, or status is invalid.'
}
$expectedDecisions = @{
    initialDistribution = 'personal_github_preview_then_formal_public_production'
    androidReleaseKeyPolicy = 'one_shared_release_key_across_public_android_products'
    wolaiAuthorization = 'personal_token_for_preview_formal_oauth_for_production'
    dataLifecycleScope = 'product_specific_analysis_and_implementation'
    paymentAutoLedger = 'first_beta_default_off_separate_consent_limitations_disclosed'
    windowsSigning = 'unsigned_portable_preview_authenticode_for_production'
}
foreach ($name in $expectedDecisions.Keys) {
    if ($config.approvedDecisions.$name -ne $expectedDecisions[$name]) {
        throw "Approved WARS decision is missing or changed: $name"
    }
}
if (-not $config.androidReleaseSigning.sharedCertificateRequired -or
        -not $config.androidReleaseSigning.mustDifferFromDebugCertificate -or
        -not $config.androidReleaseSigning.failClosedWhenReleaseRequested -or
        $config.androidReleaseSigning.registeredCertificateSha256 -ne
            '3ef6162e5c749caf81d222642997ef306380fc96696c9661b77b72a829dc3f73' -or
        -not $config.windowsReleaseSigning.sha256IdentityVerificationRequired -or
        $config.windowsReleaseSigning.authenticodeStatus -ne 'Valid' -or
        -not $config.codeAuditPassDoesNotImplyProductionRelease) {
    throw 'WARS release-signing or verdict boundary is incomplete.'
}
if ($config.releaseIdentity.publisherType -ne 'individual' -or
        $config.releaseIdentity.publisherName -ne 'Ym_Li' -or
        $config.releaseIdentity.channel -ne 'github_direct_download') {
    throw 'WARS release identity or distribution channel is invalid.'
}
$expectedPublicProducts = @(
    'x3_voice_ledger_watch',
    'x3_voice_ledger_phone',
    'x3_wolai_notes',
    'x3_offline_reader',
    'x3_schedule_phone',
    'x3_schedule_watch',
    'windows_audiobook_builder'
)
foreach ($product in $expectedPublicProducts) {
    if ($product -notin $config.publicProducts) {
        throw "Required WARS public product is missing: $product"
    }
}
$schedule = $config.productSpecificControls.x3Schedule
if ($schedule.publicArtifacts.Count -ne 2 -or
        'x3_schedule_phone' -notin $schedule.publicArtifacts -or
        'x3_schedule_watch' -notin $schedule.publicArtifacts -or
        $schedule.debugToReleaseMigration -ne
            'verified_backup_then_explicit_uninstall_then_release_install_restore_and_reauthorize' -or
        $schedule.debugToReleaseInPlaceUpgradeAllowed -or
        $schedule.debugCertificateLineageAllowed -or
        $schedule.validationArtifactsAreReleaseArtifacts -or
        -not $schedule.releaseToReleaseUpgradeRequiredAfterInitialRelease -or
        'wolai_oauth' -notin $schedule.notApplicableProductionControls -or
        'windows_authenticode' -notin $schedule.notApplicableProductionControls) {
    throw 'X3Schedule release-artifact, signing-migration, or applicability controls are incomplete.'
}
$preview = $config.distributionTracks.personalGithubPreview
$token = $preview.wolaiPersonalToken
$portable = $preview.windowsPortable
$formal = $config.distributionTracks.formalPublicProduction
if ($preview.verdict -ne 'LIMITED_PREVIEW' -or
        -not $preview.androidReleaseSigningRequired -or
        -not $token.allowed -or -not $token.defaultUnconfigured -or
        -not $token.explicitRiskDisclosureRequired -or
        -not $token.encryptedLocalStorageRequired -or
        -not $token.excludedFromApkLogsAndBackupsRequired -or
        -not $token.localClearRequired -or -not $token.notOAuthDisclosureRequired -or
        -not $portable.unsignedAllowed -or
        -not $portable.installerForbiddenWithoutAuthenticode -or
        $portable.artifactFormat -ne 'portable_zip' -or
        -not $portable.unknownPublisherDisclosureRequired -or
        -not $portable.sha256Required -or -not $portable.sourceCommitRequired -or
        -not $formal.verdictRequiresAllProductionControls -or
        -not $formal.wolaiOAuthRequired -or -not $formal.windowsAuthenticodeRequired) {
    throw 'WARS preview/production track boundary is incomplete or weakened.'
}

$document = Get-Content -LiteralPath $documentPath -Raw
foreach ($requiredText in @(
        '版本：1.1.0',
        '个人 GitHub 预览版',
        '同一把独立 Release Key',
        'X3Schedule',
        '六个 Android',
        '正式 OAuth',
        '默认关闭、单独授权',
        'Authenticode')) {
    if ($document -notmatch [regex]::Escape($requiredText)) {
        throw "WARS document is missing required text: $requiredText"
    }
}

$readme = Get-Content -LiteralPath $readmePath -Raw
foreach ($requiredText in @(
        'X3Schedule',
        'LIMITED_PREVIEW',
        '保留所有权利',
        'SECURITY.md')) {
    if ($readme -notmatch [regex]::Escape($requiredText)) {
        throw "Public repository README is missing required disclosure: $requiredText"
    }
}
$security = Get-Content -LiteralPath $securityPath -Raw
foreach ($requiredText in @('Private vulnerability reporting', 'LIMITED_PREVIEW')) {
    if ($security -notmatch [regex]::Escape($requiredText)) {
        throw "Security policy is missing required disclosure: $requiredText"
    }
}
$contributing = Get-Content -LiteralPath $contributingPath -Raw
if ($contributing -notmatch [regex]::Escape('暂不接收外部代码贡献')) {
    throw 'Contribution policy must preserve the no-license boundary.'
}
$sourceStatus = Get-Content -LiteralPath $sourceStatusPath -Raw
foreach ($requiredText in @(
        'PASS — PUBLIC SOURCE READY',
        '不等同于每个二进制产品',
        '没有统一开源许可证')) {
    if ($sourceStatus -notmatch [regex]::Escape($requiredText)) {
        throw "Public source status is missing required boundary: $requiredText"
    }
}
$checklist = Get-Content -LiteralPath $checklistPath -Raw
foreach ($requiredText in @(
        '日程手表端',
        '日程手机端',
        'Debug→Release',
        'Release→Release',
        'Wolai OAuth 与 Windows Authenticode 已登记为不适用')) {
    if ($checklist -notmatch [regex]::Escape($requiredText)) {
        throw "Production acceptance checklist is missing X3Schedule control: $requiredText"
    }
}

$formalPackage = Get-Content -LiteralPath (
    Join-Path $resolvedRoot 'X3OfflineReader\builder\package-windows.ps1') -Raw
foreach ($requiredText in @(
        'X3_WINDOWS_SIGNING_CERT_SHA256',
        'X3_WINDOWS_TIMESTAMP_URL',
        'verify-authenticode.ps1',
        'TimeStamperCertificate')) {
    if ($formalPackage -notmatch [regex]::Escape($requiredText)) {
        throw "Formal Windows packaging lost a required Authenticode control: $requiredText"
    }
}

$previewPackage = Get-Content -LiteralPath (
    Join-Path $resolvedRoot 'X3OfflineReader\builder\package-windows-preview.ps1') -Raw
foreach ($requiredText in @(
        'windows-preview',
        'UNSIGNED-PREVIEW',
        'status --porcelain -- X3OfflineReader',
        'SignatureStatus]::NotSigned',
        '.sha256',
        'LIMITED_PREVIEW')) {
    if ($previewPackage -notmatch [regex]::Escape($requiredText)) {
        throw "Windows preview packaging lost a required boundary: $requiredText"
    }
}
if ($previewPackage -match '--type\s+(exe|msi)') {
    throw 'Windows preview packaging must not generate an installer.'
}

Write-Host 'PUBLIC_RELEASE_STANDARD_OK id=WARS version=1.1.0 decisions=6 tracks=2'
