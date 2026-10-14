# Sejio · Pausa

Aplicación Android personal con contadores diarios y formalización asistida de
viajes de Renfe entre A Coruña y Santiago de Compostela.

## Banking local: ABANCA y Trade Republic

La pestaña `Banca` abre cada banco en un perfil WebView independiente y persistente
en el teléfono. No necesita Sure, un backend ni configurar credenciales en archivos.
Introduce tus credenciales y el segundo factor directamente en la web del banco.
Pausa reutiliza el perfil mientras el banco acepte la sesión; no garantiza una
duración ni realiza reintentos automáticos de acceso.

1. Abre `Banca` y selecciona el banco. Se necesita un Android System WebView actualizado
   con soporte de perfiles múltiples.
2. Completa el acceso y abre el resumen, los movimientos o la cartera.
3. Pulsa `Guardar datos de esta página`. El lector guarda texto financiero visible
   en tablas, listas y resúmenes compatibles. No pulsa botones ni realiza operaciones.
4. Vuelve a `Banca` para consultar la última captura y su fecha sin conectar al banco.
   Cada captura sustituye la anterior de ese banco; no acumula un libro de movimientos.

**Estado: base implementada, pendiente de validar el acceso con las cuentas reales.**
Las capturas son parciales: no recorren páginas, no descargan extractos, no cubren
todo el historial y no calculan un saldo consolidado. Si una página no se reconoce,
se conserva la captura anterior. La web puede rechazar navegadores integrados;
abrirla en Chrome no comparte su sesión con este módulo. Una sesión web bancaria
tiene las capacidades normales de la cuenta, aunque nuestro lector solo consulte.

Las capturas se cifran con Android Keystore. Las cookies permanecen en el perfil
privado de WebView, separado de Renfe y del otro banco. `Olvidar sesión` borra ese
perfil local; `Borrar captura` elimina los datos guardados. Para revocar una sesión
en el servidor hay que usar las opciones del propio banco.

Arquitectura, límites, fuentes y comprobaciones: [Banking local](docs/banking-local.md).

## Avisos de plazas Renfe

El monitor de `tools/renfe-seat-monitor.js` consulta cada minuto la venta pública
de Renfe para la fecha y el trayecto configurados. Filtra las salidas posteriores
a `after`, con un viajero o los que se indiquen en `passengers`, y envía un aviso
a ntfy cuando aparece disponibilidad. Si la plaza sigue disponible, no repite
el aviso; vuelve a avisar si desaparece y se libera de nuevo. No compra ni
reserva billetes.

1. Copia `tools/renfe-seat-monitor.example.json` a
   `tools/renfe-seat-monitor.local.json` y cambia la fecha y el topic.
2. Instala las dependencias con `npm install`.
3. Comprueba la consulta con `npm run monitor:renfe:check`.
4. Inicia la vigilancia con
   `powershell -ExecutionPolicy Bypass -File tools/start-renfe-seat-monitor.ps1` y
   deja el ordenador encendido. Para detenerla, ejecuta
   `powershell -ExecutionPolicy Bypass -File tools/stop-renfe-seat-monitor.ps1`.

El topic debe estar suscrito en la aplicación de ntfy. Un servidor propio se
configura en `ntfyServer`; para un topic protegido se puede definir `NTFY_TOKEN`
como variable de entorno. La sesión de Renfe se renueva si falla la consulta.
Para vigilar solo un tren, añade `"departure": "20:08"` a la configuración.
Para cambiar el trayecto, define `originCode`, `originName`, `destinationCode` y
`destinationName` con los datos de las estaciones de Renfe. Sin esos campos,
el trayecto predeterminado es Vilagarcía de Arousa → Santiago de Compostela.
La disponibilidad de venta pública puede ser distinta de la disponibilidad para
formalizar un bono. Comprueba la plaza en Renfe antes de desplazarte o comprar.

## Features

- Identidad Pausa · Amanecer: crema, verde bosque, terracota, salvia y sol; titulares serif,
  superficies suaves y un halo de luz en «Hoy» que cambia de color por la mañana, la tarde y la noche.
- Navegación inferior flotante con `Hoy`, `Tareas`, `Hábitos` y `Viajes`, al alcance del pulgar.
  Un indicador crema se desliza hasta el destino elegido. A su lado, un botón de acción terracota
  cambia según la sección: nueva tarea, añadir al plan de hoy o de mañana, nuevo hábito o revisar viajes.
- `Hoy` es el panel diario: saludo según la hora, calorías en un arco de amanecer con cantidad libre,
  proteína y cigarros compactos, el ritual de revisar Notion, el plan del día por franjas con marcado
  directo y un acceso al plan de mañana. Los ajustes viven en una hoja aparte.
