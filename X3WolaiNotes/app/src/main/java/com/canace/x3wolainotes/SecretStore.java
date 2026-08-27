package com.canace.x3wolainotes;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SecretStore {
    private static final String KEY_ALIAS = "x3_wolai_openapi_token";
    private static final String PREFS = "secure_config";
    private static final String TOKEN_IV = "token_iv";
    private static final String TOKEN_DATA = "token_data";
    private static final String PROVISION_FILE = "wolai.token";

    private final Context context;
    private final SharedPreferences preferences;

    SecretStore(Context context) {
        this.context = context.getApplicationContext();
        this.preferences = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    boolean importProvisionedToken() throws Exception {
        File file = new File(context.getFilesDir(), PROVISION_FILE);
        if (!file.isFile()) return false;
        try {
            long length = file.length();
            if (length < 20 || length > 2048) throw new IllegalArgumentException("授权文件无效");
            byte[] bytes = new byte[(int) length];
            int offset = 0;
            try (FileInputStream input = new FileInputStream(file)) {
                while (offset < bytes.length) {
                    int count = input.read(bytes, offset, bytes.length - offset);
                    if (count < 0) break;
                    offset += count;
                }
            }
            String token = new String(bytes, 0, offset, StandardCharsets.UTF_8).trim();
            if (token.length() < 20 || token.length() > 1024) {
                throw new IllegalArgumentException("授权内容无效");
            }
            saveToken(token);
            return true;
        } finally {
            eraseAndDelete(file);
        }
    }

    String loadToken() {
        String ivEncoded = preferences.getString(TOKEN_IV, null);
        String dataEncoded = preferences.getString(TOKEN_DATA, null);
        if (ivEncoded == null || dataEncoded == null) return null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(),
                    new GCMParameterSpec(128, Base64.decode(ivEncoded, Base64.NO_WRAP)));
            byte[] plain = cipher.doFinal(Base64.decode(dataEncoded, Base64.NO_WRAP));
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception error) {
            return null;
        }
    }

    private void saveToken(String token) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey());
        byte[] encrypted = cipher.doFinal(token.getBytes(StandardCharsets.UTF_8));
        boolean saved = preferences.edit()
                .putString(TOKEN_IV, Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP))
                .putString(TOKEN_DATA, Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .commit();
        if (!saved) throw new IllegalStateException("无法保存授权");
    }

    private SecretKey getOrCreateKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey existing = (SecretKey) keyStore.getKey(KEY_ALIAS, null);
        if (existing != null) return existing;

        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private static void eraseAndDelete(File file) {
        try {
            if (file.isFile() && file.length() > 0 && file.length() <= 2048) {
                byte[] zeros = new byte[(int) file.length()];
                try (FileOutputStream output = new FileOutputStream(file, false)) {
                    output.write(zeros);
                    output.getFD().sync();
                }
            }
        } catch (Exception ignored) {
            // Best-effort overwrite; deletion still happens below.
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}

