# Actualización 6.7

La cotización y el PnL actuales se describen en [trade-prices-pnl.md](trade-prices-pnl.md).

# Cliente nativo de Trade Republic

**Actualización 6.5:** las consultas financieras también se incorporan a la
[base SQLite común](banking-database.md). La sesión conserva su almacén cifrado separado.

Implementación Java de consulta desde el teléfono, sin ejecutar ni empaquetar pytr.
Se entra por **Banca → Trade Republic → Conectar y sincronizar**. Usa el protocolo
de la web, sin WebView. La prueba anterior de navegador conserva sus propios perfiles;
no comparte cookies ni datos con este cliente. ABANCA sigue con su implementación previa.

La APK de prueba se llama **Pausa Banking · TR nativo**, versión 6.3-banking-tr (20),
con el mismo ID `com.sejio.calorapp.bankingua` y firma de la prueba anterior.

## Uso y alcance

1. Introducir teléfono con prefijo internacional y PIN de cuatro cifras.
2. Aprobar el acceso en la app oficial y volver a Pausa. Si la cuenta requiere una
   aplicación de autenticación, introducir su código en Pausa.
3. Pulsar **Sincronizar saldo y movimientos**. Se valida la sesión y se consulta
   efectivo, movimientos y actividad de la cuenta.

El proceso pendiente y la aprobación ya recibida se conservan al salir de la pantalla
o recrearse el proceso Android. Una aprobación no se muestra como sesión válida hasta
comprobarla con el banco. Se consulta el proceso cada 2,5 segundos solo mientras la
pantalla esté visible y no haya errores; un error requiere reintento explícito.
No hay reintentos de PIN ni nuevos desafíos automáticos. Una sesión caducada requiere
reconectar. La duración de la sesión depende de Trade Republic.

La primera versión descarga hasta cinco páginas o 500 registros por listado y muestra
los primeros 50. Señala cuándo no ha llegado al final del listado del banco. Deduplica
por ID dentro de cada descarga y conserva el estado de los movimientos; omite entradas
marcadas como ocultas o eliminadas. No suma movimientos ni trata los pendientes como
contabilizados. Los importes se guardan como cadenas decimales, con límites de precisión
y escala. Una descarga completa y guardada sustituye la anterior: todavía no es una
base histórica incremental. Si falla una consulta, se conserva la descarga anterior.

El botón separado **Consultar posiciones de inversión** valida la sesión (dos GET),
abre un WebSocket y obtiene una única respuesta `compactPortfolioByType`, asociada al
`securitiesAccountNumber` devuelto por el banco. No se repiten las consultas de saldo
y movimientos, no se hace una petición por instrumento y no se mantienen cotizaciones
abiertas. La nueva copia de posiciones se guarda con fecha propia, sin sustituir el
saldo ni la actividad; una respuesta inválida conserva la cartera anterior.

Se muestran identificador, cantidad (incluidas fracciones) y precio medio de compra
si está informado. La referencia no garantiza nombre comercial ni moneda: no se
infiere EUR a partir del efectivo, ni se inventan valoraciones. Se aceptan hasta 500
posiciones y se muestran 100; un exceso se rechaza, sin sustituir la copia anterior por
una truncada. Una lista vacía no se confunde con un esquema desconocido. La cobertura
de todos los productos de inversión queda pendiente de contrastar con la cuenta real.

El botón **Actualizar valoración** añade cotizaciones de TR al flujo de cartera,
descrito abajo. No incluye documentos, detalles completos de movimientos,
transferencias, operaciones de compra/venta ni sincronización periódica en segundo plano.
No hay backend, Python, automatización de navegador ni puente JavaScript.

### Valoración bajo demanda

- Actualiza las cantidades de la cartera antes de calcular valores. Usa el mismo
  WebSocket para `compactPortfolioByType`, `instrument` y `ticker`.
- Consulta como máximo 20 instrumentos distintos por pulsación, secuencialmente y sin
  suscripciones continuas. Los instrumentos repetidos comparten una cotización.
  Hay un presupuesto de 60 segundos para empezar nuevas consultas de metadatos/precios;
  cada una espera como máximo 10 segundos. El acceso y la cartera tienen sus propios
  límites de espera. Los elementos omitidos quedan señalados, sin valor ficticio.
- Nombre, mercado seleccionado y compatibilidad de unidad se guardan cifrados en caché
  durante 24 horas. Se usa el primer `exchangeIds`, como pytr, sin probar otros mercados
  ante errores. Se desuscribe tras cada respuesta y se cierra normalmente el socket.