- `Tareas` reúne `Lista`, `Hoy`, `Mañana` y `Semana` en un selector segmentado. Cada sección conserva
  su última vista. La lista agrupa Hoy, Pendientes, Planificadas y Hechas (plegable); tocar una tarea
  abre su editor, que también la planifica en un día y una franja. Deslizar a la izquierda la elimina.
- Hojas inferiores coherentes en lugar de diálogos del sistema, con asa para cerrar arrastrando.
  Las acciones que borran o reinician ofrecen `Deshacer` en un aviso flotante en vez de pedir confirmación.
- Movimiento con propósito: las secciones aparecen elevándose, los segmentos se deslizan en la
  dirección del cambio, los contadores cuentan hasta su valor y las marcas se dibujan. Todo respeta
  la escala de animación del sistema.
- Los contenedores desplazables descuentan la barra flotante: el foco, el teclado y la accesibilidad
  siempre dejan el control visible por encima de ella. La barra se oculta con el teclado.
- `Semana` muestra una tira de siete días y una línea de tiempo con bloques de media hora para hoy y los
  seis días siguientes. Tocar un bloque permite asignar una tarea pendiente, crear una nueva o vaciarlo.
  Conserva las antiguas asignaciones horarias y funciona independientemente del tablero de `Mañana`.
- Conserva localmente los contadores de calorías, cigarros y proteína.
- Mantiene las acciones rápidas desde las notificaciones.
- Hábitos recurrentes con una marca diaria y un mínimo semanal configurable de 1 a 7.
  La primera semana cumplida suma 5 puntos y cada semana consecutiva multiplica la
  recompensa anterior por 1,15, redondeando hacia arriba (`5, 6, 7, 9, 11…`). Cada
  repetición extra suma el 20 % redondeado hacia arriba. Una semana fallida reduce
  en un 15 % el marcador del hábito y reinicia la recompensa base en 5. El editor
  permite corregir el número de días realizados esta semana y recalcula sus puntos,
  sin modificar semanas anteriores. El recuento no puede superar los días transcurridos.
  Cada tarjeta muestra progreso, racha, puntos y una curva acumulada; el periodo
  seleccionable de 10, 20, 26, 52 o 104 semanas se guarda y la curva se amplía al tocarla.
- Tableros de hoy y mañana con tres franjas de tamaño flexible: mañana, tarde y noche.
  Comparten distribución, edición, arrastre y deshacer. Al cambiar la fecha local,
  el plan preparado para mañana aparece en Hoy con sus franjas y su orden.
- Mantener pulsado y arrastrar para ordenar tareas o cambiar de franja, con
  indicador de posición y desplazamiento automático.
- Añadido continuo de tareas nuevas, selección de pendientes y recuperación
  de tareas de hoy desde un panel inferior. Guardado automático y deshacer al quitar.
- El plan preparado pasa a «Hoy» en la lista de tareas al cambiar el día.
  Las tareas terminadas que se repiten desde «De hoy» crean una nueva tarea pendiente.
- Migración de las antiguas asignaciones por hora a franjas, conservando sus fechas.
- Planificador semanal para Sergio, Miriam o ambos.
- 27 horarios predefinidos A Coruña → Santiago y 28 horarios Santiago → A Coruña.
- Una ida y una vuelta opcionales por cada día laborable, de lunes a viernes.
- Perfiles WebView aislados para las dos cuentas de Renfe.
- Automatización de la multiformalización mediante los formularios y llamadas
  DWR de Renfe, sin depender de coordenadas o del diseño visual.
- Selección automática de asiento mediante el protocolo propio del plano de
  Renfe.
- Si viajan Sergio y Miriam, procesa cada tren de forma consecutiva, busca dos
  plazas contiguas y reserva en la segunda cuenta la plaza compañera exacta.
- En numeración simple solo considera parejas `1-2`, `3-4`, `5-6`…; en la
  numeración de AVE admite `A-B`, `C-D` y `D-E` dentro de la misma fila.
- Compara los coches y penaliza las plazas próximas a marcadores de mesa,
  manteniéndolas como alternativa si no quedan plazas ordinarias.
- Prioriza parejas y plazas individuales en el sentido de la marcha cuando la
  preferencia de mesa es equivalente.
- Si la plaza contigua deja de estar disponible, se detiene antes de asignar
  dos asientos separados.
- Pausas controladas para inicio de sesión, verificación en dos pasos,
  trenes no disponibles o cambios en la página de Renfe.

Las contraseñas y códigos de verificación no se guardan en la aplicación.
La selección contigua entre cuentas no es atómica: Renfe formaliza primero una
plaza y, a continuación, la plaza compañera. La aplicación nunca sustituye esa
segunda plaza por otra separada sin avisar.

