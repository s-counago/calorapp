package com.sejio.calorapp;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.CancellationSignal;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

import org.json.JSONObject;

import java.security.KeyStore;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Signs the phone in to the Pausa backend: Google ID token from Credential Manager (with the web
 * client ID as serverClientId), exchanged at POST /auth/device for a long-lived device token. The
 * token is encrypted with an Android Keystore key, so it never sits in plain text on disk.
 */
final class CloudAuth {
    private static final String PREFS = "pausa_cloud";
    private static final String ALIAS = "pausa_cloud_v1";

    interface Callback {
        void done(String error);
    }

    private CloudAuth() {}

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean signedIn(Context context) {
        return prefs(context).contains("token") && !prefs(context).getBoolean("auth_error", false);
    }

    static String account(Context context) { return prefs(context).getString("account", ""); }
    static String deviceId(Context context) { return prefs(context).getString("device_id", ""); }
    static boolean authError(Context context) { return prefs(context).getBoolean("auth_error", false); }

    /** The device token, or null when signed out or when it can no longer be decrypted. */
    static String token(Context context) {
        String stored = prefs(context).getString("token", null);
        if (stored == null) return null;
        try {
            return BankingCrypto.decrypt(key(false), ALIAS, Base64.decode(stored, Base64.NO_WRAP)).getString("token");
        } catch (Exception failure) {
            PausaLog.w("auth", "Device token could not be decrypted", failure);
            return null;
        }
    }

    /** Runs the Google sign-in sheet on `activity` and registers this phone with the backend. */
    static void signIn(Activity activity, Callback callback) {
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty()) { callback.done("Falta el client ID web de Google en la compilación."); return; }
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(new GetSignInWithGoogleOption.Builder(BuildConfig.GOOGLE_WEB_CLIENT_ID).build()).build();
        PausaLog.i("auth", "Starting Google sign-in");
        CredentialManager.create(activity).getCredentialAsync(activity, request, new CancellationSignal(), Executors.newSingleThreadExecutor(),
                new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                    @Override public void onResult(GetCredentialResponse response) {
                        String error = register(activity, response.getCredential());
                        activity.runOnUiThread(() -> callback.done(error));
                    }

                    @Override public void onError(GetCredentialException failure) {
                        boolean cancelled = failure instanceof GetCredentialCancellationException;
                        PausaLog.event(cancelled ? "info" : "warn", "auth", "Google sign-in failed",
                                json("type", failure.getType(), "message", String.valueOf(failure.getMessage())));
                        activity.runOnUiThread(() -> callback.done(cancelled ? "Inicio de sesión cancelado."
                                : "Google no devolvió la cuenta (" + failure.getType() + "). Revisa que exista la credencial Android en Google Cloud."));
                    }
                });
    }

    private static String register(Context context, Credential credential) {
        if (!(credential instanceof CustomCredential) || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            return "Credencial inesperada: " + credential.getType();
        }
        GoogleIdTokenCredential google = GoogleIdTokenCredential.createFrom(((CustomCredential) credential).getData());
        String requestId = UUID.randomUUID().toString();
        try {
            OkHttpClient http = new OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build();
            JSONObject body = new JSONObject().put("id_token", google.getIdToken()).put("name", Build.MANUFACTURER + " " + Build.MODEL);
            Request request = new Request.Builder().url(BuildConfig.PAUSA_API_URL + "/auth/device").header("X-Request-Id", requestId)
                    .post(RequestBody.create(body.toString(), MediaType.get("application/json"))).build();
            try (Response response = http.newCall(request).execute()) {
                JSONObject answer = new JSONObject(response.body() == null ? "{}" : response.body().string());
                if (!response.isSuccessful()) {
                    String code = answer.optString("code", "http_" + response.code());
                    PausaLog.event("warn", "auth", "Device registration refused", json("status", response.code(), "code", code, "request_id", requestId));
                    return code.equals("account_not_allowed") ? "Esta cuenta no tiene acceso a Pausa." : "El servidor rechazó el inicio de sesión (" + code + ").";
                }
                store(context, answer.getString("token"), answer.getString("device_id"), google.getId());
                PausaLog.event("info", "auth", "Device registered", json("device_id", answer.getString("device_id"), "request_id", requestId));
            }
            PausaSync.schedulePeriodic(context);
            PausaSync.requestSoon(context);
            return null;
        } catch (Exception failure) {
            PausaLog.w("auth", "Device registration failed", failure);
            return "No se pudo contactar con el servidor. Vuelve a intentarlo con cobertura.";
        }
    }

    private static void store(Context context, String token, String deviceId, String account) throws Exception {
        byte[] encrypted = BankingCrypto.encrypt(key(true), ALIAS, new JSONObject().put("token", token));
        prefs(context).edit().putString("token", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("device_id", deviceId).putString("account", account).putBoolean("auth_error", false).apply();
    }

    /** Called when the backend answers 401: keep local data, stop syncing until the user signs in again. */
    static void markRejected(Context context, String code) {
        prefs(context).edit().putBoolean("auth_error", true).putString("auth_error_code", code).apply();
    }

    /** Forgets the device token. Local data stays on the phone. */
    static void signOut(Context context) {
        prefs(context).edit().clear().apply();
        PausaSync.cancel(context);
        PausaLog.i("auth", "Signed out of the cloud");
    }

    private static SecretKey key(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        if (!create) throw new IllegalStateException("Missing cloud key");
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    static JSONObject json(Object... pairs) {
        JSONObject object = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) object.put((String) pairs[i], pairs[i + 1]);
        } catch (Exception ignored) {
            // Keys are literals.
        }
        return object;
    }
}
