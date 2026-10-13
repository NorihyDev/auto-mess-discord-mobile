package dev.norihy.discordmobile;

import android.annotation.SuppressLint;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
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
    @Override protected void attachBaseContext(Context base) {
        android.content.res.Configuration english = new android.content.res.Configuration(base.getResources().getConfiguration());
        english.setLocale(Locale.ENGLISH); english.setLayoutDirection(Locale.ENGLISH);
        super.attachBaseContext(base.createConfigurationContext(english));
    }
    private final Handler handler = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private LicenseClient licenses;
    private boolean licenseChecking;
    private final Runnable recheckLicense = new Runnable() {
        @Override public void run() {
            if (!active()) return;
            String key = prefs.getString("license_key", "");
            licenses.validate(key, false, (valid, reason) -> {
                if (!active()) return;
                if (!valid) { finish("License unavailable", reason + ". Watching stopped."); return; }
                handler.postDelayed(this, 30000);
            });
        }
    };
    private LinearLayout root;
    private ScrollView form;
    private WebView web;
    private TextView status, detail;
    private UiStyle ui;
    private LinearLayout statusCard;
    private View statusDot;
    private TextView scheduleTime, scheduleZone;
    private Button scheduleEdit;
    private ProgressBar progress;
    private EditText url, message, time, zone, before, after;
    private Button arm, preview, connect, settings;
    private WatchConfig config;
    private WatchConfig.Window window;
    private String token, script, day, phase = "idle";
    private boolean foreground, loaded, installed, previewMode, bridgeSupported, resumeAfterPermission;
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!active()) return;
            Instant now = Instant.now();
            if (!now.isBefore(window.end())) { finish("Watch window ended", "No automatic retry. Start a new session when ready."); return; }
            if (phase.equals("armed")) {
                if (window.active(now)) {
                    if (!previewMode && blocked()) { finish("Today's attempt is used", "Check Discord before allowing another attempt."); return; }
                    if (loaded && !installed) install();
                    else if (!installed) showStatus("Loading your channel...", "Sign in to Discord below if needed.");
                } else {
                    long seconds = Duration.between(now, window.start()).getSeconds();
                    showStatus("Watching starts soon", String.format(Locale.US, "Starts in %02d:%02d:%02d · %s", seconds / 3600, seconds / 60 % 60, seconds % 60, config.zone));
                }
            }
            long untilStart = Duration.between(Instant.now(), window.start()).toMillis();
            handler.postDelayed(this, untilStart > 0 ? Math.min(1000, untilStart) : 1000);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("watcher", MODE_PRIVATE);
        licenses = new LicenseClient(this);
        try (InputStream input = getAssets().open("watcher.js")) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] block = new byte[4096]; int count;
            while ((count = input.read(block)) != -1) buffer.write(block, 0, count);
            script = buffer.toString(StandardCharsets.UTF_8.name());
        } catch (IOException e) { throw new IllegalStateException("Missing watcher asset", e); }
        createUi();
        configureWebView();
        if (Build.VERSION.SDK_INT >= 33) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
            android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::handleBack);
        showStatus("Ready when you are", "One message per channel, per day.");
    }

    private int dp(int value) { return ui.dp(value); }
    private TextView label(String value, int size, int color) { return ui.text(value, size, color, 500); }
    private LinearLayout column() { return ui.column(); }
    private LinearLayout.LayoutParams spaced(int width, int height, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(width, height); p.topMargin = dp(top); return p;
    }
    private void createUi() {
        ui = new UiStyle(this);
        root = column(); root.setBackgroundColor(UiStyle.PAGE);
        root.setFocusableInTouchMode(true);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top, bottom;
            if (Build.VERSION.SDK_INT >= 30) {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.ime());
                top = bars.top; bottom = bars.bottom;
            } else { top = insets.getSystemWindowInsetTop(); bottom = insets.getSystemWindowInsetBottom(); }
            root.setPadding(dp(20), top + dp(12), dp(20), bottom + dp(8)); return insets;
        });
        setContentView(root);

        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView mark = new ImageView(this); mark.setImageResource(R.drawable.ic_app_mark);
        mark.setPadding(dp(10), dp(10), dp(10), dp(10)); mark.setBackground(ui.surface(UiStyle.GREEN, 14, false));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        header.addView(mark, new LinearLayout.LayoutParams(dp(44), dp(44)));
        LinearLayout brand = column();
        TextView title = ui.text("Message Auto", 21, UiStyle.INK, 750); title.setLetterSpacing(-0.025f); brand.addView(title);
        brand.addView(label("Send when your channel opens.", 11, UiStyle.MUTED), spaced(-1, -2, 4));
        LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1); brandParams.leftMargin = dp(12);
        header.addView(brand, brandParams);
        Button more = ui.button("\u22ef", Color.TRANSPARENT, UiStyle.INK); more.setTextSize(26); more.setContentDescription("More options");
        header.addView(more, new LinearLayout.LayoutParams(dp(48), dp(48)));
        more.setOnClickListener(v -> showMenu(more)); root.addView(header);

        statusCard = column(); statusCard.setPadding(dp(14), dp(12), dp(14), dp(12));
        statusCard.setBackground(ui.surface(UiStyle.TINT, 16, false));
        LinearLayout statusRow = new LinearLayout(this); statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this); statusDot.setBackground(ui.surface(UiStyle.GREEN, 4, false));
        LinearLayout.LayoutParams dotParams = new LinearLayout.LayoutParams(dp(7), dp(7)); dotParams.rightMargin = dp(9);
        statusRow.addView(statusDot, dotParams);
        status = ui.text("", 14, UiStyle.INK, 700); status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        statusRow.addView(status, new LinearLayout.LayoutParams(0, -2, 1)); statusCard.addView(statusRow);
        detail = label("", 11, UiStyle.MUTED); detail.setLineSpacing(dp(2), 1);
        statusCard.addView(detail, spaced(-1, -2, 5)); root.addView(statusCard, spaced(-1, -2, 20));
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(UiStyle.GREEN));
        progress.setVisibility(View.GONE); root.addView(progress, spaced(-1, dp(2), 4));

        form = new ScrollView(this); form.setFillViewport(false); form.setClipToPadding(false); form.setVerticalScrollBarEnabled(false);
        form.setPadding(0, dp(16), 0, dp(12));
        LinearLayout fields = column(); form.addView(fields);
        LinearLayout channelCard = ui.card(); fields.addView(channelCard);
        LinearLayout channelHeading = new LinearLayout(this); channelHeading.setGravity(Gravity.CENTER_VERTICAL);
        channelHeading.addView(ui.text("Channel", 14, UiStyle.INK, 700), new LinearLayout.LayoutParams(0, -2, 1));
        Button paste = ui.button("Paste link", Color.TRANSPARENT, UiStyle.GREEN); paste.setTextSize(11);
        channelHeading.addView(paste, new LinearLayout.LayoutParams(-2, dp(48))); channelCard.addView(channelHeading);
        url = ui.input(prefs.getString("url", ""), "https://discord.com/channels/..."); url.setContentDescription("Discord channel link");
        url.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        url.setTextSize(12); url.setSelectAllOnFocus(true);
        channelCard.addView(url, new LinearLayout.LayoutParams(-1, dp(48)));
        paste.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = clipboard.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(this);
                if (text != null) { url.setText(text.toString().trim()); url.setError(null); persistFields(); }
            } else Toast.makeText(this, "Copy a channel link from Discord first.", Toast.LENGTH_SHORT).show();
        });
        connect = ui.button("Open Discord  \u2197", Color.TRANSPARENT, UiStyle.GREEN);
        connect.setGravity(Gravity.START | Gravity.CENTER_VERTICAL); connect.setPadding(0, 0, 0, 0); connect.setTextSize(12);
        channelCard.addView(connect, spaced(-1, dp(48), 4)); connect.setOnClickListener(v -> openDiscord());

        LinearLayout messageCard = ui.card(); fields.addView(messageCard, spaced(-1, -2, 12));
        LinearLayout messageHeading = new LinearLayout(this); messageHeading.setGravity(Gravity.CENTER_VERTICAL);
        messageHeading.addView(ui.text("Your message", 14, UiStyle.INK, 700), new LinearLayout.LayoutParams(0, -2, 1));
        TextView count = label("", 10, UiStyle.MUTED); messageHeading.addView(count); messageCard.addView(messageHeading);
        message = ui.input(prefs.getString("message", "Check-in \u2705"), "Write your check-in...");
        message.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        message.setSingleLine(false); message.setMinLines(2); message.setMaxLines(5); message.setGravity(Gravity.TOP | Gravity.START);
        message.setContentDescription("Message to send"); messageCard.addView(message, spaced(-1, dp(88), 12));
        count.setText(message.length() + " / 2000");
        message.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int added) {
                count.setText(s.length() + " / 2000"); count.setTextColor(s.length() > 2000 ? 0xffb44428 : UiStyle.MUTED);
            }
            public void afterTextChanged(android.text.Editable s) { }
        });

        // Backing fields retain the same configuration and scheduling behavior.
        time = ui.input(prefs.getString("time", "20:00"), "20:00");
        zone = ui.input(prefs.getString("zone", ZoneId.systemDefault().getId()), "Time zone");
        before = ui.input(prefs.getString("before", "5"), "5"); after = ui.input(prefs.getString("after", "15"), "15");
        LinearLayout scheduleCard = ui.card(); fields.addView(scheduleCard, spaced(-1, -2, 12));
        LinearLayout scheduleRow = new LinearLayout(this); scheduleRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout scheduleCopy = column(); scheduleCopy.addView(ui.text("Opening time", 12, UiStyle.MUTED, 600));
        scheduleTime = ui.text("", 24, UiStyle.INK, 750); scheduleTime.setLetterSpacing(-0.025f);
        scheduleCopy.addView(scheduleTime, spaced(-1, -2, 5));
        scheduleZone = label("", 11, UiStyle.MUTED); scheduleCopy.addView(scheduleZone, spaced(-1, -2, 3));
        scheduleRow.addView(scheduleCopy, new LinearLayout.LayoutParams(0, -2, 1));
        scheduleEdit = ui.button("Edit", UiStyle.TINT, UiStyle.GREEN);
        scheduleEdit.setContentDescription("Edit opening time and time zone");
        scheduleRow.addView(scheduleEdit, new LinearLayout.LayoutParams(dp(64), dp(48))); scheduleCard.addView(scheduleRow);
        scheduleEdit.setOnClickListener(v -> showSchedule()); refreshSchedule();
        root.addView(form, new LinearLayout.LayoutParams(-1, 0, 1));

        web = new WebView(this) {
            @Override protected void onWindowVisibilityChanged(int visibility) {
                super.onWindowVisibilityChanged(active() ? View.VISIBLE : visibility);
            }
        };
        web.setBackgroundColor(Color.WHITE); web.setVisibility(View.GONE);
        LinearLayout.LayoutParams webParams = new LinearLayout.LayoutParams(-1, 0, 1); webParams.topMargin = dp(12); root.addView(web, webParams);
        arm = ui.button("Start watching  \u2192", UiStyle.INK, Color.WHITE); arm.setTextSize(15);
        root.addView(arm, spaced(-1, dp(54), 8));
        arm.setOnClickListener(v -> { if (active()) finish("Watching stopped", "Check Discord for any unfinished draft."); else start(false); });
        LinearLayout footer = new LinearLayout(this); footer.setGravity(Gravity.CENTER_VERTICAL);
        preview = ui.button("Check without sending", Color.TRANSPARENT, UiStyle.MUTED); preview.setTextSize(11);
        settings = ui.button("View Discord", Color.TRANSPARENT, UiStyle.MUTED); settings.setTextSize(11);
        settings.setVisibility(View.GONE);
        footer.addView(preview, new LinearLayout.LayoutParams(0, dp(48), 1));
        footer.addView(settings, new LinearLayout.LayoutParams(0, dp(48), 1)); root.addView(footer);
        preview.setOnClickListener(v -> start(true));
        settings.setOnClickListener(v -> { if (form.getVisibility() == View.VISIBLE) openDiscord(); else showSetup(); });
    }
    private void refreshSchedule() {
        scheduleTime.setText(time.getText()); scheduleZone.setText(zone.getText().toString().replace('_', ' '));
    }
    private void persistFields() {
        if (after == null) return;
        prefs.edit().putString("url", url.getText().toString()).putString("message", message.getText().toString())
            .putString("time", time.getText().toString()).putString("zone", zone.getText().toString())
            .putString("before", before.getText().toString()).putString("after", after.getText().toString()).apply();
    }
    private void openDiscord() {
        try {
            String address = WatchConfig.normalizeChannel(url.getText().toString());
            url.setError(null); persistFields(); browser();
            if (!address.equals(web.getUrl())) web.loadUrl(address);
            showStatus("Open Discord", "Sign in if needed, then check your channel.");
        } catch (IllegalArgumentException e) { url.setError(e.getMessage()); url.requestFocus(); }
    }
    private void showSetup() {
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0);
        web.setVisibility(View.GONE); form.setVisibility(View.VISIBLE); settings.setVisibility(View.GONE); root.requestFocus();
    }
    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Activity log"); menu.getMenu().add(0, 2, 1, "Watch window");
        menu.getMenu().add(0, 3, 2, "Reset today's attempt"); menu.getMenu().add(0, 5, 3, "Activation key"); menu.getMenu().add(0, 4, 4, "About & help");
        menu.getMenu().findItem(2).setEnabled(!active()); menu.getMenu().findItem(3).setEnabled(!active());
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: ui.polishDialog(new AlertDialog.Builder(this).setTitle("Activity log")
                    .setMessage(events.isEmpty() ? "No activity yet." : String.join("\n\n", events)).setPositiveButton("Done", null).show()); break;
                case 2: showWatchWindow(); break;
                case 3: resetAttempt(); break;
                case 5: showLicenseDialog(); break;
                case 4: ui.polishDialog(new AlertDialog.Builder(this).setTitle("About Message Auto")
                    .setMessage("One send attempt per channel and scheduled day. Start a new session each day.\n\nWatching continues in the background. Allow notifications and unrestricted battery use for longer sessions. Don't force-stop the app.\n\nAn existing draft stops sending. If a send isn't confirmed, check Discord before resetting the attempt.\n\nDiscord prohibits automated personal accounts and may sanction your account.\n\nVersion " + BuildConfig.VERSION_NAME + " \u00b7 Manrope font (SIL Open Font License)")
                    .setNeutralButton("Discord policy", (d, w) -> openExternal(Uri.parse("https://support.discord.com/hc/en-us/articles/115002192352-Automated-User-Accounts-Self-Bots")))
                    .setPositiveButton("Done", null).show()); break;
                default: return false;
            }
            return true;
        }); menu.show();
    }
    private LinearLayout dialogContent() {
        LinearLayout content = column(); content.setPadding(dp(24), dp(8), dp(24), dp(8)); return content;
    }
    private ScrollView dialogScroll(LinearLayout content) {
        ScrollView scroll = new ScrollView(this); scroll.addView(content); return scroll;
    }
    private void showSchedule() {
        if (active()) return;
        LinearLayout content = dialogContent();
        Button chooseTime = ui.button(time.getText().toString(), UiStyle.TINT, UiStyle.GREEN);
        chooseTime.setTextSize(26); content.addView(label("Time", 12, UiStyle.MUTED));
        content.addView(chooseTime, spaced(-1, dp(64), 8));
        chooseTime.setOnClickListener(v -> {
            LocalTime t;
            try { t = LocalTime.parse(chooseTime.getText()); } catch (Exception e) { t = LocalTime.of(20, 0); }
            TimePickerDialog picker = new TimePickerDialog(this, (view, h, m) -> chooseTime.setText(String.format(Locale.US, "%02d:%02d", h, m)), t.getHour(), t.getMinute(), true);
            picker.show(); ui.polishDialog(picker);
        });
        content.addView(label("Time zone", 12, UiStyle.MUTED), spaced(-1, -2, 20));
        AutoCompleteTextView chooseZone = new AutoCompleteTextView(this); ui.font(chooseZone, 500);
        chooseZone.setTextSize(13); chooseZone.setSingleLine(true); chooseZone.setTextColor(UiStyle.INK);
        chooseZone.setBackground(ui.surface(UiStyle.PAGE, 12, false)); chooseZone.setPadding(dp(12), dp(14), dp(12), dp(14));
        chooseZone.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        chooseZone.setText(zone.getText()); chooseZone.setSelectAllOnFocus(true); chooseZone.setThreshold(1);
        List<String> zones = new ArrayList<>(ZoneId.getAvailableZoneIds()); Collections.sort(zones);
        chooseZone.setAdapter(new ArrayAdapter<String>(this, android.R.layout.simple_dropdown_item_1line, new ArrayList<>(zones)) {
            @Override public Filter getFilter() {
                return new Filter() {
                    @Override protected FilterResults performFiltering(CharSequence constraint) {
                        String query = constraint == null ? "" : constraint.toString().toLowerCase(Locale.US).replace('_', ' ');
                        List<String> matches = new ArrayList<>();
                        for (String id : zones) if (id.toLowerCase(Locale.US).replace('_', ' ').contains(query)) matches.add(id);
                        FilterResults results = new FilterResults(); results.values = matches; results.count = matches.size(); return results;
                    }
                    @Override protected void publishResults(CharSequence constraint, FilterResults results) {
                        setNotifyOnChange(false); clear();
                        for (Object id : (List<?>)results.values) add(id.toString());
                        notifyDataSetChanged();
                    }
                };
            }
        });
        content.addView(chooseZone, spaced(-1, -2, 8));
        Button deviceZone = ui.button("Use device time zone", Color.TRANSPARENT, UiStyle.GREEN); deviceZone.setTextSize(12);
        content.addView(deviceZone); deviceZone.setOnClickListener(v -> chooseZone.setText(ZoneId.systemDefault().getId(), false));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Opening time").setView(dialogScroll(content))
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(d -> { ui.polishDialog(dialog); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                ZoneId selected = ZoneId.of(chooseZone.getText().toString().trim());
                time.setText(chooseTime.getText()); zone.setText(selected.getId()); refreshSchedule(); persistFields(); dialog.dismiss();
            } catch (DateTimeException e) { chooseZone.setError("Choose a valid time zone, such as Europe/Zurich."); }
        }); }); dialog.show();
    }
    private void showWatchWindow() {
        LinearLayout content = dialogContent();
        TextView hint = label("Watch around the opening time. An already open channel can receive your message as soon as this window starts.", 13, UiStyle.MUTED);
        hint.setLineSpacing(dp(3), 1); content.addView(hint);
        content.addView(label("Minutes before opening", 12, UiStyle.MUTED), spaced(-1, -2, 20));
        EditText early = ui.input(before.getText().toString(), "5"); early.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        content.addView(early, spaced(-1, -2, 8));
        content.addView(label("Minutes after opening", 12, UiStyle.MUTED), spaced(-1, -2, 16));
        EditText late = ui.input(after.getText().toString(), "15"); late.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        content.addView(late, spaced(-1, -2, 8));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Watch window").setView(dialogScroll(content))
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create();
        dialog.setOnShowListener(d -> { ui.polishDialog(dialog); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            int a, b;
            try { a = Integer.parseInt(early.getText().toString()); if (a < 0 || a > 180) throw new NumberFormatException(); }
            catch (NumberFormatException e) { early.setError("Use 0 to 180 minutes."); return; }
            try { b = Integer.parseInt(late.getText().toString()); if (b < 1 || b > 180) throw new NumberFormatException(); }
            catch (NumberFormatException e) { late.setError("Use 1 to 180 minutes."); return; }
            before.setText(String.valueOf(a)); after.setText(String.valueOf(b)); persistFields(); dialog.dismiss();
        }); }); dialog.show();
    }
    private void browser() {
        ((InputMethodManager)getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(root.getWindowToken(), 0);
        form.setVisibility(View.GONE); web.setVisibility(View.VISIBLE); settings.setVisibility(View.VISIBLE); settings.setText(R.string.settings);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
        web.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
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
            } catch (Exception e) { finish("Watching interrupted", "Check your channel and draft before trying again."); }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override public void onProgressChanged(WebView view, int value) { progress.setProgress(value); progress.setVisibility(value < 100 ? View.VISIBLE : View.GONE); }
        });
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (!request.isForMainFrame()) return false;
                Uri dest = request.getUrl();
                if ("https".equals(dest.getScheme()) && "discord.com".equals(dest.getHost()) && (dest.getPort() == -1 || dest.getPort() == 443)) return false;
                if (active()) finish("Channel navigation stopped", "Return to your channel and start watching again.");
                openExternal(dest); return true;
            }
            @Override public void onPageStarted(WebView view, String address, android.graphics.Bitmap icon) {
                loaded = false; installed = false;
                if (active() && !phase.equals("armed")) finish("Page reloaded during sending", "The result is uncertain. Check Discord before trying again.");
            }
            @Override public void onPageFinished(WebView view, String address) {
                // Discord's desktop layout collapses the composer at a phone-width viewport.
                if (address.startsWith("https://discord.com/")) view.evaluateJavascript(
                    "(()=>{const m=document.querySelector('meta[name=viewport]');if(m)m.content='width=980';})()", null);
                loaded = true;
                if (active() && phase.equals("armed") && window.active(Instant.now()) && !installed) install();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
                if (request.isForMainFrame()) finish("Couldn't load Discord", "Check your internet connection, then reopen the channel.");
            }
            @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) { finish("Discord view stopped", "Reopen the app. Your previous attempt stays protected."); view.destroy(); MainActivity.this.finish(); return true; }
        });
    }
    private void openExternal(Uri uri) {
        if (!"https".equals(uri.getScheme())) return;
        try { startActivity(new Intent(Intent.ACTION_VIEW, uri)); } catch (ActivityNotFoundException e) { showStatus("No browser available", "Install a browser to open this link."); }
    }
    private WatchConfig readConfig() {
        url.setError(null); message.setError(null);
        try { WatchConfig.normalizeChannel(url.getText().toString()); }
        catch (IllegalArgumentException e) { showSetup(); url.setError("Paste a full Discord channel link."); url.requestFocus(); return null; }
        if (message.getText().toString().trim().isEmpty() || message.length() > 2000) {
            showSetup(); message.setError("Write a message between 1 and 2000 characters."); message.requestFocus(); return null;
        }
        try { return new WatchConfig(url.getText().toString(), message.getText().toString(), time.getText().toString().trim(), zone.getText().toString().trim(), Integer.parseInt(before.getText().toString()), Integer.parseInt(after.getText().toString())); }
        catch (Exception e) { showStatus("Check your setup", e.getMessage() == null ? "Check your channel, message and opening time." : e.getMessage()); return null; }
    }
    private void save(WatchConfig c) {
        prefs.edit().putString("url", c.url).putString("message", c.message).putString("time", time.getText().toString().trim()).putString("zone", c.zone.toString()).putString("before", "" + c.before).putString("after", "" + c.after).apply();
    }
    private boolean active() { return !phase.equals("idle"); }
    private String stateKey() { return "attempt:" + config.url + ":" + day; }
    private boolean blocked() { return prefs.contains(stateKey()); }
    private void resetAttempt() {
        if (active()) { showStatus("Currently watching", "Stop watching before resetting an attempt."); return; }
        WatchConfig c = readConfig(); if (c == null) return;
        String scheduledDay = c.window(Instant.now()).day();
        String key = "attempt:" + c.url + ":" + scheduledDay;
        if (!prefs.contains(key)) { showStatus("No attempt to reset", "No attempt is saved for this channel on " + scheduledDay + "."); return; }
        CheckBox checked = new CheckBox(this);
        checked.setText("I checked Discord and want to allow another send.");
        checked.setPadding(dp(20), dp(8), dp(20), dp(8));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Allow another attempt?")
            .setMessage("Channel: " + c.url + "\nDay: " + scheduledDay + "\n\nA previous send may already exist. Another attempt could create a duplicate. Only this channel's attempt for this day will be reset.")
            .setView(checked).setNegativeButton("Cancel", null).setPositiveButton("Allow attempt", (d, which) -> {
                if (prefs.edit().remove(key).commit()) {
                    showStatus("Another attempt is allowed", "Nothing sent. Tap Start watching when you're ready.");
                    log("Attempt manually reset for " + c.url + " - " + scheduledDay + ".");
                } else showStatus("Couldn't reset attempt", "The previous attempt stays protected.");
            }).create();
        dialog.setOnShowListener(d -> {
            ui.polishDialog(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            checked.setOnCheckedChangeListener((button, value) -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(value));
        });
        dialog.show();
    }
    private void start(boolean previewOnly) {
        if (!foreground || active()) return;
        if (licenseChecking) return;
        if (BuildConfig.DEBUG && !licenses.configured()) { startVerified(previewOnly); return; }
        if (!licenses.configured()) { showStatus("License server missing", "This APK needs a configured HTTPS license server."); return; }
        String key = prefs.getString("license_key", "");
        if (key.isEmpty()) { showLicenseDialog(); return; }
        licenseChecking = true;
        showStatus("Checking activation...", "Contacting the license server.");
        licenses.validate(key, false, (valid, reason) -> {
            licenseChecking = false;
            if (isFinishing() || isDestroyed()) return;
            if (!valid) { showStatus("License unavailable", reason); return; }
            startVerified(previewOnly);
        });
    }
    private void showLicenseDialog() {
        if (active() || licenseChecking) return;
        EditText field = ui.input("", "DMA-...");
        field.setSingleLine(true); field.setAllCaps(true);
        LinearLayout box = dialogContent();
        box.addView(label("Enter the key given by the administrator. It activates on this phone only.", 13, UiStyle.MUTED));
        box.addView(field, spaced(-1, dp(52), 12));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Activate this phone")
            .setView(box).setNegativeButton("Cancel", null).setPositiveButton("Activate", null).create();
        dialog.setOnShowListener(d -> {
            ui.polishDialog(dialog);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String key = field.getText().toString().trim().toUpperCase(Locale.US);
                if (!key.matches("DMA-(?:[0-9A-F]{6}-){3}[0-9A-F]{6}")) { field.setError("Enter a complete activation key."); return; }
                licenseChecking = true; dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
                licenses.validate(key, true, (valid, reason) -> {
                    licenseChecking = false;
                    if (isFinishing() || isDestroyed()) return;
                    if (!valid) { field.setError(reason); dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true); return; }
                    prefs.edit().putString("license_key", key).apply();
                    dialog.dismiss(); showStatus("Phone activated", "You can now start watching.");
                });
            });
        }); dialog.show();
    }
    private void startVerified(boolean previewOnly) {
        WatchConfig c = readConfig(); if (c == null) return;
        if (!bridgeSupported) { showStatus("Update Android System WebView", "Update Android System WebView or Chrome, then reopen this app."); return; }
        config = c; save(c); previewMode = previewOnly;
        Instant now = Instant.now();
        window = previewOnly ? new WatchConfig.Window(now.atZone(c.zone).toLocalDate().toString(), now, now.plusSeconds(c.after * 60L)) : c.window(now);
        day = window.day();
        if (!previewOnly && blocked()) { showStatus("Today's attempt is used", "Check Discord. A second message won't be sent automatically."); return; }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 10);
            showStatus("Allow notifications", "Allow notifications to keep watching in the background."); return;
        }
        WatchService.onStopRequested = () -> finish("Watching stopped", "The session was stopped or its watch window ended.");
        try {
            startForegroundService(new Intent(this, WatchService.class).putExtra("deadline", window.end().toEpochMilli()));
        } catch (RuntimeException e) {
            WatchService.onStopRequested = null;
            showStatus("Couldn't start watching", "Keep this app open and try again."); return;
        }
        phase = "armed"; token = UUID.randomUUID().toString(); installed = false;
        if (licenses.configured()) handler.postDelayed(recheckLicense, 30000);
        browser(); controls(true);
        String address = web.getUrl();
        if (address == null || !address.replaceAll("/$", "").equals(c.url)) web.loadUrl(c.url);
        log(previewOnly ? "Channel check started. No message will be sent." : "Watching started for " + day + " (" + c.zone + ").");
        handler.post(tick);
    }
    private void controls(boolean running) {
        arm.setText(running ? "Stop watching" : "Start watching  \u2192");
        for (Button control : new Button[]{preview, connect, settings, scheduleEdit}) {
            control.setEnabled(!running); control.setAlpha(running ? 0.4f : 1f);
        }
    }
    private void install() {
        if (!phase.equals("armed") || (!previewMode && blocked())) return;
        if (!config.url.equals(web.getUrl() == null ? "" : web.getUrl().replaceAll("/$", ""))) {
            showStatus("Open your channel", "Sign in to Discord and open your configured channel."); return;
        }
        installed = true;
        try {
            JSONObject options = new JSONObject().put("url", config.url).put("message", config.message).put("token", token).put("deadline", window.end().toEpochMilli()).put("preview", previewMode);
            showStatus(previewMode ? "Checking your channel..." : "Watching for an opening", "You can leave the app. Keep your phone connected.");
            web.evaluateJavascript(script + "\nwindow.DMA.install(" + options + ");", null);
        } catch (Exception e) { finish("Couldn't start watching", "Check your setup and try again."); }
    }
    private boolean eligible(String run, String expectedPhase) {
        return Objects.equals(token, run) && phase.equals(expectedPhase) && window.active(Instant.now()) &&
            config.url.equals(web.getUrl() == null ? "" : web.getUrl().replaceAll("/$", ""));
    }
    private void onEvent(JSONObject event) {
        switch (event.optString("type")) {
            case "ready": if (!previewMode && phase.equals("armed")) prepareNative(); break;
            case "prepared": if (!previewMode && phase.equals("preparing")) sendNative(); break;
            case "preview": if (previewMode) finish("Your channel is ready", "You can write here. This check didn't send a message."); break;
            case "draft": finish("A draft is already there", "Nothing sent. Clear the draft in Discord to continue."); break;
            case "prepare_failed": finish("Message preparation stopped", "Nothing sent. Check the draft in Discord."); break;
            case "wrong_channel": finish("The channel changed", "Nothing was sent to another channel."); break;
            case "expired": finish("Watch window ended", "No automatic retry. Start a new session when ready."); break;
            case "sent":
                if (phase.equals("confirming")) {
                    String elapsed = String.format(Locale.US, "%.1f ms", event.optDouble("elapsed"));
                    log("Detection to Enter: " + elapsed + " (page timing).");
                    showStatus("Checking the send...", "Waiting for your message to appear in Discord.");
                }
                break;
            case "confirmed":
                if (phase.equals("confirming")) {
                    prefs.edit().putString(stateKey(), "confirmed").apply();
                    log(String.format(Locale.US, "Message observed %.0f ms after detection (page timing).", event.optDouble("elapsed")));
                    finish("Message seen in your channel", "Check Discord to verify your check-in.");
                }
                break;
            case "uncertain": if (phase.equals("confirming")) finish("Send not confirmed", "Check Discord first. Today's attempt stays protected."); break;
            default: break;
        }
    }
    private void prepareNative() {
        String run = token;
        if (!eligible(run, "armed") || blocked()) return;
        phase = "preparing";
        web.requestFocus();
        web.evaluateJavascript("Boolean(window.DMA && window.DMA.beginNativePreparation(" + JSONObject.quote(run) + "))", empty -> {
            if (!eligible(run, "preparing")) return;
            if (!"true".equals(empty)) { finish("Message preparation stopped", "Your draft or channel changed. Nothing sent."); return; }
            InputConnection input = web.onCreateInputConnection(new EditorInfo());
            if (input == null || !input.commitText(config.message, 1)) {
                finish("Couldn't enter your message", "Nothing sent. Check the draft in Discord."); return;
            }
        });
    }
    private void sendNative() {
        String run = token;
        if (!eligible(run, "preparing") || blocked()) { finish("Sending stopped", "The watch window, channel or saved attempt changed."); return; }
        if (!prefs.edit().putString(stateKey(), "reserved").commit()) {
            finish("Couldn't save this attempt", "Nothing sent."); return;
        }
        if (!eligible(run, "preparing")) {
            finish("Watch window ended", "Nothing sent. This attempt stays protected."); return;
        }
        phase = "confirming";
        web.evaluateJavascript("Boolean(window.DMA && window.DMA.authorizeNativeSend(" + JSONObject.quote(run) + "))", authorized -> {
            if (!active() || !Objects.equals(token, run)) return;
            if (!"true".equals(authorized) || !eligible(run, "confirming")) {
                finish("Sending stopped", "Your draft or channel changed. Check Discord before resetting the attempt."); return;
            }
            web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
            web.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
            web.evaluateJavascript("window.DMA && window.DMA.nativeSent(" + JSONObject.quote(run) + ")", null);
        });
    }
    private void finish(String title, String explanation) {
        WatchService.onStopRequested = null;
        stopService(new Intent(this, WatchService.class));
        phase = "idle"; token = null; installed = false; handler.removeCallbacks(tick); handler.removeCallbacks(recheckLicense);
        if (web != null) web.evaluateJavascript("window.DMA && window.DMA.stop()", null);
        if (web != null && !foreground) web.onPause();
        controls(false); showStatus(title, explanation); log(title + ". " + explanation);
    }
    private void showStatus(String title, String explanation) {
        status.setText(title); detail.setText(explanation);
        boolean attention = title.startsWith("Couldn't") || title.equals("Send not confirmed") ||
            title.equals("Today's attempt is used") || title.equals("A draft is already there") ||
            title.equals("Check your setup") || title.equals("The channel changed");
        statusCard.setBackground(ui.surface(attention ? 0xfffff0dc : UiStyle.TINT, 16, false));
        statusDot.setBackground(ui.surface(attention ? 0xffaa6516 : UiStyle.GREEN, 4, false));
    }
    private void log(String text) {
        if (events.size() >= 60) events.removeFirst();
        events.addLast(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " · " + text);
    }
    @Override protected void onResume() { super.onResume(); foreground = true; web.onResume(); handler.post(this::resumeRequestedWatch); }
    @Override protected void onPause() {
        foreground = false;
        persistFields();
        CookieManager.getInstance().flush(); if (!active()) web.onPause(); super.onPause();
    }
    @SuppressLint("GestureBackNavigation") // API 33+ uses the registered OnBackInvokedCallback.
    @Override public void onBackPressed() { handleBack(); }
    private void handleBack() {
        if (active()) moveTaskToBack(true);
        else if (web.getVisibility() == View.VISIBLE) showSetup();
        else finish();
    }
    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 10 && grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            resumeAfterPermission = true; handler.post(this::resumeRequestedWatch);
        }
    }
    private void resumeRequestedWatch() {
        if (resumeAfterPermission && foreground) { resumeAfterPermission = false; start(previewMode); }
    }
    @Override protected void onDestroy() { if (active()) finish("Watching stopped", "The app was closed. Start watching again manually."); handler.removeCallbacksAndMessages(null); licenses.close(); web.destroy(); super.onDestroy(); }
}