## Build

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-17'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
.\gradlew.bat assembleDebug
```

The debug APK is created at:

```text
app\build\outputs\apk\debug\app-debug.apk
```

## Prueba de los tableros de hoy y mañana

La prueba instrumental `PlannerSmokeTest` comprueba migración, persistencia,
añadido continuo, repetición de tareas terminadas, arrastre táctil y deshacer.
También simula medianoche y cambio de año, conserva el orden y comprueba
que el plan se recupera al reabrir la app sin duplicar tareas.
Ejecutarla únicamente en un emulador desechable: sustituye las tareas y los planes
con datos de prueba. Compilar con `assembleDebug assembleDebugAndroidTest`, instalar
ambos APK en el emulador y ejecutar:

```powershell
adb -s emulator-5554 shell pm grant com.sejio.calorapp android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell am instrument -w com.sejio.calorapp.test/com.sejio.calorapp.PlannerSmokeTest
adb -s emulator-5554 shell am instrument -w -e dayOffset 0 com.sejio.calorapp.test/com.sejio.calorapp.PlannerSmokeTest
```

El resultado debe indicar `PASS`; la captura se guarda en los archivos externos
de la aplicación como `planner-smoke.png` y `planner-today-smoke.png`.

## Prueba del rediseño

Además de `PlannerSmokeTest`, `PausaUiSmokeTest` recorre las siete vistas con toques reales y comprueba
la barra flotante, el selector de `Tareas`, los contadores, la hoja de ajustes, los borradores, el
teclado, la agenda semanal, el arrastre de las hojas, el botón de acción contextual y la navegación
rápida. Ejecutar solo en el emulador de pruebas.
Para elegir este ejecutor al compilar en PowerShell:

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest '-PuiTestRunner=com.sejio.calorapp.PausaUiSmokeTest'
adb -s emulator-5554 install -r appuild\outputspk\debugpp-debug.apk
adb -s emulator-5554 install -r appuild\outputspkndroidTest\debugpp-debug-androidTest.apk
adb -s emulator-5554 shell pm grant com.sejio.calorapp android.permission.POST_NOTIFICATIONS
adb -s emulator-5554 shell am instrument -w com.sejio.calorapp.test/com.sejio.calorapp.PausaUiSmokeTest
```

Sin el argumento `uiTestRunner` se conserva el ejecutor de planificación original.
Las capturas se guardan en los archivos externos de la app, carpeta `pausa-review`.
La comprobación visual incluye tamaño normal, 320 dp de ancho con fuente al 130 % y animaciones
desactivadas. La app mantiene siempre la paleta clara de Pausa. El emulador usa SwiftShader: sus
métricas no certifican 60 fps en un teléfono físico.

`PausaDemoSeed` sustituye los datos del emulador por un día de ejemplo realista (tareas, planes,
bloques semanales, hábitos con historial y contadores) para revisar el diseño. Se ejecuta igual que
las demás pruebas usando su nombre como `uiTestRunner`. No ejecutarlo en el teléfono personal.

