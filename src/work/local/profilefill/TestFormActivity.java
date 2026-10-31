package work.local.profilefill;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.view.autofill.AutofillManager;
import android.widget.EditText;
import android.widget.LinearLayout;

public final class TestFormActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        LinearLayout content = Ui.page(this);
        // Test fields participate in autofill; the settings page deliberately does not.
        content.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_AUTO);
        Ui.title(this, content, "离线测试表单");
        Ui.text(this, content, "点击“显示填写建议”，在列表中选择要填入的那份资料。此表单只显示结果，不提交或联网。", 16);
        EditText first = field(content, "名", "personGivenName", InputType.TYPE_CLASS_TEXT);
        field(content, "姓", "personFamilyName", InputType.TYPE_CLASS_TEXT);
        field(content, "邮箱", "emailAddress", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        field(content, "密码", "newPassword", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        field(content, "电话", "phoneNumber", InputType.TYPE_CLASS_PHONE);
        field(content, "街道地址", "postalAddress", InputType.TYPE_CLASS_TEXT);
        field(content, "城市", "addressLocality", InputType.TYPE_CLASS_TEXT);
        field(content, "州", "addressRegion", InputType.TYPE_CLASS_TEXT);
        field(content, "邮编", "postalCode", InputType.TYPE_CLASS_TEXT);
        field(content, "国家", "addressCountry", InputType.TYPE_CLASS_TEXT);
        field(content, "验证码（应保持空白）", "smsOTPCode", InputType.TYPE_CLASS_NUMBER);
        Ui.button(this, content, "显示填写建议", v -> {
            first.requestFocus();
            AutofillManager manager = getSystemService(AutofillManager.class);
            if (manager != null) manager.requestAutofill(first);
        });
        Ui.button(this, content, "返回", v -> finish());
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
    }

    private EditText field(LinearLayout parent, String label, String hint, int type) {
        EditText input = new EditText(this);
        input.setId(View.generateViewId()); input.setHint(label); input.setAutofillHints(hint);
        input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_YES);
        input.setInputType(type); input.setSingleLine(true);
        parent.addView(input, new LinearLayout.LayoutParams(-1, Ui.dp(this, 56)));
        return input;
    }
}
