# Muestra de las consultas de ABANCA

Objetivo: documentar las consultas necesarias para sincronizar cuentas y movimientos
desde Pausa. La captura de texto de la web existente es útil para lectura local,
pero no contiene el protocolo HTTP. Pausa no registra tráfico de ABANCA actualmente.

## Una inspección manual en el ordenador

1. Entrar en la web oficial de ABANCA con Chrome/Edge y completar el segundo factor.
   Las credenciales se introducen en el banco, no se comparten en el chat.
2. Abrir las herramientas de desarrollador, pestaña **Network / Red**. Después del
   login, limpiar el listado y activar **Preserve log / Conservar registro** para
   conservar la navegación entre páginas durante esta captura puntual.
   Esto reduce lo que aparece; no elimina cookies/cabeceras de las peticiones nuevas.
3. Seleccionar **All / Todo** y quitar los filtros de texto, abrir una cuenta y
   consultar sus movimientos como de costumbre. Incluir las peticiones **Doc**:
   los datos pueden llegar en HTML, no necesariamente en Fetch/XHR. No hacer
   transferencias ni otras operaciones para esta comprobación.
4. Localizar una respuesta que contenga los datos que aparecen en pantalla. Si el
   listado está vacío, cambiar de sección normalmente una vez; no repetir logins ni
   hacer un barrido de URLs. La web podría usar otro transporte o HTML; anotar ese
   resultado en vez de asumir que utiliza una API JSON.
5. Revisar **Headers → General**, **Payload** y **Preview/Response**. Preparar el
   resumen de abajo, editado manualmente, con una o dos filas ficticias que mantengan
   los nombres de campos y los tipos. Compartir el resumen pegándolo en el chat o
   adjuntando un `.txt`/`.json`. No hace falta el listado entero de la cuenta.

No compartir un HAR completo, «Copy as cURL», cookies, cabeceras de autorización,
tokens CSRF, identificadores de sesión, PIN o códigos. Si un parámetro sirve como
credencial, sustituir su valor por `[SECRETO]`; conservar el nombre permite describir
el mecanismo sin compartir la credencial. La ruta o los parámetros también pueden
contener datos personales o tokens: revisarlos, no solo las cabeceras.

La opción de exportar un HAR «sanitized» no garantiza que se anonimicen las URLs,
los cuerpos de petición o las respuestas. Si se comparte una muestra de HTML,
mantener etiquetas y nombres de campos, sustituyendo datos personales y valores
de sesión. Revisar también campos ocultos de formularios; si aparecen, ocultar los
valores de `__VIEWSTATE`, `__EVENTVALIDATION` y tokens de verificación.

## Resultado de la primera captura

La muestra revisada contenía 22 peticiones (14 XHR y 8 Fetch), sin respuestas de
tipo Document. Se identificaron publicidad, analítica y consentimiento, pero no
una respuesta identificable de cuentas o movimientos. Los referentes apuntaban a
una página bajo `/wele200/General/ConsultaMovimientos/…aspx`, con un parámetro `k`
cuyo valor no se conserva aquí. Esto sugiere revisar el HTML de la navegación;
no demuestra todavía cómo se transportan los movimientos. No se reprodujo ninguna
petición ni se incorporó el HAR al repositorio.

La captura posterior con documentos sí contiene las tablas de posición, cuenta y
tarjeta. Ver [contrato web observado](abanca-observed-web-protocol.md). No hace falta
repetir el login para desarrollar los parsers iniciales.

## Plantilla para compartir

```text
Pantalla: cuenta / movimientos / tarjeta (elegir)
Acción manual que generó la consulta: abrir cuenta / página siguiente / filtrar fechas
Método HTTP: GET o POST (tal como aparezca; no asumir que POST modifica datos)
Origen y ruta: https://HOST/RUTA/[CUENTA_A]/...
Estado HTTP: ...
Parámetros de URL o cuerpo: nombres y valores ficticios con el mismo formato
Content-Type de la respuesta: ...
Paginación: cursor / número de página / siguiente enlace / no se observa
Nombres de cabeceras de sesión, si aparecen: SOLO nombres, nunca valores

Respuesta de ejemplo revisada:
{ "nombresDeCamposReales": "valores ficticios con los mismos tipos" }
```

Sustituir nombres, DNI, IBAN, tarjetas, identificadores de cuentas/movimientos,
conceptos personales y tokens por marcadores coherentes. También se pueden cambiar
importes y fechas: conservar signo, separadores, moneda, formato y distinción entre
fecha de operación/valor. Mantener las claves reales y la estructura de arrays/objetos,
`null`, cadenas y números. Un mismo identificador sustituido debe mantener el mismo
marcador dentro del ejemplo para entender las relaciones entre cuenta y movimientos.

Si solo se desea identificar qué petición mirar, basta inicialmente con **método,
ruta revisada, estado y nombres de las claves de respuesta**. No hace falta enviar
movimientos hasta saber que la consulta es relevante.

## Qué se implementaría después

Con el contrato observado: parser con fixtures ficticias, identificadores estables,
paginación acotada, distinción entre pendientes/contabilizados, actualización sin
duplicados y consultas manuales. La sesión del navegador del ordenador no pasa por
sí sola a Pausa: hay que estudiar por separado el flujo de autenticación compatible,
CSRF, cookies/tokens, segundo factor, caducidad y las condiciones del banco. No guardar
el PIN por defecto, ni interpretar que guardar credenciales evita MFA.

El diagnóstico es puntual; no se propone conservar tráfico bancario completo durante
el uso normal. Las pruebas repetibles irían contra un servidor local simulado. Se
comprobaría cada flujo real una vez desde el teléfono, iniciado por el usuario, sin
reintentos automáticos ante rechazo, límites o desafíos. También permanece abierta la
opción de la API oficial descrita en `abanca-integration-next.md`.
