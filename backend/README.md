# Backend de Pausa

Un único Cloudflare Worker con D1 que sirve tres cosas:

- **API de sincronización** para la app Android, que sigue funcionando sin conexión.
- **Web** para usar Pausa desde el ordenador (`public/`, ver abajo).
- **Servidor MCP** en `/mcp` para que Claude u otros agentes lean y editen los datos.

Solo entra una cuenta de Google, la de `ALLOWED_EMAIL`. La banca no pasa por aquí: los movimientos y las credenciales se quedan cifrados en el móvil; solo sube, si lo enciendes, el resumen de cada ciclo (`budget_cycles`).

## Datos

| Tabla | Qué guarda |
| --- | --- |
| `tasks` | Tareas con proyecto, área, prioridad, fecha límite, día y franja del plan, origen (`source`, `source_id`) para importar desde Notion y seguimiento: `following` marca las tareas que esperan a alguien (`waiting_for`, `expected_date`, `review_date`) y no entran en el plan. `board` coloca la tarea en el tablero Kanban: `stack` (stack infinito), `priority` (prioridad), `vault` (baúl) o `failed` (fracasos); vacío si es una tarea normal. |
| `habits`, `habit_checks` | Hábitos con objetivo semanal y una fila por día hecho (`<habit_id>:<día>`). |
| `metric_entries` | Calorías, proteína y cigarros como entradas sueltas (+300 kcal a las 14:02), no como un total. |
| `settings` | Objetivos diarios (`calorie_goal`, `protein_goal`, `cigarette_goal`) y `tasks_done_base`, las tareas hechas antes del tablero (el contador de Notion). |
| `health_days` | Una fila por día (el id es el propio día) con lo que el móvil lee de Health Connect: minutos dormidos la noche que acaba ese día, inicio y fin del sueño, pasos, minutos de ejercicio y pulso en reposo. Solo la escribe el teléfono. |
| `budget_cycles` | El resumen de cada ciclo de nómina a nómina que calcula el móvil (id = primer día): ingresos, fijos, ahorro, dinero libre, colchón, cada línea y tope con lo previsto y lo pagado, y el gasto libre por categoría, en céntimos. Sin movimientos, comercios, cuentas ni credenciales. Solo lo escribe el teléfono, y solo con la función `budget_cloud`. |
| `devices` | Teléfonos con sesión iniciada; solo se guarda el hash del token. |

## Sincronización

Cada registro lleva `id`, `updated_at` (milisegundos, de quien hizo el cambio), `deleted` y `seq` (número de cambio en el servidor).

1. El móvil guarda cada cambio en local y en una cola de pendientes.
2. Con cobertura, envía la cola: `POST /api/sync` con `{ "changes": [{ "table", "id", "updated_at", "deleted", "data" }] }`. Siempre se manda el registro completo, también al borrar.
3. Gana la edición más reciente de cada registro. La respuesta dice qué ids se aplicaron; los demás perdieron frente a un cambio más nuevo y llegarán en el siguiente paso.
4. Después pide lo nuevo: `GET /api/sync?since=<cursor>` devuelve los cambios con `seq` mayor que el cursor, el cursor nuevo y `more: true` si hay que pedir otra página.

Los contadores son entradas y no totales, así que sumar desde el móvil sin red y desde la web a la vez nunca se pisa.

## Autenticación

- **Web:** `/auth/login` lleva a Google y deja una cookie de sesión firmada durante 30 días.
- **Android:** inicia sesión con Google en el teléfono (Credential Manager, con el client ID web como `serverClientId`) y envía el ID token a `POST /auth/device` con `{ "id_token", "name" }`. Recibe un token `pd_…` que no caduca y se manda como `Authorization: Bearer`. Se revoca con `DELETE /api/devices/<id>`.
- **Agentes (MCP):** OAuth 2.1 con registro dinámico de clientes. La página de consentimiento usa la misma sesión de Google que la web.

## API

Todas las rutas `/api/*` necesitan sesión o token de dispositivo.

