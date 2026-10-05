# Banking en Pausa: investigación y propuesta

> Actualización 04/10/2026: la decisión actual del usuario es gestionar sesiones
> propias de ABANCA y Trade Republic, sin Sure. La base Android y sus límites se
> describen en [banking-local.md](banking-local.md). El resto de este documento
> conserva la investigación histórica, no la recomendación vigente.

Fecha de consulta inicial: 18 de septiembre de 2026. Revisión de alojamiento: 21 de septiembre de 2026.

Objetivo: conectar ABANCA y Trade Republic en modo solo lectura para reducir el trabajo de registrar gastos y calcular un presupuesto que se adapte al gasto real sin consumir inadvertidamente los ahorros. El usuario confirma IBAN español en Trade Republic; medir la cartera sería deseable, pero opcional. Se toma como escenario inicial el uso personal descrito en el README; abrir el servicio a otras personas requeriría revisar las condiciones del proveedor.

## Decisión recomendada

Actualización del 21/09: el usuario prefiere aprovechar Sure completo y considera insuficientes los datos de Trade Republic por AIS. La propuesta pasa a Sure estable en el PC, ABANCA mediante Enable Banking y una importación local separada para Trade Republic. Para automatizarla, evaluar un cliente reducido a efectivo y movimientos a partir del código existente, sin portar toda la integración alpha. Validar primero un CSV oficial y el acceso real; la descarga automática de archivos también necesitaría una sesión bancaria. Esta propuesta no equivale a implementación ni conexión autorizada ya realizada.

La recomendación inicial fue probar Enable Banking con ABANCA y Trade Republic. Su modalidad restringida a cuentas propias permite uso personal gratuito según las condiciones vigentes. La revisión ampliada encuentra un anuncio oficial del conector Trade Republic, una implementación real en Sure y un catálogo derivado de la API que incluye Trade Republic España para particulares en beta. La falta de resultados en el buscador público no justificaba relegarlo directamente a extractos. No se ha probado ninguna cuenta real: falta validar el consentimiento y los datos de este IBAN ES.

Parqet ofrece otra vía personal interesante para la cartera: sincroniza Trade Republic y permite conectar una app propia mediante su API oficial con permiso de lectura. No está demostrada su utilidad para importar todos los gastos de tarjeta españoles. Los clientes de la API privada, como pytr o la integración nativa de Sure, amplían las alternativas, pero no acreditan un permiso bancario limitado a lectura.

La primera pantalla debería responder «cuánto puedo gastar hoy y hasta el próximo ingreso», con el ahorro protegido separado. La cartera de inversiones puede incorporarse después sin condicionar esta primera versión.

## Qué está comprobado

| Aspecto | ABANCA España | Trade Republic con IBAN ES |
| --- | --- | --- |
| Interfaz oficial | El portal de ABANCA publica servicios de cuentas y movimientos. | Guía oficial PSD2, versión 2.1, julio de 2026. |
| Enable Banking | Figura en cobertura con filtros España y Personal, AIS y producción. | Conector anunciado oficialmente. El catálogo de BankMCP, obtenido de Enable Banking el 14/09/2026, lista España, Personal, beta y consentimiento de 90 días. Falta prueba con la cuenta real. |
| Lunch Flow | Figura ABANCA España a través de GoCardless. | La búsqueda pública no devuelve resultados. |
| Datos para el presupuesto | Saldos y movimientos; comprobar en la cuenta real descripciones, tarjetas e histórico. | La guía contempla saldos y movimientos contabilizados; Enable Banking documenta solo importe, moneda y fecha, sin descripción, a agosto de 2026 y para todos los países. |
| Inversiones | Fuera del alcance de la primera prueba. | Fuera de la API AIS examinada. Parqet Connect y clientes de la API privada ofrecen una vía separada. |

