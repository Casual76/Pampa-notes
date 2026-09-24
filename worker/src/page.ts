/**
 * La pagina che un compagno apre: titolo, appunti, trascrizione, e un lettore con le parole che si
 * accendono mentre vengono dette. Niente da installare, niente account, nessuna risorsa esterna:
 * un file solo, che funziona anche sul computer di scuola.
 *
 * I dati arrivano da `/s/<token>/data`; l'audio da `/s/<token>/audio/<partId>`, una parte alla
 * volta. Il lettore ragiona in tempo di sessione, come `SessionPlayer` nell'app: dentro ci sono N
 * file e un indice, ma chi tocca la parola del minuto quaranta sente il minuto quaranta della
 * lezione. `locate` qui sotto e' la stessa funzione di `SessionAssembler.locate`, in JavaScript.
 *
 * Le parole: `inizio,fine,testo` per riga, tempi relativi all'inizio del segmento nella sessione
 * (vedi `WordTimings.encode`, che li salva relativi all'inizio del segmento nella parte: la
 * differenza fra i due e' lo scarto della parte, e `sessionStartMs` lo ha gia' dentro).
 *
 * Tutto il file e' un template di TypeScript: ogni `\` del JavaScript qui dentro va scritto doppio.
 * Un `\d` singolo arriva al browser come `d`, e la data della lezione non si riconosceva mai.
 */

