package work.local.profilefill;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public final class MainActivity extends Activity {
    private static final int IMPORT = 10;
    private TextView status;
    private boolean importing;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        setTitle("本地资料填写");
        LinearLayout content = Ui.page(this);
        Ui.title(this, content, "本地资料填写");
        Ui.text(this, content, "导入一份或多份资料（JSON 对象数组），在支持的 eBay 原生页面中点选要使用的资料。Android 8.0 及以上。", 16);
        status = Ui.text(this, content, "", 15);
        Ui.button(this, content, "1  导入本地 JSON", v -> importJson());
        Ui.button(this, content, "2  启用系统自动填充", v -> enable());
        Ui.button(this, content, "3  授权已安装的 eBay", v -> trust());
        Ui.button(this, content, "4  打开 eBay", v -> openEbay());
        Ui.button(this, content, "先在离线表单中测试", v -> startActivity(new Intent(this, TestFormActivity.class)));
        Ui.button(this, content, "查看资料格式", v -> new AlertDialog.Builder(this).setTitle("UTF-8 JSON · 批量")
            .setMessage("顶层是资料对象数组（单个对象也兼容）。每个元素必填：first_name、last_name、email\n\n可选：password、phone、street、city、state、postal_code、country\n\n每个值都必须是字符串。电话号码、邮编不要写成数字。一次最多 200 份，文件不超过 1 MB；再次导入将替换全部资料。")
            .setPositiveButton("知道了", null).show());
        Ui.button(this, content, "管理已导入资料", v -> manage());
        Ui.button(this, content, "清除已导入资料", v -> new AlertDialog.Builder(this).setTitle("清除本机资料？")
            .setMessage("会删除本应用保存的资料。你导入的原始 JSON 文件仍需自行保管或删除。")
            .setNegativeButton("取消", null).setPositiveButton("清除", (d, which) -> {
                ProfileStore.clear(this); render();
            }).show());
        Ui.text(this, content, "资料保存在本机加密文件中。本应用没有联网权限。\n\n提交表单、接受协议及验证码由你在 eBay 中完成。若页面没有提供可填写字段，系统建议不会出现，需手动输入。", 14);
    }

    @Override protected void onResume() { super.onResume(); if (status != null) render(); }

    private void render() {
        String text;
        try {
            List<Map<String, String>> values = ProfileStore.load(this);
            if (values == null || values.isEmpty()) {
                text = "尚未导入资料";
            } else {
                StringBuilder list = new StringBuilder("已导入 " + values.size() + " 份资料\n");
                int shown = Math.min(values.size(), 20);
                for (int i = 0; i < shown; i++) {
                    Map<String, String> profile = values.get(i);
                    list.append(i + 1).append(".  ").append(profile.get("first_name")).append(" ")
                        .append(profile.get("last_name")).append("  ").append(profile.get("email")).append('\n');
                }
                if (values.size() > shown) list.append("……（共 ").append(values.size()).append(" 份）\n");
                text = list.toString();
            }
        } catch (Exception error) { text = "已存资料无法解密，请清除后重新导入。"; }
        AutofillManager manager = getSystemService(AutofillManager.class);
        text += "\n\n自动填充：" + (manager != null && manager.hasEnabledAutofillServices() ? "已启用" : "未启用");
        text += "\neBay 授权：" + (ProfileStore.trustedEbay(this) ? "已授权当前签名" : "未授权或签名已变化");
        status.setText(text);
    }

    private void importJson() {
        if (importing) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
        try { startActivityForResult(intent, IMPORT); }
        catch (Exception error) { message("无法打开文件选择器。请确认云手机提供了文件管理应用。"); }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMPORT || result != RESULT_OK || data == null || data.getData() == null) return;
        final Uri uri = data.getData();
        importing = true;
        status.setText("正在导入…");
        new Thread(() -> {
            String errorText = null;
            int imported = 0;
            try (InputStream input = getContentResolver().openInputStream(uri);
                  ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                if (input == null) throw new IllegalArgumentException("无法读取选中的文件");
                byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() + count > ProfileStore.MAX_FILE_BYTES)
                        throw new IllegalArgumentException("资料文件不能超过 1 MB");
                    output.write(buffer, 0, count);
                }
                List<Map<String, String>> values = ProfileStore.parse(output.toByteArray());
                imported = values.size();
                ProfileStore.save(this, values);
            } catch (IllegalArgumentException error) { errorText = error.getMessage(); }
            catch (Exception error) { errorText = "导入失败。请检查 JSON 格式和本机加密存储是否可用。原资料未被替换。"; }
            final String resultText = errorText;
            final int count = imported;
            runOnUiThread(() -> {
                importing = false;
                if (isFinishing() || isDestroyed()) return;
                render();
                message(resultText == null ? "已导入 " + count + " 份资料。可以先打开离线表单测试。" : resultText);
            });
        }, "import-local-json").start();
    }

    private void enable() {
        AutofillManager manager = getSystemService(AutofillManager.class);
        if (manager == null || !manager.isAutofillSupported()) { message("当前云手机系统未提供自动填充支持。"); return; }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivityForResult(intent, 11);
        } catch (Exception error) { message("系统没有提供自动填充设置入口。请在 Android 设置中搜索“自动填充服务”。"); }
    }

    private void trust() {
        try {
            String fingerprint = ProfileStore.installedEbayFingerprint(this);
            new AlertDialog.Builder(this).setTitle("授权向当前 eBay 填入资料")
                .setMessage("请确认已从可信来源安装官方 eBay。\n\n包名：" + ProfileStore.EBAY
                    + "\n签名 SHA-256：\n" + fingerprint
                    + "\n\n授权后，只有包名和此签名都匹配的应用可以收到填写建议。此操作记录当前签名，并非官方身份认证。")
                .setNegativeButton("取消", null).setPositiveButton("授权当前应用", (dialog, which) -> {
                    try { ProfileStore.trustInstalledEbay(this); render(); }
                    catch (Exception error) { message("无法记录 eBay 签名，请重新尝试。"); }
                }).show();
        } catch (Exception error) { message("未找到可读取签名的 eBay 应用。请先安装官方 Android 版 eBay。"); }
    }

    private void openEbay() {
        Intent intent = getPackageManager().getLaunchIntentForPackage(ProfileStore.EBAY);
        if (intent == null) { message("未找到 eBay 应用，请先安装官方 Android 版。"); return; }
        try { startActivity(intent); }
        catch (Exception error) { message("无法打开 eBay，请检查云手机中的安装状态。"); }
    }

    private void manage() {
        new Thread(() -> {
            List<Map<String, String>> tmpValues = null;
            String tmpError = null;
            try {
                tmpValues = ProfileStore.load(this);
            } catch (Exception error0) {
                tmpError = error0.getMessage() == null ? "读取资料失败。" : error0.getMessage();
            }
            final List<Map<String, String>> values = tmpValues;
            final String error = tmpError;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) return;
                if (error != null) { message(error); return; }
                if (values == null || values.isEmpty()) { message("尚未导入资料。"); return; }
                String[] items = new String[values.size()];
                for (int i = 0; i < values.size(); i++) {
                    Map<String, String> profile = values.get(i);
                    items[i] = (i + 1) + ".  " + profile.get("first_name") + " " + profile.get("last_name")
                        + "  " + profile.get("email");
                }
                new AlertDialog.Builder(this).setTitle("删除某一份资料")
                    .setItems(items, (dialog, which) -> {
                        Map<String, String> profile = values.get(which);
                        new AlertDialog.Builder(this).setTitle("删除这份资料？")
                            .setMessage(profile.get("first_name") + " " + profile.get("last_name")
                                + "\n" + profile.get("email"))
                            .setNegativeButton("取消", null)
                            .setPositiveButton("删除", (d, w) -> {
                                new Thread(() -> {
                                    String delError = null;
                                    try { ProfileStore.deleteAt(this, which); }
                                    catch (Exception e) {
                                        delError = e.getMessage() == null ? "删除失败。" : e.getMessage();
                                    }
                                    final String result = delError;
                                    runOnUiThread(() -> {
                                        if (isFinishing() || isDestroyed()) return;
                                        render();
                                        message(result == null ? "已删除该资料。" : result);
                                    });
                                }, "delete-profile").start();
                            }).show();
                    }).show();
            });
        }, "manage-profiles").start();
    }

    private void message(String text) {
        new AlertDialog.Builder(this).setMessage(text).setPositiveButton("知道了", null).show();
    }
}

