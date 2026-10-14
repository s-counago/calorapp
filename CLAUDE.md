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

## Lo que no sale del teléfono

La banca (ABANCA, Trade Republic, presupuesto con movimientos y credenciales) se queda cifrada en el móvil. No la subas al backend ni la expongas por MCP sin que Sejio lo pida expresamente.

## Observabilidad

El objetivo es que cualquier fallo se pueda depurar sin tener el móvil delante.

- **Backend:** usa `log(level, event, fields)` de `backend/src/log.ts`, nunca `console.log` suelto. Cada línea es JSON con `request_id`, `actor` y `source`. Los errores devuelven `{ error, code, request_id }`. Nunca registres tokens, secretos ni el contenido de los cuerpos de las peticiones.
- **Android:** cada petición al backend envía `X-Request-Id` y registra el que devuelve el servidor, para cruzar las dos partes. *(Pendiente para la fase de sincronización en Android: un logger `PausaLog`, con la etiqueta `Pausa` en logcat, un archivo rotativo y subida de avisos y errores a `POST /api/logs`, más una pantalla oculta de Diagnóstico.)*

## Cómo depurar

1. **Estado de la sincronización:** `GET /api/status` o la herramienta MCP `sync_status`. Muestra cada dispositivo, su último pull y push, cuánto va por detrás y los errores recientes del cliente.
2. **Logs de la app:** `GET /api/logs?level=warn` o `get_client_logs`.
3. **Historial de un registro** (por qué cambió o desapareció): `GET /api/history/<tabla>/<id>` o `record_history`.
4. **Logs del Worker:** `npx wrangler tail --format json` dentro de `backend/`, o Workers Logs en el panel de Cloudflare. Filtra por `request_id`.
5. **Teléfono por adb**, solo cuando esté conectado al PC por USB o depuración inalámbrica:
   - `adb logcat -s Pausa:V AndroidRuntime:E`
   - `adb shell run-as com.sejio.calorapp ls databases shared_prefs`
   - Instalar: `.\gradlew.bat assembleDebug` y `adb install -r app\build\outputs\apk\debug\app-debug.apk`

## Pruebas en Android

Las pruebas instrumentadas (`PlannerSmokeTest`, `PausaUiSmokeTest`, `HabitStoreSmokeTest`, `HabitUiSmokeTest`, `PausaDemoSeed`…) **borran los datos de la app**. Ejecútalas solo en un emulador, nunca en el teléfono personal. Antes de instalar algo en el teléfono, confirma que la firma coincide con la de la app instalada: si no coincide, Android obliga a desinstalar y se pierden los datos locales que aún no estén sincronizados.

## Trabajo

- Haz commit y push al terminar cada paso. Un backend entero se perdió una vez por quedarse en un entorno temporal.
- No cambies `wrangler.jsonc` de producción (ids, `PUBLIC_URL`) ni ejecutes `npm run deploy` o `db:migrate:remote` sin que Sejio lo pida.