La prueba instrumental `HabitStoreSmokeTest` valida la puntuación escalonada, los
extras, el deshacer y la caída semanal sin duplicados:

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest '-PuiTestRunner=com.sejio.calorapp.HabitStoreSmokeTest'
adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s emulator-5554 install -r app\build\outputs\apk\androidTest\debug\app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w com.sejio.calorapp.test/com.sejio.calorapp.HabitStoreSmokeTest
```

`HabitUiSmokeTest` comprueba con pulsaciones reales el editor del recuento, el selector
de semanas y el gráfico ampliado. Se ejecuta del mismo modo usando su nombre como
`uiTestRunner` y como ejecutor de `am instrument`. Ambas pruebas sustituyen los hábitos
del emulador por datos de prueba: no ejecutarlas en el teléfono personal.

## Google Calendar y horarios

En Ajustes → Google Calendar se conecta una cuenta y se solicitan permisos al usar la integración.
El proyecto `pausa-calendar-sc00` tiene Calendar API, consentimiento en pruebas y cliente Android para
`com.sejio.calorapp`, certificado SHA-1 `32:08:9C:FD:B5:51:20:0C:BA:3B:99:92:3E:BD:A3:55:CA:D0:D9:95`.
Otra firma o variante necesita su propio cliente Android. No se incluye un secreto OAuth en la APK.
Las APIs usadas siguen la [autorización de Android](https://developer.android.com/identity/authorization).

Las tareas guardadas con día, hora y duración crean o actualizan eventos en el calendario secundario Pausa.
Los permisos son `calendar.app.created` y `calendar.calendarlist.readonly`; el segundo permite recuperar
el calendario si se pierde la respuesta de su creación. Las tareas sin fecha/hora y los seguimientos no
generan eventos. Quitar el horario o eliminar la tarea elimina su evento; completar una tarea lo conserva.
La sincronización es de Pausa hacia Google, con cola local y reintentos cuando vuelve la red.
Los bloques heredados conservan su horario, pero solo se exportan al volver a guardarlos explícitamente.
Desconectar detiene la sincronización y conserva los eventos existentes.

`FeedbackSmokeTest` comprueba persistencia, horarios, estados y crédito con datos sintéticos.
Solo debe ejecutarse en un emulador desechable: reemplaza las preferencias de tareas y presupuesto.
La prueba no valida una sesión real de Google ni una sesión bancaria.

En 6.8.1 se añade `ACCESS_NETWORK_STATE`, requerido por JobScheduler para la cola con red.
Se recupera la cola al abrir Pausa y un error al programarla deja un estado visible sin cerrar la app.
`CalendarSchedulingSmokeTest` reproduce la conexión activada, la recuperación de la cola y el guardado
de una tarea con hora. Ejecutarlo solo en un emulador desechable: limpia las preferencias de Calendar.
El 12 de octubre de 2026 se verificó en el Pixel la conexión real y el estado «Sincronizado con el
calendario Pausa». No había horarios elegibles guardados tras el cierre, por lo que la creación real
de un evento desde una tarea nueva queda por comprobar al guardar su hora.

## Diagnóstico de precios y movimientos

En Dinero → Cartera, pulsar Actualizar valoración y después Diagnóstico de precios → Copiar diagnóstico.
El informe registra etapas, estructura de respuestas y códigos de error; no incluye tokens, conceptos,
instrumentos, cantidades ni precios. La fuente sigue siendo Trade Republic mediante su conexión nativa.
El diagnóstico real identificó dos instrumentos `fund` con factor unitario que el filtro anterior
rechazaba. En 6.8.1 se admite ese caso y se renueva la caché antigua; ambos devolvieron `VALUED` en el Pixel.

Movimientos permite renombrar una transacción conservando su concepto bancario y desplegar Tarjetas de crédito.

En 6.8.2, las copias de compras y devoluciones de débito en cuenta se concilian con la tarjeta
por banco, importe con signo, fecha valor y comercio. Se reconocen tanto el prefijo del extracto
como los tipos «OPERACIONES TARJETA DEBITO» y «OPERACIONES T.P.V.» de la web. El emparejamiento
es uno a uno; la copia permanece visible sin contar y enlaza al movimiento de tarjeta.
Las reglas generales de comercios no reclasifican transferencias ni movimientos ignorados;
se mantienen las correcciones explícitas sobre un movimiento concreto.
La previsión usa el ritmo de compras, con las devoluciones recibidas aplicadas una sola vez.
El detalle «Cómo se calcula» separa compras, devoluciones y gasto previsto hasta el cierre.
Verificado con 130 pruebas JVM, 18 comprobaciones de interfaz en emulador y lint.

En 6.8.3, las respuestas de «Por revisar» guardan también la clasificación explícita del movimiento.
Se reconocen las confirmaciones «Entre mis cuentas» ya recordadas sin permitir que otras reglas
generales conviertan traspasos en ingresos o devoluciones. `BudgetUiSmokeTest -e reviewOnly true`
comprueba la respuesta, su desaparición de pendientes, deshacer y reabrir la revisión en un emulador.

Las compras del ciclo se agrupan por cobro previsto: último día del mes siguiente a la compra. La clasificación
de crédito se puede corregir por tarjeta. La previsión no equivale a una confirmación bancaria de pago.
Los pagos fijos admiten concepto y rango de importe, con extremos incluidos. Reasignar manualmente un pago a
un fijo con rango actualiza su importe previsto; el rango permanece como lo configuró el usuario.

## Install Over USB

1. On the phone, enable Developer options.
2. Enable USB debugging.
3. Plug the phone into this computer and accept the USB debugging prompt on the phone.
4. Run:

```powershell
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" devices
& "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe" install -r app\build\outputs\apk\debug\app-debug.apk
```

When the app opens, allow notification permission so the sticky notification can appear.

## Prueba del Cogedor con asientos

1. Abre `Viajes`, marca el viajero o los viajeros y elige un viaje futuro.
2. Pulsa `Revisar y formalizar`.
3. Inicia sesión en la ventana de Renfe y completa la verificación en dos pasos.
4. Cuando aparezca la cuenta, pulsa `Continuar`.
5. La aplicación abrirá el plano, buscará primero plazas a favor de la marcha
   y formalizará una fecha cada vez.
6. Si están marcados Sergio y Miriam, inicia sesión en cada perfil cuando se
   solicite y comprueba al final que las dos plazas son contiguas.

La web de Renfe puede cambiar sin previo aviso. Cuando una pantalla no se
reconozca, la aplicación se detiene y permite completar ese paso manualmente
antes de continuar.
