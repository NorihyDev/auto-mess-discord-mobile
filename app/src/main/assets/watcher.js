(function () {
    'use strict';
    if (window.DMA) return;
    const selector = 'main [role="textbox"][contenteditable="true"][data-slate-editor="true"]';
    const rows = 'li[id^="chat-messages-"]';
    const normalize = text => text.replace(/[\uFEFF\u200B]/g, '').trim().replace(/\s+/g, ' ');
    let config, observer, fallback, timer, editor, previous, ready = false, preparationArmed = false, started, submitted = false;
    let inputGuard, enterGuard;
    const emit = (type, extra = {}) => DiscordMobile.postMessage(JSON.stringify({token: config.token, type, ...extra}));
    const sameChannel = () => location.href.replace(/\/$/, '') === config.url;
    function current() {
        if (!sameChannel()) return null;
        const all = document.querySelectorAll(selector);
        if (all.length !== 1) return null;
        const el = all[0], style = getComputedStyle(el);
        if (!el.isContentEditable || el.closest('[aria-disabled="true"], [inert]') ||
            el.matches(':disabled') || ['hidden', 'collapse'].includes(style.visibility) ||
            style.display === 'none' || !el.getClientRects().length) return null;
        return el;
    }
    function stop() {
        observer?.disconnect();
        clearInterval(fallback); clearTimeout(timer);
        if (inputGuard) document.removeEventListener('beforeinput', inputGuard, true);
        if (enterGuard) document.removeEventListener('keydown', enterGuard, true);
        inputGuard = enterGuard = null;
        ready = preparationArmed = false;
    }
    function validEmpty() {
        return Date.now() < config.deadline && current() === editor &&
            document.activeElement === editor && normalize(editor.innerText) === '';
    }
    function validPrepared() {
        return Date.now() < config.deadline && current() === editor &&
            document.activeElement === editor && normalize(editor.innerText) === normalize(config.message);
    }
    function check() {
        if (ready) return;
        if (Date.now() >= config.deadline) { stop(); emit('expired'); return; }
        if (!sameChannel()) { stop(); emit('wrong_channel'); return; }
        editor = current();
        if (!editor) return;
        started = performance.now();
        if (config.preview) { stop(); emit('preview'); return; }
        if (normalize(editor.innerText)) { stop(); emit('draft'); return; }
        editor.focus({preventScroll: true});
        if (!validEmpty()) { stop(); emit('prepare_failed'); return; }
        const selection = getSelection();
        if (!selection) { stop(); emit('prepare_failed'); return; }
        selection.selectAllChildren(editor);
        previous = new Set(Array.from(document.querySelectorAll(rows), row => row.id));
        inputGuard = event => {
            if (event.target !== editor || !validEmpty() ||
                !editor.contains(selection.anchorNode) || !editor.contains(selection.focusNode)) event.preventDefault();
        };
        document.addEventListener('beforeinput', inputGuard, {capture: true, once: true});
        ready = true;
        observer.disconnect(); clearInterval(fallback); clearTimeout(timer);
        // Arm the draft observer before notifying Android, so native text input
        // can start immediately without an extra evaluateJavascript round trip.
        if (!window.DMA.beginNativePreparation(config.token)) { stop(); emit('prepare_failed'); return; }
        emit('ready');
    }
    function confirmation() {
        if (!sameChannel()) { stop(); emit('uncertain'); return; }
        for (const row of document.querySelectorAll(rows)) {
            if (previous.has(row.id)) continue;
            const content = row.querySelectorAll('[id^="message-content-"]');
            if (content.length === 1 && normalize(content[0].innerText) === normalize(config.message)) {
                const elapsed = performance.now() - started;
                stop(); emit('confirmed', {elapsed}); return;
            }
        }
    }
    window.DMA = {
        stop,
        validEmpty,
        validPrepared,
        beginNativePreparation(token) {
            if (token !== config.token || !ready || submitted || !validEmpty()) return false;
            if (preparationArmed) return true;
            preparationArmed = true;
            const checkPrepared = () => {
                if (!ready) return;
                if (!sameChannel()) { stop(); emit('wrong_channel'); return; }
                if (Date.now() >= config.deadline) { stop(); emit('expired'); return; }
                if (!validPrepared()) return;
                observer.disconnect(); clearInterval(fallback); clearTimeout(timer);
                // Arm the Enter guard while still on the page. Android persists
                // the daily reservation before it dispatches the key.
                if (!window.DMA.authorizeNativeSend(config.token)) { stop(); emit('prepare_failed'); return; }
                emit('prepared');
            };
            observer = new MutationObserver(checkPrepared);
            observer.observe(editor, {subtree: true, childList: true, characterData: true});
            fallback = setInterval(checkPrepared, 1);
            timer = setTimeout(() => { stop(); emit('prepare_failed'); }, 2000);
            return true;
        },
        authorizeNativeSend(token) {
            // Native text input updates Discord's editor state. Reserve before real Enter.
            if (token !== config.token || !ready || submitted || !window.DMA.beforeSend()) return false;
            submitted = true;
            return true;
        },
        install(options) {
            stop(); config = options; submitted = false;
            observer = new MutationObserver(check);
            observer.observe(document, {subtree: true, childList: true, attributes: true, characterData: true});
            // Best-effort CSS-only fallback. Chromium may clamp/throttle timers.
            fallback = setInterval(check, 1);
            timer = setTimeout(check, Math.max(1, config.deadline - Date.now()));
            check();
        },
        beforeSend() {
            if (!validPrepared()) return false;
            enterGuard = event => {
                if (event.key !== 'Enter') return;
                document.removeEventListener('keydown', enterGuard, true);
                if (event.target !== editor || !validPrepared()) {
                    event.preventDefault(); event.stopImmediatePropagation();
                } else {
                    emit('sent', {elapsed: performance.now() - started});
                }
            };
            document.addEventListener('keydown', enterGuard, {capture: true});
            observer = new MutationObserver(confirmation);
            observer.observe(document, {subtree: true, childList: true, characterData: true});
            timer = setTimeout(() => { stop(); emit('uncertain'); }, 10000);
            return true;
        }
    };
})();
