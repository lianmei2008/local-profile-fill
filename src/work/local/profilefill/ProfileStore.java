package work.local.profilefill;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class ProfileStore {
    public static final String EBAY = "com.ebay.mobile";
    public static final int MAX_FILE_BYTES = 1048576;
    public static final int MAX_PROFILES = 200;
    private static final String KEY = "local_profile_fill_v1";
    private static final String[] FIELDS = {"first_name", "last_name", "email", "password",
        "phone", "street", "city", "state", "postal_code", "country", "phone_api"};
    private ProfileStore() {}

    public static List<Map<String, String>> parse(byte[] data) throws Exception {
        if (data.length > MAX_FILE_BYTES) throw new IllegalArgumentException("资料文件不能超过 1 MB");
        String source = new String(data, StandardCharsets.UTF_8);
        if (source.startsWith("\ufeff")) source = source.substring(1);
        String trimmed = source.trim();
        if (!trimmed.startsWith("{") && !trimmed.startsWith("["))
            throw new IllegalArgumentException("请导入单个 JSON 对象或对象数组");
        JSONArray array = trimmed.startsWith("[") ? new JSONArray(trimmed) : new JSONArray("[" + trimmed + "]");
        if (array.length() == 0) throw new IllegalArgumentException("资料列表不能为空");
        if (array.length() > MAX_PROFILES) throw new IllegalArgumentException("一次最多导入 " + MAX_PROFILES + " 份资料");
        List<Map<String, String>> profiles = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            Object item = array.get(i);
            if (!(item instanceof JSONObject))
                throw new IllegalArgumentException("第 " + (i + 1) + " 项必须是 JSON 对象");
            profiles.add(parseObject((JSONObject) item));
        }
        return profiles;
    }

    private static Map<String, String> parseObject(JSONObject json) throws Exception {
        Map<String, String> values = new LinkedHashMap<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            String name = keys.next();
            boolean known = false;
            for (String field : FIELDS) if (field.equals(name)) known = true;
            if (!known) throw new IllegalArgumentException("不支持的字段：" + name);
            Object value = json.get(name);
            if (!(value instanceof String)) throw new IllegalArgumentException("字段必须是字符串：" + name);
            String text = (String) value;
            values.put(name, name.equals("password") ? text : text.trim());
        }
        FieldRules.validate(values);
        return values;
    }

    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY)) return (SecretKey) store.getKey(KEY, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private static AtomicFile file(Context c) {
        return new AtomicFile(new File(c.getNoBackupFilesDir(), "profile.enc"));
    }

    public static void save(Context c, List<Map<String, String>> values) throws Exception {
        if (values == null || values.isEmpty()) throw new IllegalArgumentException("资料列表不能为空");
        if (values.size() > MAX_PROFILES) throw new IllegalArgumentException("一次最多保存 " + MAX_PROFILES + " 份资料");
        JSONArray array = new JSONArray();
        for (Map<String, String> profile : values) {
            FieldRules.validate(profile);
            array.put(new JSONObject(profile));
        }
        byte[] plain = array.toString().getBytes(StandardCharsets.UTF_8);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(plain);
        FileOutputStream stream = null;
        AtomicFile file = file(c);
        try {
            stream = file.startWrite();
            DataOutputStream out = new DataOutputStream(stream);
            out.writeInt(1); out.writeInt(cipher.getIV().length); out.write(cipher.getIV()); out.write(encrypted); out.flush();
            file.finishWrite(stream);
        } catch (Exception error) {
            if (stream != null) file.failWrite(stream);
            throw error;
        }
    }

    public static List<Map<String, String>> load(Context c) throws Exception {
        AtomicFile file = file(c);
        if (!file.getBaseFile().exists()) return null;
        byte[] data = file.readFully();
        if (data.length > 1100000) throw new IllegalArgumentException("资料文件损坏，请重新导入");
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
        if (in.readInt() != 1) throw new IllegalArgumentException("资料版本不支持");
        int size = in.readInt();
        if (size != 12) throw new IllegalArgumentException("资料文件损坏");
        byte[] iv = new byte[size]; in.readFully(iv);
        byte[] encrypted = new byte[in.available()]; in.readFully(encrypted);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
        return parse(cipher.doFinal(encrypted));
    }

    public static void clear(Context c) { file(c).delete(); }

    public static List<Map<String, String>> deleteAt(Context c, int index) throws Exception {
        List<Map<String, String>> values = load(c);
        if (values == null) throw new IllegalArgumentException("尚未导入资料");
        if (index < 0 || index >= values.size()) throw new IllegalArgumentException("资料不存在，请刷新后重试");
        values.remove(index);
        if (values.isEmpty()) { clear(c); return values; }
        save(c, values);
        return values;
    }

    @SuppressWarnings("deprecation")
    public static String installedEbayFingerprint(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo info = pm.getPackageInfo(EBAY, PackageManager.GET_SIGNING_CERTIFICATES);
            if (info.signingInfo == null) throw new IllegalStateException("无法读取 eBay 签名");
            signatures = info.signingInfo.getApkContentsSigners();
        } else {
            signatures = pm.getPackageInfo(EBAY, PackageManager.GET_SIGNATURES).signatures;
        }
        if (signatures == null || signatures.length == 0) throw new IllegalStateException("无法读取 eBay 签名");
        ArrayList<String> hashes = new ArrayList<>();
        for (Signature signature : signatures) {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray());
            StringBuilder text = new StringBuilder();
            for (byte b : hash) text.append(String.format(java.util.Locale.ROOT, "%02X", b & 255));
            hashes.add(text.toString());
        }
        Collections.sort(hashes);
        return android.text.TextUtils.join(":", hashes);
    }

    public static void trustInstalledEbay(Context c) throws Exception {
        c.getSharedPreferences("settings", Context.MODE_PRIVATE).edit()
            .putString("ebay_certificate", installedEbayFingerprint(c)).apply();
    }

    public static boolean trustedEbay(Context c) {
        try {
            String trusted = c.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("ebay_certificate", "");
            return !trusted.isEmpty() && trusted.equals(installedEbayFingerprint(c));
        } catch (Exception error) { return false; }
    }
}
