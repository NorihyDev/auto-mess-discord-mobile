package dev.norihy.discordmobile;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.*;
import android.webkit.*;
import android.widget.*;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

public final class MainActivity extends Activity {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LinearLayout root;
    private ScrollView form;
    private WebView web;
    private TextView status, detail;
    private ProgressBar progress;
    private EditText url, message, time, zone, before, after;
    private Button arm, preview, connect, settings;
    private WatchConfig config;
    private WatchConfig.Window window;
    private String token, script, day, phase = "idle";
    private boolean foreground, loaded, installed, previewMode, bridgeSupported;
    private long preparationStarted;
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active()) return;
            Instant now = Instant.now();
            if (!now.isBefore(window.end())) { finish("Fenêtre terminée", "Aucun nouvel essai automatique."); return; }
            if (phase.equals("armed")) {
                if (window.active(now)) {
                    if (!previewMode && blocked()) { finish("Déjà tenté aujourd’hui", "Vérifie le salon. La réservation empêche un double envoi."); return; }
                    if (loaded && !installed) install();
                    else if (!installed) showStatus("Chargement du salon…", "Connecte-toi dans la vue Discord.");
                } else {
                    long seconds = Duration.between(now, window.start()).getSeconds();
                    showStatus("En attente de la fenêtre", String.format(Locale.FRANCE, "Début dans %02d:%02d:%02d · %s", seconds / 3600, seconds / 60 % 60, seconds % 60, config.zone));
                }
            }
            handler.postDelayed(this, 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("watcher", MODE_PRIVATE);
        try (InputStream input = getAssets().open("watcher.js")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] block = new byte[4096]; int count;
            while ((count = input.read(block)) != -1) buffer.write(block, 0, count);
            script = buffer.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) { throw new IllegalStateException("Missing watcher asset", e); }
        createUi();
        configureWebView();
        showStatus("Prêt à configurer", "Une session locale · un seul essai par salon et par jour");
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private GradientDrawable background(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color); drawable.setCornerRadius(dp(radius)); return drawable;
    }
    private TextView label(String value, int size, int color) {
        TextView text = new TextView(this); text.setText(value); text.setTextSize(size); text.setTextColor(color); return text;
    }
    private LinearLayout column() { LinearLayout layout = new LinearLayout(this); layout.setOrientation(LinearLayout.VERTICAL); return layout; }
    private void createUi() {
        root = column(); root.setPadding(dp(18), dp(12), dp(18), dp(12)); root.setBackgroundColor(0xff0c1120);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = bars.top; bottom = bars.bottom;
            } else {
                top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom();
            }
            root.setPadding(dp(18), top + dp(12), dp(18), bottom + dp(12)); return insets;
        });
        setContentView(root);
        TextView eyebrow = label("ANDROID  /  FAST WATCH", 11, 0xff8da2ff); eyebrow.setLetterSpacing(0.15f); root.addView(eyebrow);
        TextView title = label("Message Auto", 28, Color.WHITE); title.setTypeface(null, Typeface.BOLD); root.addView(title);
        TextView subtitle = label("Ton check-in, dès l’ouverture du salon.", 14, 0xffa7b1cd); root.addView(subtitle);
        LinearLayout card = column(); card.setPadding(dp(14), dp(12), dp(14), dp(12)); card.setBackground(background(0xff192239, 16));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2); cardParams.setMargins(0, dp(16), 0, dp(12)); root.addView(card, cardParams);
        status = label("", 17, 0xffdce3ff); status.setTypeface(null, Typeface.BOLD); card.addView(status);
        detail = label("", 12, 0xffa7b1cd); card.addView(detail);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal); progress.setVisibility(View.GONE); root.addView(progress, new LinearLayout.LayoutParams(-1, dp(3)));
        form = new ScrollView(this); LinearLayout fields = column(); form.addView(fields);
        url = field(fields, "Lien du salon", "https://discord.com/channels/…/…", prefs.getString("url", ""));
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        message = field(fields, "Message exact", "Check-in ✅", prefs.getString("message", "Check-in ✅")); message.setMinLines(2); message.setMaxLines(5);
        message.setSingleLine(false);
        message.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        time = field(fields, "Heure d’ouverture · HH:MM", "20:00", prefs.getString("time", "20:00"));
        zone = field(fields, "Fuseau horaire IANA", "Indian/Antananarivo", prefs.getString("zone", "Indian/Antananarivo"));
        LinearLayout offsets = new LinearLayout(this); fields.addView(offsets);
        LinearLayout left = column(), right = column(); offsets.addView(left, new LinearLayout.LayoutParams(0, -2, 1)); offsets.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
        before = field(left, "Minutes avant", "5", prefs.getString("before", "5")); after = field(right, "Minutes après", "15", prefs.getString("after", "15"));
        before.setInputType(2); after.setInputType(2);
        TextView info = label("Garde l’application ouverte au premier plan. L’écran reste allumé pendant la surveillance. Un brouillon existant arrête l’envoi.", 12, 0xffa7b1cd); info.setPadding(0, dp(14), 0, dp(12)); fields.addView(info);
        TextView policy = label("Discord interdit l’automatisation des comptes personnels et peut sanctionner le compte. Lire la règle officielle ↗", 12, 0xff8da2ff); fields.addView(policy);
        policy.setOnClickListener(view -> openExternal(Uri.parse("https://support.discord.com/hc/en-us/articles/115002192352-Automated-User-Accounts-Self-Bots")));
        root.addView(form, new LinearLayout.LayoutParams(-1, 0, 1));
        web = new WebView(this); web.setVisibility(View.GONE); root.addView(web, new LinearLayout.LayoutParams(-1, 0, 1));
        connect = button("1  ·  Connexion Discord", 0xff25324f); root.addView(connect);
        connect.setOnClickListener(view -> { WatchConfig c = readConfig(); if (c != null) { save(c); browser(); web.loadUrl(c.url); showStatus("Connexion manuelle", "Termine la connexion et la 2FA dans Discord, puis lance une vérification."); } });
        LinearLayout actions = new LinearLayout(this); root.addView(actions);
        preview = button("Vérifier", 0xff25324f); arm = button("Surveiller", 0xff5865f2);
        actions.addView(preview, new LinearLayout.LayoutParams(0, dp(54), 1)); actions.addView(arm, new LinearLayout.LayoutParams(0, dp(54), 1));
        preview.setOnClickListener(view -> start(true)); arm.setOnClickListener(view -> { if (active()) finish("Surveillance arrêtée", "Vérifie le brouillon si une préparation était en cours."); else start(false); });
        LinearLayout footer = new LinearLayout(this); root.addView(footer);
        settings = button("Réglages", 0xff0c1120); Button logs = button("Journal", 0xff0c1120);
        footer.addView(settings, new LinearLayout.LayoutParams(0, dp(44), 1)); footer.addView(logs, new LinearLayout.LayoutParams(0, dp(44), 1));
        settings.setOnClickListener(view -> { if (form.getVisibility() == View.VISIBLE) browser(); else { web.setVisibility(View.GONE); form.setVisibility(View.VISIBLE); settings.setText(R.string.channel); } });
        logs.setOnClickListener(view -> new AlertDialog.Builder(this).setTitle("Journal de cette session").setMessage(events.isEmpty() ? "Aucun événement." : String.join("\n\n", events)).setPositiveButton("Fermer", null).show());
    }
    private EditText field(LinearLayout parent, String name, String hint, String value) {
        TextView label = label(name, 12, 0xffa7b1cd); label.setPadding(dp(3), dp(12), 0, dp(5)); parent.addView(label);
        EditText input = new EditText(this); input.setTextColor(Color.WHITE); input.setTextSize(15); input.setHintTextColor(0xff6f7e9d);
        input.setBackground(background(0xff192239, 10)); input.setPadding(dp(12), dp(10), dp(12), dp(10)); input.setHint(hint); input.setText(value);
        input.setSingleLine(true); parent.addView(input, new LinearLayout.LayoutParams(-1, -2)); return input;
    }
    private Button button(String text, int color) {
        Button button = new Button(this); button.setText(text); button.setAllCaps(false); button.setTextColor(Color.WHITE); button.setTextSize(14);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color)); button.setMinHeight(dp(48)); return button;
    }
    private void browser() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0);
        form.setVisibility(View.GONE); web.setVisibility(View.VISIBLE); settings.setText(R.string.settings);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        WebSettings s = web.getSettings(); s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true);
        s.setAllowFileAccess(false); s.setAllowContentAccess(false); s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true); s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false);
        Matcher version = Pattern.compile("Chrome/([0-9.]+)").matcher(s.getUserAgentString());
        if (version.find()) s.setUserAgentString("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/" + version.group(1) + " Safari/537.36");
        CookieManager.getInstance().setAcceptCookie(true); CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);
        bridgeSupported = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER);
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.addWebMessageListener(web, "DiscordMobile", Collections.singleton("https://discord.com"), (view, data, origin, mainFrame, reply) -> {
            if (!mainFrame || !"https".equals(origin.getScheme()) || !"discord.com".equals(origin.getHost()) || !active()) return;
            try {
                JSONObject event = new JSONObject(data.getData());
                if (token.equals(event.optString("token"))) onEvent(event);
            } catch (Exception e) { finish("Erreur de surveillance", "Aucun nouvel essai. Consulte le salon et le brouillon."); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) { progress.setProgress(value); progress.setVisibility(value < 100 ? View.VISIBLE : View.GONE); }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri dest = request.getUrl();
                if ("https".equals(dest.getScheme()) && "discord.com".equals(dest.getHost()) && (dest.getPort() == -1 || dest.getPort() == 443)) return false;
                if (active()) finish("Navigation interrompue", "Relance la surveillance dans le salon prévu.");
                openExternal(dest); return true;
            }
            @Override public void onPageStarted(WebView view, String address, android.graphics.Bitmap icon) {
                loaded = false; installed = false;
                if (active() && !phase.equals("armed")) finish("Page rechargée pendant l’envoi", "Résultat incertain. Vérifie Discord avant toute action manuelle.");
            }
            @Override public void onPageFinished(WebView view, String address) { loaded = true; if (active() && phase.equals("armed") && window.active(Instant.now()) && !installed) install(); }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) finish("Chargement impossible", "Vérifie la connexion Internet puis rouvre le salon.");
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) { finish("WebView interrompu", "Ferme et relance l’application. Une réservation existante reste conservée."); view.destroy(); MainActivity.this.finish(); return true; }
        });
    }
    private void openExternal(Uri uri) {
        if (!"https".equals(uri.getScheme())) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (ActivityNotFoundException e) { showStatus("Navigateur indisponible", "Installe un navigateur pour ouvrir ce lien."); }
    }
    private WatchConfig readConfig() {
        try { return new WatchConfig(url.getText().toString(), message.getText().toString(), time.getText().toString().trim(), zone.getText().toString().trim(), Integer.parseInt(before.getText().toString()), Integer.parseInt(after.getText().toString())); }
        catch (Exception e) { showStatus("Configuration à corriger", e.getMessage() == null ? "Vérifie les champs." : e.getMessage()); return null; }
    }
    private void save(WatchConfig c) {
        prefs.edit().putString("url", c.url).putString("message", c.message).putString("time", time.getText().toString().trim()).putString("zone", c.zone.toString()).putString("before", "" + c.before).putString("after", "" + c.after).apply();
    }
    private boolean active() { return !phase.equals("idle"); }
    private String stateKey() { return "attempt:" + config.url + ":" + day; }
    private boolean blocked() { return prefs.contains(stateKey()); }
    private void start(boolean previewOnly) {
        if (!foreground || active()) return;
        WatchConfig c = readConfig(); if (c == null) return;
        if (!bridgeSupported) { showStatus("WebView à mettre à jour", "Mets à jour Android System WebView / Chrome, puis relance l’application."); return; }
        config = c; save(c); previewMode = previewOnly;
        Instant now = Instant.now();
        window = previewOnly ? new WatchConfig.Window(now.atZone(c.zone).toLocalDate().toString(), now, now.plusSeconds(c.after * 60L)) : c.window(now);
        day = window.day();
        if (!previewOnly && blocked()) { showStatus("Déjà tenté pour cette journée", "Vérifie le salon. Aucun deuxième envoi automatique."); return; }
        phase = "armed"; token = UUID.randomUUID().toString(); installed = false;
        browser(); controls(true); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        String address = web.getUrl();
        if (address == null || !address.replaceAll("/$", "").equals(c.url)) web.loadUrl(c.url);
        log(previewOnly ? "Vérification sans envoi démarrée." : "Surveillance armée pour " + day + " (" + c.zone + ").");
        handler.post(tick);
    }
    private void controls(boolean running) {
        arm.setText(running ? "Arrêter" : "Surveiller"); preview.setEnabled(!running); connect.setEnabled(!running); settings.setEnabled(!running);
    }
    private void install() {
        if (!foreground || !phase.equals("armed") || (!previewMode && blocked())) return;
        if (!config.url.equals(web.getUrl() == null ? "" : web.getUrl().replaceAll("/$", ""))) {
            showStatus("Connexion ou salon requis", "Connecte-toi et ouvre le salon prévu dans Discord."); return;
        }
        installed = true;
        try {
            JSONObject options = new JSONObject().put("url", config.url).put("message", config.message).put("token", token).put("deadline", window.end().toEpochMilli()).put("preview", previewMode);
            showStatus(previewMode ? "Vérification en cours" : "À l’écoute du salon", "Détection DOM active · garde cette vue ouverte");
            web.evaluateJavascript(script + "\nwindow.DMA.install(" + options + ");", null);
        } catch (Exception e) { finish("Surveillance indisponible", "Vérifie la configuration et relance."); }
    }
    private boolean eligible(String run, String expectedPhase) {
        return foreground && Objects.equals(token, run) && phase.equals(expectedPhase) && window.active(Instant.now()) &&
            config.url.equals(web.getUrl() == null ? "" : web.getUrl().replaceAll("/$", ""));
    }
    private void onEvent(JSONObject event) {
        switch (event.optString("type")) {
            case "ready": if (!previewMode && phase.equals("armed")) prepare(); break;
            case "preview": if (previewMode) finish("Salon ouvert · vérification réussie", "Aucun texte inséré, aucun message envoyé, aucune réservation."); break;
            case "draft": finish("Brouillon existant", "Le brouillon est conservé. Aucun envoi."); break;
            case "prepare_failed": finish("Préparation interrompue", "Aucun appui sur Entrée. Vérifie le brouillon et le focus."); break;
            case "wrong_channel": finish("Le salon a changé", "Aucun envoi dans un autre salon."); break;
            case "expired": finish("Fenêtre terminée", "Aucun nouvel essai automatique."); break;
            case "confirmed":
                if (phase.equals("confirming")) {
                    prefs.edit().putString(stateKey(), "confirmed").apply();
                    finish("Message observé dans le salon", String.format(Locale.FRANCE, "%.0f ms depuis la détection · observation de l’interface", event.optDouble("elapsed")));
                }
                break;
            case "uncertain": if (phase.equals("confirming")) finish("Envoi non confirmé", "Vérifie Discord. La réservation bloque tout nouvel essai aujourd’hui."); break;
            default: break;
        }
    }
    private void prepare() {
        String run = token;
        if (!eligible(run, "armed") || blocked()) { finish("Envoi interrompu", "Fenêtre, salon ou réservation modifiés."); return; }
        phase = "preparing"; preparationStarted = SystemClock.elapsedRealtime();
        web.requestFocus();
        web.evaluateJavascript("Boolean(window.DMA && window.DMA.validEmpty())", empty -> {
            if (!eligible(run, "preparing")) { abortCallback(run); return; }
            if (!"true".equals(empty)) { finish("Brouillon ou focus modifié", "Aucun envoi. Vérifie le salon."); return; }
            InputConnection connection = web.onCreateInputConnection(new EditorInfo());
            if (connection == null || !connection.commitText(config.message, 1)) { finish("Saisie native indisponible", "Aucun envoi. Vérifie le brouillon."); return; }
            web.evaluateJavascript("Boolean(window.DMA && window.DMA.beforeSend())", prepared -> {
                if (!eligible(run, "preparing")) { abortCallback(run); return; }
                if (!"true".equals(prepared)) { finish("Message préparé différent", "Aucun appui sur Entrée. Vérifie le brouillon."); return; }
                if (blocked() || !prefs.edit().putString(stateKey(), "reserved").commit()) { finish("Réservation impossible", "Aucun appui sur Entrée."); return; }
                if (!eligible(run, "preparing")) { finish("Fenêtre terminée", "Réservation conservée ; aucun appui sur Entrée."); return; }
                phase = "confirming";
                web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                long elapsed = SystemClock.elapsedRealtime() - preparationStarted;
                log("Préparation et Entrée : " + elapsed + " ms.");
                showStatus("Entrée envoyée · confirmation…", elapsed + " ms de préparation · aucun nouvel essai automatique");
            });
        });
    }
    private void abortCallback(String run) {
        if (active() && Objects.equals(token, run)) finish("Préparation interrompue", "Le salon, la fenêtre ou le premier plan a changé. Vérifie le brouillon.");
    }
    private void finish(String title, String explanation) {
        phase = "idle"; token = null; installed = false; handler.removeCallbacks(tick);
        if (web != null) web.evaluateJavascript("window.DMA && window.DMA.stop()", null);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); controls(false); showStatus(title, explanation); log(title + ". " + explanation);
    }
    private void showStatus(String title, String explanation) { status.setText(title); detail.setText(explanation); }
    private void log(String text) {
        if (events.size() >= 60) events.removeFirst();
        events.addLast(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " · " + text);
    }
    @Override protected void onResume() { super.onResume(); foreground = true; web.onResume(); }
    @Override protected void onPause() {
        foreground = false;
        if (active()) finish("Surveillance suspendue", "L’application a quitté le premier plan. Relance la surveillance manuellement.");
        CookieManager.getInstance().flush(); web.onPause(); super.onPause();
    }
    @Override protected void onDestroy() { handler.removeCallbacksAndMessages(null); web.destroy(); super.onDestroy(); }
}
