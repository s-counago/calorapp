# Sejio 5.0 · Pausa

Implementación nativa Android del concepto Pausa, terminada el 17 de septiembre de 2026.

- Fondo crema, verde bosque, terracota, salvia y amarillo cálido.
- Logotipo serif, símbolo del amanecer e icono adaptativo.
- Cuatro secciones superiores: Hoy, Plan, Hábitos y Viajes.
- Diario/Tareas dentro de Hoy; Mañana/Semana dentro de Plan.
- Resumen de hasta tres tareas de hoy en Diario, con marcado conectado al almacén de tareas.
- Tarjetas, formularios, diálogos y gráficos adaptados al tema claro.
- Cabecera y navegación se ocultan al abrir el teclado. Hábitos desplaza la pantalla completa para mantener accesibles los controles con texto grande.

## Validación

- assembleDebug y assembleDebugAndroidTest: correctos.
- lintDebug: 0 errores y 56 avisos; quedan avisos de recursos, traducciones y otras recomendaciones.
- PausaUiSmokeTest: 33 comprobaciones correctas en tamaño normal y otras 33 a 320 dp, fuente al 130 % y sistema en modo oscuro.
- PlannerSmokeTest: 13 comprobaciones correctas, incluido arrastre real, persistencia y deshacer.
- HabitUiSmokeTest: 6 comprobaciones correctas en tamaño normal y otras 6 en 320 dp con fuente al 130 %, incluido el gráfico ampliado tras desplazar la pantalla.
- Capturas revisadas visualmente. Pruebas ejecutadas únicamente en emulator-5554; no se hicieron reservas reales de Renfe.

## Archivos

- APK: ../../app/build/outputs/apk/debug/app-debug.apk (versión 5.0, código 15).
- Galería: index.html.
- Capturas: standard, compact, habits y habits-compact.
- Copia del código anterior: ../pre-pausa-source.zip.
- Concepto: ../branding-concepts/02-pausa.png.

La persistencia de contadores, tareas, hábitos y planes conserva sus formatos. El motor de reservas y las reglas de puntuación no se han modificado.
