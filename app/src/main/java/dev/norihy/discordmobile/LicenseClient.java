package dev.norihy.discordmobile;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** License requests run away from the UI and never enter the Discord send path. */
final class LicenseClient {
    interface Callback { void done(boolean valid, String explanation); }
    private static final String ALIAS = "message-auto-device-v1";
    private final Context context;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    LicenseClient(Context context) { this.context = context.getApplicationContext(); }

    boolean configured() { return BuildConfig.LICENSE_SERVER_URL.startsWith("https://"); }

    void validate(String code, boolean activate, Callback callback) {
        if (!configured()) { main.post(() -> callback.done(false, "License server is not configured in this APK.")); return; }
        executor.execute(() -> {
            boolean valid = false; String explanation = "Could not reach the license server.";
            try {
                KeyPair pair = keyPair();
                String action = activate ? "activate" : "check";
                String nonce = UUID.randomUUID().toString();
                Signature signer = Signature.getInstance("SHA256withECDSA");
                signer.initSign(pair.getPrivate());
                signer.update((action + "\n" + code + "\n" + nonce).getBytes(StandardCharsets.UTF_8));
                JSONObject request = new JSONObject()
                    .put("key", code).put("public_key", Base64.getEncoder().encodeToString(pair.getPublic().getEncoded()))
                    .put("nonce", nonce).put("signature", Base64.getEncoder().encodeToString(signer.sign()))
                    .put("model", Build.MANUFACTURER + " " + Build.MODEL);
                URL endpoint = new URL(BuildConfig.LICENSE_SERVER_URL + "/api/" + action);
                HttpURLConnection connection = (HttpURLConnection) endpoint.openConnection();
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setConnectTimeout(5000); connection.setReadTimeout(5000);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                connection.setRequestProperty("Cache-Control", "no-store");
                byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                int status = connection.getResponseCode();
                InputStream stream = status < 400 ? connection.getInputStream() : connection.getErrorStream();
                ByteArrayOutputStream response = new ByteArrayOutputStream();
                try (InputStream input = stream) {
                    byte[] buffer = new byte[1024]; int count;
                    while (response.size() < 8192 && (count = input.read(buffer, 0, Math.min(buffer.length, 8192 - response.size()))) != -1) response.write(buffer, 0, count);
                }
                JSONObject result = new JSONObject(response.toString("UTF-8"));
                valid = status == 200 && result.optBoolean("valid");
                explanation = valid ? "License active" : result.optString("reason", "License rejected");
                connection.disconnect();
            } catch (Exception e) { explanation = "Could not verify the license. Check your connection."; }
            final boolean accepted = valid; final String message = explanation;
            main.post(() -> callback.done(accepted, message));
        });
    }

    private static KeyPair keyPair() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return new KeyPair(store.getCertificate(ALIAS).getPublicKey(), (java.security.PrivateKey)store.getKey(ALIAS, null));
        KeyPairGenerator generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
        generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
            .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
            .setDigests(KeyProperties.DIGEST_SHA256).build());
        return generator.generateKeyPair();
    }

    void close() { executor.shutdownNow(); }
}
