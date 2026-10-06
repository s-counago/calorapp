# Cotizaciones y PnL · 6.7

Se actualizó `main` a `2135d2e` antes de implementar esta fase. Se integró también
la carga inicial `2e83b1b`, que aún no estaba en remoto: su acceso vive ahora en
Bancos → Más opciones. Los movimientos CSV conservan su fecha contable y los tipos
exportados de TR se reconocen en Dinero; no se borra ni migra destructivamente SQLite.

## Fuente y consultas

Los precios se consultan directamente a Trade Republic desde el teléfono mediante
el cliente Java de Pausa. **No se instala, ejecuta ni incorpora pytr**, ni se añade
ninguna dependencia de producción. Se leyó como referencia de protocolo:

- [pytr/tickers.py, revisión e7f3ba37167bda15c0b5501f7ddab24c8d24d37e](https://github.com/pytr-org/pytr/blob/e7f3ba37167bda15c0b5501f7ddab24c8d24d37e/pytr/tickers.py)
- [Ejemplo publicado para el caso de Trade Republic en CDTM Hacks, revisión b800bd1acfb547680697d60d2172a845a87102ca](https://github.com/CDTM/TradeRepublic_CDTMHacks2025/blob/b800bd1acfb547680697d60d2172a845a87102ca/README.md)

El segundo muestra `instrument`, selección del primer `exchangeIds` y `ticker`
con `last` y `pre`, sin campo de moneda. La moneda ausente explicaba una vía por la
que el lector anterior obtenía precio numérico pero excluía su valor del subtotal.
Esto es evidencia del formato público; no es un diagnóstico confirmado de una
respuesta actual de la cuenta del usuario.

Se conserva la selección de mercado existente. Para acciones/ETF de precio unitario
en **LSX**, si el ticker no indica moneda se aplica la convención EUR del mercado,
registrada como `currencyBasis: lsx_eur_unit_quote_convention`. Es una inferencia
explícita del cliente, no un campo enviado por el banco. Una moneda explícita tiene
prioridad. En otros mercados sin moneda se conserva `UNKNOWN_CURRENCY`. Nunca se
usa la divisa del fondo o la cuenta de efectivo para denominar el ticker. No hay FX.

El `averageBuyIn` usa su moneda explícita. Si falta y la cotización es EUR en LSX,
se aplica y registra `lsx_eur_buy_in_convention`. Si la moneda del coste contradice
la del precio, no se calcula PnL total. Estas convenciones necesitan comprobarse con
la app oficial en la prueba real; no se extienden a bonos, derivados o mercados nuevos.

Cartera → Actualizar valoración consulta solo sesión, posiciones, metadatos y precios;
ya no descarga también los movimientos. No añade consultas `performance` ni históricos:
la referencia diaria procede del mismo ticker. Se mantienen el máximo de 20
instrumentos, metadatos en caché 24 horas, una lectura por suscripción seguida de
`unsub`, consultas secuenciales, espera de un minuto entre intentos y parada ante
rechazos/errores. No hay actualizaciones de cotización en segundo plano.

## Cálculo y significado

Para cantidad actual `q`, precio `p`, precio medio de compra `b` y referencia `r`:

- Valor estimado: `q × p`.
- PnL total de posiciones abiertas: `q × (p − b)`.
- PnL total porcentual: `(q × (p − b)) / (q × b) × 100`.
- PnL diario estimado sobre las participaciones actuales: `q × (p − r)`.
- Porcentaje diario: `(q × (p − r)) / (q × r) × 100`.

Se interpreta `ticker.pre` como referencia de la sesión anterior. Se conservan su
precio y fecha propios. Para mostrar diario se exige referencia positiva, ambas
fechas conocidas, `pre.time < last.time`, días de mercado diferentes en Europe/Berlin
y una separación máxima de siete días. Admite por ejemplo lunes contra viernes, sin
inventar cotizaciones durante el fin de semana. No hay fallback al primer punto de
un gráfico, a la apertura ni a la lectura anterior de Pausa. Sin referencia válida,
el PnL diario permanece desconocido; el precio y el PnL total pueden seguir disponibles.

El resultado diario es **una estimación de variación de precio sobre la posición
actual**, no el beneficio real de la cuenta durante el día. No ajusta por compras o
ventas intradía, efectivo, aportaciones, dividendos, comisiones adicionales ni impuestos.
El total tampoco es rentabilidad acumulada de toda la cuenta: usa las posiciones
abiertas y el precio medio informado por TR. Si el coste es cero, el PnL absoluto
puede existir pero el porcentaje no se define. Los campos ausentes no se convierten
en cero ni se presentan como una ganancia nula.

Importes y bases se calculan con BigDecimal sin redondeos intermedios; porcentajes
persistidos con hasta 12 decimales y redondeo HALF_EVEN. Euros de presentación con
dos decimales, porcentajes de Cartera con uno. Los porcentajes agregados dividen la
suma del PnL por la suma de las bases de las mismas posiciones, no promedian porcentajes.

## Presentación, trazabilidad y compatibilidad

Cartera muestra total y diario en €/% y cobertura de cada métrica. Al tocar una
posición aparecen el precio, coste, cantidad, PnL total/diario y fecha de referencia.
La sesión se muestra por su fecha real: una cotización de viernes no se anuncia como
«hoy» el lunes. En el total diario solo se agregan posiciones de la sesión más reciente
disponible; las demás conservan su detalle y no entran en ese subtotal. Solo se suman
cotizaciones en EUR, sin suponer conversiones.

Se conserva `last.time` separado de `receivedAt`: recibir ahora un precio no cambia
su fecha de mercado. Una actualización solo de posiciones puede reutilizar el último
precio guardado con su referencia y fecha, aplicándolo a la cantidad actual. Las
lecturas de otra cuenta no aportan precios o historia a la cuenta más reciente.

Los gráficos de cartera solo dibujan valoraciones con todas las posiciones valoradas
en euros; las lecturas parciales permanecen en SQLite y en la historia individual.
El coste desconocido deja un hueco en su curva, no se sustituye por el valor actual.
También se corrige la escala de gráficos planos para evitar dividir entre cero.

Cada posición conserva en la captura/observaciones cifradas su `quote`, procedencia
de moneda, calidad comunicada, referencia anterior y `pnl` versionado. La captura TR
usa `parserVersion: trade-client-v2-pnl`. El modelo de Cartera comparte el calculador
con el cliente; no hay un segundo conjunto de fórmulas en la UI. La carga CSV y los
datos anteriores permanecen compatibles y no necesitan volver a importarse.

## Verificación

108 pruebas JVM pasan: incluyen simulación HTTP/WebSocket, cierre de suscripciones,
rechazo con conservación de la cartera anterior, moneda ausente LSX y mercado
desconocido, fracciones, pérdidas, coste cero/desconocido, referencias ausentes o
inválidas, fines de semana, agregación ponderada, sesiones distintas, precios
reutilizados, cuentas separadas y compatibilidad del histórico importado con Dinero.

Compilan APK bankingUa y pruebas instrumentadas de BudgetUiSmokeTest; lint sin errores.
La prueba SQLite de esquema/persistencia y la política bancaria también pasan.
Las pruebas Android se compilan pero no se ejecutan aquí, por falta de emulador.
No se ha iniciado sesión ni enviado pruebas autenticadas al banco desde este entorno.

Pendiente en el teléfono: actualizar la APK encima, abrir Cartera → Actualizar
valoración y comparar precio, referencia, moneda y resultados con Trade Republic.
Los tests sintéticos validan código y cálculos, no garantizan la compatibilidad actual
del servicio ni que sus datos tengan tiempo real.
