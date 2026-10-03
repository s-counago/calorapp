# Sure local

Despliegue de Sure 0.7.4 en Docker Desktop. La imagen de Sure está fijada por versión y digest. PostgreSQL 16 y Redis 7.4 mantienen ramas de versión compatibles.

Dirección: https://localhost. Instalar primero la [CA local](HTTPS.md) en cada dispositivo.

Desplegado el 21 de septiembre y verificado de nuevo el 23 de septiembre de 2026: web, Caddy, PostgreSQL, Redis y túnel saludables; Sidekiq iniciado. HTTPS ya funciona en el servidor. Falta instalar la CA en Android y confirmar su importación en Windows. Enable Banking está configurado con España y credenciales cifradas; falta registrar el callback HTTPS en su portal y autorizar el banco. Ver [el diagnóstico y los pasos pendientes](ENABLE-BANKING.md).

Docker Desktop sigue en 4.49.0 tras recuperar un fallo de arranque. Está pendiente aceptar el aviso de administrador para instalar 4.91.0 y verificar un reinicio. El diagnóstico, la copia de seguridad y el instalador están documentados en [la reparación de Docker](../docs/docker-reparacion-2026-09-21.md).

El puerto solo escucha en `127.0.0.1`. Todavía no hay sesiones bancarias ni servicios de IA configurados.

El [acceso privado con Cloudflare One/WARP](REMOTE.md) está configurado en Zero Trust Free para `s.counago00@gmail.com`. Dirección desde un dispositivo conectado: `https://sure.pausa.internal`. Queda pendiente la prueba real desde Android.

## Arrancar

Abre Docker Desktop y ejecuta desde PowerShell:

```powershell
Set-Location C:\Users\Sejio\calorapp\sure-local
./start.ps1
```

`start.ps1` inicia Docker Desktop, arranca Sure y el túnel privado y espera a que estén disponibles. `Abrir Sure.url` abre la dirección local en el navegador una vez arrancado el servidor.

Abre https://localhost cuando los servicios hayan terminado de arrancar y Windows confíe en el certificado. Docker debe permanecer activo; puedes cerrar la pestaña de Sure sin detener las sincronizaciones futuras. Utilizar siempre los dos archivos Compose, porque Caddy se define en `compose.remote.yml`.

## Parar y consultar el estado

```powershell
Set-Location C:\Users\Sejio\calorapp\sure-local
./stop.ps1
docker compose -f compose.yml -f compose.remote.yml ps
```

`stop` conserva los datos. Los servicios usan `restart: unless-stopped`; si se detienen expresamente, hay que volver a ejecutar `up -d`.

Para consultar errores de arranque:

```powershell
docker compose logs --tail 80 web worker
```

No compartas los logs sin revisar si contienen información financiera o cabeceras de sesión. No ejecutes `docker compose config` sin `--quiet`, porque la salida completa incluye secretos interpolados.

## Secretos y datos

`.env` contiene claves aleatorias propias para la aplicación, el cifrado de credenciales y PostgreSQL. Está excluido de Git y su ACL permite acceso al usuario de Windows que creó el despliegue y a SYSTEM.

Los datos se guardan en volúmenes Docker con prefijo `pausa-sure`: `postgres-data`, `redis-data`, `app-storage`, `caddy-data` y `caddy-config`. La CA y sus claves están en `caddy-data`. Parar o recrear contenedores conserva esos volúmenes. No uses `docker compose down -v` si quieres conservarlos.

Antes de conectar bancos, crea una copia recuperable de PostgreSQL, de `app-storage` y de `.env`. Las claves deben conservarse para descifrar credenciales ya guardadas; no las regeneres para una actualización. Protege las copias y guarda una fuera del disco del PC.

## Actualizaciones

La versión de Sure no cambia al ejecutar `pull`, porque está fijada por digest. Para actualizarla, revisa la nueva release estable, haz una copia, revisa o retira los dos montajes del parche de Enable Banking, cambia la referencia de imagen en `web` y `worker`, y vuelve a arrancar. No sustituyas la imagen por `latest`, que sigue las versiones alpha.

## Conexiones pendientes

ABANCA aparece en el catálogo real de Enable Banking, marcada como beta. La aplicación de Enable Banking está activa y configurada en Sure, pero todavía no hay consentimiento bancario ni movimientos importados. El [callback HTTPS](HTTPS.md) está listo en Sure y pendiente de registrar en Enable Banking. La importación local de Trade Republic se evaluará por separado. El acceso móvil usa WARP.

## Referencias

- [Guía oficial de Docker](https://github.com/we-promise/sure/blob/v0.7.4/docs/hosting/docker.md)
- [Release 0.7.4](https://github.com/we-promise/sure/releases/tag/v0.7.4)
