# ABANCA: lectura estructurada en el teléfono

Versión de prueba 6.4-banking-tr, código 21. Mantiene el identificador y la clave
de firma de las pruebas anteriores. El cliente nativo de Trade Republic no cambia.

## Qué probar

1. Instalar la APK como actualización, sin desinstalar la versión anterior.
2. Abrir **Banca → Abrir ABANCA** y completar el acceso en la web oficial si lo pide.
   Si el banco rechaza WebView, detener la prueba. La sesión de Chrome no se comparte
   con Pausa y no se trata de superar un bloqueo repitiendo accesos.
3. En el resumen de productos, pulsar **Guardar datos de esta página**. Debe indicar
   «Resumen de productos» y el número de registros. Volver a Banca para contrastar
   etiquetas e importes: saldo de cuenta, dispuesto de tarjeta y pendiente de préstamo
   tienen significados distintos y no se suman.
4. Abrir movimientos de una cuenta, guardar y contrastar una fila: fecha de operación,
   fecha valor, descripción, importe con signo y saldo tras la operación.
5. Repetir en una tarjeta y comprobar tipo, situación y dato de pago. Este último
   se conserva como texto del banco; puede no ser una fecha y no se trata como fecha valor.

Cada captura **sustituye la anterior de ABANCA**. Se etiqueta como parcial y lleva
fecha de lectura. No hay navegación automática, descargas, conciliación ni histórico
acumulado. Para informar del resultado basta con el mensaje de estado y qué campo
no coincide; no hace falta enviar PIN, tokens, importes personales ni otro HAR de login.

## Implementación y límites

`read-abanca.js` reconoce las tres rutas observadas de posición, cuenta y tarjeta
bajo `bancaelectronica.abanca.com`, mediante tablas conocidas en `#content`. No lee
formularios, cookies, almacenamiento web ni URLs de enlaces; tampoco hace peticiones
ni clics. Se detiene ante campos visibles de PIN, contraseña o código de un solo uso.

Los importes se convierten del formato español observado a cadenas decimales exactas,
sin `float`. La moneda debe aparecer en la celda; si falta, se conserva como desconocida.
El signo procede del número, no del color CSS. Las fechas se validan por separado.
Las filas idénticas se conservan. Cabeceras inesperadas rechazan la página; filas
malformadas se omiten con un aviso y un contador.

`AbancaSnapshot` valida y reconstruye únicamente los campos reconocidos. Produce
registros estructurados y texto con etiquetas para la vista actual; claves ajenas al
contrato no se persisten. La copia usa `BankSnapshotStore`, AES-GCM, Android Keystore,
escritura atómica y `noBackupFilesDir`. Las copias genéricas antiguas siguen siendo legibles.

Límites: 200 registros, 240 caracteres por concepto, 400 por fila mostrada y un
presupuesto de 110 KiB para filas y registros, dejando espacio para metadatos bajo
el límite de 128 KiB del almacén. Se indica el recorte. Una página sin registros
interpretables o un error no sobrescribe la copia anterior ni se convierte en saldo cero.

## OFX y siguiente fase

OFX (Open Financial Exchange) es un formato de intercambio de extractos bancarios.
Puede contener fechas, importes, conceptos e identificadores de movimientos, según
lo que exporte el banco. El enlace OFX observado requiere sesión web; no evita
reCAPTCHA ni ofrece acceso permanente. No se ha descargado una muestra ni implementado
su importación.

Primero validar estas pantallas en WebView. Después, estudiar una sincronización
iniciada por el usuario que descubra los enlaces de consulta de la sesión actual,
los recorra de forma acotada y se detenga ante el login o un desafío. Para cuentas,
una muestra OFX anonimizada puede aclarar identificadores y cobertura temporal y
permitir una importación más estable que el HTML. Para tarjetas solo se ha confirmado
la tabla HTML. No se promete actualización desatendida cuando la sesión haya caducado.

## Validación

- 85 comprobaciones del lector ABANCA en Chromium con HTML ficticio y todas las
  peticiones interceptadas y resueltas localmente: pasan.
- 24 comprobaciones del lector genérico anterior: pasan.
- 45 pruebas JVM, incluidas nueve de normalización ABANCA y las de Trade Republic: pasan.
- Compilación `bankingUa`: correcta. Lint: cero errores, 102 avisos.

No se contactó con ABANCA ni se utilizaron credenciales bancarias. No hay emulador
disponible: estas pruebas no acreditan aún el acceso real en WebView, MFA ni la
lectura de la cuenta en el teléfono. Los HAR no se incorporan al repositorio ni
se convierten en fixtures.

```sh
npm run test:abanca
npm run test:banking
bash gradlew :app:testBankingUaUnitTest :app:assembleBankingUa :app:lintBankingUa
```

Contrato observado: [abanca-observed-web-protocol.md](abanca-observed-web-protocol.md).
