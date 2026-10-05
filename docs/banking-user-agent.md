# Prueba del acceso web de Trade Republic

Desde el 05/10/2026 la misma variante incorpora el [cliente nativo de TR](trade-republic-native.md)
y se llama **Pausa Banking · TR nativo**. La prueba A/B/C sigue disponible.

La variante `bankingUa` se instalaba como **Pausa Banking · prueba UA**, con ID
`com.sejio.calorapp.bankingua`. Convive con la app personal y la anterior prueba
`com.sejio.calorapp.bankingtest`; no hereda sus datos, sesiones ni firma.

El módulo bancario se ha recuperado del código completo conservado en el chat
«Implementa módulo bancario seguro». La implementación original se guardó en
`feat/local-banking-sessions`, commit `cf2e4fd`, pero no llegó al remoto. Esta
copia recupera sus 18 archivos y añade la prueba descrita aquí. No conserva el
keystore del APK anterior.

## Comparar en el teléfono

1. Abre **Banca → Abrir Trade Republic**.
2. Prueba **A · Móvil** y observa si muestra «descarga la app» o un formulario.
3. Pulsa el botón del modo y elige **B · Identificación PC**. Comprueba lo mismo.
4. Si B tampoco funciona, prueba **C · PC + pantalla ancha**.
5. **Diagnóstico** muestra el User-Agent, el indicador móvil de Client Hints,
   la plataforma y el ancho que ve JavaScript. No lee datos de la cuenta,
   cookies, tokens, campos de acceso ni URL de la página.

No hace falta introducir credenciales para comparar la aparición del formulario.
Cada modo usa un perfil persistente distinto para evitar compartir cookies,
almacenamiento o redirecciones almacenadas entre pruebas. Cambiar de modo vuelve
al inicio del banco. Para repetir desde cero, cierra la ventana del banco y usa
**Olvidar sesión en este teléfono**: elimina los tres perfiles de prueba y el
perfil anterior de Trade Republic, conservando la captura.

## Interpretación

- A redirige a la app y B muestra acceso: evidencia de que la identificación del
  navegador influye. A y B mantienen el mismo ancho, motor y capacidad táctil.
- Solo C muestra acceso: el ancho también influye en estas condiciones. C usa la
  misma identificación que B, pero un WebView de al menos 1100 dp, reducido
  visualmente para caber en el teléfono.
- Ninguno muestra acceso: no demuestra por sí solo un bloqueo de WebView.
  Puede haber otras comprobaciones o un error de carga; conserva el texto exacto
  del mensaje y los datos de Diagnóstico. El contraste siguiente es Chrome en el
  mismo teléfono, primero normal y luego con «Sitio para ordenador».

La identificación de PC usa la versión real de Chromium instalada y, cuando
WebView lo soporta, Client Hints coherentes (`mobile=false`, plataforma Linux,
arquitectura x86). No se utiliza la API interna de WebKit para forzar form factors.
Si no permite configurar Client Hints,
Diagnóstico advierte de que la prueba es parcial. El motor sigue siendo Android
WebView: no se falsifican propiedades JavaScript táctiles ni se omiten errores TLS.
ABANCA conserva su identificación y perfil originales.

Ver el formulario no acredita que el banco acepte toda la autenticación. El acceso
real y el segundo factor se prueban después, directamente en la web del banco.

## Compilar

Se necesitan JDK 17 o posterior, Android SDK 35 y Build Tools 34.0.0. Crea una
clave de prueba una sola vez y consérvala para poder actualizar esta variante:

```sh
mkdir -p build
keytool -genkeypair -keystore build/banking-ua.keystore \
  -storepass android -keypass android -alias androiddebugkey \
  -dname 'CN=Android Debug,O=Android,C=US' \
  -keyalg RSA -keysize 2048 -validity 10000
bash gradlew :app:assembleBankingUa
```

La clave es exclusivamente de desarrollo y está excluida de Git. El APK queda en
`app/build/outputs/apk/bankingUa/app-bankingUa.apk`.

```sh
sh tools/test-banking-policy.sh
npm run test:banking
```

Estas comprobaciones cubren la política de orígenes, separación de perfiles,
conversión del User-Agent y lector de páginas sintéticas. No reemplazan la
prueba del login con Trade Republic en el teléfono.
