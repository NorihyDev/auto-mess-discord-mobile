package dev.norihy.discordmobile;

import android.app.Activity;
import android.content.*;
import androidx.test.platform.app.InstrumentationRegistry;
import org.junit.*;
import static org.junit.Assert.*;
import android.webkit.WebView;
import android.widget.*;
import java.lang.reflect.*;
import java.time.*;
import java.util.concurrent.atomic.AtomicReference;

/** Device regression: real WebView, synthetic HTTPS page, no Discord requests. */
public final class BackgroundWatchTest {
    private android.app.Instrumentation getInstrumentation() { return InstrumentationRegistry.getInstrumentation(); }
    private MainActivity activity;
    private WebView web;
    private static final String URL = "https://discord.com/channels/123/456";
    private Object field(String name) throws Exception {
        Field f = MainActivity.class.getDeclaredField(name); f.setAccessible(true); return f.get(activity);
    }
    private void ui(Runnable r) { getInstrumentation().runOnMainSync(r); }
    private void js(String script) throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        ui(() -> web.evaluateJavascript(script, result::set));
        long until = System.currentTimeMillis() + 10000;
        while (result.get() == null && System.currentTimeMillis() < until) Thread.sleep(50);
        assertNotNull("JavaScript callback must run in background", result.get());
    }
    @Before public void setUp() throws Exception {
        getInstrumentation().getUiAutomation().executeShellCommand("input keyevent 224").close();
        getInstrumentation().getUiAutomation().executeShellCommand("wm dismiss-keyguard").close();
        Thread.sleep(500);
        Context context = getInstrumentation().getTargetContext();
        context.getSharedPreferences("watcher", 0).edit().clear()
            .putString("url", URL).putString("message", "USB background test")
            .putString("time", LocalTime.now(ZoneOffset.UTC).toString().substring(0, 5))
            .putString("zone", "UTC").putString("before", "5").putString("after", "15").commit();
        activity = (MainActivity)getInstrumentation().startActivitySync(
            new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        web = (WebView)field("web");
        ui(() -> web.loadDataWithBaseURL(URL,
            "<html><body><main><ul id='messages'></ul><div role='textbox' contenteditable='true' data-slate-editor='true' style='display:none'></div></main>"
            + "<script>window.sent=0;document.addEventListener('keydown',e=>{if(e.key==='Enter'&&!e.defaultPrevented){window.sent++;document.querySelector('#messages').innerHTML='<li id=chat-messages-1><div id=message-content-1>'+document.querySelector('[role=textbox]').innerText+'</div></li>';}});</script></body></html>",
            "text/html", "UTF-8", URL));
        Thread.sleep(1500);
        ui(() -> { try { ((Button)field("arm")).performClick(); } catch(Exception e) { throw new RuntimeException(e); } });
        Thread.sleep(1000);
        assertEquals("Status=" + ((TextView)field("status")).getText(), "armed", field("phase"));
    }
    @Test public void testSendAfterHome() throws Exception {
        ui(() -> activity.moveTaskToBack(true));
        checkBackgroundSend();
    }
    @Test public void testSendWithScreenOff() throws Exception {
        try (android.os.ParcelFileDescriptor ignored = getInstrumentation().getUiAutomation()
                .executeShellCommand("input keyevent 223")) {
            checkBackgroundSend();
        } finally {
            getInstrumentation().getUiAutomation().executeShellCommand("input keyevent 224").close();
        }
    }
    private void checkBackgroundSend() throws Exception {
        Thread.sleep(3000);
        assertEquals(Boolean.FALSE, field("foreground"));
        assertEquals("armed", field("phase"));
        js("document.querySelector('[role=textbox]').style.display='block'");
        long until = System.currentTimeMillis() + 15000;
        while (!"idle".equals(field("phase")) && System.currentTimeMillis() < until) Thread.sleep(100);
        assertEquals("idle", field("phase"));
        AtomicReference<String> sent = new AtomicReference<>();
        ui(() -> web.evaluateJavascript("window.sent", sent::set));
        Thread.sleep(500);
        assertEquals("One Enter in background; status=" + ((TextView)field("status")).getText(), "1", sent.get());
        assertTrue(((TextView)field("status")).getText().toString().startsWith("Message observ"));
        assertEquals("confirmed", getInstrumentation().getTargetContext().getSharedPreferences("watcher", 0)
            .getString("attempt:" + URL + ":" + LocalDate.now(ZoneOffset.UTC), ""));
    }
    @Test public void testNotificationStop() throws Exception {
        ui(() -> activity.moveTaskToBack(true)); Thread.sleep(1000);
        getInstrumentation().getTargetContext().startService(new Intent(
            getInstrumentation().getTargetContext(), WatchService.class).setAction("stop"));
        Thread.sleep(1000); assertEquals("idle", field("phase"));
        js("document.querySelector('[role=textbox]').style.display='block'");
        assertFalse(getInstrumentation().getTargetContext().getSharedPreferences("watcher", 0)
            .contains("attempt:" + URL + ":" + LocalDate.now(ZoneOffset.UTC)));
    }
    @Test public void testServiceDeadlineStopsWithoutReservation() throws Exception {
        ui(() -> activity.moveTaskToBack(true));
        getInstrumentation().getTargetContext().startService(new Intent(
            getInstrumentation().getTargetContext(), WatchService.class)
            .putExtra("deadline", System.currentTimeMillis() + 1000));
        Thread.sleep(2000);
        assertEquals("idle", field("phase"));
        assertFalse(getInstrumentation().getTargetContext().getSharedPreferences("watcher", 0)
            .contains("attempt:" + URL + ":" + LocalDate.now(ZoneOffset.UTC)));
    }
    @After public void tearDown() throws Exception {
        if (activity != null) ui(() -> activity.finish());
        Thread.sleep(500);
    }
}