Fuentes: [portal ABANCA](https://openbanking.abanca.com/), [catálogo Enable Banking](https://enablebanking.com/open-banking-apis), [catálogo Lunch Flow](https://www.lunchflow.app/coverage), [guía oficial Trade Republic](https://assets.traderepublic.com/assets/files/TPP_API_Guide_v2.pdf) y [limitaciones de Trade Republic documentadas por Enable Banking](https://enablebanking.com/docs/markets/de/#trade-republic).

Los catálogos comerciales se comprobaron en navegador, porque sus listas se cargan dinámicamente. La presencia de un banco es evidencia de cobertura declarada, no una prueba de funcionamiento con la cuenta concreta. La ausencia en un buscador no demuestra ausencia del conector; en este caso contradice otras evidencias más concretas, detalladas abajo.

## Acceso de solo lectura y requisitos

La vía propuesta es AIS: consentimiento para consultar cuentas, saldos y movimientos. Pausa debe habilitar exclusivamente ese servicio y excluir capacidades de pago en el proveedor y en su backend. El usuario autoriza en el flujo del banco y conserva la posibilidad de revocar la conexión. «Solo lectura» describe el permiso bancario; Pausa sí guardará movimientos, categorías y presupuestos propios.

### ABANCA y prueba de Trade Republic mediante Enable Banking

Las condiciones de Enable Banking permiten gratuitamente el uso personal de particulares con sus propias cuentas vinculadas. No cubren el uso comercial ni cuentas ajenas; pueden aplicarse límites de cuentas y consultas. Enable Banking actúa como AISP autorizado. La modalidad comercial requiere un acuerdo separado. [Condiciones, actualizadas el 9 de enero de 2026](https://enablebanking.com/terms/).

Para la prueba se necesita:

1. Una cuenta del usuario en Enable Banking y una aplicación registrada para Pausa.
2. Clave privada de aplicación, identificador y URL de retorno registrada.
3. Activación restringida mediante vinculación de las cuentas propias.
4. Seleccionar el conector disponible para la cuenta personal y obtener el consentimiento del titular en el banco.
5. Una lectura real de saldos y movimientos para verificar calidad e histórico.

El registro contempla datos de la aplicación y, para producción con la autorización del proveedor, contacto de privacidad y URLs de términos y privacidad. El proceso exacto debe completarse con datos reales de Pausa. [Registro de aplicaciones](https://enablebanking.com/docs/api/control-panel/) y [activación mediante cuentas propias](https://enablebanking.com/docs/api/linked-accounts/).

La fricción posterior depende del banco: renovaciones de consentimiento, incidencias y límites de consultas. Enable Banking advierte de límites habituales de cuatro recuperaciones diarias en segundo plano y problemas con autenticación dentro de WebViews. No ofrece categorización de gastos. Propuesta: navegador del sistema, sincronización conservadora y fecha visible de la última actualización. [FAQ](https://enablebanking.com/docs/faq/).

### Trade Republic

La guía oficial está dirigida a proveedores regulados, exige un certificado eIDAS QSeal con identidad y rol del TPP, y separa `ACCOUNT_INFORMATION` de `PAYMENT_INITIATION`. Documenta consentimiento recurrente de hasta 90 días. Estos son parámetros publicados por Trade Republic, no una afirmación sobre el límite legal general de PSD2. La página de soporte también menciona QWAC; una integración directa tendría que resolver esa diferencia con el banco. [Guía técnica](https://assets.traderepublic.com/assets/files/TPP_API_Guide_v2.pdf) y [soporte oficial](https://traderepublic.com/de-de/support?articleId=0c5b7d7c-5970-4c6e-88ef-7be7e96c8fbd).

No es una API key que pueda solicitar cualquier cliente para una app personal. Convertir Pausa en proveedor de información de cuentas supondría un proyecto regulatorio distinto: el Banco de España publica un procedimiento específico de registro. Para esta app conviene utilizar un proveedor ya autorizado. [Registro del Banco de España](https://sedeelectronica.bde.es/sede/es/tramites/registro-prestadores-informacion-cuentas-p222.html).

Además, obtener movimientos sin comercio ni concepto no permite clasificar con fiabilidad qué fue supermercado, ocio o una transferencia propia. Tampoco conviene inferirlo solo por el importe. Esta carencia afecta directamente al objetivo de uso sin fricción. [Limitación publicada por Enable Banking](https://enablebanking.com/docs/markets/de/#trade-republic).

Alternativa de respaldo: importar el extracto descargado por el usuario y verificar su formato antes de implementar el lector. Parqet anunció el 15/04/2026 una nueva sincronización trabajada con Trade Republic y basada en un CSV de historial; esto no prueba que la app española ofrezca ese mismo archivo como descarga manual. El extracto mensual de Trade Republic incluye operaciones completadas, por lo que no sustituye al control de gastos pendientes. [Anuncio de Parqet](https://parqet.com/de/blog/trade-republic-neuer-autosync) y [extractos y operaciones de tarjeta](https://support.traderepublic.com/es-es/1663-Why-are-some-card-transactions-missing-from-my-monthly-account-statement).

## Revisión ampliada: Trade Republic para particulares

### 1. Enable Banking: evidencia suficiente para intentarlo

Tres hallazgos corrigen la conclusión inicial:

- El changelog oficial de julio de 2026 anuncia Trade Republic (DE) como nueva integración. La página muestra 12 de agosto aunque su URL tenga una fecha distinta. Confirma la existencia del conector, no por sí sola el funcionamiento de cuentas españolas. [Anuncio oficial](https://enablebanking.com/blog/2026/12/08/enable-banking-changelog-july-2026).
- BankMCP publica una instantánea del catálogo del proveedor obtenida el 14/09/2026. Su página de España incluye Trade Republic, beta, cuentas personales y de empresa, con consentimiento de 90 días. Es evidencia de un consumidor de la API; BankMCP declara no estar afiliado a Enable Banking. Debe contrastarse con el catálogo autenticado de nuestra aplicación. [Listado de España](https://bankmcp.dk/banks/es/) y [procedencia y fecha del catálogo](https://bankmcp.dk/banks/).
- Sure incorporó una corrección concreta para sincronizar Trade Republic mediante Enable Banking: al fallar páginas posteriores conserva los movimientos recuperados y tolera la falta de movimientos pendientes. Demuestra una implementación real y también que existen problemas de integridad del histórico. [PR 2828](https://github.com/we-promise/sure/pull/2828).

La prueba debe consultar el catálogo autenticado para España, particulares y AIS, comprobar si el conector beta está disponible para la aplicación personal y completar el consentimiento. No asumir que seleccionar Alemania resuelve automáticamente un IBAN español. Si no aparece, queda por aclarar la activación de beta o cobertura con el proveedor, no desarrollar otra app entera.

La limitación de descripciones sigue vigente en la documentación consultada. Podemos mostrar evolución de saldo y entradas/salidas, pero no reconocer con fiabilidad comercios o transferencias internas a partir de fecha e importe. Los fallos de paginación deben mostrarse como sincronización incompleta; conservar datos parciales no permite presentar un presupuesto como actualizado.

### 2. Parqet: cuenta particular, sincronización de Trade Republic y API propia

Existe una ruta concreta: Trade Republic → Parqet → Pausa. No depende de contratar una API bancaria empresarial. Parqet permite registrar una integración privada que solo autoriza el propietario; su guía dice expresamente que el uso personal no necesita pasar por publicación. Harían falta una cuenta Parqet, conectar Trade Republic, registrar Pausa y su URL de retorno, y autorizar la integración. [Guía oficial para integraciones privadas](https://developer.parqet.com/docs/build-your-first-parqet-integration).

Para Pausa usaríamos OAuth con PKCE y únicamente `portfolio:read`. El permiso limita el acceso a los datos de Parqet; no demuestra por sí mismo qué permisos técnicos tiene la conexión entre el intermediario y Trade Republic. [OAuth y permisos](https://developer.parqet.com/blog/implementing-oauth-authentication).

El plan Basic anuncia 0 €/mes, un portfolio, Autosync de Trade Republic y dos integraciones. Sobre esa oferta podría probarse este puente sin suscripción de pago. Hay que comprobar que las funciones concretas necesarias estén incluidas al conectar la cuenta. [Precios oficiales](https://parqet.com/en/pricing).

La interfaz documenta posiciones, valoración, rentabilidad, compras, ventas, dividendos, intereses, depósitos y retiradas. Es prometedora para el extra opcional de inversiones. No hay confirmación suficiente de un libro completo de compras con tarjeta, comercios y pendientes para el presupuesto cotidiano. La presencia de activos de efectivo no demuestra esa cobertura. [Datos expuestos por Parqet Connect a través de su MCP oficial](https://developer.parqet.com/docs/give-ai-portfolio-context-with-the-parqet-mcp).

Hay una discrepancia que impide prometer sincronización sin intervención: la ayuda de Trade Republic describe Autosync local con teléfono, PIN y confirmación; la política de privacidad actual asigna Trade Republic al Autosync cloud de QPLIX. En este segundo flujo hay contrato y consentimiento separados con QPLIX, y las credenciales se introducen en su entorno, no se entregan a Parqet. Se debe verificar el flujo efectivo ofrecido al usuario español, frecuencia de renovación y actualización real de datos. [Ayuda de Autosync](https://faq.parqet.com/de/articles/633071-trade-republic-autosync-parqet) y [política actual, apartado III.5](https://parqet.com/en/data-protection).

### 3. API privada: alternativas reales con otra frontera de permisos

pytr permite consultar cartera, descargar documentos y exportar movimientos a CSV/JSON. Su README actual incluye un login v2 con confirmación en la app o código de autenticador y advierte de reautenticaciones. Es software abierto para uso particular; no debe descartarse como inexistente. Su interfaz también incluye escritura de alertas de precio: no es una concesión AIS de lectura restringida por el banco. [Repositorio de pytr](https://github.com/pytr-org/pytr).

Sure tiene además una integración nativa independiente de Enable Banking. Al revisar su código aparecen login web mediante teléfono/PIN y QR, cuentas separadas de efectivo y cartera, y tratamiento de pagos de tarjeta, reembolsos y retiradas. Es una referencia útil si AIS resulta insuficiente; la existencia del código no acredita que se haya probado nuestro IBAN ES ni que el banco limite esa sesión a lectura. [Cliente nativo de Sure](https://github.com/we-promise/sure/blob/main/app/models/provider/trade_republic_client.rb) y [procesador de movimientos](https://github.com/we-promise/sure/blob/main/app/models/trade_republic_account/activities_processor.rb).

Comprobación de versiones del 18/09/2026: el conector nativo está en `v0.7.5-alpha.9`, publicada el 16/09, y no en la última estable `v0.7.4`, publicada el 31/08. No confundir la integración de Trade Republic mediante Enable Banking, disponible en la estable, con este nuevo cliente directo. [Alpha](https://github.com/we-promise/sure/releases/tag/v0.7.5-alpha.9), [estable](https://github.com/we-promise/sure/releases/tag/v0.7.4) y [código nativo en la alpha](https://github.com/we-promise/sure/blob/v0.7.5-alpha.9/app/models/provider/trade_republic_client.rb).

Podríamos limitar un adaptador propio a descargar datos, pero conservaría credenciales o una sesión del usuario con permisos distintos de un token bancario de lectura. Por eso esta ruta requiere aceptar expresamente ese cambio respecto al requisito original antes de conectar una cuenta. No se ha instalado ni ejecutado ningún cliente bancario.

## Alternativas y coste

| Opción | Coste publicado o situación | Valor para Pausa |
| --- | --- | --- |
| Enable Banking, cuentas propias | Gratis dentro de sus condiciones de uso personal. El alojamiento de Pausa se valora aparte. | Primera prueba para ambos bancos. Trade Republic figura en beta para España en el catálogo derivado consultado. |
| Parqet Basic + Connect | 0 €/mes, un portfolio y dos integraciones según su tarifa publicada. | Alternativa personal con API oficial de lectura para la cartera. Validar IBAN ES y datos de gastos antes de usarlo para budgeting. |
| Lunch Flow | La web muestra 34,99 USD al año con dos conexiones; equivalencia anunciada de 2,92 USD/mes, facturados anualmente. Revisar moneda e impuestos al contratar. | Alternativa para ABANCA; acceso API incluido según su página de integración. No resuelve Trade Republic en el catálogo consultado. |
| Powens | No se ha obtenido una tarifa pública aplicable a este caso. | Publicó un conector Trade Republic en 2022 y ofrece agregación patrimonial; esa publicación no confirma funcionamiento actual en España ni el mecanismo de autenticación. |
| GoCardless/Nordigen directo | Actual Budget documenta el cierre de altas nuevas de Bank Account Data desde julio de 2025. | No basar una integración nueva en los antiguos tutoriales del plan gratuito. Esto no impide que otro servicio use su propia integración contratada. |
| API privada / pytr / Sure nativo | Proyectos abiertos; alojamiento y mantenimiento por cuenta propia. | Movimientos y cartera, con autenticación de usuario. No equivale a permiso bancario estrictamente de lectura. |

Fuentes: [precio Lunch Flow](https://www.lunchflow.app/#pricing), [API incluida](https://www.lunchflow.app/features/api-integration), [anuncio histórico Powens](https://www.powens.com/blog/acceleration-api-european-converage/), [Powens Wealth](https://www.powens.com/products/wealth/), [estado de GoCardless en la documentación de Actual Budget](https://actualbudget.org/docs/advanced/bank-sync/gocardless/) y [pytr](https://github.com/pytr-org/pytr).

Aplicaciones para particulares como Finary también sincronizan Trade Republic, pero eso no garantiza una API utilizable por Pausa. Finary documenta una nueva autenticación para cada actualización de sus movimientos; actualizar cotizaciones no significa que se hayan importado nuevas operaciones. [Ayuda oficial de Finary](https://help.finary.com/en/articles/6518724-manual-update-required).

Si se explora Powens, hay que verificar concretamente: aceptación de uso individual, coste mínimo total, cobertura de Trade Republic España, efectivo frente a cartera, conceptos de tarjeta, permisos efectivos y frecuencia real de reautenticación. No se ha enviado ninguna consulta a proveedores.

## Sure alojado por nosotros

Sure y pytr no cobran licencia por ejecutarlos por cuenta propia. Sure utiliza AGPLv3 y pytr MIT. Para ABANCA seguiría haciendo falta Enable Banking; alojar Sure no aloja ese proveedor. Trade Republic puede usar el mismo intermediario o el conector directo de la alpha. [Sure](https://github.com/we-promise/sure) y [pytr](https://github.com/pytr-org/pytr).

El Compose oficial levanta web, worker de sincronización, PostgreSQL y Redis, con volúmenes persistentes. Se configura `.env`, secretos propios y HTTPS; se crea la cuenta y se cierran nuevas altas si el uso es individual. Las actualizaciones y copias de seguridad quedan a nuestro cargo. Conviene fijar una versión: `latest` sigue alpha y `stable` sigue la estable. [Guía de despliegue](https://github.com/we-promise/sure/blob/main/docs/hosting/docker.md) y [Compose](https://github.com/we-promise/sure/blob/main/compose.example.yml).

En Settings → Providers se configura Enable Banking con país ES, Application ID, certificado/clave privada de aplicación y URL de retorno. Después se vincula ABANCA mediante el consentimiento bancario. Son credenciales de la aplicación en Enable Banking, no una licencia bancaria propia ni un certificado regulatorio que deba contratar el usuario. [Formulario y flujo implementado](https://github.com/we-promise/sure/blob/main/app/views/settings/providers/_enable_banking_panel.html.erb).

En la alpha, Trade Republic admite teléfono/PIN o QR. Su modelo no persiste el PIN; guarda la sesión y la cifra cuando está configurado el cifrado de Active Record. El inicializador permite claves explícitas o, en instalaciones propias, derivarlas de `SECRET_KEY_BASE`. Por tanto, no es obligatorio introducir tres claves manualmente, pero sí usar secretos propios y comprobar el cifrado efectivo antes de enlazar datos reales. No basta con conservar los valores de ejemplo del Compose. [Modelo de sesión](https://github.com/we-promise/sure/blob/v0.7.5-alpha.9/app/models/trade_republic_item.rb), [inicializador de cifrado](https://github.com/we-promise/sure/blob/main/config/initializers/active_record_encryption.rb) y [Compose examinado](https://github.com/we-promise/sure/blob/v0.7.5-alpha.9/compose.example.yml).

Con un equipo existente no hay alquiler adicional, pero sí consumo eléctrico y mantenimiento. Como referencia externa, Hetzner publica CX23 a 5,49 €/mes antes de IVA, IPv4 y extras, sujeto a disponibilidad. No es una tarifa de Sure ni un presupuesto cerrado del despliegue. [Tarifas publicadas](https://docs.hetzner.com/general/infrastructure-and-availability/price-adjustment/).

## Confianza y seguridad: revisión del 18/09/2026

La comparación separa al proveedor que accede al banco de la aplicación que conserva los movimientos. Sure es una aplicación de finanzas que podemos alojar; Enable Banking es el intermediario de acceso bancario. Usar Sure con Enable Banking introduce ambas piezas, no sustituye una por otra. Esta revisión de fuentes públicas y archivos concretos no equivale a una auditoría completa ni a una prueba de penetración.

### Enable Banking

Se ha comprobado directamente en el registro de la EBA la ficha de Enable Banking Oy, identificador nacional 29884997: proveedor de información de cuentas, estado registrado actual desde el 12/06/2020, autoridad nacional FIN-FSA y prestación de información de cuentas en España. La EBA reproduce los datos de las autoridades nacionales; no es una certificación técnica de la integración con nuestro banco. [Ficha del registro](https://euclid.eba.europa.eu/register/pir/view/PSD_AISP/FI_FIN_FSA!29884997).

La empresa publica el certificado de Sertika 24I.2464 para ISO/IEC 27001:2022, emitido el 05/12/2024 y válido según el documento hasta el 02/11/2026. Se ha inspeccionado visualmente el PDF: su alcance incluye desarrollo, mantenimiento y servicios cloud para agregación Open Banking. Acredita un sistema de gestión de seguridad; no demuestra ausencia de vulnerabilidades. Su documentación declara pruebas de penetración independientes al menos anuales, pero no se ha revisado un informe completo de dichas pruebas ni validado el certificado en el registro del emisor. [Certificado publicado](https://enablebanking.com/ISO27001_CERT_2464_SERTIKA_EN.pdf) y [gestión de riesgos](https://enablebanking.com/docs/tpp/operational-risk-management/).

La política de privacidad del servicio declara tratamiento de datos de cuentas durante un máximo de 60 segundos para conversión, conservación de datos de sesión hasta 180 días y de IP/agente de usuario durante 30 días. También contempla procesar credenciales durante un máximo de 15 minutos cuando el flujo del banco lo requiere: no se puede prometer de forma general que el proveedor nunca las recibe. Identifica proveedores cloud en el EEE. Son plazos declarados por el servicio, no garantías verificadas mediante una auditoría propia. Los datos entregados a Pausa o Sure tienen después su propio almacenamiento. [Política de privacidad](https://tilisy.enablebanking.com/privacy).

El permiso AIS solicitado para Pausa permite consultar información; no concede iniciación de pagos. Reduce la capacidad concedida al intermediario frente a una sesión bancaria general, pero sigue exponiendo información sensible sobre saldos, ingresos y gastos. La valoración es de confianza razonable para una prueba de acceso AIS, sustentada en evidencias públicas, sin garantía absoluta de seguridad.

### Sure

El código abierto permite revisar el funcionamiento, pero no sustituye una auditoría. No se ha encontrado una auditoría independiente publicada que cubra la versión que desplegaríamos. La política de seguridad ofrece un canal para comunicar vulnerabilidades; su tabla de versiones soportadas no está al día respecto de las releases examinadas. Esto limita lo que podemos concluir sobre el mantenimiento de seguridad, sin demostrar por sí solo una vulnerabilidad. [Repositorio](https://github.com/we-promise/sure) y [política de seguridad](https://github.com/we-promise/sure/blob/main/SECURITY.md).

El Compose examinado contiene valores públicos de ejemplo para `SECRET_KEY_BASE` y la contraseña de PostgreSQL, y no fuerza SSL por defecto. Dado que las claves de cifrado pueden derivarse de ese secreto, sustituirlo es especialmente importante. Un despliegue real necesita secretos propios, HTTPS, acceso restringido, actualizaciones y copias protegidas. La configuración de ejemplo no permite concluir que cualquier instalación existente sea insegura; hay que comprobar la configuración efectiva. [Compose](https://github.com/we-promise/sure/blob/v0.7.5-alpha.9/compose.example.yml) e [inicializador](https://github.com/we-promise/sure/blob/main/config/initializers/active_record_encryption.rb).

Con Sure estable y Enable Banking, el acceso al banco puede mantenerse en AIS. Con el conector directo de Trade Republic de la alpha se conserva una sesión de usuario: que Sure solo implemente consultas no demuestra que el banco limite esa sesión a lectura. El PIN no persistente reduce un riesgo, pero la sesión sigue siendo un secreto sensible. Un servicio de Sure alojado por otra empresa requeriría evaluar además a ese operador; esta revisión se refiere al proyecto y a su instalación propia.

Para Pausa, la opción preferida por alcance de permisos y menor infraestructura propia es integrar directamente Enable Banking con AIS y validar ambos bancos. Sure puede servir si se desea utilizar su aplicación completa. Escribir nuestro propio conector no aporta seguridad automáticamente: cambia el responsable del código y deja pendiente la amplitud del permiso bancario. No se ha conectado ninguna cuenta durante esta revisión.

## Alojamiento de Sure: Cloudflare y PC, revisión del 21/09/2026

El usuario prefiere aprovechar la aplicación completa de Sure y mantener las conexiones bancarias en Enable Banking, evitando gestionar una sesión directa de Trade Republic. La alternativa recomendada para esa preferencia y ausencia de cuotas adicionales es Sure en el PC, con acceso remoto opcional mediante Cloudflare Tunnel y Access Free. Esta revisión evalúa viabilidad; no instala ni arranca Sure ni modifica recursos de Cloudflare.

### Qué hay que ejecutar

El Compose de la estable `v0.7.4`, aún la última release estable al consultar GitHub, utiliza cuatro servicios principales: aplicación web Rails, worker Sidekiq para tareas, PostgreSQL 16 y Redis. Conserva datos en volúmenes y permite un contenedor adicional para copias. No requiere GPU ni un servicio de IA para sus funciones básicas. La aplicación proporciona cuentas, movimientos y presupuestos; habría que contrastar su presupuesto con la lógica diaria específica propuesta para Pausa. [Compose estable](https://github.com/we-promise/sure/blob/v0.7.4/compose.example.yml), [release](https://github.com/we-promise/sure/releases/tag/v0.7.4) y [presupuestos implementados](https://github.com/we-promise/sure/blob/v0.7.4/app/models/budget.rb).

La instalación es de complejidad moderada: preparar Compose y secretos, arrancar servicios, crear el usuario, cerrar nuevas altas, configurar la integración y comprobar copias y restauración. Después exige actualizaciones y mantenimiento. La guía distingue `stable` de `latest`, que sigue las alpha; para esta prueba conviene fijar una release estable concreta. [Guía de alojamiento](https://github.com/we-promise/sure/blob/main/docs/hosting/docker.md).

La guía actual de la rama principal publica mediciones de un despliegue ajustado para contenedores de 512 MB. No se debe presentar esa cifra como consumo total garantizado de los cuatro servicios y Docker Desktop, ni extrapolarla automáticamente a la estable. Para una primera prueba sin IA reservaría un margen de 2–4 GB de RAM para Docker y 10–20 GB de disco inicial. Son estimaciones de planificación, no requisitos oficiales ni consumo medido en este PC; el histórico, adjuntos, copias e imágenes pueden aumentar el espacio necesario.

### Cloudflare como servidor completo

Workers y Pages no ejecutan directamente el Compose de Rails, Sidekiq, PostgreSQL y Redis. Adaptarlo a otros servicios supondría trabajo adicional. Cloudflare Containers permite ejecutar imágenes, pero exige Workers Paid, desde 5 USD/mes, y factura recursos que excedan sus franquicias. Incluso si la cuenta ya paga Workers, no se puede prometer que añadir Sure no incremente el consumo facturado. Además, el disco del contenedor es efímero: habría que resolver la persistencia de PostgreSQL y de los archivos fuera de ese disco. No es una ruta de alojamiento completo gratuito y directo. [Precios de Containers](https://developers.cloudflare.com/containers/platform/pricing/) y [persistencia](https://developers.cloudflare.com/containers/faq/#is-disk-persistent-what-happens-to-my-disk-when-my-container-sleeps).

No se ha inspeccionado la facturación de la cuenta del usuario. Esta conclusión se basa en los requisitos y tarifas públicas, y no necesita activar productos ni contratar nada.

### PC como servidor y Cloudflare para acceder

Comprobación local de solo lectura: Windows 11 Home, unos 16 GB de RAM, Intel i5-12400F y aproximadamente 55 GB libres en C:. Están instalados Docker Desktop, Docker Compose, WSL con versión predeterminada 2 y `cloudflared`. El motor Linux de Docker no responde porque no está disponible en ese momento; aún hay que arrancarlo y comprobarlo. No se ha medido el consumo de Sure. Docker Desktop permite uso personal gratuito. [Licencia y requisitos de Docker](https://docs.docker.com/desktop/setup/install/windows-install/).

El esquema propuesto es navegador del PC o móvil → Cloudflare Access → Cloudflare Tunnel → Sure en el PC. Tunnel está disponible en el plan gratuito; Access tiene modalidad gratuita para hasta 50 usuarios. Para este uso individual se puede mantener la configuración en productos gratuitos, sin Containers ni almacenamiento cloud facturable. La URL pública estable requiere un dominio en Cloudflare: si ya existe uno, se puede usar un subdominio; adquirir o renovar un dominio es un coste separado. [Tunnel](https://developers.cloudflare.com/tunnel/), [requisitos del dominio](https://developers.cloudflare.com/tunnel/get-started/) y [planes de Access](https://www.cloudflare.com/plans/).

Tunnel aporta conectividad, no autorización de usuarios por sí solo. La propuesta restringe Access al usuario, mantiene el login de Sure y publica una URL HTTPS estable para el retorno de Enable Banking. El callback debe funcionar en el mismo navegador conservando las sesiones de Access y Sure. El código de producción de Sure genera el retorno desde su URL, por lo que habrá que configurar correctamente el proxy y el esquema HTTPS. No se ha probado aún ese flujo extremo a extremo. [Generación del callback](https://github.com/we-promise/sure/blob/v0.7.4/app/helpers/application_helper.rb).

El PC debe estar encendido, despierto y con Docker activo para abrir Sure o sincronizar. Con el PC apagado o suspendido la app remota queda inaccesible y no se ejecutan consultas; los datos permanecen en sus volúmenes. Sure incluye una programación diaria configurable, que conviene situar cuando el PC esté encendido. No se ha comprobado la recuperación automática de ejecuciones perdidas durante la suspensión. [Planificador](https://github.com/we-promise/sure/blob/v0.7.4/app/services/auto_sync_scheduler.rb).

El coste de software y servicios puede ser 0 € con este diseño y un dominio ya disponible, pero hay consumo eléctrico, mantenimiento y almacenamiento local. Mantener un PC encendido exclusivamente para Sure puede tener un coste apreciable; no se ha medido su potencia ni la tarifa eléctrica del usuario. Las copias deben incluir datos y claves de cifrado, con protección y una copia fuera del mismo disco.

### Trade Republic únicamente a través de Enable Banking

El proveedor de Enable Banking de la estable utiliza `https://api.enablebanking.com` para autorización, sesiones AIS, cuentas, saldos y movimientos. El árbol de esta release no incluye el cliente nativo `trade_republic`; no necesitamos activarlo ni guardar en Sure teléfono, PIN o cookies de una sesión directa de Trade Republic para esta vía. Sure conserva sus credenciales de aplicación y sesión con Enable Banking, que siguen necesitando protección. [Proveedor examinado](https://github.com/we-promise/sure/blob/v0.7.4/app/models/provider/enable_banking.rb).

La cobertura efectiva del IBAN español sigue pendiente de una prueba real. La documentación del proveedor sigue indicando que Trade Republic solo entrega importe, moneda y fecha de contabilización, sin datos descriptivos. Self-hostear Sure no elimina esa limitación para categorizar compras. Si la prueba no satisface el presupuesto, habría que valorar importar extractos, sin cambiar automáticamente a una conexión directa. [Limitación publicada](https://enablebanking.com/docs/markets/de/#trade-republic).

## Simplificar Sure y completar Trade Republic: revisión del conector del 21/09

### Alcance realmente examinado

Se han descargado y leído archivos del conector fijados a `v0.7.5-alpha.9`: cliente, sesión HTTP, WebSocket, modelo de sesión, importador, procesador de movimientos y partes de controlador y pruebas. También se ha contrastado la API de importación de la estable `v0.7.4`. No se ha ejecutado el conector, usado credenciales reales, conectado cuentas ni ejecutado la suite Ruby.

La PR original 3168 fue incorporada al repositorio, pero el conector sigue fuera de la release estable consultada. Afecta a 94 archivos y añade 7.771 líneas, incluyendo pruebas, traducciones y configuración. Los archivos de aplicación dedicados descargados de la alpha suman aproximadamente 3.900 líneas en 26 archivos; el cliente principal tiene unas 860. Portar todo requiere tocar modelos, rutas, dependencias, vistas y esquema: no es copiar un único archivo. [PR original](https://github.com/we-promise/sure/pull/3168) y [cliente fijado a la alpha](https://github.com/we-promise/sure/blob/v0.7.5-alpha.9/app/models/provider/trade_republic_client.rb).

El conector combina:

- Login con teléfono/PIN y segundo factor, o QR aprobado en la aplicación de Trade Republic.
- Cookies de sesión, restauración, renovación, errores de autenticación y límites de peticiones.
- Consultas HTTP y WebSocket para efectivo disponible, saldo, cartera, precios e historial.
- Dos fuentes de actividad, `timelineTransactions` y `timelineActivityLog`, deduplicadas por identificador, y consultas de detalle.
- Tratamiento de compras con tarjeta, devoluciones, cajeros, transferencias e intereses, además de operaciones de inversión.
- Persistencia, cuentas separadas, importación, reconciliación y diagnóstico.

El procesador conserva título y subtítulo de los movimientos y reconoce tipos de tarjeta y devoluciones; ofrece una vía para obtener información descriptiva que AIS no entrega. Que esté implementada no garantiza cobertura completa de la cuenta española ni de cargos pendientes. La sincronización actual solicita cartera y precios aunque solo interese presupuesto: una variante de efectivo debe omitir esas consultas en el cliente, no solo esconder su pantalla. [Procesador](https://github.com/we-promise/sure/blob/v0.7.5-alpha.9/app/models/trade_republic_account/activities_processor.rb).

La descripción de la PR declara explícitamente que las pruebas HTTP/WebSocket usan respuestas simuladas y que no se utilizaron credenciales reales; deja pendiente verificar el acceso de producción. Es un límite de esa evidencia, no una afirmación de que nadie lo haya probado después. Hay pruebas de duplicados, tipos de movimientos y respuestas parciales, pero no bastan para prometer que el login funcionará con nuestra cuenta. El cliente limita páginas y detalles; la importación debe comunicar resultados incompletos y no afirmar que el presupuesto está actualizado cuando falten movimientos.

### Extracto, CSV y automatización

Parqet documenta una exportación CSV desde la app de Trade Republic: perfil, extractos, informe de transacciones, compartir y crear. Es evidencia primaria del importador de Parqet, no una comprobación directa de nuestra aplicación española. Sigue pendiente verificar que el archivo disponible para este usuario contiene compras con tarjeta, comercio, fecha, importe, devoluciones e identificadores, además de inversiones. [Guía de CSV](https://faq.parqet.com/de/articles/651186-trade-republic-csv-import).

Si el CSV contiene los datos necesarios, importarlo es preferible a interpretar un PDF: su estructura facilita identificar columnas y validar cantidades. La descarga manual seguida de importación automática permite que el importador no conserve acceso al banco. Descargarlo automáticamente también requiere autenticación bancaria o un mecanismo oficial alternativo de entrega, que no se ha encontrado; no hereda permisos AIS por el hecho de producir un archivo.

El cliente de Sure examinado consulta el historial y no implementa la descarga de ese CSV oficial. Su existencia en la app no demuestra que pueda obtenerse con un endpoint estable o desde el navegador web. La automatización visual del navegador añade dependencia de pantallas, menús y generación de archivos además de la sesión; no la elegiría como primera vía diaria mientras podamos consultar datos estructurados.

El extracto mensual solo contiene movimientos completados dentro del mes, según Trade Republic. Descargarlo repetidamente no aporta automáticamente cargos pendientes o compras de hoy. Para presupuesto diario hay que verificar la fecha efectiva cubierta, las reservas y la diferencia entre saldo contable y disponible. [Explicación del banco](https://support.traderepublic.com/es-es/1663-Why-are-some-card-transactions-missing-from-my-monthly-account-statement).

### Propuesta para reducir mantenimiento y exposición

Mantener Sure estable y sus funciones existentes. Simplificar la configuración y la interfaz utilizada, evitando un fork que elimine grandes partes de la aplicación. ABANCA seguiría por Enable Banking. Para Trade Republic, crear una herramienta local independiente de la aplicación web y reutilizar únicamente la autenticación y consultas necesarias del cliente examinado, después de una prueba real del protocolo.

La herramienta tendría una única tarea de adquisición e importación. Podría recibir inicialmente un CSV descargado por el usuario y después incorporar la consulta automática de efectivo e historial. Si se verifica que el CSV oficial puede descargarse programáticamente y cubre mejor los movimientos, se cambiaría esa adquisición sin rehacer el importador de Sure. No se propone implementar pagos ni operaciones sobre valores, pero esa restricción del código no convierte la sesión bancaria en una autorización AIS.

La sesión de Trade Republic quedaría en el proceso local, protegida con el almacén de secretos del sistema y sin exponerse en el servicio web de Sure. QR permitiría evitar introducir PIN en nuestra herramienta si el flujo funciona con esta cuenta. Se pueden ofrecer dos modos: sesión solo durante la actualización con nueva aprobación en cada ejecución, o persistencia cifrada para mayor automatización hasta que el banco exija reconectar. Borrar la copia local no implica revocar la sesión en el banco; cerrar o revocar debe verificarse separadamente. Separar procesos reduce la exposición directa de la cookie desde la web, pero no protege frente a una intrusión total en el PC.

Sure estable ya dispone de API de movimientos y de importaciones CSV. La creación de movimientos admite `external_id` y `source` para detectar operaciones ya importadas. Esto permite alimentar Sure sin añadir toda la integración nativa ni escribir directamente en PostgreSQL. Escribir en la API de Sure modifica el libro local; no concede capacidad de escritura en el banco. Hay que preservar correcciones manuales y tratar por separado modificaciones de operaciones ya existentes: deduplicar una creación no equivale a actualizarla. [API de movimientos estable](https://github.com/we-promise/sure/blob/v0.7.4/app/controllers/api/v1/transactions_controller.rb) y [API de importaciones](https://github.com/we-promise/sure/blob/v0.7.4/app/controllers/api/v1/imports_controller.rb).

### Comportamiento diario propuesto

1. Al abrir Sure mediante el acceso local, mostrar los últimos datos guardados y su fecha.
2. Si no se ha actualizado ese día, iniciar una única tarea en segundo plano; impedir ejecuciones concurrentes y reintentos de login continuos.
3. Reutilizar una sesión válida o solicitar aprobación de Trade Republic cuando corresponda. No prometer una duración fija de sesión.
4. Recuperar efectivo y movimientos con solapamiento entre fechas, incluyendo el cambio de mes, en vez de limitar siempre la descarga al mes actual. La ventana exacta depende de lo que admita y entregue la fuente.
5. Normalizar identificadores, importes, moneda, fecha, concepto y estados; importar sin duplicar y conservar correcciones del usuario. No tratar toda entrada como ingreso ni una transferencia propia como consumo.
6. Mostrar si la actualización está completa, requiere intervención o dejó datos parciales. No sumar dos importaciones independientes de la misma cuenta TR por AIS y por el cliente local.

La primera comprobación debe ser pequeña: revisar un CSV real, contrastar unas compras y devoluciones, y validar login y lectura aislada antes de desarrollar pantallas o prometer sincronización diaria. Para máxima comodidad, la opción preferida es cliente local reducido con importación por API; para evitar por completo custodiar una sesión TR, la alternativa es descarga manual del CSV y automatización solo desde ese archivo.

## Implementar un cliente propio en lugar de pytr

El código de pytr inicia sesión web con teléfono/PIN, completa el segundo factor y usa cookies en HTTP y WebSocket. Intenta restaurar la sesión guardada y refresca el acceso durante las consultas; si falla, necesita otro login. El intervalo interno de 290 segundos no es la duración garantizada de la sesión. [Implementación examinada, commit e7f3ba3](https://github.com/pytr-org/pytr/blob/e7f3ba37167bda15c0b5501f7ddab24c8d24d37e/pytr/api.py).

Su CLI puede guardar teléfono y PIN en un archivo de texto si se solicita persistencia. Es una elección de ese programa, no un requisito del protocolo: podemos diseñar Pausa con PIN solo en memoria y sesión cifrada. [Gestión de credenciales de pytr](https://github.com/pytr-org/pytr/blob/e7f3ba37167bda15c0b5501f7ddab24c8d24d37e/pytr/account.py).

Es técnicamente viable escribir un adaptador propio con HTTP/WebSocket y sin instalar pytr. Seguiríamos dependiendo del protocolo privado de Trade Republic, sin contrato público de estabilidad para nuestra integración. Habría que mantener login, renovación, errores, paginación y normalización de movimientos. El permiso bancario seguiría siendo una sesión de usuario; restringir funciones en Pausa no modifica ese permiso.

Para Pausa propondría un componente independiente del motor de presupuesto, limitado a consultas, con sincronización incompleta y necesidad de reconectar como estados visibles. Puede estudiarse su ejecución en Android al abrir la app, conservando la sesión cifrada en el dispositivo, o en un servicio privado para sincronización periódica. Instalar Sure entero no es requisito para esa arquitectura. Esta sección evalúa viabilidad; no se ha iniciado su implementación.

## Encaje en el código actual

Pausa es una app Android nativa en Java, con vistas construidas en código y persistencia en SharedPreferences. No se encontró un backend de banking ni un almacén de transacciones. La propuesta requiere:

- Nueva sección Banking con resumen, movimientos y presupuesto, usando la identidad visual actual.
- Cambiar la selección de secciones en `MainActivity` y `PausaNavigation`: actualmente los índices mayores o iguales a cuatro se interpretan como Plan. Añadir una vista al final sin ajustar esa lógica la clasificaría incorrectamente.
- Un repositorio de movimientos independiente del proveedor, almacenamiento transaccional local y cantidades en céntimos para EUR, evitando coma flotante.
- Un pequeño backend privado HTTPS como opción recomendada para custodiar claves del proveedor, sesiones y sincronización. Autenticación del dispositivo, comprobación del retorno de autorización y claves fuera del APK y del repositorio.
- Datos locales protegidos y trazas sin tokens, IBAN completos ni descripciones de gastos. Prever exportación/copia cifrada: el manifiesto actual desactiva el backup automático.
- Importación y sincronización idempotentes, conciliación de pendientes y contabilizados, y conservación de correcciones manuales.

Para Enable Banking, su API expone duración máxima de consentimiento por banco y permite pedir saldos y movimientos. El identificador de cuenta de sesión puede cambiar al reconectar, y la API proporciona datos para reconocer la misma cuenta. Debe conservarse la identidad interna de Pausa. [Referencia API](https://enablebanking.com/docs/api/reference/).

No reutilizar la automatización de Renfe para entrar en bancos: el flujo propuesto utiliza autorización bancaria delegada en navegador. Estas son decisiones de arquitectura para la siguiente fase, no cambios ya implementados.

## Presupuesto dinámico propuesto

Configuración inicial breve: próximo ingreso, gastos fijos pendientes, ahorro que se quiere proteger y presupuesto de gasto variable del periodo. No hacen falta cifras personales para evaluar la conexión.

El motor calcularía:

1. Liquidez utilizable: saldo de las cuentas incluidas, menos ahorro protegido, compromisos pendientes y cargos aún no reflejados. Cada reserva se descuenta una sola vez.
2. Margen del presupuesto: presupuesto variable del periodo menos gasto variable ya realizado.
3. Margen disponible: el menor entre liquidez utilizable y margen del presupuesto.
4. Orientación diaria: margen disponible dividido entre los días restantes. Si es negativo, mostrar el déficit en vez de ocultarlo o aumentar el presupuesto automáticamente.

Si hoy se gasta más, baja la orientación de los días restantes. Si se gasta menos, aumenta dentro del límite del periodo. No se incorpora como dinero disponible una nómina que aún no ha llegado ni la revalorización de inversiones. Cambiar el ahorro protegido requiere una decisión visible del usuario.

Las transferencias entre ABANCA y Trade Republic no son ingresos ni consumo. Las aportaciones a inversiones deben reducir liquidez disponible y aparecer como ahorro/inversión, separadas de gastos cotidianos. Una venta de activos no equivale a una nómina. Si hay tarjeta de crédito, evitar contar tanto la compra como el pago de su liquidación.

La categorización puede empezar con reglas locales por comercio y correcciones que se recuerdan. Los movimientos sin descripción quedan pendientes de clasificación. No hace falta contratar IA ni enviar los movimientos a un modelo para esta primera versión.

## Siguiente fase verificable

1. Registrar una aplicación personal restringida en Enable Banking con AIS. Consultar el catálogo autenticado de cuentas personales ES y comprobar ABANCA y Trade Republic, incluida la disponibilidad de beta.
2. Probar ambos bancos: contrastar saldo, una compra, un ingreso y una transferencia con la app del banco; comprobar histórico completo, paginación, identificadores y campos disponibles. En Trade Republic, distinguir problemas de conexión de carencias de descripción.
3. Verificar nueva lectura sin login inmediato, caducidad comunicada y revocación. Registrar resultados sin datos bancarios en Git. Si falta historia o pendientes, mostrarlo explícitamente.
4. Si se quiere añadir cartera, probar Parqet Basic con la cuenta española y una integración privada `portfolio:read`. Contrastar posiciones, efectivo y una operación reciente; medir cuánta intervención exige refrescar.
5. Si AIS es insuficiente para los gastos de Trade Republic, comparar importación de extractos con un adaptador de API privada, explicitando la diferencia de permisos antes de conectar la cuenta.
6. Implementar Banking y el motor de presupuesto sobre el formato normalizado. Probar duplicados, devoluciones, transferencias internas, pendientes que se contabilizan y cambio de periodo.

No se han creado cuentas, contratado servicios, autorizado acceso bancario ni modificado el código de la app durante esta investigación. La conclusión revisada es que sí existen vías personales para Trade Republic; la primera prueba debería ser Enable Banking para ambos bancos, con Parqet como opción de cartera y una validación explícita de los datos necesarios para el presupuesto.