export function pageHtml(): string {
  return `<!doctype html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>Pampa Notes</title>
<style>
  :root { color-scheme: light dark; --bg:#f6f4fb; --fg:#1b1a22; --muted:#6b6879; --card:#ffffff; --line:#e3e0ee; --accent:#7c3aed; --hl:#e9d8fd; --veil:.42; }
  @media (prefers-color-scheme: dark) { :root { --bg:#141320; --fg:#ece9f6; --muted:#a19dad; --card:#1e1c2b; --line:#2c2a3a; --accent:#b08cff; --hl:#3b2a63; } }
  * { box-sizing: border-box; }
  body { margin:0; background:var(--bg); color:var(--fg); font: 17px/1.55 -apple-system, "Segoe UI", Roboto, "Helvetica Neue", sans-serif; }
  main { max-width: 740px; margin: 0 auto; padding: 24px 16px 96px; }
  header h1 { font-size: 30px; line-height: 1.15; margin: 0 0 6px; letter-spacing: -.01em; }
  header .meta { color: var(--muted); font-size: 15px; }
  h2 { font-size: 20px; margin: 36px 0 12px; }
  h3 { font-size: 17px; margin: 24px 0 8px; }
  .card { background: var(--card); border: 1px solid var(--line); border-radius: 18px; padding: 16px 18px; }
  .notes p { margin: 0 0 12px; } .notes ul { padding-left: 22px; } .notes blockquote { margin: 0 0 12px; padding-left: 12px; border-left: 3px solid var(--line); color: var(--muted); }
  .notes h2, .notes h3, .notes h4 { margin: 14px 0 6px; }
  .player { position: sticky; top: 8px; z-index: 2; display: flex; gap: 10px; align-items: center; background: var(--card); border: 1px solid var(--line); border-radius: 999px; padding: 8px 12px; margin: 10px 0 14px; box-shadow: 0 6px 24px rgba(0,0,0,.08); }
  .player button { border: 0; background: transparent; color: var(--fg); font: inherit; cursor: pointer; padding: 6px 8px; border-radius: 999px; }
  .player button.play { background: var(--accent); color: #fff; width: 44px; height: 44px; font-size: 18px; }
  .player .time { font-variant-numeric: tabular-nums; color: var(--muted); font-size: 14px; min-width: 96px; }
  .player .bar { flex: 1; height: 6px; background: var(--line); border-radius: 999px; position: relative; cursor: pointer; }
  .player .bar > i { position: absolute; left: 0; top: 0; bottom: 0; width: 0; background: var(--accent); border-radius: 999px; }
  .tabs { display: flex; gap: 6px; margin: 8px 0 12px; }
  .tabs button { border: 1px solid var(--line); background: var(--card); color: var(--fg); font: inherit; font-size: 14px; padding: 6px 14px; border-radius: 999px; cursor: pointer; }
  .tabs button[aria-selected="true"] { background: var(--fg); color: var(--bg); border-color: var(--fg); }
  .para { display: flex; gap: 12px; margin: 0 0 12px; }
  .para .t { flex: 0 0 52px; color: var(--muted); font-size: 13px; font-variant-numeric: tabular-nums; padding-top: 4px; cursor: pointer; }
  .para .x { flex: 1; }
  .w { opacity: var(--veil); transition: opacity .12s; border-radius: 4px; cursor: pointer; }
  .w.done, .w.now { opacity: 1; }
  .w.now { background: var(--hl); }
  .static .w { opacity: 1; cursor: default; }
  .note { color: var(--muted); font-size: 14px; margin: 8px 0 0; }
  .silence { color: var(--muted); font-size: 13px; text-align: center; margin: 4px 0 16px; cursor: pointer; }
  .missing { color: var(--muted); font-size: 15px; padding: 12px 0; }
  footer { margin-top: 48px; color: var(--muted); font-size: 13px; text-align: center; }
  .tags span { display: inline-block; background: var(--hl); border-radius: 999px; padding: 2px 10px; font-size: 13px; margin: 6px 6px 0 0; }
</style>
</head>
<body>
<main id="root"><header><h1>…</h1><div class="meta">Sto aprendo la nota</div></header></main>
<script>
(function () {
  var token = location.pathname.split('/')[2];
  var root = document.getElementById('root');

  function esc(s) { return String(s == null ? '' : s).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }
  function inline(s) {
    return esc(s)
      .replace(/\\*\\*(.+?)\\*\\*/g, '<b>$1</b>')
      .replace(/(^|[^*])\\*(?!\\*)([^*]+?)\\*(?!\\*)/g, '$1<i>$2</i>')
      .replace(/\`([^\`]+)\`/g, '<code>$1</code>');
  }
  // Un Markdown minimo: titoli, elenchi, citazioni, grassetto e corsivo. Il resto e' testo.
  function md(src) {
    var out = [], para = [], list = false;
    function closePara() { if (para.length) { out.push('<p>' + inline(para.join(' ')) + '</p>'); para = []; } }
    function closeList() { if (list) { out.push('</ul>'); list = false; } }
    var lines = String(src || '').split(/\\r?\\n/);
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i].replace(/\\s+$/, '');
      if (!line.trim()) { closePara(); closeList(); continue; }
      var h = /^(#{1,6})\\s+(.*)$/.exec(line);
      if (h) { closePara(); closeList(); var n = Math.min(h[1].length + 1, 4); out.push('<h' + n + '>' + inline(h[2]) + '</h' + n + '>'); continue; }
      var li = /^\\s*[-*]\\s+(.*)$/.exec(line);
      if (li) { closePara(); if (!list) { out.push('<ul>'); list = true; } out.push('<li>' + inline(li[1]) + '</li>'); continue; }
      if (/^>\\s?/.test(line)) { closePara(); closeList(); out.push('<blockquote>' + inline(line.replace(/^>\\s?/, '')) + '</blockquote>'); continue; }
      closeList(); para.push(line.trim());
    }
    closePara(); closeList();
    return out.join('\\n');
  }
  function fmt(ms) {
    var s = Math.max(0, Math.floor(ms / 1000)), h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), r = s % 60;
    var mm = (h ? (m < 10 ? '0' : '') : '') + m, ss = (r < 10 ? '0' : '') + r;
    return (h ? h + ':' : '') + mm + ':' + ss;
  }
  function el(tag, cls, html) { var e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; }

  // Le parole di un segmento, in tempo di sessione. Senza parole: il segmento intero e' una parola.
  function wordsOf(seg) {
    var out = [];
    if (seg.words) {
      var lines = seg.words.split('\\n');
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i]; if (!line) continue;
        var a = line.indexOf(','), b = line.indexOf(',', a + 1);
        if (a <= 0 || b <= a) continue;
        var s = Number(line.slice(0, a)), e = Number(line.slice(a + 1, b)), t = line.slice(b + 1);
        if (!t.trim() || !isFinite(s) || !isFinite(e)) continue;
        out.push({ s: seg.sessionStartMs + s, e: seg.sessionStartMs + e, t: t });
      }
    }
    if (!out.length) out.push({ s: seg.sessionStartMs, e: seg.sessionEndMs, t: seg.text });
    return out;
  }
  // Paragrafi: si spezza su una pausa lunga o quando il testo si fa lungo, come nell'app.
  function paragraphs(segments) {
    var out = [], cur = null, chars = 0;
    for (var i = 0; i < segments.length; i++) {
      var seg = segments[i];
      var gap = cur ? seg.sessionStartMs - cur.end : 0;
      if (!cur || gap > 2000 || chars > 600) {
        // Un minuto o piu' senza parlato si dice, come nell'app: sedici minuti di silenzio non sono
        // la pausa fra due frasi.
        cur = { start: seg.sessionStartMs, end: seg.sessionEndMs, segs: [], silence: gap >= 60000 ? gap : 0 };
        out.push(cur); chars = 0;
      }
      cur.segs.push(seg); cur.end = seg.sessionEndMs; chars += seg.text.length;
    }
    return out;
  }

  function silenceText(ms) {
    var min = Math.round(ms / 60000), h = Math.floor(min / 60), m = min % 60;
    var d = h ? (m ? h + ' h ' + m + ' min' : h + ' h') : Math.max(1, min) + ' min';
    return '— ' + d + ' di silenzio —';
  }

  function locate(parts, ms) {
    var off = 0;
    for (var i = 0; i < parts.length; i++) { if (ms < off + parts[i].durationMs) return { i: i, offset: ms - off }; off += parts[i].durationMs; }
    var last = parts.length - 1; return { i: last, offset: parts[last].durationMs };
  }

  function buildSession(session, container) {
    var parts = session.parts, total = 0, offsets = [];
    for (var i = 0; i < parts.length; i++) { offsets.push(total); total += parts[i].durationMs; }
    var playable = parts.length > 0 && parts.every(function (p) { return p.available; });

    // --- il lettore ---
    var audio = null, index = 0, pendingSeek = null, words = [], cur = -1, raf = 0, bar, fill, time, playBtn, speedBtn, speeds = [1, 1.25, 1.5, 2], speed = 0;
    function sessionMs() { return audio ? offsets[index] + audio.currentTime * 1000 : 0; }
    function load(i) { index = i; audio.src = '/s/' + token + '/audio/' + encodeURIComponent(parts[i].id); audio.load(); }
    function seek(ms) {
      if (!audio) return;
      var at = locate(parts, Math.max(0, Math.min(ms, total)));
      var wasPlaying = !audio.paused;
      if (at.i !== index) { load(at.i); pendingSeek = at.offset / 1000; if (wasPlaying) audio.play(); }
      else if (audio.readyState >= 1) { audio.currentTime = at.offset / 1000; }
      else { pendingSeek = at.offset / 1000; }
      tick();
    }
    function mark(ms) {
      // La parola in corso: l'ultima cominciata. Le precedenti sono piene, le successive velate.
      var lo = 0, hi = words.length - 1, found = -1;
      while (lo <= hi) { var mid = (lo + hi) >> 1; if (words[mid].s <= ms) { found = mid; lo = mid + 1; } else hi = mid - 1; }
      if (found === cur) return;
      if (found > cur) { for (var i = Math.max(cur, 0); i < found; i++) { words[i].el.classList.add('done'); words[i].el.classList.remove('now'); } }
      else { for (var j = found + 1; j <= cur && j < words.length; j++) { words[j].el.classList.remove('done', 'now'); } }
      if (cur >= 0 && cur < words.length) words[cur].el.classList.remove('now');
      if (found >= 0) { words[found].el.classList.add('now'); words[found].el.classList.remove('done'); }
      cur = found;
    }
    function tick() {
      if (!audio) return;
      var ms = sessionMs();
      fill.style.width = (total ? (100 * ms / total) : 0) + '%';
      time.textContent = fmt(ms) + ' / ' + fmt(total);
      mark(ms);
      if (!audio.paused) raf = requestAnimationFrame(tick);
    }
    if (playable) {
      audio = new Audio(); audio.preload = 'metadata';
      var player = el('div', 'player');
      var back = el('button', null, '−15'); back.title = 'Indietro di 15 secondi';
      playBtn = el('button', 'play', '▶'); playBtn.setAttribute('aria-label', 'Riproduci');
      var fwd = el('button', null, '+15'); fwd.title = 'Avanti di 15 secondi';
      time = el('span', 'time', fmt(0) + ' / ' + fmt(total));
      bar = el('div', 'bar'); fill = el('i'); bar.appendChild(fill);
      speedBtn = el('button', null, '1×');
      player.appendChild(back); player.appendChild(playBtn); player.appendChild(fwd); player.appendChild(time); player.appendChild(bar); player.appendChild(speedBtn);
      // Nell'albero, anche se non si vede: cosi' chi ispeziona la pagina trova il lettore.
      audio.style.display = 'none'; player.appendChild(audio);
      container.appendChild(player);
      load(0);
      audio.addEventListener('loadedmetadata', function () { if (pendingSeek != null) { audio.currentTime = pendingSeek; pendingSeek = null; } audio.playbackRate = speeds[speed]; });
      audio.addEventListener('play', function () { playBtn.innerHTML = '❚❚'; playBtn.setAttribute('aria-label', 'Pausa'); cancelAnimationFrame(raf); tick(); });
      audio.addEventListener('pause', function () { playBtn.innerHTML = '▶'; playBtn.setAttribute('aria-label', 'Riproduci'); cancelAnimationFrame(raf); tick(); });
      audio.addEventListener('ended', function () { if (index < parts.length - 1) { load(index + 1); audio.play(); } else { tick(); } });
      // Anche in riproduzione: in una scheda in secondo piano requestAnimationFrame si ferma, timeupdate no.
      audio.addEventListener('timeupdate', function () { tick(); });
      playBtn.addEventListener('click', function () { if (audio.paused) audio.play(); else audio.pause(); });
      back.addEventListener('click', function () { seek(sessionMs() - 15000); });
      fwd.addEventListener('click', function () { seek(sessionMs() + 15000); });
      bar.addEventListener('click', function (ev) { var r = bar.getBoundingClientRect(); seek(total * (ev.clientX - r.left) / r.width); });
      speedBtn.addEventListener('click', function () { speed = (speed + 1) % speeds.length; audio.playbackRate = speeds[speed]; speedBtn.textContent = speeds[speed] + '×'; });
    } else if (parts.length) {
      container.appendChild(el('div', 'missing', 'L’audio di questa lezione non è (ancora) disponibile qui: chi ha condiviso la nota non l’ha caricato.'));
    }

    // --- la trascrizione ---
    var raw = session.raw, refined = session.refined;
    if (!raw && !refined) { container.appendChild(el('div', 'missing', 'Questa lezione non è stata trascritta.')); return; }
    var views = {};
    if (refined) views.refined = el('div', 'card notes', md(refined.text));
    if (raw) {
      var rawView = el('div', playable ? 'card' : 'card static');
      var paras = paragraphs(raw.segments);
      for (var p = 0; p < paras.length; p++) {
        var para = paras[p], row = el('div', 'para'), t = el('span', 't', fmt(para.start)), x = el('span', 'x');
        if (para.silence) {
          var quiet = el('div', 'silence', silenceText(para.silence));
          (function (start) { quiet.addEventListener('click', function () { seek(start); }); })(para.start);
          rawView.appendChild(quiet);
        }
        (function (start) { t.addEventListener('click', function () { seek(start); }); })(para.start);
        for (var s = 0; s < para.segs.length; s++) {
          var ws = wordsOf(para.segs[s]);
          for (var k = 0; k < ws.length; k++) {
            var w = ws[k], span = el('span', 'w', esc(w.t));
            (function (start) { span.addEventListener('click', function () { seek(start); }); })(w.s);
            x.appendChild(span); x.appendChild(document.createTextNode(' '));
            words.push({ s: w.s, e: w.e, el: span });
          }
        }
        row.appendChild(t); row.appendChild(x); rawView.appendChild(row);
      }
      words.sort(function (a, b) { return a.s - b.s; });
      if (raw.wordsEstimated) rawView.appendChild(el('p', 'note', 'I tempi delle singole parole sono stimati: il servizio ha dato i tempi di ogni frase, non di ogni parola.'));
      views.raw = rawView;
    }
    if (refined && raw) {
      var tabs = el('div', 'tabs'), bRef = el('button', null, 'Ripulita'), bRaw = el('button', null, 'Grezza');
      tabs.appendChild(bRef); tabs.appendChild(bRaw); container.appendChild(tabs);
      var holder = el('div'); container.appendChild(holder);
      function show(which) {
        holder.innerHTML = ''; holder.appendChild(views[which]);
        bRef.setAttribute('aria-selected', which === 'refined'); bRaw.setAttribute('aria-selected', which === 'raw');
        if (which === 'refined') holder.appendChild(el('p', 'note', 'Testo ripulito da un modello (' + esc(refined.model) + '). Le parole che si accendono con l’audio sono nella grezza.'));
      }
      bRef.addEventListener('click', function () { show('refined'); }); bRaw.addEventListener('click', function () { show('raw'); });
      show(playable ? 'raw' : 'refined');
    } else {
      container.appendChild(views.refined || views.raw);
      if (refined && !raw) container.appendChild(el('p', 'note', 'Testo ripulito da un modello (' + esc(refined.model) + '): non ha i tempi.'));
    }
  }

  function render(data) {
    document.title = data.title + ' — Pampa Notes';
    root.innerHTML = '';
    var header = el('header');
    header.appendChild(el('h1', null, esc(data.title)));
    var meta = [];
    if (data.folder) meta.push(esc(data.folder));
    if (data.updatedAt) meta.push(new Date(data.updatedAt).toLocaleDateString('it-IT', { day: 'numeric', month: 'long', year: 'numeric' }));
    header.appendChild(el('div', 'meta', meta.join(' · ')));
    if (data.tags && data.tags.length) { var tg = el('div', 'tags'); for (var i = 0; i < data.tags.length; i++) tg.appendChild(el('span', null, esc(data.tags[i]))); header.appendChild(tg); }
    root.appendChild(header);

    if (data.body && data.body.trim()) {
      root.appendChild(el('h2', null, 'Appunti'));
      root.appendChild(el('div', 'card notes', md(data.body)));
    }
    for (var s = 0; s < data.sessions.length; s++) {
      var session = data.sessions[s];
      var when = session.date && /^\\d{4}-\\d{2}-\\d{2}$/.test(session.date)
        ? new Date(session.date + 'T12:00:00').toLocaleDateString('it-IT', { day: 'numeric', month: 'long', year: 'numeric' })
        : (session.date || '');
      var title = (session.title || ('Sessione ' + (s + 1))) + (when ? ' — ' + when : '');
      root.appendChild(el('h2', null, esc(title)));
      var box = el('div'); root.appendChild(box);
      buildSession(session, box);
    }
    if (data.sources && data.sources.length) {
      root.appendChild(el('h3', null, 'Fonti'));
      root.appendChild(el('p', 'note', esc(data.sources.join(' · '))));
    }
    var foot = el('footer'); foot.innerHTML = 'Condivisa con <b>Pampa Notes</b>. Il testo della trascrizione è di una macchina: può sbagliare.';
    root.appendChild(foot);
  }

  fetch('/s/' + token + '/data', { credentials: 'omit' })
    .then(function (r) { if (!r.ok) throw new Error(String(r.status)); return r.json(); })
    .then(render)
    .catch(function () {
      root.innerHTML = '<header><h1>Questo link non è più valido</h1><div class="meta">Chi lo aveva condiviso lo ha ritirato, oppure non è mai esistito.</div></header>';
    });
})();
</script>
</body>
</html>`;
}
