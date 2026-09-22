package com.babasitaram.pro;

import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.CancellationSignal;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillCallback;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveCallback;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;
import android.widget.RemoteViews;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@RequiresApi(api = Build.VERSION_CODES.O)
public class BsrAutofillService extends AutofillService {

    @Override
    public void onFillRequest(@NonNull FillRequest request,
                              @NonNull CancellationSignal signal,
                              @NonNull FillCallback callback) {
        try {
            List<FillContext> contexts = request.getFillContexts();
            if (contexts == null || contexts.isEmpty()) { callback.onSuccess(null); return; }

            AssistStructure structure = contexts.get(contexts.size() - 1).getStructure();
            ParsedFields fields = parseStructure(structure);
            if (fields.usernameId == null && fields.passwordId == null) { callback.onSuccess(null); return; }

            String packageName = structure.getActivityComponent().getPackageName();
            String appLabel = getAppLabel(packageName);
            List<VaultEntry> matches = findMatches(packageName, appLabel, fields.domains);

            FillResponse.Builder responseBuilder = new FillResponse.Builder();
            if (fields.usernameId != null && fields.passwordId != null) {
                responseBuilder.setSaveInfo(new SaveInfo.Builder(
                        SaveInfo.SAVE_DATA_TYPE_USERNAME | SaveInfo.SAVE_DATA_TYPE_PASSWORD,
                        new AutofillId[]{fields.usernameId, fields.passwordId}).build());
            }

            if (matches.isEmpty()) {
                Intent intent = new Intent(this, MainActivity.class);
                intent.putExtra("autofill_package", packageName);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                PendingIntent pi = PendingIntent.getActivity(this, 1001, intent,
                        PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
                Dataset ds = buildDataset(fields, "", "", "BabaSitaRam Pro", "Vault kholein", pi.getIntentSender());
                if (ds != null) responseBuilder.addDataset(ds);
            } else {
                for (VaultEntry entry : matches) {
                    Dataset ds = buildDataset(fields, entry.user, entry.pw, entry.site, entry.user, null);
                    if (ds != null) responseBuilder.addDataset(ds);
                }
            }
            callback.onSuccess(responseBuilder.build());
        } catch (Exception e) { callback.onSuccess(null); }
    }

    @Override public void onSaveRequest(@NonNull SaveRequest request, @NonNull SaveCallback callback) { callback.onSuccess(); }

    private Dataset buildDataset(ParsedFields fields, String user, String pw, String title, String subtitle, IntentSender auth) {
        try {
            RemoteViews rv = buildPresentation(title, subtitle);
            Dataset.Builder ds = new Dataset.Builder(rv);
            if (auth != null) ds.setAuthentication(auth);
            if (fields.usernameId != null) ds.setValue(fields.usernameId, AutofillValue.forText(user), rv);
            if (fields.passwordId != null) ds.setValue(fields.passwordId, AutofillValue.forText(pw), rv);
            return ds.build();
        } catch (Exception e) { return null; }
    }

    // Same field family as extension: password selector + username/email/login/phone + generic text fallback.
    private ParsedFields parseStructure(AssistStructure structure) {
        ParsedFields fields = new ParsedFields();
        for (int i = 0; i < structure.getWindowNodeCount(); i++) traverseNode(structure.getWindowNodeAt(i).getRootViewNode(), fields);
        if (fields.usernameId == null && fields.firstTextId != null) fields.usernameId = fields.firstTextId;
        return fields;
    }

    private void traverseNode(AssistStructure.ViewNode node, ParsedFields fields) {
        for (int i = 0; i < node.getChildCount(); i++) traverseNode(node.getChildAt(i), fields);
        if (node.getAutofillId() == null) return;

        String hint = safeLower(node.getHint());
        String idEntry = safeLower(node.getIdEntry());
        String className = safeLower(node.getClassName());
        String webDomain = safeLower(node.getWebDomain());
        if (!webDomain.isEmpty()) fields.domains.add(webDomain);

        String[] hints = node.getAutofillHints();
        boolean hintPassword = hasHint(hints, "password", "currentPassword", "newPassword");
        boolean hintUsername = hasHint(hints, "username", "emailAddress", "phoneNumber");

        int inputType = node.getInputType();
        int typeClass = inputType & android.text.InputType.TYPE_MASK_CLASS;
        int typeVar = inputType & android.text.InputType.TYPE_MASK_VARIATION;
        boolean isText = typeClass == android.text.InputType.TYPE_CLASS_TEXT || className.contains("edittext");
        if (!isText) return;

        boolean isPassword = hintPassword
                || typeVar == android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                || typeVar == android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || typeVar == android.text.InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || hint.contains("password") || hint.contains("pass") || hint.contains("pwd")
                || idEntry.contains("password") || idEntry.contains("pass") || idEntry.contains("pwd");

        boolean isUsername = !isPassword && (hintUsername
                || typeVar == android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
                || typeVar == android.text.InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
                || hint.contains("email") || hint.contains("user") || hint.contains("login")
                || hint.contains("phone") || hint.contains("mobile") || hint.contains("username")
                || idEntry.contains("email") || idEntry.contains("user") || idEntry.contains("login")
                || idEntry.contains("phone") || idEntry.contains("username"));

        if (fields.firstTextId == null) fields.firstTextId = node.getAutofillId();
        if (isPassword && fields.passwordId == null) fields.passwordId = node.getAutofillId();
        else if (isUsername && fields.usernameId == null) fields.usernameId = node.getAutofillId();
    }

    private boolean hasHint(String[] hints, String... wanted) {
        if (hints == null) return false;
        for (String h : hints) for (String w : wanted) if (h != null && w.equalsIgnoreCase(h)) return true;
        return false;
    }

    private String getAppLabel(String packageName) {
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName, 0);
            CharSequence label = info.loadLabel(getPackageManager());
            return label == null ? "" : label.toString();
        } catch (Exception e) { return ""; }
    }

    // Unified matcher: web domain first, then app label, then package fallback.
    private List<VaultEntry> findMatches(String packageName, String appLabel, Set<String> domains) {
        List<VaultEntry> result = new ArrayList<>();
        try {
            String raw = getSharedPreferences("WebViewAppPrefs", MODE_PRIVATE).getString("vx3_passwords", null);
            if (raw == null || raw.trim().isEmpty()) return result;
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String site = obj.optString("site", "");
                String url = obj.optString("url", "");
                String user = obj.optString("user", "");
                String pw = obj.optString("pw", "");
                if (user.isEmpty() && pw.isEmpty()) continue;
                if (matchesTarget(site, url, packageName, appLabel, domains)) {
                    result.add(new VaultEntry(site, user, pw));
                    if (result.size() >= 10) break;
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    private boolean matchesTarget(String site, String url, String packageName, String appLabel, Set<String> domains) {
        Set<String> candidates = new HashSet<>();
        for (String d : domains) addCandidate(candidates, d);
        addCandidate(candidates, appLabel);
        addCandidate(candidates, packageName);

        Set<String> entryTokens = new HashSet<>();
        addCandidate(entryTokens, site);
        addCandidate(entryTokens, url);
        if (entryTokens.isEmpty()) return false;

        for (String candidate : candidates) for (String token : entryTokens) if (sameOrRelated(candidate, token)) return true;
        return false;
    }

    private void addCandidate(Set<String> out, String value) {
        if (value == null || value.trim().isEmpty()) return;
        String v = normalize(value);
        if (!v.isEmpty()) out.add(v);
        String host = hostOnly(value);
        if (!host.isEmpty()) out.add(host);
        for (String part : v.split("[^a-z0-9]+")) if (part.length() >= 3) out.add(part);
    }

    private boolean sameOrRelated(String a, String b) {
        if (a.equals(b) || a.contains(b) || b.contains(a)) return true;
        String ah = hostOnly(a), bh = hostOnly(b);
        return !ah.isEmpty() && !bh.isEmpty() && (ah.equals(bh) || ah.endsWith("." + bh) || bh.endsWith("." + ah));
    }

    private String normalize(String value) {
        String v = value.toLowerCase(Locale.ROOT).trim();
        v = v.replaceFirst("^[a-z][a-z0-9+.-]*://", "");
        v = v.split("[/?#]", 2)[0];
        v = v.split(":", 2)[0];
        v = v.replaceFirst("^www\\.", "");
        return v.replaceAll("[^a-z0-9.\\-]+", "");
    }

    private String hostOnly(String value) { return normalize(value); }
    private String safeLower(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT); }

    private RemoteViews buildPresentation(String title, String subtitle) {
        RemoteViews rv = new RemoteViews(getPackageName(), android.R.layout.simple_list_item_2);
        rv.setTextViewText(android.R.id.text1, title != null ? title : "");
        rv.setTextViewText(android.R.id.text2, subtitle != null ? subtitle : "");
        return rv;
    }

    static class ParsedFields {
        AutofillId usernameId, passwordId, firstTextId;
        Set<String> domains = new HashSet<>();
    }

    static class VaultEntry {
        String site, user, pw;
        VaultEntry(String s, String u, String p) { site = s; user = u; pw = p; }
    }
}