| Ruta | Uso |
| --- | --- |
| `GET/POST /api/sync` | Sincronización (ver arriba). |
| `GET /api/tasks` | Filtros: `status`, `plan_date`, `plan_from` y `plan_to` (rango del plan), `project`, `due_before`, `following` (`true`/`false`), `board` (columna o `none`), `search`, `limit`. |
| `POST /api/tasks`, `PATCH/DELETE /api/tasks/<id>` | Crear, editar y borrar. |
| `POST /api/tasks/import` | `{ "items": [{ "title", "notes", "board", "status", "source", "source_id", "created_at" }] }`, hasta 200. Se salta lo ya importado con el mismo `source` y `source_id`. |
| `GET /api/board`, `PUT /api/board/done` | Tareas abiertas por columna y `done_total` (`tasks_done_base` + tareas del tablero hechas). `PUT` con `{ "total" }` corrige el total. |
| `GET/POST /api/habits`, `POST /api/habits/<id>/check` | Hábitos de la semana con sus puntos, racha y gráfica (`?day=&weeks=`, de 10 a 104 semanas) y marcar un día (`{ "day", "done" }`). |
| `PATCH/DELETE /api/habits/<id>` | Cambiar nombre, objetivo semanal (1 a 7), `archived` o `position`, y borrar. |
| `GET /api/counters?day=&kind=` | Entradas de un día, en orden. Las correcciones son negativas. |
| `POST /api/counters` | `{ "kind": "calories" \| "protein" \| "cigarettes", "amount", "day" }`. |
| `PUT /api/counters` | `{ "kind", "total", "day" }`: fija el total de un día (añade la diferencia como corrección). Para días olvidados. |
| `GET/PUT /api/goals` | Objetivos diarios: `calorie_goal` (kcal), `protein_goal` (g) y `cigarette_goal`. `PUT` cambia solo los que envíes. |
| `GET /api/day?day=` | Contadores frente a objetivos, plan por franjas y hábitos del día. |
| `GET /api/review?day=&period=` | Revisión de la semana (lunes a domingo, `period=week`, por defecto) o del mes (`period=month`) que contiene ese día. |
| `GET /api/health?from=&to=` | Sueño, pasos, ejercicio y pulso en reposo por día (`health_days`) y sus medias. Por defecto, los 7 días que acaban hoy; como mucho un año. `GET /api/day` y `GET /api/review` también los incluyen en `health`. |
| `GET /api/budget?day=` | Resumen de dinero del ciclo que contiene ese día (por defecto el último) y la lista de ciclos. |
| `GET /api/features` | Funciones activables de las apps y si están encendidas. |
| `PUT /api/features/<clave>` | `{ "enabled": true \| false }`: enciende o apaga una función. Se guarda como el ajuste `feature:<clave>`. |
| `GET /api/devices`, `DELETE /api/devices/<id>` | Dispositivos con sesión. |
| `POST /api/logs`, `GET /api/logs` | Logs que envían las apps (`{ app_version, entries: [{ at, level, tag, message, data }] }`). Filtros: `level` (mínimo), `tag`, `device_id`, `search`, `since`, `limit`. Se guardan 30 días. |
| `GET /api/status` | Estado de la sincronización: `seq` del servidor, cada dispositivo con su último pull y push y cuánto va por detrás, recuento de registros y errores del cliente en 24 h. |
| `GET /api/history/<tabla>/<id>` | Todas las versiones guardadas de un registro y quién escribió cada una. |

Las fechas son `YYYY-MM-DD` en `Europe/Madrid` (`TIME_ZONE`).

## MCP

Herramientas de datos: `list_tasks`, `get_task`, `create_task`, `update_task`, `complete_task`, `delete_task`, `tasks_overview` (cuentas por carril, plan del día y lo que quedó de días anteriores), `move_task` (cambiar de carril), `plan_task` (día, franja, hora y duración), `board_summary`, `set_done_total`, `import_tasks`, `list_habits` (con puntos y racha), `create_habit`, `update_habit`, `delete_habit`, `check_habit`, `habit_to_task` (un hábito como tarea de hoy o mañana), `capture`, `list_captures`, `suggest_capture` y `file_capture` (el cajón), `get_note` y `set_note` (la nota discreta de Hoy), `log_counter`, `set_counter`, `counter_entries`, `set_goals`, `day_summary`, `weekly_review` (semana o mes), `health_days` (sueño, pasos, ejercicio y pulso del reloj), `budget` (resumen del dinero por ciclo, solo lectura), `list_features` y `set_feature`.

Herramientas de diagnóstico, de solo lectura: `get_client_logs`, `sync_status` y `record_history`. Lo que haga un agente llega al móvil en la siguiente sincronización.

Para conectarlo en Claude: Ajustes → Conectores → Añadir conector personalizado → `https://<tu-worker>/mcp`.

## Web

`public/` es una app de una sola página en JavaScript sin dependencias ni paso de compilación (`index.html`, `styles.css`, `js/app.js` y una vista por archivo en `js/views/`). Solo entra con la sesión de Google y usa la misma API REST que el móvil, así que lo que cambies allí llega al teléfono en su siguiente sincronización.