- Una pausa local persistente de un minuto entre intentos evita pulsaciones repetidas,
  incluso si falló el intento anterior. No representa un límite autorizado por TR.
- Solo se valoran tipos `stock` y `etf` identificados por los metadatos; un factor de
  precio distinto de 1 se excluye. No se detectan bonos por su nombre ni se adivinan
  unidades. Se utiliza `last.price`, sin sustituirlo por bid/ask ante ausencia.
- `cantidad × precio` usa `BigDecimal`; se guarda sin redondear y se muestran dos
  decimales para los valores. Desde 6.7 se calculan PnL total y diario estimado: véase
  [trade-prices-pnl.md](trade-prices-pnl.md) para fórmulas, cobertura y limitaciones.
- La moneda explícita del ticker tiene prioridad. Desde 6.7, acciones/ETF unitarios
  en LSX sin moneda usan la convención EUR, con su inferencia registrada. Otros mercados
  sin moneda se excluyen del subtotal. No se usa la divisa del fondo ni se aplica FX.
  La cobertura incompleta se indica como parcial.
- Se distingue fecha del último precio (`last.time`) de fecha de recepción. Si falta
  la fecha de precio, se indica; no se sustituye por la hora de descarga. Un precio de
  ayer puede mostrarse como tal, sin prometer tiempo real ni precio ejecutable.
- Rechazo del banco, corte o respuesta malformada detienen la tanda y conservan la
  valoración anterior. Una respuesta válida sin último precio marca esa posición como
  no disponible. No se reintenta automáticamente ni se consulta un proveedor alternativo.

