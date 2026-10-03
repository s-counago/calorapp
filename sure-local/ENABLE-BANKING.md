# Enable Banking: diagnóstico y configuración

Estado comprobado el 23 de septiembre de 2026. Sure 0.7.4 está funcionando, con el parche local descrito abajo. La aplicación `Sure finance` de Enable Banking está activa en producción, permite España y tiene únicamente el servicio AIS. Sus credenciales ya están guardadas en Sure con país `ES`, cifradas mediante Active Record Encryption. Todavía no hay sesión bancaria ni cuentas importadas.

Actualización: **HTTPS ya está desplegado y comprobado en el servidor**. Las URLs actuales son `https://sure.pausa.internal/enable_banking_items/callback` y `https://localhost/enable_banking_items/callback`. Falta confiar en la CA desde los dispositivos y registrar esas direcciones en el panel de Enable Banking. La comprobación de la API después del despliegue todavía devuelve la URL antigua. Ver [HTTPS.md](HTTPS.md) para instrucciones actuales; los apartados de diagnóstico siguientes conservan el historial del fallo HTTP.

## Causas verificadas

Los seis intentos de guardar del 22 de septiembre enviaron `country_code` vacío. El modelo lo rechazó correctamente, pero el controlador contestaba con una supuesta redirección de estado 422 a una petición Turbo Stream. El navegador no mostraba la validación. Además, el controlador apuntaba al panel de configuración anterior, mientras el formulario inicial está dentro de `enable_banking-connect-form`.

La API de Enable Banking devuelve como única dirección registrada:

```text
https://localhost:3000/eb-callback
```

Esa dirección no correspondía al protocolo ni a la ruta del despliegue inicial. Sure usaba HTTP en el puerto 3000 y su ruta real es `/enable_banking_items/callback`. Ahora HTTPS funciona en el puerto estándar 443.

La clave generada inicialmente por el asistente no corresponde a esta aplicación: dio `401 Wrong signature`. La clave descargada al registrar la aplicación manualmente sí devuelve HTTP 200 en `GET /application`. Se ha conservado una copia con permisos limitados al usuario de Windows y SYSTEM en `secrets/enable-banking-1a03005e.pem`. El archivo antiguo se conserva y no debe usarse para esta aplicación. Ninguna clave se incluye en este documento ni en Git.

## Revisión del formato del callback

El usuario confirmó después que el panel rechaza las direcciones anteriores por su formato. Se encontró un [reporte de primera mano con exactamente la URL de Sure](https://github.com/we-promise/sure/issues/1326): el panel rechaza `http://localhost:3000/enable_banking_items/callback` con `uses unsupported scheme`. Las dos barras de `http://` forman parte del protocolo; no deben eliminarse. El diagnóstico apunta a la exigencia de HTTPS. Sigue pendiente observar el mensaje exacto en el navegador del usuario.

Las siguientes eran las direcciones HTTP que generaba el despliegue antes de instalar Caddy, **no instrucciones para registrarlas en Enable Banking**:

```text
http://localhost:3000/enable_banking_items/callback
http://sure.pausa.internal:3000/enable_banking_items/callback
```

La recomendación inicial de registrarlas directamente fue incorrecta. Se ha preparado un endpoint HTTPS real con Caddy, manteniendo el acceso privado. No basta con añadir `https://` a un servidor HTTP. WARP cifra el transporte privado, pero no convierte la URL del navegador en HTTPS.

Después de instalar la CA, iniciar sesión en Sure desde la dirección HTTPS y copiar el callback que muestre a [Applications](https://enablebanking.com/cp/applications). La ruta debe seguir siendo `/enable_banking_items/callback`, no `/eb-callback`. Registrar y verificar ese valor antes de retirar la dirección antigua. No se han cambiado las URLs del portal ni las políticas de Cloudflare. El navegador continúa sin estar disponible para la herramienta.

El acceso actual a los ajustes es `https://localhost/settings/providers` en el PC y `https://sure.pausa.internal/settings/providers` con WARP. El flujo bancario debe esperar a registrar el callback HTTPS. Las cookies pertenecen a cada hostname: iniciar sesión en Sure y completar todo el flujo usando la misma dirección y navegador. `localhost` en Android apunta al móvil, no al PC.

No se prueba el callback abriéndolo a mano: requiere los parámetros de autorización devueltos por Enable Banking. Si se necesita vincular previamente la cuenta propia en el portal de Enable Banking, completar ese paso allí, y después usar “Connect bank” en Sure y autorizar ABANCA.

## Comprobaciones realizadas

- API real `GET /application`: HTTP 200, producción, activa, España permitida y servicio AIS.
- API real `GET /aspsps?country=ES`, usando el cliente de Sure: 117 entradas; `Abanca` disponible para clientes personales y empresas, marcada como beta por Enable Banking.
- Credencial guardada, recargada y comparada en memoria; representación almacenada cifrada, sin PEM en claro.
- No se ha solicitado una autorización bancaria, intercambiado un código de consentimiento ni descargado movimientos.
- Web y túnel saludables tras desplegar; `http://localhost:3000/up` devuelve 200.

## Parche local

`compose.yml` monta dos archivos de `patches/enable-banking` sobre la imagen fijada de Sure:

- `enable_banking_items_controller.rb`: errores Turbo Stream visibles en los dos contenedores del formulario, conservación del marco para posteriores envíos y redirección HTML con 303.
- `_enable_banking_panel.html.erb`: país y datos iniciales obligatorios en el navegador, conservación de los valores enviados cuando la validación falla.

Se conservaron el resto del controlador y los comentarios existentes de upstream. No se ha cambiado el protocolo bancario ni la autenticación del usuario. Revisar o retirar estos montajes al actualizar Sure, para no mantener un controlador antiguo sobre una imagen nueva.

`verify.rb` ejecutó 30 comprobaciones contra una base de datos temporal que solo contenía el esquema y datos ficticios. Cubren creación y edición, errores visibles, conservación de campos y marcos, retorno de HTML, las dos URLs y rechazo de peticiones anónimas. La base temporal se eliminó después. No se ha verificado visualmente en un navegador en esta sesión.

Para repetirlas, crear una base vacía llamada `sure_enable_banking_check_20260923`, copiar únicamente el esquema y ejecutar:

```powershell
Get-Content patches/enable-banking/verify.rb -Raw | docker compose -f compose.yml -f compose.remote.yml run --rm -T --no-deps -e POSTGRES_DB=sure_enable_banking_check_20260923 -e RAILS_LOG_LEVEL=fatal --entrypoint bin/rails web runner -
```

El script comprueba el nombre de la base antes de crear datos o desactivar CSRF en su proceso de pruebas. No modifica la protección CSRF del servidor. Eliminar la base temporal al finalizar.

Antes de guardar la configuración se creó `backups/before-enable-banking-20260923.dump`, copia PostgreSQL en formato custom, protegida por ACL y excluida de Git. Conservar también `.env` para poder descifrar los datos restaurados.

Referencias: [guía de Sure](https://github.com/we-promise/docs/blob/main/providers/enable-banking.mdx), [API de Enable Banking](https://enablebanking.com/docs/api/reference/).
