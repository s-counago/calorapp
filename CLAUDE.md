# Pausa: guía para agentes

Pausa es la app personal de Sejio para organizar su vida. Tiene un solo usuario. La interfaz y la documentación están en español.

- `app/`: app Android nativa en Java (sin frameworks de UI). Es la interfaz principal y tiene que funcionar sin conexión: Sejio pasa horas al día sin cobertura.
- `backend/`: Cloudflare Worker + D1. Es la fuente común de datos, y sirve la API de sincronización, la web y el servidor MCP. Lee `backend/README.md`.
- `tools/`: monitor de plazas de Renfe y pruebas en Node.

## Regla principal: primero el backend, y el MCP siempre al día

Cualquier petición que cree o cambie datos o lógica (tareas, hábitos, plan, contadores, revisiones, Notion…) empieza en el backend, en este orden:

1. **Migración nueva** en `backend/migrations/NNNN_descripcion.sql`. Nunca edites una migración que ya existe: puede estar aplicada en producción.
2. **Registro de tablas** en `backend/src/schema.ts`, si cambian campos sincronizados.
3. **Lógica** en `backend/src/domain.ts`. La usan a la vez la API REST y el MCP, así que no la dupliques en las rutas.
4. **API REST** en `backend/src/app.ts`.
5. **Herramienta MCP** en `backend/src/mcp.ts`. Todo lo que el usuario pueda hacer con sus datos tiene que poder hacerlo un agente. Escribe descripciones claras (qué hace, unidades, formato de fechas), marca `readOnly` correctamente y añade la herramienta a la lista de `backend/README.md`. Un test falla si falta.
6. **Tests** en `backend/test/`. Ejecuta `npm test` y `npm run typecheck` dentro de `backend/` antes de cada commit.
7. Solo entonces, adapta Android (sincronización y vistas) y la web.

Si una petición parece solo de Android pero toca datos, aplica igualmente esta regla. Lo que de verdad es solo Android (diseño, animaciones, notificaciones, Renfe) no necesita backend.

## Contrato de sincronización (no romperlo)

- Cada registro sincronizado tiene `id` (UUID generado por el cliente), `updated_at` (ms del dispositivo que lo cambió), `deleted` (borrado lógico) y `seq` (lo asigna el servidor).
- Los clientes envían siempre el registro completo. Gana el `updated_at` más reciente y los borrados viajan como tombstones.
- Los contadores (calorías, proteína, cigarros) son entradas en `metric_entries`, nunca totales. Así no se pisan dos sumas hechas sin conexión.
- `habit_checks.id` es `<habit_id>:<YYYY-MM-DD>`.
- Un campo nuevo tiene que ser opcional o tener un valor por defecto: hay teléfonos con versiones antiguas que siguen enviando registros.
- Toda escritura queda en `change_log` con su autor (`device:<id>`, `web`, `mcp:<cliente>`).

## Funciones activables

Las funciones nuevas que cambian cómo se ve o se comporta la app se pueden encender y apagar desde Ajustes → Funciones (y por MCP con `list_features` / `set_feature`). Cada una es el ajuste sincronizado `feature:<clave>` con valor `on` u `off`. Para añadir una: entrada en `FEATURES` de `backend/src/domain.ts` y en `Features.ALL` de Android con la misma clave, y un valor por defecto que deje la app como estaba. Consulta `Features.isOn(context, Features.X)` donde la función aparezca.

## Lo que no sale del teléfono

La banca (ABANCA, Trade Republic, presupuesto con movimientos y credenciales) se queda cifrada en el móvil. No la subas al backend ni la expongas por MCP sin que Sejio lo pida expresamente.

## Observabilidad

El objetivo es que cualquier fallo se pueda depurar sin tener el móvil delante.

- **Backend:** usa `log(level, event, fields)` de `backend/src/log.ts`, nunca `console.log` suelto. Cada línea es JSON con `request_id`, `actor` y `source`. Los errores devuelven `{ error, code, request_id }`. Nunca registres tokens, secretos ni el contenido de los cuerpos de las peticiones.
- **Android:** usa `PausaLog` (etiqueta `Pausa` en logcat, archivo rotativo `files/logs/pausa.log`), nunca `android.util.Log` suelto. Los avisos, errores y eventos de la etiqueta `sync` se guardan en la cola `log_queue` y se suben a `POST /api/logs` en cada sincronización. Cada petición al backend envía `X-Request-Id` y lo registra junto con el que devuelve el servidor, para cruzar las dos partes. Los cierres inesperados quedan en el log (`PausaApp`). Nunca registres tokens, credenciales ni datos de la banca.

## Android sin conexión