Referencia de precios:
[pytr/tickers.py, misma revisión fijada](https://github.com/pytr-org/pytr/blob/e7f3ba37167bda15c0b5501f7ddab24c8d24d37e/pytr/tickers.py).
Para los nombres `typeId`/`priceFactor` se ha contrastado también el esquema público de
[instrumento del cliente Go, revisión b10c7ac563fa](https://github.com/dhojayev/traderepublic-portfolio-downloader/blob/b10c7ac563fa/v2/pkg/traderepublic/schemas/instrument.json).
No se instala ni ejecuta ninguno de estos proyectos. El esquema de cotización sigue
pendiente de validarse con la cuenta real; no se añade una dependencia de ejecución.

## Almacenamiento y red

- PIN, teléfono y códigos se usan durante la petición; no se escriben en el almacén.
- Cookies, identificador del dispositivo, proceso pendiente y última descarga viven
  juntos en un archivo atómico AES-GCM, con clave Android Keystore, en `noBackupFilesDir`.
  No se exige biometría al usar esa clave. La protección de acceso es la del teléfono.
- Las pantallas tienen `FLAG_SECURE`; campos sin restauración de texto ni autofill.
  El PIN y el código se vacían al enviarlos y al salir de la pantalla.
- No se añaden logs de red/cuerpos ni errores que expongan tokens o identificadores.
  La APK de prueba `bankingUa` se genera sin depuración aunque usa la firma de prueba
  previa para poder actualizar la instalación existente.
- HTTPS y WebSocket con validación TLS del sistema. Destinos de producción fijos:
  `api.traderepublic.com` y el HTML público de `app.traderepublic.com/login`.
  No se siguen redirecciones; las cookies solo se envían al origen exacto de la API.
- **Olvidar conexión y datos locales** borra archivo y clave, también si el archivo
  está dañado. No revoca la sesión en el banco ni elimina perfiles de la prueba WebView.
  Estos tienen su botón independiente en Banca.

## Dependencias

Una dependencia directa nueva de ejecución: **OkHttp 5.3.2**, compartida para HTTPS,
cookies y WebSocket. Sus dependencias transitivas incluyen Okio, Kotlin estándar y
componentes AndroidX. No se afirma que el APK use una sola biblioteca en total.
`org.json`, criptografía y Keystore son APIs del sistema en producción. JUnit,
MockWebServer y la implementación JVM de JSON solo se utilizan en pruebas.

Se actualizan AGP a 8.10.1 y Gradle a 8.11.1 para que D8 soporte los metadatos Kotlin
2.2 de OkHttp. Se mantienen compile/target SDK 35 y mínimo Android 6 (API 23).

## Protocolo y referencia

Referencia leída, sin instalar ni ejecutar:
[pytr/api.py, revisión e7f3ba37167bda15c0b5501f7ddab24c8d24d37e](https://github.com/pytr-org/pytr/blob/e7f3ba37167bda15c0b5501f7ddab24c8d24d37e/pytr/api.py),
y sus ejemplos públicos de eventos. Véase el aviso de licencia en
[`../app/src/main/assets/licenses/pytr-reference.txt`](../app/src/main/assets/licenses/pytr-reference.txt).

| Paso | Intercambio |
| --- | --- |
| Inicio | `POST /api/v2/auth/web/login`, teléfono y PIN |
| Confirmación | `GET /api/v2/auth/web/login/processes/{id}` |
| Autenticador | `POST /api/v2/auth/web/login/processes/{id}/authenticator-verification` |
| Sesión | `GET /api/v1/auth/web/session` y `GET /api/v2/auth/account` |
| Datos | WebSocket al origen API, `connect 31`, suscripciones `cash`, `timelineTransactions`, `timelineActivityLog` |
| Posiciones, consulta separada | `compactPortfolioByType` con `secAccNo`, una respuesta y desuscripción |
| Valoración, botón independiente | `instrument` con ID; `ticker` con `ID.mercado`; una respuesta por consulta |

Se envían las cabeceras de identificación del flujo web v2. Se lee `app-version` del
HTML público al iniciar una conexión, con respaldo 2.2640.20 comprobado el 05/10/2026.
El identificador aleatorio del cliente persiste, junto con la descripción del dispositivo.
Se usa una identidad de navegador compatible con el flujo web; no es el protocolo
privado de la app móvil. Las constantes de conexión WebSocket siguen la referencia.
Este protocolo no oficial puede cambiar. Ante 403/405, respuesta incompatible o límites
de intentos se detiene; no se intenta resolver ni eludir un desafío WAF.

## Validación

Resultado del 05/10/2026: **36 pruebas JVM, 0 fallos**, incluidas once nuevas de valoración;
compilación de `bankingUa`
correcta; lint con **0 errores y 99 avisos** (incluidos textos sin recursos de
traducción). Las pruebas existentes de política de orígenes/perfiles y modos A/B/C
también pasan. APK verificada con `apksigner`, mismo certificado que la prueba anterior,
ID y versión comprobados y `debuggable=false`.

```sh
bash gradlew :app:testBankingUaUnitTest :app:assembleBankingUa :app:lintBankingUa
```

Las pruebas locales usan servidor HTTP/WebSocket simulado y credenciales ficticias.
Cubren reanudación del proceso y de una aprobación, errores/caducidad/limitación,
autenticador, redirecciones, persistencia, aislamiento de cookies, paginación,
deduplicación, importes y conservación de la copia anterior ante respuestas inválidas.

El usuario ha comunicado que el acceso, los saldos básicos y las posiciones funcionan
en su teléfono. Esto no acredita las cotizaciones/valoraciones nuevas, movimientos,
todos los productos ni estabilidad futura.
No se ha autenticado una cuenta bancaria real desde el entorno de desarrollo. Las pruebas JVM
no ejecutan Android Keystore ni el ciclo de vida Android: quedan pendientes la prueba
en teléfono, el cambio a la app bancaria y la comparación del saldo y movimientos con
la aplicación oficial. Para reportar un fallo basta la fase y el código visible entre
corchetes, sin PIN, códigos de aprobación, cookies ni respuestas bancarias.

### Pruebas y controles del banco

Los casos de cartera usan respuestas sintéticas construidas según `api.py` y
`portfolio.py` de la referencia fijada. Prueban el código de Pausa, no la compatibilidad
con una cuenta real. No se mandan credenciales ficticias a Trade Republic ni se ejecutan
baterías de pruebas contra su API. La prueba real pendiente consiste en una consulta
manual desde el teléfono y comparación de posiciones conocidas con la app oficial.

Las pruebas de valoración comprueban fracciones, monedas desconocidas, subtotales por
moneda, precios/fechas ausentes, límite de consultas, caché, reutilización entre posiciones,
rechazo de instrumentos o mercados incorrectos, desuscripción, espera entre intentos
y conservación de la copia previa ante rechazo del banco. Son fixtures sintéticas;
no se han enviado peticiones autenticadas reales desde el entorno de desarrollo.

Se conserva la identificación de compatibilidad web ya utilizada; no se afirma que
Pausa se identifique como una integración oficial. Se reutiliza el dispositivo/sesión,
no se rotan identidades, no se simulan gestos humanos, no hay tareas de mantenimiento
de sesión ni consultas de inversión automáticas. Se detiene ante errores, límites o
desafíos. Una frecuencia baja por sí sola no garantiza evitar controles ni acredita
que el banco admita este cliente no oficial.
