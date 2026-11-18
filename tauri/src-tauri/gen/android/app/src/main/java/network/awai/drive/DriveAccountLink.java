package network.awai.drive;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import android.util.Base64;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.spec.ECGenParameterSpec;
import org.json.JSONObject;

/** Holds the device key and the signed public link. Drive file custody is separate. */
final class DriveAccountLink {
    private static final String ALIAS = "awai.drive.account.device.v1";
    private static final int LINK_LIMIT = 8192;
    private final AtomicFile proof;

    DriveAccountLink(Context context) {
        proof = new AtomicFile(new File(context.getNoBackupFilesDir(), "awai-account-link-v1.json"));
    }

    private JSONObject device(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias(ALIAS) && create) {
            if (read().has("request")) throw new IOException("account-device-key-missing");
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC", "AndroidKeyStore");
            generator.initialize(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256).build());
            generator.generateKeyPair();
        }
        Certificate certificate = store.getCertificate(ALIAS);
        if (certificate == null) return new JSONObject();
        return new JSONObject().put("publicKey", Base64.encodeToString(certificate.getPublicKey().getEncoded(),
            Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP));
    }

    private JSONObject read() throws Exception {
        try (FileInputStream input = proof.openRead()) {
            long size = input.getChannel().size();
            if (size <= 0 || size > LINK_LIMIT) throw new IOException("invalid-account-link");
            byte[] bytes = new byte[(int) size];
            new DataInputStream(input).readFully(bytes);
            return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        } catch (FileNotFoundException absent) {
            return new JSONObject();
        }
    }

    private JSONObject save(JSONObject link) throws Exception {
        String account = link.getString("accountDid");
        String prior = read().optString("accountDid");
        if (!prior.isEmpty() && !prior.equals(account)) throw new IOException("account-mismatch");
        if (!account.matches("did:key:z[1-9A-HJ-NP-Za-km-z]{1,100}") ||
            !"awai.drive.device-authorize.v1".equals(link.getJSONObject("request").optString("purpose")) ||
            !"passkey".equals(link.getJSONObject("response").optString("method")) ||
            !device(false).has("publicKey")) throw new IOException("invalid-account-link");
        byte[] bytes = link.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LINK_LIMIT) throw new IOException("invalid-account-link");
        FileOutputStream output = proof.startWrite();
        try {
            output.write(bytes);
            proof.finishWrite(output);
        } catch (IOException error) {
            proof.failWrite(output);
            throw error;
        }
        return new JSONObject();
    }

    JSONObject execute(JSONObject request) throws Exception {
        switch (request.getString("op")) {
            case "accountPublic": return device(false);
            case "accountPrepare": return device(true);
            case "accountRead": return read();
            case "accountSave": return save(request.getJSONObject("link"));
            default: throw new IOException("unsupported-operation");
        }
    }
}
