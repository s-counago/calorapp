# Persistencia bancaria y sincronización desde WebView

Versión 6.5-banking-tr (código 22). El objetivo de esta fase es extraer y guardar,
con procedencia verificable. No se incorpora un importador CSV ni budgeting. El CSV
aportado se utiliza únicamente como referencia del significado de los campos.

## Flujo ABANCA

1. **Banca → Conectar y sincronizar ABANCA** abre la posición global con el perfil
   existente de WebView. Si el banco pide acceso, el usuario completa el formulario,
   MFA y reCAPTCHA en la propia web.
2. Al reconocer el resumen, comienza la lectura automáticamente. Un panel de progreso
   cubre visualmente la misma WebView; no se crea una segunda sesión ni un navegador
   que suplante a otro dispositivo. No se envían credenciales desde código nativo.
3. Se guarda el resumen y se descubren los enlaces de consulta de sus productos. Se
   visitan secuencialmente los enlaces observados de cuentas, tarjetas y préstamos.
   Cada página se guarda antes de avanzar a la siguiente.
4. Al terminar, **Ver datos guardados** permite inspeccionar la consulta y sus fuentes.
   Cada sincronización conserva una nueva captura; no reemplaza el historial anterior.

El CAPTCHA no se resuelve ni reutiliza desde Pausa. Puede aparecer de nuevo aunque
la navegación sea normal. Si cambia inesperadamente la página, aparece un desafío,
hay un error HTTP o no se reconoce la estructura, la lectura se detiene y se muestra
la web. Lo ya guardado permanece en una consulta marcada como interrumpida. Después
se necesita una nueva acción del usuario para sincronizar; no hay reintentos ocultos.

Se admite una navegación inicial al resumen y hasta 20 productos por intento,
25 segundos por página y 120 segundos para la lectura después del acceso. Se deja
un minuto entre intentos. No hay paginación automática del historial ni descargas.
Los recursos normales de las páginas (scripts, imágenes, etc.) los sigue cargando la
web: el límite de navegaciones principales no es un límite de todas sus peticiones.
La lectura se detiene si la app pasa a segundo plano, salvo mientras espera el login
para permitir confirmar MFA en otra app. No hay sincronización programada en segundo plano.

## Fuentes observadas

Se reconocen posición global, movimientos de cuenta, tarjetas con seis o siete
columnas y la página de préstamo. La tarjeta de crédito de la nueva captura carece
de `F.PAGO`; no se inventa ese campo. En el préstamo se conservan las secciones
Datos del préstamo, Datos generales, Pendiente de pago y Condiciones, incluyendo los
subgrupos que distinguen los distintos campos llamados `IMPORTE`.

Cada captura ABANCA contiene dos capas:

- Tablas originales: título, orden de filas, texto de cada celda, cabecera y `colspan`.
  Conservan campos que aún no tienen una interpretación común, titulares visibles,
  descripciones ampliadas, unidades y condiciones del préstamo.
- Interpretación: productos, saldos, movimientos o condiciones. Cada registro apunta
  a su tabla y fila de origen. Se conservan la versión del lector, fecha de captura,
  producto y ruta de consulta sin parámetros de sesión.

No se guardan HTML ejecutable, formularios, PIN, cookies, CAPTCHA, cabeceras de
sesión, URLs con `k` ni los HAR. Los enlaces de navegación solo existen en memoria.
El texto de las tablas se muestra como texto, nunca se ejecuta como código.

Límites explícitos: hasta 200 registros interpretados por página, 2001 filas por
tabla original, ocho celdas por fila y 4096 caracteres por celda. Las fuentes se
acotan a 150000 caracteres JSON durante la extracción y 512 KiB en la validación.
La vista normalizada puede recortar conceptos a 240 caracteres, pero la identidad
de movimientos utiliza el concepto original conservado cuando está disponible.
Se indica el recorte de fuentes por separado del recorte de interpretación.
Son capturas de lo cargado, no una garantía de histórico bancario completo.

## SQLite y modelo común

Se usa SQLite nativo de Android, sin nuevas librerías de producción. La base está
en `noBackupFilesDir/banking.sqlite`, con claves foráneas, WAL y `synchronous=FULL`.

| Tabla | Responsabilidad |
| --- | --- |
| `sync_runs` | Banco, origen, inicio/fin, estado y productos omitidos de cada lectura. |
| `products` | Identidad y tipo de cuenta, tarjeta, préstamo o cuenta de inversión. |
| `captures` | Documento o conjunto de datos obtenido, versión del lector y fecha; inmutable. |
| `entities` | Interpretación más reciente de cada saldo, movimiento, condición o posición. |
| `observations` | Cada aparición de una entidad, vinculada a captura, registro y tabla/fila original. |

Un guardado de página escribe captura, productos, entidades y observaciones dentro
de una transacción. Si falla, esa página se revierte por completo; las anteriores
permanecen. Al reabrir tras una interrupción del proceso, las consultas inconclusas
se marcan interrumpidas y no se reejecutan. No hay borrado automático de consultas
antiguas ni migraciones que borren tablas para recrearlas.

