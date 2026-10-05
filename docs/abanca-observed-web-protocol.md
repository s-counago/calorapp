# ABANCA: contrato web observado

Revisión local del 05/10/2026 de las capturas aportadas por el usuario:
`har1.har` (acceso y posición), `acc-har.har` (cuenta) y `card-har.har`
(tarjeta). No se reprodujeron peticiones ni se utilizaron credenciales. Los HAR,
valores de sesión, identificadores de productos y datos financieros no forman
parte de este documento ni del repositorio.

## Lo confirmado

Las tres pantallas relevantes llegan como documentos HTML del servidor, no como
respuestas JSON de una API de movimientos. Origen observado:
`https://bancaelectronica.abanca.com`.

| Pantalla | Petición observada | Estructura de respuesta |
| --- | --- | --- |
| Posición global | `GET /wele200/General/Posicion/WELE200M_Posicion.aspx` | Tablas `#positions`, `#cards`, `#loans` |
| Movimientos de cuenta | `GET /wele200/General/ConsultaMovimientos/WELE200M_ConsultaMovimientos_Res.aspx?k=[OPACO]` | `table.movements` |
| Movimientos de tarjeta | `GET /wele200/Tarjetas/MovimientosTarjeta/WELE200M_MovimientosTarjeta_Ini.aspx?k=[OPACO]` | `table.movements` |

Las tres respuestas son HTTP 200, `text/html`. Los enlaces de cuenta y tarjeta
de la posición global coinciden exactamente con las URLs posteriormente visitadas
en las otras capturas; la comparación fue local, sin imprimir sus valores.
Esto permite descubrir las rutas de consulta desde la página autenticada.
No hay evidencia de que `k` sea estable entre sesiones ni de que sea un ID público:
debe tratarse como opaco y sensible, sin fabricarlo, decodificarlo ni persistirlo
como identificador de producto o movimiento.

La posición incluye columnas de tipo y saldo en cuentas; tipo, límite concedido y
saldo dispuesto en tarjetas; tipo, límite concedido y saldo pendiente en préstamos.
El saldo dispuesto de una tarjeta y la deuda de un préstamo no son efectivo
disponible. Que exista una tabla no acredita soporte de todos sus productos.

Columnas de movimientos observadas:

- Cuenta: `F.OPERAC.`, `F.VALOR`, `DESCRIPCIÓN`, `IMPORTE`, `SALDO`.
- Tarjeta: una columna sin título, `F.OPERAC.`, `TIPO OPERACIÓN`, `SITUACIÓN`,
  `CONCEPTO`, `IMPORTE`, `F.PAGO`.

La situación y la fecha de pago de tarjeta necesitan campos propios; no equivalen
automáticamente a la fecha valor de cuenta. La clase CSS `neg` aparece en importes;
el parser deberá verificar el signo textual y no inferirlo solo del color.
No se ha probado conciliación entre un pago de tarjeta y su cargo en cuenta.

## Acceso observado y límites

El tramo capturado es:

1. `POST /WELE200M_Logon_Conf.aspx` → 302.
2. `GET /wele200/WELE200M_Logon_Res.aspx` → 302.
3. `GET /wele200/General/Posicion/WELE200M_Posicion.aspx` → 200.

El formulario enviado tiene campos `entidad`, `card01`, `pin_number`,
`g-recaptcha-response`, `recaptcha-sitekey` y `recaptcha-action`. Sus valores
no se conservan aquí. La captura incluye contenido sensible en el cuerpo del
login: la exportación sanitizada no basta para anonimizarlo.

La presencia de un token reCAPTCHA impide asumir que bastaría repetir usuario y
PIN en un cliente HTTP. La captura no explica cómo se obtiene ese token, ni acredita
todos los pasos posibles de MFA, renovación o recuperación de sesión. No se plantea
reproducir el token capturado ni automatizar la resolución de desafíos.

Las listas de cookies de los HAR están vacías. Esto no significa que la web no
utilice cookies: no permite determinar aquí el contrato de sesión, sus atributos,
su caducidad ni si funcionaría trasladarla a otro cliente HTTP. El navegador de
escritorio y la WebView de Pausa tienen sesiones diferentes.

## Enlaces presentes, todavía sin probar

Cuenta y tarjeta contienen enlaces `h10Ultimos` y `hMasMvtos` con parámetros
`op` y `k`: respectivamente `op=1` y `op=0`. No se capturó su ejecución;
no son evidencia suficiente para implementar un contador de páginas o prometer
histórico completo. Hay que seguir el enlace real y comprobar su resultado.

La cuenta contiene enlaces de exportación a
`/wele200/General/ConsultaMovimientos/WELE200M_DescargaMovimientos_Res.aspx`:

| Control | Parámetro observado |
| --- | --- |
| `cDownload_lExcel` | `tf=1` |
| `cDownload_lOfc` | `tf=2` |
| `cDownload_lOfx` | `tf=3` |
| `cDownload_lN43` | `tf=4` |
| `cDownload_lPdf` | `tf=5` |

No se descargó ningún archivo. OFX podría ser preferible para importar movimientos
de cuenta con campos estructurados, si el contenido real lo confirma. Se desconocen
sus IDs, moneda, cobertura temporal y formato/versiones. La URL no lleva la cuenta
como parámetro visible; podría depender de estado de sesión. No ejecutar descargas
en paralelo ni asumir que basta con cambiar `tf`. No se ha observado una exportación
equivalente en la página de tarjeta analizada.

## Aplicación a Pausa

Enfoque propuesto: acceso interactivo en la web oficial dentro del perfil aislado
de ABANCA; extracción estructurada local de las páginas autenticadas; después,
sincronización iniciada por el usuario siguiendo únicamente enlaces de consulta
descubiertos en esas páginas. Detenerse al caducar la sesión o aparecer un desafío.
No es una garantía de compatibilidad de WebView ni de ausencia de alertas del banco.

Primera implementación comprobable sin contactar con ABANCA: parser específico
para las tres estructuras, probado con HTML sintético, separando productos,
monedas, fechas y estados. El lector genérico actual busca `main` o `[role=main]`;
estas tres respuestas usan `#content` y carecen de esos elementos. Por ello no se
puede dar por compatible el lector genérico con estas páginas. El
[lector específico de la versión 6.4](abanca-reader.md) ya reconoce estas tablas,
excluye formularios y scripts, y está probado con datos ficticios. Todavía no añade
navegación automática ni un cliente HTTP con sesión propia.

Sin identificadores de transacción estables verificados, no deduplicar únicamente
por fecha, concepto e importe: dos compras iguales pueden ser distintas. Conservar
las filas como una instantánea parcial hasta verificar IDs y paginación. No sumar
gastos de cuenta y tarjeta sin una regla de conciliación comprobada.

Validación pendiente: compatibilidad en el teléfono, sesión y caducidad reales,
histórico/fechas, cambio entre productos, formato de descarga y conciliación.
No hace falta otra captura de login para desarrollar los parsers iniciales.
