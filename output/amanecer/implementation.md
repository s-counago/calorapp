# Sejio 6.0 · Pausa Amanecer

Rediseño completo de la interfaz, terminado el 2 de octubre de 2026. Conserva la paleta, la tipografía
serif y la idea de Pausa; cambia la arquitectura, los flujos y el movimiento.

## Concepto

La app sigue la luz del día. «Hoy» saluda según la hora y tiñe su cabecera de sol por la mañana,
de terracota por la tarde y de salvia por la noche. Las calorías se dibujan como un amanecer: un
arco que sube desde el horizonte con un pequeño sol en la punta. Todo lo demás se aparta para dejar
sitio al contenido.

## Qué cambia

- **Navegación inferior flotante.** Sustituye la cabecera de marca fija y las dos barras de pestañas
  que ocupaban casi un tercio de la pantalla. Un indicador crema se estira y se desliza entre destinos.
- **Botón de acción contextual.** Crea una tarea en «Hoy» y en la lista, añade al plan en los tableros,
  crea un hábito en «Hábitos» y abre la revisión en «Viajes».
- **Arquitectura sin duplicados.** Antes existían «Hoy › Diario/Tareas» y «Plan › Hoy/Mañana/Semana».
  Ahora «Hoy» es el panel diario y «Tareas» agrupa Lista, Hoy, Mañana y Semana en un selector segmentado.
- **Panel «Hoy».** Saludo, arco de calorías con cantidad libre y límite editable, proteína y cigarros en
  tiras compactas, ritual de Notion, plan del día por franjas con la actual marcada y acceso a mañana.
- **Lista de tareas.** Compositor fijo con botón de envío que aparece al escribir. Grupos en tarjetas,
  «Hechas» plegable, deslizar para eliminar y un editor que además planifica día y franja.
- **Semana.** Tira de siete días y línea de tiempo de medias horas, en lugar del acordeón.
- **Hábitos.** Tarjeta protagonista oscura con el total, el anillo de mínimos y la semana en puntos.
  Cada hábito muestra sus siete días, racha, recompensa y curva; la marca se dibuja al tocarla.
- **Viajes.** Viajeros como píldoras, semana con etiqueta relativa, días en una sola tarjeta con
  billetes de ida y vuelta, horarios en cuadrícula por franjas con opción «Toda la semana» y un
  resumen oscuro que cuenta las formalizaciones antes de revisar.
- **Hojas en lugar de diálogos.** Todas las ediciones usan hojas inferiores con asa. Borrar o poner a
  cero ofrece «Deshacer» en un aviso flotante en vez de pedir confirmación. Eliminar un hábito, que
  borra su historial, pide un segundo toque.
- **Accesibilidad.** Etiquetas habladas en controles de icono, marcas que se anuncian como casillas,
  objetivos táctiles de 48 dp y desplazamientos que nunca dejan el foco bajo la barra flotante.

## Archivos nuevos

- `TodayView.java`: panel diario.
- `TaskSheets.java`: captura rápida continua y editor con planificación.
- `Meters.java`: arco de amanecer, puntos de cigarros, semana de hábito y anillo de mínimos.
- `PausaDemoSeed.java` (androidTest): datos de ejemplo para revisar el diseño en el emulador.

`PausaUi.java` crece hasta ser el sistema de diseño: hojas, avisos, casillas animadas, selector
segmentado, fila deslizable, contenedor desplazable consciente de la barra y la familia de iconos.

## Validación

Todas las pruebas se ejecutaron únicamente en `emulator-5554` con Android 15.

| Prueba | Normal | 320 dp y fuente 130 % | Sin animaciones |
|---|---|---|---|
| `PausaUiSmokeTest` | 53 correctas | 53 correctas | 53 correctas |
| `PlannerSmokeTest` mañana | 24 correctas | 24 correctas | — |
| `PlannerSmokeTest` hoy | 24 correctas | 24 correctas | — |
| `HabitUiSmokeTest` | 6 correctas | 6 correctas | — |
| `HabitStoreSmokeTest` | 38 correctas | — | — |

`assembleDebug`, `assembleDebugAndroidTest` y `lintDebug` terminan sin errores.

Las capturas revisadas están en `capturas/`, con `galeria.png` y `antes-despues.png` como resumen.
La persistencia de contadores, tareas, planes, hábitos y viajes conserva sus formatos. El motor de
reservas de Renfe, sus pantallas y las reglas de puntuación no se han modificado. No se hicieron
reservas reales.

Copia del código anterior: `../pre-amanecer-source.zip`.
