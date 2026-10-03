# Sejio 5.1 · Plan de hoy

Plan incorpora Hoy, Mañana y Semana. Hoy y Mañana utilizan el mismo componente con fechas distintas y conservan todas las operaciones del tablero. Plan abre Hoy inicialmente y recuerda la última pestaña durante la sesión.

Los planes existentes siguen guardados por fecha. Al pasar medianoche, Hoy lee la fecha nueva y muestra lo preparado en Mañana, con los mismos identificadores, franjas y orden. También se actualiza al reabrir la app. Los editores y el deshacer del día anterior se cierran al cambiar de fecha.

Verificación en emulator-5554:

- Compilación de la app y de las pruebas: correcta.
- PlannerSmokeTest para Hoy: 23 comprobaciones correctas.
- PlannerSmokeTest para Mañana: 23 comprobaciones correctas.
- Incluye arrastre real, añadido continuo, retirada y deshacer, separación entre días, medianoche en Europe/Madrid, cambio de año y reapertura.
- PausaUiSmokeTest: 36 comprobaciones correctas, incluyendo las siete vistas.
- lintDebug: 0 errores, 55 avisos.
- Captura de Hoy revisada: hoy.png.

APK actualizada: ../../../app/build/outputs/apk/debug/app-debug.apk, versión 5.1, código 16.
