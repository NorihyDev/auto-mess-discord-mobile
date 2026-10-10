"""Real Chromium DOM/keyboard regression tests; all network requests are intercepted."""
import json
from pathlib import Path
import unittest
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parents[1]
URL = "https://discord.com/channels/123/456"
HTML = '''<!doctype html><main><ul id="messages"></ul>
<div role="textbox" contenteditable="true" data-slate-editor="true" style="display:none"></div></main>'''


class WatcherTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playwright = sync_playwright().start()
        cls.browser = cls.playwright.chromium.launch()

    @classmethod
    def tearDownClass(cls):
        cls.browser.close()
        cls.playwright.stop()

    def setUp(self):
        self.page = self.browser.new_page()
        self.page.route("**/*", lambda route: route.fulfill(content_type="text/html", body=HTML))
        self.page.goto(URL)
        self.page.evaluate("window.events=[]; window.DiscordMobile={postMessage: value => events.push(JSON.parse(value))}")
        self.page.evaluate((ROOT / "app/src/main/assets/watcher.js").read_text(encoding="utf-8"))

    def tearDown(self):
        self.page.close()

    def install(self, preview=False, deadline=None):
        self.page.evaluate("options => DMA.install({...options,deadline: options.deadline ?? Date.now()+10000})", dict(url=URL, message="Check-in ✅", token="session-one", preview=preview, deadline=deadline))

    def open_editor(self):
        self.page.evaluate("document.querySelector('[role=textbox]').style.display='block'")

    def event(self, kind):
        self.page.wait_for_function("kind => events.some(e=>e.type===kind)", arg=kind)

    def test_mutation_opening_and_native_keyboard_confirmation(self):
        self.install(); self.open_editor(); self.event("ready")
        self.assertTrue(self.page.evaluate("DMA.validEmpty()"))
        self.page.keyboard.insert_text("Check-in ✅")
        self.assertTrue(self.page.evaluate("DMA.beforeSend()"))
        self.page.evaluate("""document.querySelector('[role=textbox]').addEventListener('keydown', e => {
            if(e.key==='Enter' && !e.defaultPrevented) {
                document.querySelector('#messages').innerHTML='<li id="chat-messages-1"><div id="message-content-1">Check-in ✅</div></li>';
            }
        })""")
        self.page.keyboard.press("Enter"); self.event("confirmed")
        self.assertEqual("session-one", self.page.evaluate("events[0].token"))

    def test_preview_never_focuses_or_prepares_a_draft(self):
        self.install(preview=True); self.open_editor(); self.event("preview")
        self.assertEqual("", self.page.locator("[role=textbox]").inner_text())
        self.assertFalse(self.page.evaluate("document.activeElement.matches('[role=textbox]')"))
        self.assertEqual(["preview"], self.page.evaluate("events.map(e=>e.type)"))

    def test_native_input_updates_editor_state_and_sends_once(self):
        # Model an editor that handles beforeinput, and rejects synthetic Enter.
        self.page.evaluate("""window.model='';window.sends=0;
            const el=document.querySelector('[role=textbox]');
            el.addEventListener('beforeinput',e=>{
                if(e.inputType==='insertText'&&!e.defaultPrevented) {
                    model+=e.data;e.preventDefault();el.textContent=model;
                }
            });
            el.addEventListener('keydown',e=>{
                if(e.key==='Enter'&&e.isTrusted&&!e.defaultPrevented&&model) {
                    sends++;document.querySelector('#messages').innerHTML=
                    '<li id="chat-messages-new"><div id="message-content-new">'+model+'</div></li>';
                }
            });""")
        self.install(); self.open_editor(); self.event("ready")
        self.assertEqual("", self.page.locator("[role=textbox]").inner_text())
        self.assertFalse(self.page.evaluate("DMA.authorizeNativeSend('session-one')"))
        self.page.keyboard.insert_text("Check-in \u2705")
        self.assertTrue(self.page.evaluate("DMA.validPrepared()"))
        self.assertFalse(self.page.evaluate("DMA.authorizeNativeSend('old-token')"))
        self.assertTrue(self.page.evaluate("DMA.authorizeNativeSend('session-one')"))
        self.assertFalse(self.page.evaluate("DMA.authorizeNativeSend('session-one')"))
        self.page.keyboard.press("Enter")
        self.event("confirmed")
        self.assertEqual(1, self.page.evaluate("sends"))

    def test_async_native_preparation_waits_for_input_and_stops_on_cancel(self):
        self.install(); self.open_editor(); self.event("ready")
        self.assertFalse(self.page.evaluate("DMA.beginNativePreparation('old-token')"))
        self.assertTrue(self.page.evaluate("DMA.beginNativePreparation('session-one')"))
        self.assertEqual(["ready"], self.page.evaluate("events.map(e=>e.type)"))
        self.page.keyboard.insert_text("Check-in \u2705")
        self.event("prepared")
        self.assertEqual(["ready", "prepared"], self.page.evaluate("events.map(e=>e.type)"))
        self.page.evaluate("DMA.stop()")
        self.assertFalse(self.page.evaluate("DMA.authorizeNativeSend('session-one')"))

    def test_native_send_rejects_changed_channel(self):
        self.install(); self.open_editor(); self.event("ready")
        self.page.keyboard.insert_text("Check-in \u2705")
        self.page.evaluate("history.pushState({},'', '/channels/123/999')")
        self.assertFalse(self.page.evaluate("DMA.authorizeNativeSend('session-one')"))

    def test_native_send_rechecks_modified_draft_and_cancellation(self):
        self.install(); self.open_editor(); self.event("ready")
        self.page.keyboard.insert_text("Check-in \u2705")
        self.page.evaluate("document.querySelector('[role=textbox]').textContent='Changed'")
        self.assertFalse(self.page.evaluate("DMA.authorizeNativeSend('session-one')"))
        self.page.evaluate("DMA.stop()")
        self.assertFalse(self.page.evaluate("DMA.authorizeNativeSend('session-one')"))
        self.assertEqual(["ready"], self.page.evaluate("events.map(e=>e.type)"))

    def test_native_sent_reports_timing_only_for_authorized_session(self):
        self.install(); self.open_editor(); self.event("ready")
        self.page.evaluate("DMA.nativeSent('session-one')")
        self.assertEqual(["ready"], self.page.evaluate("events.map(e=>e.type)"))
        self.page.keyboard.insert_text("Check-in \u2705")
        self.assertTrue(self.page.evaluate("DMA.authorizeNativeSend('session-one')"))
        self.page.evaluate("DMA.nativeSent('old-token');DMA.nativeSent('session-one')")
        self.assertEqual(["ready", "sent"], self.page.evaluate("events.map(e=>e.type)"))
        self.assertGreaterEqual(self.page.evaluate("events[1].elapsed"), 0)

    def test_existing_draft_preserved(self):
        self.page.evaluate("document.querySelector('[role=textbox]').textContent='Do not overwrite'")
        self.install(); self.open_editor(); self.event("draft")
        self.assertEqual("Do not overwrite", self.page.locator("[role=textbox]").inner_text())

    def test_channel_change_stops_watcher(self):
        self.install(); self.page.evaluate("history.pushState({},'', '/channels/123/999')"); self.event("wrong_channel")
        self.open_editor(); self.page.wait_for_timeout(60)
        self.assertNotIn("ready", self.page.evaluate("events.map(e=>e.type)"))

    def test_cancel_removes_observer_and_input_guard(self):
        self.install(); self.page.evaluate("DMA.stop()"); self.open_editor(); self.page.wait_for_timeout(60)
        self.assertEqual([], self.page.evaluate("events"))

    def test_expired_window_never_prepares(self):
        self.install(deadline=0); self.event("expired"); self.open_editor()
        self.assertEqual(["expired"], self.page.evaluate("events.map(e=>e.type)"))

    def test_disabled_editor_and_multiple_editors_wait(self):
        self.page.evaluate("document.querySelector('[role=textbox]').setAttribute('aria-disabled','true')")
        self.install(); self.open_editor(); self.page.wait_for_timeout(60)
        self.assertEqual([], self.page.evaluate("events"))
        self.page.evaluate("let el=document.querySelector('[role=textbox]'); el.removeAttribute('aria-disabled'); el.after(el.cloneNode(true))")
        self.page.wait_for_timeout(60); self.assertEqual([], self.page.evaluate("events"))
        self.page.evaluate("document.querySelectorAll('[role=textbox]')[1].remove()"); self.event("ready")

    def test_focus_change_blocks_native_insertion(self):
        self.install(); self.open_editor(); self.event("ready")
        self.page.evaluate("document.querySelector('[role=textbox]').blur()")
        self.assertFalse(self.page.evaluate("DMA.validEmpty()"))

    def test_channel_change_before_enter_cancels_key(self):
        self.install(); self.open_editor(); self.event("ready"); self.page.keyboard.insert_text("Check-in ✅")
        self.assertTrue(self.page.evaluate("DMA.beforeSend()"))
        self.page.evaluate("history.pushState({},'', '/channels/123/999'); window.sent=false; document.addEventListener('keydown', e=>{if(e.key==='Enter') sent=true})")
        self.page.keyboard.press("Enter"); self.assertFalse(self.page.evaluate("sent"))

    def test_unrelated_key_does_not_remove_enter_guard(self):
        self.install(); self.open_editor(); self.event("ready"); self.page.keyboard.insert_text("Check-in ✅")
        self.assertTrue(self.page.evaluate("DMA.beforeSend()"))
        self.page.keyboard.press("Shift")
        self.page.evaluate("history.pushState({},'', '/channels/123/999'); window.sent=false; document.addEventListener('keydown', e=>{if(e.key==='Enter') sent=true})")
        self.page.keyboard.press("Enter"); self.assertFalse(self.page.evaluate("sent"))

    def test_old_matching_message_does_not_confirm(self):
        self.page.evaluate("document.querySelector('#messages').innerHTML='<li id=chat-messages-old><div id=message-content-old>Check-in ✅</div></li>'")
        self.install(); self.open_editor(); self.event("ready"); self.page.keyboard.insert_text("Check-in ✅")
        self.assertTrue(self.page.evaluate("DMA.beforeSend()"))
        self.page.evaluate("document.querySelector('#messages').append(document.createElement('span'))")
        self.page.wait_for_timeout(60)
        self.assertNotIn("confirmed", self.page.evaluate("events.map(e=>e.type)"))

    def test_css_only_visibility_change_uses_fallback(self):
        self.page.evaluate("document.querySelector('[role=textbox]').removeAttribute('style'); let style=document.createElement('style'); style.textContent='[role=textbox]{visibility:hidden}'; document.head.append(style)")
        self.install(); self.page.wait_for_timeout(30)
        self.assertEqual([], self.page.evaluate("events"))
        self.page.evaluate("document.styleSheets[0].deleteRule(0)")
        self.event("ready")

    def test_guard_preserves_draft_created_after_detection(self):
        self.install(); self.open_editor(); self.event("ready")
        self.page.evaluate("document.querySelector('[role=textbox]').textContent='New draft'")
        self.page.keyboard.insert_text("Check-in ✅")
        self.assertEqual("New draft", self.page.locator("[role=textbox]").inner_text())
        self.assertFalse(self.page.evaluate("DMA.beforeSend()"))

    def test_multiline_and_emoji_can_be_verified(self):
        self.page.evaluate("DMA.install({url:location.href,message:'Hello ✅\\nSecond line',token:'multiline',deadline:Date.now()+10000,preview:false})")
        self.open_editor(); self.event("ready")
        self.page.keyboard.insert_text("Hello ✅\nSecond line")
        self.assertTrue(self.page.evaluate("DMA.beforeSend()"))

    def test_reinstall_drops_previous_token_and_observer(self):
        self.install()
        self.page.evaluate("DMA.install({url:location.href,message:'x',token:'replacement',deadline:Date.now()+10000,preview:true})")
        self.open_editor(); self.event("preview")
        self.assertEqual(["replacement"], self.page.evaluate("events.map(e=>e.token)"))


if __name__ == "__main__":
    unittest.main()
