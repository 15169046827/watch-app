param(
    [string]$Root = (Split-Path -Parent $PSScriptRoot)
)

$ErrorActionPreference = 'Stop'
$resolvedRoot = (Resolve-Path -LiteralPath $Root).Path
$configPath = Join-Path $resolvedRoot 'config\audit\watch-app-audit-profile.json'
$profilePath = Join-Path $resolvedRoot 'docs\audit\WATCH_APP_AUDIT_PROFILE.md'

foreach ($path in @($configPath, $profilePath)) {
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Required watch-app audit profile asset is missing: $path"
    }
}

$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
if ($config.profileId -ne 'WATCH-APP-UPARS' -or $config.version -notmatch '^\d+\.\d+\.\d+$') {
    throw 'Watch-app audit profile identity or semantic version is invalid.'
}
if ($config.baseStandard.id -ne 'UPARS' -or
        $config.baseStandard.version -notmatch '^\d+\.\d+\.\d+$' -or
        $config.baseStandard.codexSkill -ne 'universal-project-audit') {
    throw 'The UPARS base-standard declaration is invalid.'
}
if (-not $config.mayTightenBaseStandard -or $config.maySilentlyWeakenBaseStandard) {
    throw 'The project profile must be additive and must not silently weaken UPARS.'
}
if ($config.standardTracks.auditAndRemediation -ne 'WACAS 1.1' -or
        $config.standardTracks.publicReleaseStandardization -ne 'WARS 1.1' -or
        -not $config.standardTracks.parallelWithSeparateVerdicts -or
        $config.standardTracks.releaseChangesMayEnterIncompleteAuditWork -or
        -not $config.standardTracks.standardChangesOwnedByCoordinationTask) {
    throw 'The WACAS/WARS coordination boundary is invalid.'
}
if ($config.usageGate.doNotStartNewModificationAtOrBelowRemainingPercent -ne 5 -or
        -not $config.usageGate.checkBeforeIntensivePhase -or
        -not $config.usageGate.checkAfterMaterialMilestone -or
        -not $config.usageGate.recoverableCheckpointRequiredAtThreshold -or
        -not $config.usageGate.usageResetRequiresExplicitUserAuthorization -or
        -not $config.usageGate.mustPropagateToProductTasks) {
    throw 'The mandatory Watch App usage gate is invalid.'
}
$expectedProjects = @(
    'shared_probe',
    'voice_ledger',
    'wolai_notes',
    'x3_offline_reader',
    'x3_schedule',
    'windows_audiobook_builder'
)
foreach ($project in $expectedProjects) {
    if ($project -notin $config.projects) {
        throw "Required watch-app project is missing: $project"
    }
}
$expectedPublicProducts = @(
    'x3_voice_ledger',
    'x3_wolai_notes',
    'x3_offline_reader',
    'x3_schedule',
    'windows_audiobook_builder'
)
foreach ($product in $expectedPublicProducts) {
    if ($product -notin $config.productClassification.publicProducts) {
        throw "Required public-product classification is missing: $product"
    }
}
if ('x3_accessibility_probe' -notin $config.productClassification.internalDiagnosticTools) {
    throw 'X3AccessibilityProbe must remain classified as an internal diagnostic tool.'
}
if ('x3_schedule' -notin $config.auditOrder) {
    throw 'X3Schedule must remain in the coordinated audit order.'
}

$requiredRiskAreas = @(
    'round_screen_and_wear_input',
    'android_lifecycle_and_resources',
    'offline_and_explicit_network_consent',
    'permissions_and_exported_components',
    'data_import_export_and_recovery',
    'battery_memory_storage_and_background_work',
    'device_builder_package_compatibility',
    'signing_install_upgrade_and_rollback'
)
foreach ($riskArea in $requiredRiskAreas) {
    if ($riskArea -notin $config.requiredRiskAreas) {
        throw "Required watch-app risk area is missing: $riskArea"
    }
}

$requiredRepositoryGates = @(
    'code_conventions',
    'unit_tests',
    'android_lint',
    'debug_build',
    'production_build',
    'network_safety_positive_and_negative',
    'public_release_standard_positive_and_negative',
    'signature_identity_when_applicable'
)
foreach ($gate in $requiredRepositoryGates) {
    if ($gate -notin $config.requiredRepositoryGates) {
        throw "Required repository gate is missing: $gate"
    }
}
if ('windows_authenticode_verification' -notin $config.productionReleaseRequires) {
    throw 'Windows Authenticode verification must remain a production-release requirement.'
}
$profile = Get-Content -LiteralPath $profilePath -Raw
if ($profile -notmatch [regex]::Escape("版本：$($config.version)")) {
    throw 'The profile document version does not match its machine-readable version.'
}
if ($profile -notmatch [regex]::Escape("UPARS $($config.baseStandard.version)")) {
    throw 'The profile document does not match its declared UPARS version.'
}
if ($profile -notmatch [regex]::Escape('X3Schedule')) {
    throw 'The profile document is missing X3Schedule.'
}

Write-Host (
    "WATCH_APP_AUDIT_PROFILE_OK id={0} version={1} base={2}@{3} riskAreas={4}" -f
    $config.profileId,
    $config.version,
    $config.baseStandard.id,
    $config.baseStandard.version,
    $config.requiredRiskAreas.Count)
