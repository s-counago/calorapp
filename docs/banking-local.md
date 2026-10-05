# Banking local en Pausa

**Actualización 05/10/2026:** Trade Republic incorpora un [cliente nativo](trade-republic-native.md)
como acceso principal. Este documento describe la implementación previa de navegador,
que sigue disponible como prueba. El cliente nativo tiene un almacén cifrado separado.

## Decisión y alcance (4 de octubre de 2026)

El usuario elige gestionar sus propias sesiones y consultar datos cuando lo necesite,
sin depender de Sure ni de sus integraciones experimentales. La aplicación es Android
nativa en Java. La primera implementación ejecuta un navegador en el teléfono, con un
perfil persistente por banco, y un lector JavaScript pasivo bajo demanda. No necesita
servidor ni un PC encendido. La carpeta histórica `sure-local` no se usa desde este módulo.

La base está implementada; **no se han probado credenciales ni sesiones reales**.
La compatibilidad de ABANCA y Trade Republic con Android WebView sigue pendiente.
Las comprobaciones de las páginas públicas solo demuestran acceso web, no que una
cuenta concreta pueda autenticarse dentro de WebView ni que el lector interprete su área privada.

## Experiencia y sesiones

- `Banca` presenta las últimas capturas locales. Consultarlas no accede a Internet.
- `Abrir ABANCA` y `Abrir Trade Republic` reutilizan sus perfiles. El banco decide si
  acepta la sesión existente o pide login/segundo factor. No se marca «conectado» por
  el mero hecho de cargar una página y no se promete una caducidad determinada.
- Las credenciales se introducen en la web oficial, no en ajustes de Pausa. No hay
  código que lea PIN, contraseña, SMS, cookies o tokens para guardarlos en otro almacén.
  WebView sí necesita conservar su propio estado de sesión.
- `Guardar datos de esta página` lee texto de tablas, listas y resúmenes del documento
  principal. La captura tiene fecha, origen y una etiqueta permanente de «parcial».
- `Olvidar sesión` elimina únicamente el perfil local de ese banco. Conserva la captura.
  `Borrar captura` hace lo contrario. Eliminar cookies no equivale a revocar el acceso
  en el servidor del banco.

No se incorporan keep-alives, refrescos periódicos, reintentos de PIN ni elusión de
MFA/CAPTCHA. La comodidad deriva de conservar las sesiones que el banco admita y de
consultar los últimos datos guardados sin volver a autenticar.

## Componentes

| Componente | Responsabilidad |
| --- | --- |
| `BankProvider` | Identificadores cerrados, URL inicial, orígenes HTTPS permitidos y nombre del perfil. |
| `BankBrowserActivity` | Login manejado por el usuario, navegador aislado, captura explícita y tratamiento de errores. |
| `assets/banking/read-visible.js` | Lectura pasiva de texto, sin red, clicks, formularios, cookies ni almacenamiento web. |
| `BankSnapshotStore` | Una captura por banco, AES-GCM con clave no exportable de Android Keystore y escritura atómica. |
| `BankingView` | Consulta local, fecha, límites, apertura de bancos y borrado independiente de perfil/captura. |

Los perfiles `banking_v1_abanca` y `banking_v1_trade_republic` son distintos de
`renfe_*`. Si falta `MULTI_PROFILE`, el módulo se detiene y pide actualizar WebView;
no degrada silenciosamente a un perfil compartido. Se vacían a disco las cookies del
perfil correcto al terminar de cargar y al salir. Esto no prolonga su validez.

Los datos capturados viven en `noBackupFilesDir`, cifrados con AES-GCM y autenticados
con el identificador del banco. Una escritura fallida o una página no reconocida
no sustituye una captura válida. No se guardan URLs con parámetros, rutas o fragmentos.
Las cookies las protege el sandbox de WebView y el almacenamiento del dispositivo;
no se afirma que usen el cifrado adicional de las capturas.

Se desactiva la depuración WebView en Banking incluso en builds debug, porque Renfe
la activa globalmente. Las pantallas bancarias usan `FLAG_SECURE`, no hay bridge
JavaScript nativo, los errores TLS se cancelan, y no se permite navegación principal
a hosts distintos de los explicitados. No se añaden logs de datos bancarios.
La app ya desactiva las copias automáticas. No se añade un PIN extra de Pausa: el
desbloqueo y la protección del teléfono son la barrera de acceso de este uso personal.

## Qué extrae y qué falta