- `PausaDb` (SQLite, `databases/pausa.db`) es la fuente de verdad del teléfono. Tiene las mismas tablas sincronizadas que el backend (`SyncSchema` es el espejo de `backend/src/schema.ts`: si añades un campo allí, añádelo también aquí y en `PausaDb.onCreate`/`onUpgrade`), más `outbox`, `sync_state`, `log_queue` y `habit_scores` (solo local).
- Las vistas no cambian: `TaskStore`, `DayPlanStore`, `HabitStore` y `CalorieStore` leen y escriben `PausaDb`. Toda escritura pasa por `PausaDb.put`, que pone `updated_at`, encola el registro y pide una sincronización.
- Tareas y hábitos conservan un `local_id` numérico (el id antiguo) para las vistas, las franjas de `TaskScheduleStore` y Google Calendar; el `id` del backend es un UUID.
- El plan del día vive en la tarea (`plan_date`, `plan_slot`, `position`): una tarea está planificada como mucho en un día. El orden de la lista es el de creación.
- Las tareas con `board` (`stack`, `priority`, `vault`, `failed`) son el tablero Kanban (`KanbanView`, detrás de la función `task_kanban`) y no salen en la lista salvo que estén planificadas en un día. Las hechas del tablero suman al contador `tasks_done_base` (ajuste sincronizado) para dar el total de tareas hechas.
- Los contadores son entradas del día natural; reiniciar es una entrada negativa. Los objetivos son `settings`.
- Los puntos de los hábitos se calculan en el teléfono a partir de los checks sincronizados.
- `HealthConnect` lee del Health Connect del sistema (Android 14+) el sueño, los pasos, el ejercicio y el pulso en reposo que guarda el Pixel Watch, y escribe una fila de `health_days` por día (id = el día) solo si cambia. Lee al abrir Hoy, como mucho cada 5 minutos, detrás de la función `health_connect`. El backend tiene que conocer `health_days` (migración 0006) antes de encender la función: si no, el push devuelve 400 y la sincronización se atasca.
- Se quedan solo en el teléfono: banca, `TaskScheduleStore` y Google Calendar, rituales diarios, notificación y gráficas.
- `PausaSync` programa `SyncWorker` con WorkManager (tras cada cambio, al abrir la app y cada 30 min, siempre con red). `SyncEngine` hace push por lotes, pull paginado y gana el último `updated_at`.
- `LegacyImport` pasó una sola vez los datos de SharedPreferences a `PausaDb` y dejó las preferencias como copia.
- Login: Ajustes → Nube (`CloudActivity`, `CloudAuth`): Credential Manager con el client ID web como `serverClientId`, `POST /auth/device` y token cifrado con Android Keystore. Necesita en Google Cloud una credencial Android con `com.sejio.calorapp` y la SHA-1 de la firma.

## Cómo depurar

1. **Estado de la sincronización:** `GET /api/status` o la herramienta MCP `sync_status`. Muestra cada dispositivo, su último pull y push, cuánto va por detrás y los errores recientes del cliente.
2. **Logs de la app:** `GET /api/logs?level=warn` o `get_client_logs`.
3. **Historial de un registro** (por qué cambió o desapareció): `GET /api/history/<tabla>/<id>` o `record_history`.
4. **Logs del Worker:** `npx wrangler tail --format json` dentro de `backend/`, o Workers Logs en el panel de Cloudflare. Filtra por `request_id`.
5. **Pantalla de Diagnóstico** en el teléfono: mantén pulsada la versión en Ajustes o el estado en Ajustes → Nube. Muestra estado, cursor, pendientes por tabla, último error y el log local, y permite forzar la sincronización.
6. **Teléfono por adb**, solo cuando esté conectado al PC por USB o depuración inalámbrica:
   - `adb logcat -s Pausa:V AndroidRuntime:E`
   - `adb shell run-as com.sejio.calorapp cat files/logs/pausa.log`
   - `adb shell run-as com.sejio.calorapp ls databases shared_prefs`
   - Instalar: `.\gradlew.bat assembleDebug` y `adb install -r app\build\outputs\apk\debug\app-debug.apk`

## Pruebas en Android

Las pruebas instrumentadas (`PlannerSmokeTest`, `PausaUiSmokeTest`, `HabitStoreSmokeTest`, `HabitUiSmokeTest`, `SyncStoreSmokeTest`, `CloudSyncProbe`, `PausaDemoSeed`…) **borran los datos de la app**. Ejecútalas solo en un emulador, nunca en el teléfono personal. Antes de instalar algo en el teléfono, confirma que la firma coincide con la de la app instalada: si no coincide, Android obliga a desinstalar y se pierden los datos locales que aún no estén sincronizados.

Cada prueba es su propio runner: `.\gradlew.bat assembleDebug assembleDebugAndroidTest -PuiTestRunner=com.sejio.calorapp.<Prueba>`, instala los dos APK en el emulador (`adb -s emulator-5554 install -r -t …`) y ejecuta `adb -s emulator-5554 shell am instrument -w com.sejio.calorapp.test/com.sejio.calorapp.<Prueba>`. Para reiniciar datos en una prueba usa `PausaTestData.reset`.

**Sincronización de punta a punta sin tocar producción:** en `backend/`, crea `.dev.vars` a partir de `.dev.vars.example`, ejecuta `npm run db:migrate:local` y `npx wrangler dev --port 8787`, e inserta un dispositivo de prueba en la D1 local (`token_hash` = SHA-256 en base64url del token `pd_…`). Compila la app con `-PpausaApiUrl=http://10.0.2.2:8787 -PuiTestRunner=com.sejio.calorapp.CloudSyncProbe` (solo las compilaciones debug permiten HTTP a esa dirección) y ejecuta los pasos `-e step reset`, `-e step login -e token pd_…`, `-e step edit`, `-e step sync`, `-e step dump`. El modo avión se activa con `adb -s emulator-5554 shell cmd connectivity airplane-mode enable|disable`.

## Trabajo

- Haz commit y push al terminar cada paso. Un backend entero se perdió una vez por quedarse en un entorno temporal.
- No cambies `wrangler.jsonc` de producción (ids, `PUBLIC_URL`) ni ejecutes `npm run deploy` o `db:migrate:remote` sin que Sejio lo pida.
