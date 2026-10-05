# ABANCA: siguiente integración

**Capturas revisadas:** el acceso, la posición global y las consultas de cuenta y
tarjeta están documentados en el [contrato web observado](abanca-observed-web-protocol.md).
Los datos llegan en tablas HTML; el login capturado incluye un token reCAPTCHA.
Ya está implementado un [lector específico con pruebas locales](abanca-reader.md),
manteniendo el acceso interactivo. El siguiente paso es comprobarlo en el teléfono.
La sesión, la paginación y las exportaciones aún necesitan verificación.
Pausa no registra tráfico HTTP de ABANCA actualmente.

El usuario confirma que su acceso web está desbloqueado. No se ha confirmado todavía
en esta revisión el acceso desde WebView de Pausa ni el resultado del lector con su
cuenta. No se han utilizado sus credenciales ni hecho pruebas de login desde cloud.

## Paso disponible hoy

En Pausa: **Banca → Abrir ABANCA**, login y segundo factor manuales en la web del banco.
Después, abrir una página de saldos o movimientos y pulsar **Guardar datos de esta página**.
El lector actual consulta texto visible del DOM local; no inicia peticiones de datos,
no lee credenciales/cookies ni repite operaciones. La propia web sí hace sus peticiones
normales. La captura es parcial y no acredita extracción de todos los productos.
ABANCA utiliza el user-agent normal de WebView; el experimento de identidad de escritorio
de Trade Republic no se aplica a ABANCA. Si hay un bloqueo, detenerse y utilizar el
navegador oficial/sistema; no encadenar nuevos intentos ni falsear más señales.

Limitación detectada al revisar los HAR: las tres páginas autenticadas capturadas
usan `#content`, mientras el lector genérico exige `main` o `[role=main]`. Desde la
versión 6.4, ABANCA utiliza un lector específico de estas tablas con campos separados.
No se ha probado aún la lectura con una sesión real en el teléfono.

## Automatización estable: comprobar primero el canal oficial

Fuente oficial consultada el 05/10/2026: https://openbanking.abanca.com/
El portal anuncia APIs de cuentas (datos y saldos) y movimientos, documentación,
entorno de pruebas y revisión de la aplicación antes de proporcionar la API key.
La documentación https://openbanking.abanca.com/developer/ requiere una aplicación web
que esta consulta de texto no ha podido leer. No se ha registrado ninguna aplicación.

Falta verificar si admiten el uso personal de Pausa, requisitos de producción/identidad,
consentimientos, coste, límites, renovación y cobertura de tarjetas/inversiones. No se
asume que tener cuenta bancaria o una API key baste para acceso de producción. Es el
primer canal a valorar para sincronización automática bajo las reglas del banco.

Alternativa sin automatizar el login: importación local de extractos. ABANCA documenta
exportación de movimientos en Excel/CSV o PDF; advierte que los movimientos de tarjeta
de crédito se consultan en su extracto específico, no necesariamente en los de cuenta:
https://www.abanca.com/es/ayuda/como-puedo-descargar-el-justificante-de-una-transferencia/
El importador todavía no está implementado; requeriría un ejemplo anonimizado para
confirmar columnas, fechas, importes y reglas de duplicados. La descarga en el navegador
de Pausa tampoco está integrada actualmente; se puede descargar desde la web externa.

## Criterio de pruebas

Fixtures sintéticas y servidor local para pruebas repetibles. Una comprobación real,
iniciada por el usuario, de cada flujo necesario; fecha y resultados separados de las
pruebas simuladas. Nada de credenciales de prueba contra producción, barridos de endpoints,
reintentos de PIN, saltos de CAPTCHA/MFA, cambios de identidad o simulación de conducta
humana para evadir controles. Revisar condiciones del canal elegido antes de automatizar.
No existe garantía de cero alertas para una integración no oficial.
