package com.cytv.ecard;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.provider.MediaStore;
import android.text.InputFilter;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * TN-NHIS e-card helper for elders.
 *
 * Home (type PPO number) -> the app drives the NHIS website in a covered WebView
 * (search -> member details -> Generate e-Card) -> "Is this you?" -> card screen with
 * two PDF sizes. The last card is kept inside the app for offline viewing.
 */
public class MainActivity extends Activity {

    private static final String TAG = "ecard";
    private static final String BASE = "https://claims.tn-nhis.net/nhis/Payer/";
    private static final String SEARCH_URL = BASE + "PayerMembersearch.aspx";
    private static final String CARD_PAGE = "NewPayerCard.aspx";

    private static final int CAPTURE_CSS_WIDTH = 1200;  // wide enough that the two cards never shrink
    private static final int START_CSS_HEIGHT = 60;
    private static final int CARD_BITMAP_WIDTH = 1600;
    private static final long STEP_TIMEOUT_MS = 90000;

    private static final int NAVY = 0xFF0B3D91;
    private static final int GREEN = 0xFF1B7F3B;
    private static final int RED = 0xFFB3261E;
    private static final int GREY = 0xFF55606E;
    private static final int INK = 0xFF17202B;

    private enum Mode {IDLE, SEARCH_LOAD, SEARCH_WAIT, DETAILS, CARD, MANUAL}

    private final Handler ui = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private float density;

    private FrameLayout stage;      // holds the WebView, underneath the screens
    private ScrollView screen;      // opaque layer with the simple screens
    private LinearLayout content;
    private TextView backBar;       // shown only while the real website is visible
    private WebView web;

    private Mode mode = Mode.IDLE;
    private volatile boolean lightweight = true;   // read on WebView's network thread
    private boolean onHome = true;
    private int token;              // bumps on every new step; stale callbacks compare and bail
    private String ppo = "";
    private String detailsUrl;
    private String memberName = "";

    private Bitmap front, back;
    private Uri lastPdf;
    private File lastPdfFile;
    private LinearLayout afterSave;
    private TextView savedLabel;

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Before the first WebView exists: allows drawing the whole page, not only the visible part.
        WebView.enableSlowWholeDocumentDraw();
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("ecard", MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        getWindow().setStatusBarColor(NAVY);
        buildShell();
        showHome(null);
    }

