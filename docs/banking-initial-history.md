# Carga inicial de histórico · 6.6

Los CSV/TXT aportados para el histórico se leen desde el selector de documentos del
teléfono. No se incorporan al repositorio, a fixtures ni a la APK. La sincronización
habitual de ABANCA sigue siendo WebView y la de TR sigue usando su cliente nativo.

## Uso

1. Instalar la APK 6.6 encima de la 6.5, sin desinstalar ni borrar datos.
2. Sincronizar los bancos para tener productos identificados en SQLite.
3. Banca → Cargar histórico inicial → seleccionar un archivo CSV/TXT.
4. Revisar banco, número de registros, fechas y muestra. Seleccionar explícitamente
   la cuenta, tarjeta o préstamo correspondiente entre los productos sincronizados.
5. Guardar y repetir con los demás archivos. La pantalla muestra registros conservados
   e identidades nuevas. Los originales se inspeccionan en las consultas guardadas.

No se presupone a qué tarjeta pertenece una tabla sin número de tarjeta. Si no hay
un producto compatible, se ofrece abrir la sincronización. No se crea una cuenta
provisional que pudiera duplicar la real. Los productos antiguos sin identidad conocida
no son destinos de carga. El formato y el tipo limitan las opciones; las dos tarjetas
requieren que el usuario escoja correctamente débito/crédito según su etiqueta bancaria.

## Formatos reconocidos

- TR: CSV con 23 columnas, IDs de transacción y fecha/hora. Se guardan todos los campos,
  incluidos comisiones, impuestos, activo, cantidad, precio y contrapartida. No se
  derivan posiciones actuales ni saldos a partir del histórico de operaciones.
- Cuenta ABANCA: CSV Windows-1252 o UTF-8, ocho columnas separadas por punto y coma.
  Se conservan por separado concepto, concepto ampliado, importe/moneda, saldo/moneda,
  **fecha contable** y fecha valor. Fecha de operación queda desconocida. La fecha
  contable sirve como fecha provisional para identificar la fila; no se rebautiza.
- Tarjetas ABANCA: tablas copiadas, sin cabecera, de seis o siete columnas separadas
  por tabuladores. La primera fila es un movimiento. La séptima columna observada
  contiene «Cuota fija», conservada como modalidad de pago, nunca como fecha.
- Préstamo ABANCA: texto por secciones, grupos y parejas etiqueta/valor. Fechas,
  porcentajes, plazos e importes conservan sus unidades y contexto. No genera movimientos.

Se soportan BOM UTF-8, comillas escapadas, campos multilínea, separadores dentro de
comillas y CRLF/LF. Importes exactos mediante BigDecimal, sin coma flotante. El texto
se muestra literalmente, sin ejecutar fórmulas o HTML. Límites: 2 MiB por archivo,
5000 registros, 32 columnas y 16384 caracteres por celda. La captura procesada se
limita a 1 MiB para poder leerla con el cursor SQLite de Android. Una fila incompatible
rechaza **todo** el archivo; no se salta silenciosamente ni guarda una carga parcial.

## Persistencia e identidad

Cada archivo se guarda en una transacción SQLite que incluye consulta, captura,
entidades y observaciones. Fallos de escritura revierten la carga completa. El texto
original decodificado (incluido BOM, si existía), codificación, hash SHA-256 de los
bytes, filas/celdas y registros interpretados se cifran con el mecanismo existente.
El nombre/ruta del documento no son necesarios ni se persisten. La fecha de importación
se distingue de las fechas del movimiento; la fecha de observación en el banco es
desconocida (`sourceObservedAt: null`). No se llama al banco durante la importación.

El ID del archivo combina versión del lector, banco y hash de bytes; renombrarlo o
repetirlo no añade otra captura. Importarlo de nuevo en otro producto se rechaza para
no duplicarlo bajo otro propietario. Un archivo distinto conserva otra observación.

TR comparte la identidad por producto + ID bancario con el cliente nativo. ABANCA
usa producto + fecha + importe/moneda + concepto normalizado + tipo de operación.
En la cuenta CSV se utiliza la fecha contable provisionalmente; si el banco ofrece
otra fecha o concepto en la WebView, no se garantiza su conciliación automática.
La descripción ampliada y el saldo no alteran la identidad. Las condiciones del
préstamo comparten identidad por producto + sección + grupo + etiqueta.

La carga solo inserta entidades que no existían: **no sustituye** la interpretación
actual de una entidad existente por un dato histórico. Siempre conserva las
observaciones y el archivo fuente. Una sincronización posterior puede actualizar la
interpretación actual; las observaciones de la carga permanecen. Repeticiones dentro
del archivo comparten identidad provisional, pero conservan todas sus filas.

No se concilian pagos de tarjeta con movimientos de cuenta ni se suman como gasto
consolidado. No se deduce que el rango entre la primera y última fila sea un histórico
completo. Guardar todos los datos aportados no implica que el banco haya exportado todo.

## Verificación local

Los cinco archivos reales, fuera del repositorio, se han leído con el mismo parser
Java de producción sin contactar con los bancos:

| Fuente | Registros | Identidades | Intervalo de fechas aportado |
| --- | ---: | ---: | --- |
| Trade Republic | 281 | 281 | 01/06/2026–04/10/2026 |
| Cuenta ABANCA | 85 | 85 | 01/06/2026–05/10/2026 (contable) |
| Débito ABANCA | 14 | 14 | 31/05/2026–05/10/2026 |
| Crédito ABANCA | 60 | 55 | 28/05/2026–01/10/2026 |
| Préstamo ABANCA | 27 campos | 27 | Detalle contractual, sin intervalo de movimientos |

Crédito contiene cinco filas adicionales exactamente iguales, distribuidas en cuatro
grupos. Se preservan las 60 observaciones. Las filas de mayo tampoco se descartan.

Pasan 68 pruebas JVM y lint termina sin errores. Las pruebas JVM cubren formatos, precisión, fechas imposibles, codificación, CSV
multilínea, datos repetidos, identidad compartida con los lectores existentes,
contexto de préstamo y rechazo completo ante errores. La prueba Android de SQLite
se amplía para comprobar idempotencia, conservación de la entidad previa y rollback.
Se compila pero no se ejecuta aquí por no disponer de emulador. Queda pendiente la
comprobación del selector y guardado con Keystore en el teléfono real.
