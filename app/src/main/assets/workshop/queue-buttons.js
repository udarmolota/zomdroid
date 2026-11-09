// Injected into the Steam Workshop pages by WorkshopFragment. Puts a round download button on
// every mod/collection card; a tap adds the item to the launcher's download queue (through the
// ZomdroidWorkshop bridge), another tap takes it out. The only thing read from Steam's markup is
// the item links (".../filedetails/?id=N"), which have been stable for years; the cards are found
// as the nearest ancestor of such a link that holds a picture. Steam renders lists on the fly, so
// a MutationObserver decorates cards as they appear. Safe to inject more than once.
(function () {
  if (window.__zdWorkshop) { window.__zdWorkshop.refresh(); return; }
  var bridge = window.ZomdroidWorkshop;
  if (!bridge) return;

  var style = document.createElement('style');
  style.textContent =
    '.zd-q{position:absolute;top:6px;right:6px;z-index:9999;width:40px;height:40px;border-radius:20px;' +
    'border:2px solid #fff;background:rgba(23,26,33,.85);color:#fff;font:bold 22px/36px sans-serif;' +
    'text-align:center;padding:0;margin:0;box-shadow:0 1px 4px rgba(0,0,0,.6);cursor:pointer}' +
    '.zd-q.on{background:#4c9a2a}' +
    '.zd-host{position:relative !important}';
  (document.head || document.documentElement).appendChild(style);

  var ID = /\/filedetails\/\?(?:[^#]*&)?id=(\d+)/;

  // A card is a tile, not a page-wide banner (the Workshop's own rules item is one).
  function cardOf(a) {
    var el = a;
    for (var i = 0; i < 6 && el && el !== document.body; i++) {
      if (el.querySelector && el.querySelector('img') && el.offsetWidth > 60 && el.offsetHeight > 60) {
        return el.offsetWidth > window.innerWidth * 0.6 ? null : el;
      }
      el = el.parentElement;
    }
    return null;
  }

  // A card usually holds two links to its item (picture and title), which can resolve to nested
  // boxes: one button per item per card, wherever it landed first.
  function alreadyDone(card, id) {
    var sel = '[data-zd-id="' + id + '"]';
    return (card.closest && card.closest(sel)) || card.querySelector(sel);
  }

  function titleOf(card, a, id) {
    var t = (a.getAttribute('title') || '').trim();
    if (!t) {
      var img = card.querySelector('img[alt]');
      if (img) t = (img.getAttribute('alt') || '').trim();
    }
    if (!t) {
      var lines = (card.innerText || '').split('\n');
      for (var i = 0; i < lines.length; i++) {
        var s = lines[i].trim();
        if (s.length > 1) { t = s; break; }
      }
    }
    return t || ('Workshop item ' + id);
  }

  function paint(btn, on) {
    btn.textContent = on ? '✓' : '↓';
    if (on) btn.classList.add('on'); else btn.classList.remove('on');
  }

  function decorate() {
    var links = document.querySelectorAll('a[href*="filedetails/?"]');
    for (var i = 0; i < links.length; i++) {
      var a = links[i];
      var m = ID.exec(a.getAttribute('href') || '');
      if (!m) continue;
      var id = m[1];
      // The page we are on is the item itself: the launcher's own bar handles it.
      if (ID.test(location.href) && ID.exec(location.href)[1] === id) continue;
      var card = cardOf(a);
      if (!card || card.getAttribute('data-zd-id') || alreadyDone(card, id)) continue;
      card.setAttribute('data-zd-id', id);
      card.classList.add('zd-host');
      var btn = document.createElement('button');
      btn.className = 'zd-q';
      btn.setAttribute('data-zd', id);
      paint(btn, bridge.isQueued(id));
      (function (btn, card, a, id) {
        btn.addEventListener('click', function (e) {
          e.preventDefault();
          e.stopPropagation();
          paint(btn, bridge.toggle(id, titleOf(card, a, id)));
        }, true);
      })(btn, card, a, id);
      card.appendChild(btn);
    }
  }

  function refresh() {
    var btns = document.querySelectorAll('button.zd-q');
    for (var i = 0; i < btns.length; i++) paint(btns[i], bridge.isQueued(btns[i].getAttribute('data-zd')));
    decorate();
  }

  var pending = false;
  new MutationObserver(function () {
    if (pending) return;
    pending = true;
    setTimeout(function () { pending = false; decorate(); }, 250);
  }).observe(document.documentElement, { childList: true, subtree: true });

  window.__zdWorkshop = { refresh: refresh };
  decorate();
})();
