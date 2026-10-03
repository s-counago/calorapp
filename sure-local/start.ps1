$ErrorActionPreference = 'Stop'

Push-Location $PSScriptRoot
try {
    docker desktop start --timeout 60
    if ($LASTEXITCODE -ne 0) {
        throw 'Docker Desktop could not start. Check its window before retrying.'
    }

    docker compose -f compose.yml -f compose.remote.yml up -d --wait --wait-timeout 180
    if ($LASTEXITCODE -ne 0) {
        throw 'Sure could not start. Run docker compose logs --tail 80 web worker.'
    }

    Write-Output 'Sure is ready at https://localhost'
    Write-Output 'With Cloudflare One connected: https://sure.pausa.internal'
    Write-Output 'Android certificate setup: http://sure.pausa.internal/instalar-certificado'
}
finally {
    Pop-Location
}
