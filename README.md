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
