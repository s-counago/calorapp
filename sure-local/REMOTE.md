# Acceso remoto privado a Sure

## Estado del 23 de septiembre de 2026

Zero Trust Free está activo. Sure, Caddy y el túnel `pausa-sure-local` están funcionando. El origen HTTPS responde 200 con validación del certificado desde la red del conector. Falta instalar la CA en Android y completar su prueba de acceso, incluyendo comprobar que una conexión nueva falla al desconectar WARP. [Guía de HTTPS y certificado](HTTPS.md).

- Organización: `young-sound-21c2`.
- Único correo permitido: `s.counago00@gmail.com`.
- Dirección remota: `https://sure.pausa.internal`.
- Dirección local: `https://localhost`.
- Aplicación Access: `Sure privado`.
- Política compartida de inscripción y acceso: `Sure - acceso personal`, Allow con Include Emails del correo exacto.
- Identidad: cuenta Cloudflare existente; no se ha habilitado One-Time PIN.
- Autenticación mediante Cloudflare One Client activada para Sure, con sesión de 24 horas.

El plan mostrado en el panel es Free. Esta configuración usa un usuario, Cloudflare Tunnel, Access y WARP, sin dominio comprado ni complementos de pago. El coste esperado de Cloudflare para este uso es 0; el PC y su conexión a Internet siguen siendo necesarios.

## Conectar Android

1. Instalar [Cloudflare One Agent desde Google Play](https://play.google.com/store/apps/details?id=com.cloudflare.cloudflareoneagent).
2. Abrir la aplicación e introducir `young-sound-21c2` como organización.
3. Iniciar sesión con la cuenta Cloudflare de `s.counago00@gmail.com` y completar su autenticación habitual.
4. Instalar el perfil VPN cuando lo solicite la aplicación y aceptar la solicitud de conexión de Android.
5. Activar el interruptor hasta ver el estado conectado.
6. Abrir `http://sure.pausa.internal/instalar-certificado` e instalar la CA siguiendo la guía.
7. Abrir `https://sure.pausa.internal` en el navegador, sin puerto 3000.

La cuenta de Sure es independiente de la cuenta Cloudflare; el usuario ya creó su cuenta de Sure. Ahora hay TLS entre el navegador y Caddy dentro de la conectividad de WARP y Tunnel. La inspección TLS de Cloudflare permanece desactivada. Para conectar ABANCA, revisar también [las direcciones de retorno pendientes](ENABLE-BANKING.md).

Para probar el acceso remoto, usar datos móviles y dejar el PC encendido, conectado y sin suspensión. Tras comprobar la carga autenticada, desconectar One Agent y comprobar que una petición nueva no llega al servidor. Una página ya cargada puede seguir visible por caché. La denegación a otra identidad todavía no se ha probado con una sesión real.

## Alcance de red

Solo se ha creado la ruta exacta `sure.pausa.internal`, sin rutas CIDR a la LAN. Access protege el hostname en todos sus puertos; Caddy recibe HTTPS en el 443 y sirve el certificado público en HTTP. El ingreso público del túnel conserva únicamente `http_status:404`. No se ha creado un hostname público ni abierto puertos del router.

`compose.remote.yml` conecta `cloudflared` y Caddy a `sure-access`. El alias Docker de Caddy es `sure.pausa.internal`; el conector lo resuelve mediante el DNS de Docker. Caddy se comunica con `web` por `sure-origin`, una red interna. PostgreSQL y Redis permanecen en otra red. En Windows, Caddy publica 80, 443 y 3000 solo en `127.0.0.1`; `web` no publica puertos.

El perfil WARP predeterminado usa Traffic and DNS, MASQUE y Split Tunnels en modo Include con estas entradas:

- `sure.pausa.internal`
- `young-sound-21c2.cloudflareaccess.com`
- `dash.cloudflare.com`
- `172.64.128.0/20`
- `2606:4700:0cf1:4000::/64`

Se retiró únicamente `internal` de Local Domain Fallback. Gateway tiene proxy TCP y UDP activo y descifrado TLS desactivado. La resolución DNS del dispositivo puede pasar por Gateway; el modo Include limita el tráfico IP a sus destinos configurados y a los destinos que incluya automáticamente el cliente.

El campo antiguo `warp-routing.enabled` de la API puede aparecer como `false`: cloudflared eliminó ese interruptor en 2023.9.0. La presencia de rutas, las políticas y el conector determinan la conectividad; ese campo no sirve para comprobar ni bloquear el acceso.

## Operación local

```powershell
Set-Location C:\Users\Sejio\calorapp\sure-local
./start.ps1
docker compose -f compose.yml -f compose.remote.yml ps
```

`start.ps1` inicia Docker Desktop, Sure y el túnel y espera sus comprobaciones de salud. Para detenerlos conservando los datos:

```powershell
./stop.ps1
```

Para cerrar solo el acceso remoto:

```powershell
docker compose -f compose.yml -f compose.remote.yml stop cloudflared
```

Para volver a abrirlo:

```powershell
docker compose -f compose.yml -f compose.remote.yml up -d --wait cloudflared
```

Los servicios usan `restart: unless-stopped`. Una parada expresa requiere un nuevo arranque. El PC, Docker y Sure deben permanecer activos; cerrar la pestaña del navegador no los detiene.

El token del conector está en `secrets/cloudflare-tunnel-token`, excluido de Git, con ACL para el usuario local y SYSTEM. Se monta como secreto de Docker Compose. `cloudflare-state.json` guarda identificadores y estado sin credenciales. No mostrar `.env`, el token ni la salida completa de `docker compose config`; validar con `--quiet`.

## Comprobaciones y pendientes

- Contenedores web, Caddy, PostgreSQL, Redis y cloudflared saludables; worker funcionando.
- `/up` y `/sessions/new` devuelven 200 por HTTPS desde un contenedor temporal con la misma red que cloudflared, validando la CA local y el hostname.
- Túnel remoto healthy, única ruta de hostname guardada y cero rutas CIDR.
- Aplicación Access guardada con el hostname exacto, política personal y autenticación WARP activa.
- Pendiente: prueba real desde Android y prueba negativa sin conexión autenticada.
- Ya existe el usuario y están guardadas las credenciales de Enable Banking; pendiente registrar el callback HTTPS y autorizar ABANCA.

Docker Desktop sigue en 4.49.0. El fallo recurrente de arranque se resolvió deteniendo Docker y su distribución WSL, apartando dos directorios de sockets de ejecución vacíos y reiniciando. Se conservaron en `C:\Users\Sejio\AppData\Local\Docker\run.stale-20260922-143741` y `C:\Users\Sejio\AppData\Local\docker-secrets-engine.stale-20260922-143741`. No se tocaron volúmenes ni copias. La actualización a 4.91.0 sigue pendiente de la intervención de administrador documentada anteriormente.

## Referencias

- [Cloudflare One: instalación manual en Android](https://developers.cloudflare.com/cloudflare-one/team-and-resources/devices/cloudflare-one-client/deployment/manual-deployment/)
- [Conectar un hostname privado](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/private-net/cloudflared/connect-private-hostname/)
- [Proteger aplicaciones privadas con Access](https://developers.cloudflare.com/cloudflare-one/access-controls/applications/non-http/self-hosted-private-app/)
- [Sesiones del cliente](https://developers.cloudflare.com/cloudflare-one/team-and-resources/devices/cloudflare-one-client/configure/client-sessions/)
- [Cambios de cloudflared](https://github.com/cloudflare/cloudflared/blob/master/CHANGES.md)
- [Planes de Cloudflare](https://www.cloudflare.com/plans/)
