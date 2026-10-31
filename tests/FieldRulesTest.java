import work.local.profilefill.FieldRules;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Standalone JVM checks: no Android emulator or third-party test library required. */
public class FieldRulesTest {
    private static int checks;
    private static void equal(Object expected, Object actual) {
        checks++;
        if (!Objects.equals(expected, actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    private static void rejected(Map<String, String> values) {
        checks++;
        try { FieldRules.validate(values); }
        catch (IllegalArgumentException expected) { return; }
        throw new AssertionError("Invalid profile accepted");
    }
    public static void main(String[] args) {
        equal("first_name", FieldRules.match(new String[]{"personGivenName"}, null, "Name"));
        equal("last_name", FieldRules.match(null, "last_name", ""));
        equal("email", FieldRules.match(new String[]{"emailAddress"}, "unrelated", ""));
        equal("password", FieldRules.match(new String[]{"newPassword"}, null, ""));
        equal("postal_code", FieldRules.match(new String[]{"postalCode"}, null, "Postal code"));
        equal("postal_code", FieldRules.match(null, "zip_code", "ZIP code"));
        equal(null, FieldRules.match(new String[]{"password"}, "confirmPassword", ""));
        equal(null, FieldRules.match(new String[]{"phoneNumber"}, "sms_verification", ""));
        equal(null, FieldRules.match(null, "phone", "Verification code"));
        equal(null, FieldRules.match(null, "email", "验证码"));
        equal(null, FieldRules.match(new String[]{"smsOTPCode"}, null, ""));
        equal(null, FieldRules.match(null, "captcha", ""));
        equal(null, FieldRules.match(null, "query", "Search"));
        equal(null, FieldRules.match(null, "unrecognized", "Some other field"));
        equal(true, FieldRules.allowedWebDomain("signin.ebay.com"));
        equal(true, FieldRules.allowedWebDomain("EBAY.COM"));
        equal(false, FieldRules.allowedWebDomain("ebay.com.evil.example"));
        equal(false, FieldRules.allowedWebDomain("noteBay.com"));
        equal(false, FieldRules.allowedWebDomain("example.com"));
        Map<String, String> profile = new LinkedHashMap<>();
        rejected(profile);
        profile.put("first_name", "Alex"); profile.put("last_name", "Example"); profile.put("email", "invalid");
        rejected(profile);
        profile.put("email", "alex@example.com"); FieldRules.validate(profile); checks++;
        profile.put("phone", "123\n456"); rejected(profile);
        profile.put("phone", new String(new char[513]).replace('\0', '1')); rejected(profile);
        System.out.println("Passed " + checks + " checks");
    }
}