final class Ui {
    private Ui() {}
    static int dp(Activity a, int value) { return Math.round(value * a.getResources().getDisplayMetrics().density); }
    static LinearLayout page(Activity a) {
        ScrollView scroll = new ScrollView(a);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(Color.rgb(245, 247, 251));
        LinearLayout content = new LinearLayout(a); content.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(a, 20); content.setPadding(pad, pad, pad, pad);
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        scroll.addView(content);
        scroll.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        a.setContentView(scroll);
        return content;
    }
    static void title(Activity a, LinearLayout parent, String value) {
        TextView title = text(a, parent, value, 28);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(Color.rgb(25, 58, 110));
    }
    static TextView text(Activity a, LinearLayout parent, String value, int size) {
        TextView view = new TextView(a); view.setText(value); view.setTextSize(size);
        view.setTextColor(Color.rgb(40, 48, 65)); view.setLineSpacing(dp(a, 3), 1f);
        view.setPadding(0, dp(a, 8), 0, dp(a, 12));
        parent.addView(view, new LinearLayout.LayoutParams(-1, -2)); return view;
    }
    static void button(Activity a, LinearLayout parent, String value, View.OnClickListener listener) {
        Button view = new Button(a); view.setText(value); view.setAllCaps(false);
        view.setOnClickListener(listener); view.setMinHeight(dp(a, 52));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(a, 6); parent.addView(view, params);
    }
}
