$ErrorActionPreference = 'Stop'

$certificatePath = Join-Path $PSScriptRoot 'public\sure-pausa-root-ca.crt'
$certificate = [System.Security.Cryptography.X509Certificates.X509Certificate2]::new($certificatePath)
$expectedFingerprint = '0EF4162C7957AA7C78EFFD3BD56BE3901FCE928BEEC1F09AACB70D630E84CF4D'
$actualFingerprint = $certificate.GetCertHashString([System.Security.Cryptography.HashAlgorithmName]::SHA256)

if ($actualFingerprint -ne $expectedFingerprint) {
    throw 'The certificate does not match the verified Sure root certificate.'
}

$storePath = 'Cert:\CurrentUser\Root\' + $certificate.Thumbprint
if (Test-Path -LiteralPath $storePath) {
    Write-Output 'The Sure certificate is already trusted for this Windows user.'
    exit 0
}

Write-Output 'Windows will ask you to trust Sure Pausa - Local Root 2026. Verify that name and confirm the dialog.'
certutil.exe -user -addstore Root $certificatePath
if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $storePath)) {
    throw 'Windows did not install the certificate. HTTPS is ready, but this device still needs to trust it.'
}

Write-Output 'Certificate installed. Open https://localhost in your browser.'
