package work.local.profilefill;

import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/** Conservative, offline matching. Unknown and verification fields are left alone. */
public final class FieldRules {
    private FieldRules() {}
    private static final Pattern BLOCKED = Pattern.compile(
        "(^|[^a-z])(otp|captcha|verification|verify|security|confirm|repeat|search|coupon|promo|token|code)([^a-z]|$)|验证码|校验码|确认密码|重复密码");

    private static String normalize(String text) {
        if (text == null) return "";
        return text.replaceAll("([a-z])([A-Z])", "$1 $2")
            .toLowerCase(Locale.ROOT).replaceAll("[_\\-.:]+", " ").replaceAll("\\s+", " ").trim();
    }

    public static String match(String[] hints, String id, String label) {
        String description = normalize(id) + " " + normalize(label);
        if (isBlocked(description)) return null;
        if (hints != null) {
            for (String hint : hints) {
                String value = normalize(hint);
                if (isBlocked(value)) return null;
            }
            for (String hint : hints) {
                String key = fromLabel(normalize(hint));
                if (key != null) return key;
            }
        }
        String key = fromLabel(normalize(id));
        return key != null ? key : fromLabel(normalize(label));
    }

    private static boolean isBlocked(String value) {
        return BLOCKED.matcher(value.replace("postal code", "postal").replace("zip code", "zip")).find();
    }

    private static String fromLabel(String v) {
        switch (v) {
            case "person given name": case "given name": case "first name":
            case "firstname": case "givenname": case "名": return "first_name";
            case "person family name": case "family name": case "last name":
            case "lastname": case "familyname": case "surname": case "姓": return "last_name";
            case "person name": case "full name": case "fullname": case "姓名": return "full_name";
            case "email address": case "email": case "emailaddress": case "e mail":
            case "username": case "new username": case "邮箱": case "电子邮件": return "email";
            case "password": case "new password": case "密码": return "password";
            case "phone": case "phone number": case "phone national": case "phone number national":
            case "telephone": case "mobile number": case "手机号": case "电话号码": return "phone";
            case "postal address": case "street address": case "address line 1":
            case "street": case "streetaddress": case "地址": return "street";
            case "address locality": case "city": case "城市": return "city";
            case "address region": case "state": case "省": return "state";
            case "postal code": case "zip code": case "zipcode": case "zip": case "邮编": return "postal_code";
            case "address country": case "country": case "国家": return "country";
            default: return null;
        }
    }

    public static boolean allowedWebDomain(String domain) {
        if (domain == null || domain.isEmpty()) return true;
        String value = domain.toLowerCase(Locale.ROOT);
        return value.equals("ebay.com") || value.endsWith(".ebay.com");
    }

    public static void validate(Map<String, String> values) {
        for (String key : new String[]{"first_name", "last_name", "email"}) {
            if (values.get(key) == null || values.get(key).trim().isEmpty())
                throw new IllegalArgumentException("缺少必填字段：" + key);
        }
        if (!values.get("email").matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))
            throw new IllegalArgumentException("email 格式不正确");
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (entry.getValue() == null || entry.getValue().length() > 512
                || entry.getValue().indexOf('\n') >= 0 || entry.getValue().indexOf('\r') >= 0)
                throw new IllegalArgumentException("字段过长或含换行：" + entry.getKey());
        }
    }
}