Los datos financieros se cifran con AES-GCM antes de llegar a SQLite, incluidos
los que pasarán por WAL/journal. La clave está en Android Keystore y cada payload
se autentica con su tabla e identidad. Los índices y metadatos (banco, tipo, fechas,
hashes, estado) no están cifrados: no se afirma que el archivo SQLite completo use
SQLCipher. El modelo guarda importes y cantidades como cadenas decimales exactas.

## Identidad y actualizaciones

ABANCA no expone un ID de movimiento en las muestras. Por decisión del usuario,
la identidad provisional combina producto, fecha de operación, importe, moneda,
concepto completo normalizado y tipo de operación. No incluye saldo posterior,
fecha valor ni estado, que pueden cambiar. El criterio se identifica como
`abanca-fingerprint-v1` para poder revisarlo después.

Dos filas que coincidan producen una entidad común, pero conservan sus dos
observaciones originales. Una segunda consulta actualiza la interpretación y añade
procedencia; no destruye la lectura previa. Si el criterio resulta ambiguo, los
originales permiten reconstruirlo. La identidad de producto de ABANCA utiliza su
etiqueta bancaria visible; puede ser provisional si el banco cambia las etiquetas
o muestra dos productos con la misma etiqueta. No se usa `k` como identidad estable.

Trade Republic utiliza el ID del banco para movimientos y el instrumento para
posiciones, dentro de su cuenta. La cuenta se identifica mediante un hash del
identificador bancario, sin persistir su número en el cliente. El saldo común tiene
importe/moneda y un tipo (efectivo, saldo de cuenta, crédito dispuesto, préstamo
pendiente). Las posiciones conservan cantidad, coste, cotización y valoración cuando
están disponibles. Las condiciones del préstamo conservan su sección, etiqueta,
texto y unidades, sin convertir porcentajes o plazos en importes.

No se inventa comercio si solo hay concepto. Las fechas de operación/valor de
ABANCA se separan del timestamp de Trade Republic, sin inventar un día local a
partir de una zona horaria desconocida. No se suman tarjeta y cuenta ni se concilian
cargos, devoluciones, pendientes o liquidaciones para presupuestos en esta fase.

Las entidades son la última interpretación observada, no un libro mayor auditado.
La ausencia de un movimiento en una consulta parcial no lo borra. Una consulta
completa de posiciones TR desactiva posiciones anteriores que ya no aparecen,
conservando sus observaciones y sus capturas históricas.

## Compatibilidad y recuperación

La sesión TR y su copia de consulta siguen en su almacén cifrado, separado de SQLite.
Tras una consulta, el repositorio incorpora los datos financieros a la base. Si esa
incorporación falla, la copia cifrada se conserva y se reintenta localmente al abrir
TR, sin repetir la consulta bancaria. Las copias anteriores se recuperan al abrir
la pantalla de datos guardados. Las cuentas antiguas sin identificador no se fusionan
por conjeturas con una cuenta nueva; quedan identificadas como origen antiguo desconocido.

Las capturas manuales antiguas de ABANCA también se incorporan sin eliminar sus
archivos originales. El acceso principal nuevo es la sincronización automática.
El diálogo existente de olvidar conexión y datos de TR borra también sus datos en
SQLite; olvidar solo el perfil web de ABANCA conserva los datos financieros.

La persistencia sobrevive al cierre de la app y a actualizaciones instaladas encima.
No es una copia de seguridad externa: desinstalar, borrar datos de la app o perder
la clave del dispositivo puede destruir el acceso a estos datos. No se ha activado
ninguna subida a la nube.

## Verificación

- 57 pruebas JVM de protocolo, normalización, identidad, procedencia y cifrado: pasan.
- 102 comprobaciones del lector ABANCA y 24 del lector genérico en Chromium: pasan.
- Cinco documentos de los HAR reconocidos localmente, sin filas omitidas ni fuentes
  recortadas en esas muestras. Scripts del banco bloqueados por CSP y peticiones
  interceptadas localmente; no se contactó con ABANCA.
- Prueba SQLite con el esquema real: persistencia al reabrir, observaciones duplicadas,
  rollback, claves foráneas y borrado explícito: pasa.
- Compilación, pruebas JVM y lint de `bankingUa`: correctos, lint sin errores.
- Prueba Android de SQLite/Keystore compilada en `assembleDebugAndroidTest`, pero no
  ejecutada porque no hay emulador. Usa su propia base desechable, sin tocar la real.

Falta comprobar el login y la navegación real de WebView en el teléfono. Las pruebas
locales no acreditan que el banco admita el navegador ni garantizan ausencia de CAPTCHA.

```sh
npm run test:abanca
npm run test:banking
python tools/banking-sqlite-test.py
sh tools/test-banking-policy.sh
bash gradlew :app:testBankingUaUnitTest :app:assembleBankingUa :app:lintBankingUa
```
