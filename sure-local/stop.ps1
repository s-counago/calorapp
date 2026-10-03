$ErrorActionPreference = 'Stop'

Push-Location $PSScriptRoot
try {
    docker compose -f compose.yml -f compose.remote.yml stop
    if ($LASTEXITCODE -ne 0) {
        throw 'Sure and its tunnel could not be stopped completely. Check docker compose ps.'
    }

    Write-Output 'Sure and its Cloudflare tunnel are stopped. Data volumes are preserved.'
}
finally {
    Pop-Location
}
