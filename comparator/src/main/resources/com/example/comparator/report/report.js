(function () {
  "use strict";

  // Tooltip for every element with data-tip.
  var tip = document.getElementById("tip");
  function place(e) {
    var pad = 14, w = tip.offsetWidth, h = tip.offsetHeight;
    var x = e.clientX + pad, y = e.clientY + pad;
    if (x + w > window.innerWidth - 8) x = Math.max(8, e.clientX - w - pad);
    if (y + h > window.innerHeight - 8) y = Math.max(8, e.clientY - h - pad);
    tip.style.left = x + "px";
    tip.style.top = y + "px";
  }
  document.addEventListener("mouseover", function (e) {
    var t = e.target.closest ? e.target.closest("[data-tip]") : null;
    if (!t) { tip.hidden = true; return; }
    tip.textContent = t.getAttribute("data-tip");
    tip.hidden = false;
    place(e);
  });
  document.addEventListener("mousemove", function (e) { if (!tip.hidden) place(e); });
  document.addEventListener("scroll", function () { tip.hidden = true; }, true);

  // Sortable tables: <th data-sort="num|text">, cells carry their sort key in data-v.
  document.querySelectorAll("table.sortable").forEach(function (table) {
    var heads = table.querySelectorAll("th");
    heads.forEach(function (th, col) {
      if (!th.hasAttribute("data-sort")) return;
      th.addEventListener("click", function () {
        var asc = th.getAttribute("aria-sort") !== "ascending";
        heads.forEach(function (h) { h.removeAttribute("aria-sort"); });
        th.setAttribute("aria-sort", asc ? "ascending" : "descending");
        var numeric = th.getAttribute("data-sort") === "num";
        var body = table.tBodies[0];
        var rows = Array.prototype.slice.call(body.rows);
        rows.sort(function (a, b) {
          var x = a.cells[col].getAttribute("data-v"), y = b.cells[col].getAttribute("data-v");
          var c = numeric ? (parseFloat(x) || 0) - (parseFloat(y) || 0) : String(x).localeCompare(String(y));
          return asc ? c : -c;
        });
        rows.forEach(function (r) { body.appendChild(r); });
      });
    });
  });

  // Filtering: a [data-filter-scope] holds the items ([data-item]); controls inside or before it
  // set one criterion each: data-group="name" buttons (data-value, "*" = any; space-separated
  // values allowed), a search box (data-search) and selects (data-select="attr").
  document.querySelectorAll("[data-filter-root]").forEach(function (root) {
    var items = root.querySelectorAll("[data-item]");
    var shown = root.querySelector("[data-shown]");
    var state = {};
    root.querySelectorAll("[data-group]").forEach(function (group) {
      var name = group.getAttribute("data-group");
      var pressed = group.querySelector('[aria-pressed="true"]');
      state[name] = pressed ? pressed.getAttribute("data-value") : "*";
      group.querySelectorAll("[data-value]").forEach(function (btn) {
        btn.addEventListener("click", function () {
          var target = btn;
          // clicking the pressed button of a clearable group goes back to its "*" (any) button
          if (btn.getAttribute("aria-pressed") === "true" && group.hasAttribute("data-clearable")) {
            target = group.querySelector('[data-value="*"]') || btn;
          }
          group.querySelectorAll("[data-value]").forEach(function (b) { b.setAttribute("aria-pressed", "false"); });
          target.setAttribute("aria-pressed", "true");
          state[name] = target.getAttribute("data-value");
          apply();
        });
      });
    });
    var search = root.querySelector("[data-search]");
    if (search) search.addEventListener("input", apply);
    var selects = root.querySelectorAll("[data-select]");
    selects.forEach(function (s) { s.addEventListener("change", apply); });

    function apply() {
      var q = search ? search.value.trim().toLowerCase() : "";
      var n = 0;
      items.forEach(function (it) {
        var ok = true;
        Object.keys(state).forEach(function (name) {
          var want = state[name];
          if (want === "*") return;
          var have = it.getAttribute("data-" + name);
          if (want.split(" ").indexOf(have) < 0) ok = false;
        });
        selects.forEach(function (s) {
          if (s.value && it.getAttribute("data-" + s.getAttribute("data-select")) !== s.value) ok = false;
        });
        if (ok && q && (it.getAttribute("data-text") || "").indexOf(q) < 0) ok = false;
        it.hidden = !ok;
        if (ok) n++;
      });
      if (shown) shown.textContent = n + " of " + items.length + " shown";
    }
    apply();
  });

  // Expand / collapse all visible units.
  document.querySelectorAll("[data-toggle-all]").forEach(function (btn) {
    btn.addEventListener("click", function () {
      var open = btn.getAttribute("data-open") !== "true";
      btn.setAttribute("data-open", open ? "true" : "false");
      btn.textContent = open ? "Collapse all" : "Expand all";
      document.querySelectorAll(btn.getAttribute("data-toggle-all")).forEach(function (d) {
        if (!d.closest("[hidden]")) d.open = open;
      });
    });
  });
})();
