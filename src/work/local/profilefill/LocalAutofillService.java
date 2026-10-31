package work.local.profilefill;

import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveRequest;
import android.view.View;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;
import java.util.List;
import java.util.Map;

public final class LocalAutofillService extends AutofillService {
    @Override public void onFillRequest(FillRequest request, CancellationSignal cancellation, FillCallback callback) {
        try {
            if (cancellation.isCanceled()) return;
            List<FillContext> contexts = request.getFillContexts();
            if (contexts.isEmpty()) { callback.onSuccess(null); return; }
            AssistStructure structure = contexts.get(contexts.size() - 1).getStructure();
            ComponentName component = structure.getActivityComponent();
            if (component == null) { callback.onSuccess(null); return; }
            String pkg = component.getPackageName();
            boolean ownTest = getPackageName().equals(pkg) && TestFormActivity.class.getName().equals(component.getClassName());
            if (!ownTest && !(ProfileStore.EBAY.equals(pkg) && ProfileStore.trustedEbay(this))) {
                callback.onSuccess(null); return;
            }
            List<Map<String, String>> profiles = ProfileStore.load(this);
            if (profiles == null || profiles.isEmpty()) { callback.onSuccess(null); return; }
            FillResponse.Builder response = new FillResponse.Builder();
            int offered = 0;
            for (int p = 0; p < profiles.size(); p++) {
                if (cancellation.isCanceled()) return;
                Map<String, String> values = profiles.get(p);
                RemoteViews presentation = new RemoteViews(getPackageName(), android.R.layout.simple_list_item_1);
                presentation.setTextViewText(android.R.id.text1, label(p, profiles.size(), values));
                Dataset.Builder dataset = new Dataset.Builder(presentation);
                int matched = 0;
                for (int i = 0; i < structure.getWindowNodeCount(); i++) {
                    matched += visit(structure.getWindowNodeAt(i).getRootViewNode(), dataset, values, null, cancellation);
                }
                if (matched > 0) { response.addDataset(dataset.build()); offered++; }
            }
            if (cancellation.isCanceled()) return;
            callback.onSuccess(offered == 0 ? null : response.build());
        } catch (Exception error) {
            // Never log view contents, credentials or imported personal data.
            if (!cancellation.isCanceled()) callback.onSuccess(null);
        }
    }

    private static String label(int index, int total, Map<String, String> values) {
        String name = values.get("first_name") + " " + values.get("last_name");
        if (total <= 1) return "填入本地资料 · " + name;
        return "填入本地资料 " + (index + 1) + "/" + total + " · " + name + " · " + values.get("email");
    }

    private int visit(AssistStructure.ViewNode node, Dataset.Builder dataset, Map<String, String> values,
                      String parentDomain, CancellationSignal cancellation) {
        if (node == null || cancellation.isCanceled()) return 0;
        String domain = node.getWebDomain() != null ? node.getWebDomain() : parentDomain;
        if (!FieldRules.allowedWebDomain(domain)) return 0;
        int count = 0;
        if (node.getAutofillId() != null && node.getAutofillType() == View.AUTOFILL_TYPE_TEXT) {
            String field = FieldRules.match(node.getAutofillHints(), node.getIdEntry(), node.getHint());
            String value = "full_name".equals(field) ? values.get("first_name") + " " + values.get("last_name") : values.get(field);
            if (value != null && !value.isEmpty()) {
                dataset.setValue(node.getAutofillId(), AutofillValue.forText(value));
                count++;
            }
        }
        for (int i = 0; i < node.getChildCount(); i++)
            count += visit(node.getChildAt(i), dataset, values, domain, cancellation);
        return count;
    }

    @Override public void onSaveRequest(SaveRequest request, SaveCallback callback) {
        // Imported data is the only source. Never collect data from other apps.
        callback.onSuccess();
    }
}
