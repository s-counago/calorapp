# ABANCA: formato CSV observado

Muestra aportada por el usuario tras fallar la descarga OFX. Revisión local del
05/10/2026, sin peticiones a ABANCA ni ejecución del contenido del archivo.
El original y sus valores personales no se incorporan al repositorio.

## Estructura confirmada

Archivo de texto separado por punto y coma, con ocho columnas en todas las filas.
No es UTF-8 válido ni tiene BOM; se decodifica correctamente como Windows-1252.
Los bytes presentes también son compatibles con ISO-8859-1, por lo que esta muestra
no permite distinguir esas dos codificaciones. Los finales de línea son CRLF.

| Posición | Cabecera | Interpretación |
| --- | --- | --- |
| 1 | `Fecha ctble` | Fecha contable, no asumir fecha de operación |
| 2 | `Fecha valor` | Fecha valor |
| 3 | `Concepto` | Descripción breve |
| 4 | `Importe` | Importe con signo |
| 5 | `Moneda` | Moneda del importe |
| 6 | `Saldo` | Saldo de la fila |
| 7 | `Moneda` | Moneda del saldo |
| 8 | `Concepto ampliado` | Descripción adicional, puede estar vacía |

Las fechas de esta muestra usan `dd-MM-yyyy` y son válidas. Los importes y saldos
usan coma decimal con dos decimales y son interpretables sin coma flotante. Los
códigos de moneda presentes tienen formato ISO de tres letras. No se debe fijar
EUR en el importador por ser la moneda observada en una sola muestra.

La cabecera `Moneda` está duplicada: no usar un diccionario por nombre de columna
que sobrescriba una de ellas. Conservar la moneda de cada campo monetario.
El concepto ampliado debe tratarse como texto, no como fórmula ni instrucción.
Aunque esta muestra no contiene campos entrecomillados, un lector CSV debe soportar
comillas, separadores dentro de campos y saltos de línea correctamente.

## Uso como referencia

El usuario aclara que este archivo es solo un ejemplo de datos. No se desarrolla
un importador CSV: la extracción debe salir de la WebView y persistirse en SQLite.
El archivo ayuda a distinguir fecha contable de fecha valor y la descripción breve
de la ampliada. No se convierte en una fixture con datos personales ni se incorpora
al repositorio.

La integración actual se describe en [banking-database.md](banking-database.md).