- **Hoy:** la nota de estos días (`focus_note`, clic para editarla); contadores con los botones rápidos del móvil, corregir el total, poner a cero y cambiar el objetivo; plan del día por franjas (arrastrar entre franjas, «Añadir de tu lista»); hábitos del día; salud; lo de mañana; entradas del día. Se puede abrir cualquier día para corregirlo.
- **Semana:** los siete días con sus franjas, lo que quedó atrás, tu lista y Prioridad a un lado; se planifica arrastrando.
- **Bandeja, Carriles y Esperando:** como en el móvil. Carriles sale solo con `task_kanban`.
- **Cajón** (`captures`): apuntar enlaces e ideas, aceptar la sugerencia de un agente, convertir en tarea, guardar en la biblioteca con etiquetas, archivar o borrar. Se ordena con el teclado: J/K, Enter acepta la sugerencia, T, G, A y 1–6 manda a un carril.
- **Hábitos:** puntos, racha y gráfica de 10 a 104 semanas, corregir cualquier semana pasada, crear, editar, archivar y borrar.
- **Resumen** (`review_zoom`) y **Salud** (`health_connect`).
- **Ajustes:** funciones, objetivos, dispositivos con su estado de sincronización y logs de las apps.
- La caja **Apuntar** (atajo `/`) crea una tarea; `+350` suma calorías, `p30` proteína y `c1` un cigarro.

**Dinero** (`budget_cloud`) enseña el resumen del ciclo que sube el móvil: disponible, topes, fijos y gasto por categoría. Lo que vive solo en el móvil (movimientos y credenciales, Viajes, Google Calendar y rituales) no aparece. Cada petición manda `X-Request-Id` (`web-…`); los fallos se muestran con ese id y se suben a `POST /api/logs` con la etiqueta `web`, así que salen en `get_client_logs`.

## Observabilidad

- Cada petición tiene un `request_id`. Si el cliente lo envía en `X-Request-Id`, se respeta; si no, se genera. Siempre vuelve en la respuesta y en el cuerpo de los errores.
- El Worker escribe una línea JSON por evento (`request`, `sync_apply`, `sync_pull`, `mcp_tool`, `auth_failed`, `client_log`…) con `request_id`, `actor` (`device:<id>`, `web`, `mcp:<cliente>`) y `source`. Workers Logs está activado en `wrangler.jsonc`. En directo: `npx wrangler tail --format json`.
- Los errores devuelven `{ error, code, request_id }`. Los fallos de login dicen el motivo: `token_expired`, `wrong_audience`, `account_not_allowed`, `email_unverified`, `invalid_device_token`…
- `change_log` guarda cada versión aceptada de cada registro con su autor.

## Desarrollo

```sh
npm install
npm test          # tests en el runtime de Workers (Miniflare), con D1 local y Google simulado
npm run typecheck
cp .dev.vars.example .dev.vars   # y rellénalo
npm run db:migrate:local
npm run dev
```

## Despliegue

1. `npx wrangler login`.
2. `npx wrangler d1 create pausa --location weur` (Europa occidental, cerca de A Coruña) y `npx wrangler kv namespace create OAUTH_KV`. Copia los ids en `wrangler.jsonc`.
3. `npm run db:migrate:remote`.
4. En Google Cloud Console → APIs y servicios:
   - Pantalla de consentimiento OAuth de tipo externo, con tu cuenta como usuario de prueba.
   - Credencial **Aplicación web** con el URI de redirección `https://<tu-worker>/auth/callback` (y `http://localhost:8787/auth/callback` para desarrollo).
   - Credencial **Android** con el paquete `com.sejio.calorapp` y la huella SHA-1 de la firma, para que el teléfono pueda pedir el ID token.
5. Secretos, cada uno con `npx wrangler secret put <NOMBRE>`: `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `ALLOWED_EMAIL` y `SESSION_SECRET` (por ejemplo, `openssl rand -base64 32`).
6. Pon la dirección final en `PUBLIC_URL` dentro de `wrangler.jsonc` y ejecuta `npm run deploy`.

Todo cabe en el plan gratuito de Workers, D1 y KV para un solo usuario.

## Arranque en frío

Un Worker no es un contenedor que se apague: se ejecuta en un isolate de V8 que arranca en milisegundos, y este pesa unos 90 KB comprimido. La latencia real es la de D1, que vive en una sola región; por eso se crea en `weur`. La app Android no espera nunca al servidor: lee y escribe en local y sincroniza en segundo plano.