    @Override
    public void onBackPressed() {
        if (onHome && mode == Mode.IDLE) {
            super.onBackPressed();
        } else {
            showHome(null);
        }
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            stage.removeView(web);
            web.destroy();
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ UI helpers

    private int dp(float v) {
        return Math.round(v * density);
    }

    private static String bi(String en, String ta) {
        return en + "\n" + ta;
    }

    private GradientDrawable box(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (stroke != 0) g.setStroke(dp(2), stroke);
        return g;
    }

    private TextView label(String text, float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setLineSpacing(dp(3), 1f);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    /** Large two-line (English + Tamil) button. */
    private TextView bigButton(String en, String ta, int color, boolean filled) {
        TextView b = label(bi(en, ta), 20, filled ? Color.WHITE : color, true);
        b.setGravity(Gravity.CENTER);
        b.setBackground(filled ? box(color, 0, 14) : box(Color.WHITE, color, 14));
        b.setPadding(dp(12), dp(12), dp(12), dp(12));
        b.setMinHeight(dp(76));
        b.setClickable(true);
        b.setFocusable(true);
        return b;
    }

    private LinearLayout.LayoutParams row(int topDp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
        p.topMargin = dp(topDp);
        return p;
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF2F4F8);
        root.setFitsSystemWindows(true);

        TextView title = label(bi("Health Insurance Card", "மருத்துவக் காப்பீட்டு அட்டை"), 20, Color.WHITE, true);
        title.setBackgroundColor(NAVY);
        title.setPadding(dp(18), dp(12), dp(18), dp(12));
        root.addView(title, new LinearLayout.LayoutParams(-1, -2));

        backBar = bigButton("Back", "பின் செல்", RED, true);
        backBar.setOnClickListener(v -> showHome(null));
        backBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams bb = new LinearLayout.LayoutParams(-1, -2);
        bb.setMargins(dp(10), dp(8), dp(10), dp(8));
        root.addView(backBar, bb);

        FrameLayout body = new FrameLayout(this);
        root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));

        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.WHITE);
        body.addView(stage, new FrameLayout.LayoutParams(-1, -1));

        web = new WebView(this);
        web.setBackgroundColor(Color.WHITE);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setTextZoom(100);
        s.setSupportMultipleWindows(false);   // "open in new tab" links load in this same view
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        stage.addView(web, new FrameLayout.LayoutParams(
                dp(CAPTURE_CSS_WIDTH), dp(START_CSS_HEIGHT), Gravity.TOP | Gravity.START));

        screen = new ScrollView(this);
        screen.setBackgroundColor(0xFFF2F4F8);
        screen.setFillViewport(true);
        screen.setClickable(true);   // swallow touches so the covered WebView never receives them
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(18), dp(18), dp(18), dp(24));
        screen.addView(content, new ScrollView.LayoutParams(-1, -2));
        body.addView(screen, new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
    }

    private void resetScreen() {
        content.removeAllViews();
        screen.scrollTo(0, 0);
        screen.setVisibility(View.VISIBLE);
        backBar.setVisibility(View.GONE);
        afterSave = null;
        savedLabel = null;
        hideKeyboard();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(content.getWindowToken(), 0);
    }

    // ------------------------------------------------------------------ screens

    private void showHome(String error) {
        stopWork();
        onHome = true;
        resetScreen();

        if (error != null) {
            TextView e = label(error, 18, RED, true);
            e.setBackground(box(0xFFFDECEA, RED, 12));
            e.setPadding(dp(14), dp(12), dp(14), dp(12));
            content.addView(e, row(0));
        }

        content.addView(label(bi("Type your PPO number", "உங்கள் PPO எண்ணை உள்ளிடவும்"), 22, INK, true),
                row(error == null ? 4 : 18));

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 30);
        input.setTypeface(Typeface.DEFAULT_BOLD);
        input.setTextColor(INK);
        input.setGravity(Gravity.CENTER);
        input.setHint("PPO");
        input.setBackground(box(Color.WHITE, NAVY, 14));
        input.setPadding(dp(12), dp(16), dp(12), dp(16));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setFilters(new InputFilter[]{new InputFilter.AllCaps(), new InputFilter.LengthFilter(30)});
        input.setImeOptions(EditorInfo.IME_ACTION_GO);
        content.addView(input, row(12));

        TextView go = bigButton("Get my card", "அட்டையைப் பெறுக", NAVY, true);
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 23);
        go.setMinHeight(dp(92));
        Runnable submit = () -> {
            String v = input.getText().toString().trim().toUpperCase();
            if (v.isEmpty()) {
                input.setHintTextColor(RED);
                input.requestFocus();
                return;
            }
            startSearch(v);
        };
        go.setOnClickListener(v -> submit.run());
        input.setOnEditorActionListener((v, id, ev) -> {
            submit.run();
            return true;
        });
        content.addView(go, row(18));

        if (new File(getFilesDir(), "front.png").exists()) {
            String who = prefs.getString("lastName", "");
            TextView last = bigButton("Show last card" + (who.isEmpty() ? "" : " – " + who),
                    "கடைசியாகச் சேமித்த அட்டை", GREEN, false);
            last.setOnClickListener(v -> showLastCard());
            content.addView(last, row(28));
            content.addView(label(bi("Works without internet", "இணையம் இல்லாமலும் பார்க்கலாம்"), 14, GREY, false), row(6));
        }
    }

    private void showWait(String en, String ta) {
        onHome = false;
        resetScreen();
        ProgressBar p = new ProgressBar(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(96), dp(96));
        lp.gravity = Gravity.CENTER_HORIZONTAL;
        lp.topMargin = dp(60);
        content.addView(p, lp);
        TextView t = label(bi(en, ta), 22, INK, true);
        t.setGravity(Gravity.CENTER);
        content.addView(t, row(28));
        TextView cancel = bigButton("Cancel", "ரத்து செய்", GREY, false);
        cancel.setOnClickListener(v -> showHome(null));
        content.addView(cancel, row(60));
    }

    private void showProblem(String en, String ta) {
        stopWork();
        onHome = false;
        resetScreen();
        TextView e = label(bi(en, ta), 20, RED, true);
        e.setBackground(box(0xFFFDECEA, RED, 12));
        e.setPadding(dp(14), dp(14), dp(14), dp(14));
        content.addView(e, row(8));
        TextView again = bigButton("Try again", "மீண்டும் முயற்சி செய்", NAVY, true);
        again.setOnClickListener(v -> showHome(null));
        content.addView(again, row(24));
        TextView site = bigButton("Open the website", "இணையதளத்தைத் திற", GREY, false);
        site.setOnClickListener(v -> openWebsite());
        content.addView(site, row(16));
    }

    private void addField(LinearLayout card, String en, String ta, String value) {
        if (value == null || value.trim().isEmpty()) return;
        card.addView(label(en + " / " + ta, 15, GREY, false), row(card.getChildCount() == 0 ? 0 : 14));
        card.addView(label(value.trim(), 24, INK, true), row(2));
    }

    private static String cell(JSONArray c, int i) {
        return c != null && i < c.length() ? c.optString(i, "").trim() : "";
    }

    private void showConfirm(final JSONObject member) {
        onHome = false;
        resetScreen();
        JSONArray c = member.optJSONArray("c");
        content.addView(label(bi("Is this you?", "இது நீங்களா?"), 26, NAVY, true), row(0));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(box(Color.WHITE, 0xFFC5CCD6, 14));
        card.setPadding(dp(16), dp(16), dp(16), dp(16));
        addField(card, "Name", "பெயர்", cell(c, 2));
        addField(card, "PPO / Employee number", "PPO எண்", cell(c, 0));
        addField(card, "Card number", "அட்டை எண்", cell(c, 1));
        addField(card, "Age", "வயது", cell(c, 4));
        addField(card, "Gender", "பாலினம்", cell(c, 5));
        addField(card, "District", "மாவட்டம்", cell(c, 6));
        content.addView(card, row(14));

        TextView yes = bigButton("Yes, this is me", "ஆம், இது நான்தான்", GREEN, true);
        yes.setMinHeight(dp(88));
        yes.setOnClickListener(v -> {
            memberName = cell(c, 2);
            detailsUrl = member.optString("h", "");
            startDetails();
        });
        content.addView(yes, row(22));

        TextView no = bigButton("No, go back", "இல்லை, பின் செல்", RED, false);
        no.setOnClickListener(v -> showHome(null));
        content.addView(no, row(14));
    }

    private void showPick(final JSONArray rows) {
        onHome = false;
        resetScreen();
        content.addView(label(bi("Choose your name", "உங்கள் பெயரைத் தேர்ந்தெடுக்கவும்"), 24, NAVY, true), row(0));
        for (int i = 0; i < rows.length() && i < 30; i++) {
            final JSONObject m = rows.optJSONObject(i);
            if (m == null) continue;
            JSONArray c = m.optJSONArray("c");
            TextView b = bigButton(cell(c, 2), cell(c, 0) + "   " + cell(c, 6), NAVY, false);
            b.setOnClickListener(v -> showConfirm(m));
            content.addView(b, row(14));
        }
        TextView no = bigButton("Go back", "பின் செல்", RED, false);
        no.setOnClickListener(v -> showHome(null));
        content.addView(no, row(22));
    }

    private ImageView cardImage(Bitmap b) {
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(b);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setBackground(box(Color.WHITE, 0xFFC5CCD6, 4));
        iv.setPadding(dp(2), dp(2), dp(2), dp(2));
        return iv;
    }

    private void showCard() {
        stopWork();
        onHome = false;
        resetScreen();
        lastPdf = null;
        lastPdfFile = null;

        if (front != null) content.addView(cardImage(front), row(0));
        if (back != null) content.addView(cardImage(back), row(10));

        content.addView(label(bi("Save as PDF – choose a size", "PDF ஆகச் சேமிக்க – அளவைத் தேர்ந்தெடுக்கவும்"), 19, INK, true), row(20));

        TextView small = bigButton("Card size", "அட்டை அளவு", NAVY, true);
        small.setOnClickListener(v -> savePdf(false));
        content.addView(small, row(12));
        TextView big = bigButton("Big size", "பெரிய அளவு", NAVY, true);
        big.setOnClickListener(v -> savePdf(true));
        content.addView(big, row(12));

        savedLabel = label("", 17, GREEN, true);
        savedLabel.setVisibility(View.GONE);
        content.addView(savedLabel, row(18));

        afterSave = new LinearLayout(this);
        afterSave.setOrientation(LinearLayout.VERTICAL);
        afterSave.setVisibility(View.GONE);
        TextView open = bigButton("Open", "திற", GREEN, true);
        open.setOnClickListener(v -> openPdf());
        afterSave.addView(open, row(10));
        TextView wa = bigButton("Share on WhatsApp", "வாட்ஸ்அப்பில் பகிர்", GREEN, true);
        wa.setOnClickListener(v -> sharePdf());
        afterSave.addView(wa, row(12));
        TextView print = bigButton("Print", "அச்சிடு", GREEN, true);
        print.setOnClickListener(v -> printPdf());
        afterSave.addView(print, row(12));
        content.addView(afterSave, row(0));

        TextView home = bigButton("Home", "முகப்பு", GREY, false);
        home.setOnClickListener(v -> showHome(null));
        content.addView(home, row(28));
    }

    private void showLastCard() {
        front = BitmapFactory.decodeFile(new File(getFilesDir(), "front.png").getPath());
        File b = new File(getFilesDir(), "back.png");
        back = b.exists() ? BitmapFactory.decodeFile(b.getPath()) : null;
        ppo = prefs.getString("lastPpo", "");
        if (front == null) {
            showHome(null);
            return;
        }
        showCard();
    }

    // ------------------------------------------------------------------ website automation

    private void stopWork() {
        token++;
        if (mode != Mode.IDLE) {
            mode = Mode.IDLE;
            web.stopLoading();
        }
    }

    private void fail(String why) {
        Log.w(TAG, "fail: " + why + " (mode " + mode + ")");
        if (mode == Mode.MANUAL || mode == Mode.IDLE) return;
        showProblem("Could not reach the NHIS website. Check the internet and try again.",
                "இணையதளத்தை அணுக முடியவில்லை. இணைய இணைப்பைச் சரிபார்த்து மீண்டும் முயற்சிக்கவும்.");
    }

    /** Arms a watchdog for the current step and returns the step's token. */
    private int beginStep(Mode m) {
        mode = m;
        final int t = ++token;
        ui.postDelayed(() -> {
            if (t == token) fail("timeout");
        }, STEP_TIMEOUT_MS);
        return t;
    }

    private void captureLayout() {
        lightweight = true;
        WebSettings s = web.getSettings();
        s.setUseWideViewPort(false);      // layout width == view width, 1 CSS px == 1 dp
        s.setLoadWithOverviewMode(false);
        s.setSupportZoom(false);
        s.setBuiltInZoomControls(false);
        setWebSize(dp(CAPTURE_CSS_WIDTH), dp(START_CSS_HEIGHT));
    }

    private void setWebSize(int w, int h) {
        ViewGroup.LayoutParams lp = web.getLayoutParams();
        lp.width = w;
        lp.height = h;
        web.setLayoutParams(lp);
    }

    /** Marks the current document as old, then navigates. Pollers ignore a document marked old. */
    private void go(String url) {
        web.evaluateJavascript("window.__stale=1;", null);
        web.loadUrl(url);
    }

    private static final String FORM_READY_JS = "(function(){"
            + "if(window.__stale||document.readyState==='loading')return 'busy';"
            + "return (document.getElementById('txtURN')&&document.getElementById('btnAdvSearch'))?'ready':'busy';})()";

    // The site's pages can keep "loading" forever on a slow connection (a stuck font or image),
    // so every step watches the page content itself instead of waiting for onPageFinished.
    private void waitForm(final int t) {
        if (t != token || mode != Mode.SEARCH_LOAD) return;
        web.evaluateJavascript(FORM_READY_JS, v -> {
            if (t != token || mode != Mode.SEARCH_LOAD) return;
            if (v != null && v.contains("ready")) submitSearch(t);
            else ui.postDelayed(() -> waitForm(t), 600);
        });
    }

    private void startSearch(String number) {
        ppo = number;
        showWait("Finding your card… Please wait", "உங்கள் அட்டையைத் தேடுகிறது… காத்திருக்கவும்");
        captureLayout();
        final int t = beginStep(Mode.SEARCH_LOAD);
        go(SEARCH_URL);
        ui.postDelayed(() -> waitForm(t), 800);
    }

    private static final String POLL_JS = "(function(){try{"
            + "if(window.__stale||document.readyState==='loading')return JSON.stringify({s:'busy'});"
            + "var links=document.querySelectorAll('a[href*=\"PayerMemberPolicyInfoDetails\"]');"
            + "var rows=[],trs=[];"
            + "for(var i=0;i<links.length;i++){var tr=links[i].closest('tr');if(!tr||trs.indexOf(tr)>=0)continue;trs.push(tr);"
            + "var c=[];for(var j=0;j<tr.cells.length;j++)c.push((tr.cells[j].innerText||'').trim());"
            + "rows.push({h:links[i].href,c:c});}"
            + "if(rows.length)return JSON.stringify({s:'ok',rows:rows});"
            + "var busy=false;try{busy=Sys.WebForms.PageRequestManager.getInstance().get_isInAsyncPostBack();}catch(e){}"
            + "if(busy)return JSON.stringify({s:'busy'});"
            + "if(!window.__em)return JSON.stringify({s:'fresh',form:!!document.getElementById('txtURN')});"
            + "return JSON.stringify({s:window.__done?'done':'idle',sub:!!window.__sub});"
            + "}catch(e){return JSON.stringify({s:'err',m:''+e});}})()";

    private void submitSearch(final int t) {
        String js = "(function(){try{"
                + "var f=document.getElementById('txtURN'),b=document.getElementById('btnAdvSearch');"
                + "if(!f||!b)return 'noform';"
                + "window.__em=1;"
                + "if(f.form)f.form.addEventListener('submit',function(){window.__sub=1;});"
                + "try{Sys.WebForms.PageRequestManager.getInstance().add_endRequest(function(){window.__done=1;});}catch(e){}"
                + "f.value=" + JSONObject.quote(ppo) + ";"
                + "b.click();return 'ok';"
                + "}catch(e){return 'err '+e;}})()";
        web.evaluateJavascript(js, v -> {
            if (t != token) return;
            Log.i(TAG, "submit -> " + v);
            if (v == null || !v.contains("ok")) {
                fail("search form not found: " + v);
                return;
            }
            mode = Mode.SEARCH_WAIT;
            ui.postDelayed(() -> pollSearch(t, 0, 0), 1200);
        });
    }

    private void pollSearch(final int t, final int n, final int emptyHits) {
        if (t != token || mode != Mode.SEARCH_WAIT) return;
        web.evaluateJavascript(POLL_JS, v -> {
            if (t != token || mode != Mode.SEARCH_WAIT) return;
            String s = "err";
            JSONObject o = null;
            try {
                o = new JSONObject((String) new JSONTokener(v).nextValue());
                s = o.optString("s", "err");
            } catch (Exception e) {
                Log.w(TAG, "poll parse " + v);
            }
            if (n % 4 == 0 || "ok".equals(s)) Log.i(TAG, "poll " + n + " " + s);
            if ("ok".equals(s)) {
                JSONArray rows = o.optJSONArray("rows");
                token++;                       // disarm the watchdog; we now wait for the person
                mode = Mode.IDLE;
                if (rows != null && rows.length() == 1) showConfirm(rows.optJSONObject(0));
                else showPick(rows);
                return;
            }
            // 'fresh' = the page reloaded after the search, 'done' = the in-page search finished:
            // either way the site answered and there are no rows. Ask twice to let the page settle.
            boolean answeredEmpty = "fresh".equals(s) || "done".equals(s);
            if (answeredEmpty && emptyHits >= 1) {
                showHome(bi("No card found for " + ppo + ". Please check the number.",
                        "இந்த எண்ணுக்கு அட்டை இல்லை. எண்ணைச் சரிபார்க்கவும்."));
                return;
            }
            ui.postDelayed(() -> pollSearch(t, n + 1, answeredEmpty ? emptyHits + 1 : 0), 800);
        });
    }

    private void startDetails() {
        if (detailsUrl == null || detailsUrl.isEmpty()) {
            fail("no details link");
            return;
        }
        showWait("Getting your card… Please wait", "உங்கள் அட்டையைப் பெறுகிறது… காத்திருக்கவும்");
        final int t = beginStep(Mode.DETAILS);
        go(detailsUrl);
        ui.postDelayed(() -> findCardLink(t, 0), 800);
    }

    private void findCardLink(final int t, final int attempt) {
        if (t != token || mode != Mode.DETAILS) return;
        String js = "(function(){if(window.__stale||document.readyState==='loading')return '';"
                + "var a=document.getElementById('a_card');"
                + "if(!a){var l=document.querySelectorAll('a');for(var i=0;i<l.length;i++){if(/NewPayerCard/i.test(l[i].href)){a=l[i];break;}}}"
                + "return a?a.href:'';})()";
        web.evaluateJavascript(js, v -> {
            if (t != token || mode != Mode.DETAILS) return;
            String url = "";
            try {
                url = (String) new JSONTokener(v).nextValue();
            } catch (Exception ignored) {
            }
            if (url != null && url.contains(CARD_PAGE)) {
                startCard(url);
            } else if (attempt < 80) {
                ui.postDelayed(() -> findCardLink(t, attempt + 1), 700);
            } else {
                fail("no Generate e-Card link");
            }
        });
    }

    private void startCard(String url) {
        if (onHome || mode == Mode.MANUAL) {
            showWait("Getting your card… Please wait", "உங்கள் அட்டையைப் பெறுகிறது… காத்திருக்கவும்");
        }
        captureLayout();
        final int t = beginStep(Mode.CARD);
        go(url);
        ui.postDelayed(() -> measureCard(t, 0, false), 900);
    }

    private static final String MEASURE_JS = "(function(){try{"
            + "if(window.__stale||document.readyState==='loading'||!/NewPayerCard/i.test(location.href))"
            + "return JSON.stringify({wait:1});"
            + "var d=document.documentElement,b=document.body||d,p=0;"
            + "for(var i=0;i<document.images.length;i++){if(!document.images[i].complete)p++;}"
            + "function r(sel){var e=document.querySelector(sel);if(!e)return null;var q=e.getBoundingClientRect();"
            + "return [q.left+window.scrollX,q.top+window.scrollY,q.width,q.height];}"
            + "return JSON.stringify({w:Math.max(d.scrollWidth,b.scrollWidth),h:Math.max(d.scrollHeight,b.scrollHeight),"
            + "p:p,t:(b.innerText||'').trim().length,f:r('.left-card'),b:r('.right-card')});"
            + "}catch(e){return JSON.stringify({err:''+e});}})()";

    private void measureCard(final int t, final int attempt, final boolean sized) {
        if (t != token || mode != Mode.CARD) return;
        web.evaluateJavascript(MEASURE_JS, v -> {
            if (t != token || mode != Mode.CARD) return;
            JSONObject o;
            try {
                o = new JSONObject((String) new JSONTokener(v).nextValue());
            } catch (Exception e) {
                fail("measure parse " + v);
                return;
            }
            if (o.has("wait")) {                               // card page not there yet
                ui.postDelayed(() -> measureCard(t, attempt, sized), 500);
                return;
            }
            Log.i(TAG, "measure " + o + " attempt " + attempt + " sized " + sized);
            if (o.optInt("p", 0) > 0 && attempt < 14) {       // images still downloading
                ui.postDelayed(() -> measureCard(t, attempt + 1, sized), 500);
                return;
            }
            if (o.optInt("t", 0) == 0) {
                fail("empty card page");
                return;
            }
            if (!sized) {
                // Make the view as tall as the page, let it repaint, then measure again and capture.
                int h = Math.max(START_CSS_HEIGHT, Math.min(o.optInt("h", 400), 5000));
                setWebSize(dp(CAPTURE_CSS_WIDTH), dp(h + 8));
                ui.postDelayed(() -> measureCard(t, 0, true), 900);
                return;
            }
            capture(o);
        });
    }

    private Bitmap grab(JSONArray r) {
        if (r == null || r.length() < 4) return null;
        float x = (float) r.optDouble(0) * density, y = (float) r.optDouble(1) * density;
        float w = (float) r.optDouble(2) * density, h = (float) r.optDouble(3) * density;
        if (w < 10 || h < 10) return null;
        float s = CARD_BITMAP_WIDTH / w;
        Bitmap bmp = Bitmap.createBitmap(CARD_BITMAP_WIDTH, Math.round(h * s), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        c.scale(s, s);
        c.translate(-x, -y);
        web.draw(c);
        return bmp;
    }

    private void capture(JSONObject o) {
        try {
            Bitmap f = grab(o.optJSONArray("f"));
            Bitmap b = grab(o.optJSONArray("b"));
            if (f == null) {            // page layout changed: keep the whole page as one picture
                JSONArray whole = new JSONArray();
                whole.put(0).put(0).put(Math.min(o.optInt("w", CAPTURE_CSS_WIDTH), CAPTURE_CSS_WIDTH)).put(o.optInt("h", 400));
                f = grab(whole);
                b = null;
            }
            if (f == null) {
                fail("capture produced nothing");
                return;
            }
            front = f;
            back = b;
            writePng(front, "front.png");
            File bf = new File(getFilesDir(), "back.png");
            if (back != null) writePng(back, "back.png");
            else if (bf.exists()) bf.delete();
            prefs.edit().putString("lastName", memberName).putString("lastPpo", ppo).apply();
            showCard();
        } catch (Throwable e) {
            Log.e(TAG, "capture", e);
            fail("capture " + e);
        }
    }

    private void writePng(Bitmap b, String name) throws Exception {
        try (FileOutputStream out = new FileOutputStream(new File(getFilesDir(), name))) {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        }
    }

    /** Fallback: show the real website so the steps can be done by hand. */
    private void openWebsite() {
        stopWork();
        onHome = false;
        hideKeyboard();
        mode = Mode.MANUAL;
        token++;
        lightweight = false;
        WebSettings s = web.getSettings();
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        setWebSize(-1, -1);
        screen.setVisibility(View.GONE);
        backBar.setVisibility(View.VISIBLE);
        memberName = "";
        web.loadUrl(SEARCH_URL);
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (mode == Mode.MANUAL && url.contains(CARD_PAGE)) {
                startCard(url);     // the person pressed "Generate e-Card" by hand
                return true;
            }
            return false;
        }

        /**
         * The search page pulls in ~170 ASP.NET helper scripts that the app does not need:
         * the search is a plain form post. Answering them with an empty file makes the page
         * load in a fraction of the time. Not applied when the person uses the website by hand.
         */
        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (!lightweight) return null;
            String path = request.getUrl().getPath();
            if (path != null && (path.contains("ScriptResource.axd") || path.contains("WebResource.axd"))) {
                return new WebResourceResponse("application/javascript", "utf-8",
                        new java.io.ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            Log.i(TAG, "finished " + mode + " " + (url == null ? "" : url.replaceAll("\\?.*", "")));
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) fail("net " + error.getDescription());
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel();
            fail("ssl " + error.getPrimaryError());
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (request.isForMainFrame()) fail("http " + response.getStatusCode());
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public boolean onJsAlert(WebView view, String url, String message, JsResult result) {
            if (mode == Mode.MANUAL) return false;     // let the person read the site's own message
            result.confirm();
            Log.w(TAG, "site alert: " + message);
            if (mode == Mode.SEARCH_WAIT || mode == Mode.SEARCH_LOAD) {
                showHome(bi("The website did not accept this number. Please check it.",
                        "இந்த எண்ணை இணையதளம் ஏற்கவில்லை. எண்ணைச் சரிபார்க்கவும்."));
            }
            return true;
        }
    }

    // ------------------------------------------------------------------ PDF

    private static final float MM = 72f / 25.4f;      // PostScript points per millimetre
    private static final int A4_W = 595, A4_H = 842;  // A4 portrait

    private void drawCard(Canvas c, Bitmap b, RectF where, Paint img, Paint cut) {
        c.drawBitmap(b, null, where, img);
        if (cut != null) c.drawRect(where, cut);
    }

    /** Fits a bitmap inside a box of boxW x boxH (points), centred horizontally at the given top. */
    private RectF fit(Bitmap b, float boxW, float boxH, float top) {
        float k = Math.min(boxW / b.getWidth(), boxH / b.getHeight());
        float w = b.getWidth() * k, h = b.getHeight() * k;
        float left = (A4_W - w) / 2f;
        return new RectF(left, top, left + w, top + h);
    }

    private void savePdf(boolean big) {
        if (front == null) return;
        PdfDocument pdf = new PdfDocument();
        Uri uri = null;
        try {
            PdfDocument.Page page = pdf.startPage(new PdfDocument.PageInfo.Builder(A4_W, A4_H, 1).create());
            Canvas c = page.getCanvas();
            c.drawColor(Color.WHITE);
            Paint img = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);

            if (big) {
                float boxW = A4_W - 2 * 12 * MM, boxH = (A4_H - 2 * 12 * MM - 8 * MM) / 2f;
                RectF f = fit(front, boxW, boxH, 12 * MM);
                drawCard(c, front, f, img, null);
                if (back != null) drawCard(c, back, fit(back, boxW, boxH, f.bottom + 8 * MM), img, null);
            } else {
                // Real ID-card size: each side fits inside 85.6 x 54 mm, with a thin dashed cut line.
                Paint cut = new Paint(Paint.ANTI_ALIAS_FLAG);
                cut.setStyle(Paint.Style.STROKE);
                cut.setStrokeWidth(0.5f);
                cut.setColor(0xFF808080);
                cut.setPathEffect(new DashPathEffect(new float[]{4, 3}, 0));
                RectF f = fit(front, 85.6f * MM, 54f * MM, 20 * MM);
                drawCard(c, front, f, img, cut);
                if (back != null) drawCard(c, back, fit(back, 85.6f * MM, 54f * MM, f.bottom + 10 * MM), img, cut);
            }
            pdf.finishPage(page);

            String safe = ppo.replaceAll("[^A-Za-z0-9]", "");
            String name = "NHIS-eCard" + (safe.isEmpty() ? "" : "-" + safe) + (big ? "-BigSize" : "-CardSize") + ".pdf";

            // Private copy for printing, public copy in Downloads for the person to find.
            File local = new File(getCacheDir(), name);
            try (FileOutputStream out = new FileOutputStream(local)) {
                pdf.writeTo(out);
            }
            // Saving the same card again replaces the earlier file instead of piling up "(1)", "(2)" copies.
            try {
                getContentResolver().delete(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                        MediaStore.Downloads.DISPLAY_NAME + "=?", new String[]{name});
            } catch (Exception ignored) {
            }
            ContentValues cv = new ContentValues();
            cv.put(MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(MediaStore.Downloads.MIME_TYPE, "application/pdf");
            cv.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            cv.put(MediaStore.Downloads.IS_PENDING, 1);
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IllegalStateException("Downloads folder not available");
            try (OutputStream out = getContentResolver().openOutputStream(uri);
                 InputStream in = new FileInputStream(local)) {
                if (out == null) throw new IllegalStateException("Cannot write to Downloads");
                copy(in, out);
            }
            cv.clear();
            cv.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, cv, null, null);

            lastPdf = uri;
            lastPdfFile = local;
            if (savedLabel != null) {
                savedLabel.setTextColor(GREEN);
                savedLabel.setText(bi("✔ Saved in Downloads: " + name, "✔ பதிவிறக்கங்களில் சேமிக்கப்பட்டது"));
                savedLabel.setVisibility(View.VISIBLE);
                afterSave.setVisibility(View.VISIBLE);
                screen.post(() -> screen.smoothScrollTo(0, savedLabel.getTop() - dp(12)));
            }
        } catch (Exception e) {
            Log.e(TAG, "savePdf", e);
            if (uri != null) {
                try {
                    getContentResolver().delete(uri, null, null);
                } catch (Exception ignored) {
                }
            }
            if (savedLabel != null) {
                savedLabel.setTextColor(RED);
                savedLabel.setText(bi("Could not save the PDF.", "PDF சேமிக்க முடியவில்லை."));
                savedLabel.setVisibility(View.VISIBLE);
            }
        } finally {
            pdf.close();
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private void note(String en, String ta) {
        if (savedLabel == null) return;
        savedLabel.setTextColor(RED);
        savedLabel.setText(bi(en, ta));
    }

    private void openPdf() {
        if (lastPdf == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(lastPdf, "application/pdf")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        } catch (Exception e) {
            note("No PDF viewer app on this phone. The file is in Downloads.",
                    "PDF பார்க்கும் செயலி இல்லை. கோப்பு பதிவிறக்கங்களில் உள்ளது.");
        }
    }

    private void sharePdf() {
        if (lastPdf == null) return;
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("application/pdf")
                .putExtra(Intent.EXTRA_STREAM, lastPdf)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(new Intent(send).setPackage("com.whatsapp"));
        } catch (Exception noWhatsApp) {
            try {
                startActivity(Intent.createChooser(send, "Share"));   // WhatsApp missing: any app
            } catch (Exception e) {
                note("No app to share with.", "பகிர செயலி இல்லை.");
            }
        }
    }

    private void printPdf() {
        if (lastPdfFile == null || !lastPdfFile.exists()) return;
        final File file = lastPdfFile;
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        if (pm == null) return;
        PrintDocumentAdapter adapter = new PrintDocumentAdapter() {
            @Override
            public void onLayout(PrintAttributes oldAttributes, PrintAttributes newAttributes,
                                 CancellationSignal signal, LayoutResultCallback callback, Bundle extras) {
                if (signal.isCanceled()) {
                    callback.onLayoutCancelled();
                    return;
                }
                callback.onLayoutFinished(new PrintDocumentInfo.Builder(file.getName())
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(1).build(), true);
            }

            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor destination,
                                CancellationSignal signal, WriteResultCallback callback) {
                try (InputStream in = new FileInputStream(file);
                     OutputStream out = new FileOutputStream(destination.getFileDescriptor())) {
                    copy(in, out);
                    callback.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                } catch (Exception e) {
                    callback.onWriteFailed(e.getMessage());
                }
            }
        };
        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .build();
        pm.print("NHIS eCard", adapter, attrs);
    }
}
