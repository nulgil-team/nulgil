/*
 * 프런트 개발 서버. 127.0.0.1에서 frontend/와 공용 클라이언트만 제공한다.
 * 저장소 루트, runs, demo-data, .git은 열지 않는다. 연결 토큰은 메모리에서만 전달한다.
 *
 * 사용: node frontend/dev-server.mjs [--port 5173] [--connection <connection.json>] [--runs <실행 폴더 상위 경로>]
 */
import { createServer } from "node:http";
import { readFile, readdir, stat } from "node:fs/promises";
import { dirname, extname, join, normalize, resolve, sep } from "node:path";
import { fileURLToPath } from "node:url";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");
const arg = (name) => {
  const index = process.argv.indexOf(name);
  return index > 0 ? process.argv[index + 1] : undefined;
};
const port = Number(arg("--port") ?? 5173);
const explicit = arg("--connection");
const runsRoot = arg("--runs");

const TYPES = {
  ".html": "text/html; charset=utf-8", ".js": "text/javascript; charset=utf-8", ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8", ".ttf": "font/ttf", ".txt": "text/plain; charset=utf-8",
};
const BLOCKED = new Set(["dev-server.mjs", "AGENTS.md", "README.md"]);

async function latestConnection() {
  if (explicit) return explicit;
  const runs = runsRoot ?? join(root, "runs");
  const names = await readdir(runs).catch(() => []);
  const found = [];
  for (const name of names) {
    const path = join(runs, name, "connection.json");
    const info = await stat(path).catch(() => null);
    if (info) found.push({ path, time: info.mtimeMs });
  }
  found.sort((a, b) => b.time - a.time);
  return found[0]?.path;
}

async function connection() {
  const path = await latestConnection();
  if (!path) return null;
  const { baseUrl, token } = JSON.parse((await readFile(path, "utf8")).replace(/^﻿/, ""));
  return baseUrl && token ? { baseUrl, token } : null;
}

const send = (res, status, type, body) => {
  res.writeHead(status, { "Content-Type": type, "Cache-Control": "no-store", "X-Content-Type-Options": "nosniff" });
  res.end(body);
};

const server = createServer(async (req, res) => {
  try {
    if (req.method !== "GET") return send(res, 405, "text/plain", "Method not allowed");
    const url = new URL(req.url, `http://${req.headers.host}`);
    if (url.pathname === "/dev-connection.json") {
      const value = await connection();
      return value ? send(res, 200, "application/json", JSON.stringify(value)) : send(res, 404, "text/plain", "No backend run");
    }
    if (url.pathname === "/client/routine-client.mjs") {
      return send(res, 200, TYPES[".mjs"], await readFile(join(root, "client", "routine-client.mjs")));
    }
    const relative = normalize(decodeURIComponent(url.pathname === "/" ? "/index.html" : url.pathname)).replace(/^[/\\]+/, "");
    const path = resolve(here, relative);
    if (!path.startsWith(here + sep) || BLOCKED.has(relative) || relative.startsWith(`tests${sep}`)) return send(res, 404, "text/plain", "Not found");
    const type = TYPES[extname(path)];
    if (!type) return send(res, 404, "text/plain", "Not found");
    return send(res, 200, type, await readFile(path));
  } catch {
    return send(res, 404, "text/plain", "Not found");
  }
});

server.listen(port, "127.0.0.1", () => console.log(`늘길 화면: http://localhost:${port}`));