El lector reconoce contenedores `main`/`role=main`, filas de tabla, listas y bloques
con importes expresados con moneda. Conserva el texto original: no convierte decimales
en números flotantes ni infiere que una cifra sea saldo disponible, gasto o ingreso.
Excluye formularios, inputs, texto editable, navegación y contenido oculto; no lee
valores de campos. Se detiene ante campos visibles de contraseña o de segundo factor.
La detección de login es orientativa, nunca una prueba de autenticación.

El resultado está limitado a 200 registros, 400 caracteres por registro y 128 KiB.
No deduplica compras idénticas. El contenido recortado se señala. Cada captura
**reemplaza la anterior del banco**; no se fusionan páginas ni se anuncia un historial
sin duplicados. No se extraen iframes, shadow DOM cerrado o páginas sin estructura
compatible. Tampoco se fuerza el scroll para cargar datos virtualizados.

Para pasar a sincronización normalizada, hay que verificar en cada cuenta:

1. Acceso en WebView, MFA, vuelta desde la app bancaria y compatibilidad con bloqueos del banco.
2. Reapertura tras cerrar Pausa y reiniciar el teléfono; registrar cuándo exige reconexión.
3. Estructura real de cuentas, efectivo, posiciones y movimientos. Contrastar la
   captura con una compra, una devolución y una transferencia conocidas por el usuario.
4. Identificadores, paginación y periodo cubierto antes de guardar un libro histórico;
   conciliación de pendientes y contabilizados antes de calcular presupuestos.

Si el banco rechaza WebView, `Ayuda` permite abrir su web oficial en el navegador
del sistema, pero esa sesión **no se puede extraer ni compartir automáticamente**.
En ese caso se necesita un conector separado en un navegador de escritorio, o una
importación de extractos verificados. No se suplanta el user-agent ni se saltan
protecciones para ocultar la incompatibilidad.

## Validación reproducible

Resultado de desarrollo (04/10/2026): 24 comprobaciones del lector en Chromium y
las pruebas JVM de la política de orígenes pasan. `assembleDebug`,
`assembleDebugAndroidTest` y `lintDebug` terminan correctamente (lint sin errores;
mantiene avisos, incluidos textos literales como en el resto de la app). No hay
emulador disponible en este entorno: las pruebas instrumentales se han compilado,
pero no ejecutado. No se ha validado una sesión bancaria real.

Pruebas del lector en Chromium con páginas sintéticas, sin red bancaria:

```sh
npm ci
CHROMIUM_PATH=/usr/bin/chromium npm run test:banking
sh tools/test-banking-policy.sh
```

La segunda prueba requiere Java 11+ con el módulo compilador. Comprueba los hosts,
esquemas, puertos, URLs engañosas y separación de proveedores. La primera verifica
lectura, exclusión de campos y contenido oculto, compras duplicadas, límites y
pantallas no reconocidas. `CHROMIUM_PATH` acepta la ruta de Chrome en otros sistemas.

Compilación Android y prueba de cifrado **solo en un emulador desechable** (sustituye
y borra las capturas bancarias; no realiza conexiones):

```sh
bash gradlew assembleDebug assembleDebugAndroidTest -PuiTestRunner=com.sejio.calorapp.BankingStoreSmokeTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.sejio.calorapp.test/com.sejio.calorapp.BankingStoreSmokeTest
```

La prueba instrumental comprueba cifrado/lectura, aislamiento, rechazo de una captura
vacía sin perder datos y detección de alteraciones del archivo. Su compilación no
equivale a ejecutarla ni a validar los bancos. Revisar también `Banca → Tareas → Hoy`
y fuente ampliada: Banking no debe entrar en el selector de tareas ni sustituir su
último segmento. Los índices de tareas quedan acotados a 2 y 4–6.

## Fuentes comprobadas

- [Banca electrónica de ABANCA](https://www.abanca.com/es/banca-a-distancia/banca-electronica/):
  confirma consulta de saldos/movimientos, acceso desde Área cliente con NIF/PIN y
  enlaces oficiales a `bancaelectronica.abanca.com` y `be.abanca.com`.
- [Aplicación web de Trade Republic](https://app.traderepublic.com/login): responde
  con la aplicación web. Esto no acredita autenticación ni extracción dentro de Android.
- [Perfiles de Android WebView](https://developer.android.com/reference/androidx/webkit/ProfileStore):
  se utiliza la API `MULTI_PROFILE` ya incluida en el proyecto, comprobada en ejecución.

No se ha registrado una cuenta, autorizado acceso bancario, contratado un proveedor
ni utilizado credenciales bancarias durante el desarrollo.
