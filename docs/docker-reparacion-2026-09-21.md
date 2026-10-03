# Reparación de Docker Desktop — 21 de septiembre de 2026

Docker Desktop 4.49.0 fallaba durante el arranque en Windows 11, antes de iniciar el motor de contenedores. El registro mostraba errores al eliminar los sockets `Docker\run\dockerInference` y `docker-secrets-engine\engine.sock`, dentro de `%LOCALAPPDATA%`.

Los dos archivos eran puntos de reanálisis de cero bytes que Windows no podía abrir. El fallo coincide con [la incidencia 554 de Docker](https://github.com/docker/desktop-feedback/issues/554), asociada a sockets que quedan atascados tras un cierre brusco. No se ha determinado qué cierre concreto lo originó en este equipo.

Con Docker detenido se renombraron únicamente las carpetas temporales afectadas, conservando su contenido. Docker las regeneró al arrancar y el motor volvió a responder: cliente y servidor 28.5.1. Los contenedores y volúmenes anteriores de Maybe y Subtitula seguían presentes y detenidos.

Carpetas conservadas:

- `%LOCALAPPDATA%\Docker\run.stale-20260921`
- `%LOCALAPPDATA%\Docker\run.stale-20260921-143845`
- `%LOCALAPPDATA%\docker-secrets-engine.stale-20260921`

No se ha restablecido Docker a valores de fábrica ni eliminado sus imágenes o volúmenes.

## Actualización disponible

Las [notas oficiales](https://docs.docker.com/desktop/release-notes/) indican que 4.89.0 y 4.90.0 corrigen el fallo de arranque por sockets atascados. La última versión comprobada es 4.91.0, publicada el 14 de septiembre de 2026.

Instalador descargado: `C:\Users\Sejio\Downloads\Docker-4.91.0\Docker Desktop Installer.exe`.

- Versión del archivo: `4.91.0.239619`.
- Firma Authenticode válida de Docker Inc.
- SHA-256 verificado frente al checksum oficial: `ac405b09942701770d581b173747fc1024cf0e6047cbe60f13d1df85437311ac`.

La instalación actual es para todos los usuarios, en `C:\Program Files\Docker\Docker`. Según la [documentación de instalación](https://docs.docker.com/desktop/setup/install/windows-install/), actualizar este modo requiere privilegios de administrador. La sesión de trabajo no está elevada.

El actualizador integrado descargó 4.91.0 y lanzó el instalador, cuyo registro indicó que necesitaba relanzarse con UAC. La actualización no se completó: requiere que el usuario confirme el aviso de Windows. Se interrumpió el proceso de instalación no elevado que estaba esperando. La versión instalada sigue siendo 4.49.0.

## Copia de seguridad y comprobación de reinicio

Antes del intento de actualización, con Docker detenido, se copiaron los discos `docker_data.vhdx` y `main-ext4.vhdx`, además de `settings-store.json`, a:

`C:\Users\Sejio\AppData\Local\DockerRecovery\20260921-before-update`

La carpeta tiene permisos para el usuario actual y SYSTEM. La copia ocupa unos 20,6 GB y se verificaron los tamaños de los discos copiados. No sustituye a una copia externa al PC.

La prueba de cierre y arranque normal reprodujo el error de sockets en 4.49.0. Después de regenerar las carpetas temporales, el entorno Linux interno también quedó esperando al motor. Se detuvo Docker con su CLI, se terminó únicamente la distribución `docker-desktop` de WSL, se apartaron los sockets temporales y se volvió a arrancar. El motor 28.5.1 respondió de nuevo correctamente.

Se conservaron además las carpetas temporales con sufijos `stale-20260921-144425`, `run.stale-20260921-144754` y `docker-secrets-engine.stale-20260921-144755`. No contienen volúmenes de aplicaciones.

La recuperación actual es provisional. El siguiente paso para comprobar una reparación duradera es completar la actualización a 4.91.0 y repetir la prueba de reinicio. Para actualizar, ejecutar el instalador verificado de Descargas y aceptar el aviso de administrador de Windows; no desinstalar ni restablecer Docker.

## Resultado del despliegue de Sure

Sure 0.7.4 quedó desplegado en el proyecto Compose `pausa-sure`, con sus propios volúmenes. Web, PostgreSQL y Redis superaron sus comprobaciones de salud, y Sidekiq inició correctamente conectado a Redis. `http://localhost:3000/up` devuelve HTTP 200 y la página principal redirige a `/registration/new`, que también responde HTTP 200 y muestra el formulario del primer usuario. La tabla de usuarios estaba vacía al terminar.

Solo se publica `127.0.0.1:3000`. Los contenedores anteriores de Maybe y Subtitula permanecen detenidos, sin cambios. La configuración y las instrucciones de arranque están en [sure-local](../sure-local/README.md).

## Reaparición el 23 de septiembre de 2026

Docker 4.49.0 volvió a bloquearse al iniciar por sockets temporales `dockerInference` y `engine.sock`. Se detuvo con `docker desktop stop --force --timeout 30`, se terminó únicamente `docker-desktop` en WSL y se verificó que las carpetas temporales contenían enlaces de sockets sin datos de aplicaciones. Se apartaron mediante rutas absolutas comprobadas y se dejó que Docker las recreara en el siguiente arranque.

Se conservaron carpetas con sufijos `stale-20260923-083850` y `stale-20260923-084023` en las ubicaciones originales de `run` y `docker-secrets-engine`. No se tocaron los discos de datos ni los volúmenes. El segundo arranque respondió correctamente y Sure, PostgreSQL, Redis y el túnel quedaron saludables. La actualización de Docker sigue pendiente; esta recuperación no demuestra una solución duradera.
