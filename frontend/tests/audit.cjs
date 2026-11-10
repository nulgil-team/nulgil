function audit() {
  const out = [];
  const app = document.getElementById("app");
  const box = app.getBoundingClientRect();
  const lum = (c) => {
    const f = c.slice(0, 3).map((v) => { v /= 255; return v <= .03928 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4; });
    return .2126 * f[0] + .7152 * f[1] + .0722 * f[2];
  };
  const parse = (s) => { const m = s.match(/[\d.]+/g).map(Number); return m.length > 3 ? m : [...m, 1]; };
  const bgOf = (el) => {
    for (let e = el; e; e = e.parentElement) {
      const c = parse(getComputedStyle(e).backgroundColor);
      if (c[3] > .95) return c;
    }
    return [0, 0, 0, 1];
  };
  const textEls = [...app.querySelectorAll("*")].filter((el) => el.closest("svg") === null && [...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim()));
  for (const el of textEls) {
    const cs = getComputedStyle(el);
    const text = el.textContent.trim().slice(0, 28);
    const r = el.getBoundingClientRect();
    if (r.width === 0) continue;
    const credit = el.classList.contains("map-credit");
    if (parseFloat(cs.fontSize) < 30 && !credit) out.push(`font ${cs.fontSize}: ${text}`);
    if (r.right > box.right + 1 || r.bottom > box.bottom + 1 || r.left < box.left - 1) out.push(`outside app: ${text}`);
    const clipped = (el.scrollWidth > el.clientWidth + 1 && cs.overflow !== "visible") || (el.scrollHeight > el.clientHeight + 2 && cs.overflow !== "visible");
    if (clipped) out.push(`clipped: ${text}`);
    const fg = parse(cs.color);
    const bg = bgOf(el);
    const [a, b] = [lum(fg), lum(bg)].sort((x, y) => y - x);
    const ratio = (a + .05) / (b + .05);
    const faded = parseFloat(cs.opacity) < 1;
    if (ratio < 4.5 && !faded && !credit) out.push(`contrast ${ratio.toFixed(2)}: ${text}`);
  }
  for (const el of app.querySelectorAll("button:not([hidden])")) {
    const r = el.getBoundingClientRect();
    if (r.width === 0) continue;
    if (r.width < 76 || r.height < 76) out.push(`touch ${Math.round(r.width)}x${Math.round(r.height)}: ${el.textContent.trim().slice(0, 20)}`);
  }
  for (const sel of [".lead-text", ".view", ".guide-side", ".sheet", ".settings section", ".places", ".empty-box", ".grow"]) {
    for (const el of app.querySelectorAll(sel)) {
      if (el.scrollHeight > el.clientHeight + 2) out.push(`overflow ${sel}: ${el.scrollHeight}>${el.clientHeight}`);
      const r = el.getBoundingClientRect();
      if (r.bottom > box.bottom + 1) out.push(`below app ${sel}`);
    }
  }
  const scrim = app.querySelector(".scrim");
  if (scrim) {
    const s = scrim.querySelector(".sheet").getBoundingClientRect();
    if (s.top < box.top || s.bottom > box.bottom) out.push(`sheet taller than window ${Math.round(s.height)}`);
  }
  const body = app.innerText;
  for (const word of ["undefined", "NaN", "null", "[object", "편의", "쾌적", "최적", "보장", "SDK", "READY", "VERIFIED"]) {
    if (body.includes(word)) out.push(`banned text: ${word}`);
  }
  if (/(^|\s)0분/.test(body)) out.push("zero minutes shown");
  return out;
}

module.exports = { audit };
