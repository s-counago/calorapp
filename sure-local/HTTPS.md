# HTTPS privado de Sure

Desplegado el 23 de septiembre de 2026. Caddy 2.11.4, fijado por digest, sirve Sure por TLS dentro del acceso privado existente. No se ha comprado un dominio ni activado un producto de pago de Cloudflare. No se han creado rutas públicas ni ampliado los usuarios autorizados.

## Direcciones

- Android con Cloudflare One: `https://sure.pausa.internal`.
- PC: `https://localhost`.
- Instalación inicial en Android: `http://sure.pausa.internal/instalar-certificado`.
- Certificado público: `http://sure.pausa.internal/sure-pausa-root-ca.crt`.

Usar las nuevas direcciones sin `:3000`. HTTP en los puertos 80 y 3000 solo sirve la guía y el certificado público; el resto redirige a HTTPS. Tras visitar HTTPS, HSTS puede hacer que el navegador actualice las antiguas URLs con puerto 3000 de manera incompatible: actualizar los favoritos a la dirección nueva.

## Paso manual en Android

1. Activar Cloudflare One y abrir la página de instalación inicial.
2. Descargar `sure-pausa-root-ca.crt`.
3. En Ajustes, buscar **Instalar un certificado**, elegir **Certificado de CA**, confirmar la advertencia de confianza y seleccionar el archivo descargado. El menú puede estar dentro de Seguridad y privacidad → Más ajustes de seguridad → Cifrado y credenciales.
4. Comprobar el nombre **Sure Pausa - Local Root 2026** y confirmar con el PIN del teléfono.
5. Abrir `https://sure.pausa.internal` en el navegador e iniciar sesión en Sure. No saltarse un aviso de certificado: si aparece, revisar que la CA se haya instalado y que One esté conectado.

Añadir esta CA permite al dispositivo confiar en los certificados que firme la autoridad local. La clave de firma permanece en el PC. Solo se distribuye el certificado público; no instalar las claves de Enable Banking ni los archivos privados de Caddy en el móvil.

Huella SHA-256 del certificado raíz:

```text
0E:F4:16:2C:79:57:AA:7C:78:EF:FD:3B:D5:6B:E3:90:1F:CE:92:8B:EE:C1:F0:9A:AC:B7:0D:63:0E:84:CF:4D
```

Caduca el 1 de agosto de 2036 a las 13:02:46 UTC. Caddy renueva automáticamente los certificados de servicio y el intermedio mientras conserva su volumen de datos.

## Paso manual pendiente en Windows

La importación al almacén del usuario mostró una confirmación interactiva. Se interrumpió al estar el usuario fuera de casa; no hay procesos de importación pendientes ni se ha instalado la raíz en Windows.

Cuando el usuario esté delante del PC, ejecutar desde `sure-local`:

```powershell
./https/install-windows-certificate.ps1
```

El script comprueba la huella exacta antes de importar. Confirmar el aviso de Windows para **Sure Pausa - Local Root 2026**. La instalación solo afecta al usuario actual, no al almacén de todos los usuarios del equipo. Después abrir `https://localhost`.

## Enable Banking

Las nuevas URLs generadas por Sure y verificadas en las pruebas son:

```text
https://sure.pausa.internal/enable_banking_items/callback
https://localhost/enable_banking_items/callback
```

La API del proveedor seguía devolviendo únicamente `https://localhost:3000/eb-callback` al terminar la configuración del servidor. Falta editar `Sure finance` en [Enable Banking](https://enablebanking.com/cp/applications), guardar las direcciones nuevas y completar el consentimiento de ABANCA. El navegador no está disponible para la herramienta; este paso puede hacerse desde Android. Todavía no hay sesión bancaria ni movimientos importados.

## Configuración y aislamiento

El alias Docker `sure.pausa.internal` corresponde ahora a Caddy. `cloudflared` y Caddy comparten únicamente `sure-access`; Caddy y Sure comparten `sure-origin`, una red Docker interna. PostgreSQL y Redis permanecen en la red de la aplicación y no comparten red con Caddy ni con el túnel.

Caddy publica los puertos 80, 443 y 3000 únicamente en `127.0.0.1`. Sure ya no publica un puerto en Windows. La API administrativa de Caddy está deshabilitada. Su comprobación de salud usa un listener accesible únicamente dentro del propio contenedor y comprueba también el servidor Rails.

Rails tiene `RAILS_FORCE_SSL=true` y `RAILS_ASSUME_SSL=true` al estar detrás del proxy TLS. Se verificaron cookies de sesión con `Secure`. No se activa inspección TLS de Cloudflare. Los datos de certificado se conservan en `pausa-sure_caddy-data` y la configuración persistente en `pausa-sure_caddy-config`.

## Comprobaciones

- Configuración de Compose y Caddy válida; todos los servicios necesarios saludables.
- Peticiones a `https://sure.pausa.internal/up` y `/sessions/new` desde la misma red que el conector: HTTP 200 con validación de cadena, hostname y caducidad. Se utilizó explícitamente la raíz local, sin desactivar la comprobación TLS.
- Archivo descargado por HTTP idéntico al certificado público generado; tipo MIME y nombre de descarga correctos.
- Redirección HTTP del origen anterior a HTTPS sin puerto 3000.
- Treinta comprobaciones de creación y edición de Enable Banking superadas con URLs HTTPS en una base temporal con datos ficticios, eliminada al finalizar.
- Separación de redes comprobada. Pendiente el recorrido completo desde Android y la prueba de denegación con WARP desconectado.

`curl.exe` de Windows con Schannel y CA explícita devolvió estado de revocación desconocido para esta CA local; no se usó ese resultado como prueba de éxito. La verificación TLS positiva se hizo con OpenSSL desde la red del conector. La confianza global en Windows sigue pendiente del paso manual.

## Copias y recuperación

Configuración anterior: `backups/before-https-20260923-150244/`. Copia privada de la PKI de Caddy: `backups/caddy-pki-20260923.tar.gz`, que contiene claves privadas y queda excluida de Git, bajo los permisos de la carpeta de copias. Mantener esa copia protegida y no distribuirla. No borrar `caddy-data`: regeneraría la autoridad y obligaría a instalar otro certificado en cada dispositivo.

Para revertir el cambio de servidor, detener primero Caddy con la configuración actual mediante `docker compose -f compose.yml -f compose.remote.yml stop caddy`, para liberar sus puertos. Después restaurar `compose.yml`, `compose.remote.yml`, `start.ps1` y `Abrir Sure.url` desde la copia de configuración y ejecutar Compose con ambos archivos. Conservar los volúmenes. La reversión devuelve HTTP y el callback de Enable Banking dejaría de poder completarse. Los navegadores que ya recuerden HSTS pueden necesitar limpiar la política de este hostname o usar un perfil limpio.

Referencias: [Caddy y HTTPS local](https://caddyserver.com/docs/automatic-https#local-https), [certificados en Android](https://developers.cloudflare.com/cloudflare-one/team-and-resources/devices/user-side-certificates/manual-deployment/#android), [hostnames privados de Cloudflare](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/private-net/cloudflared/connect-private-hostname/).
