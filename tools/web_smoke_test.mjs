#!/usr/bin/env node
/**
 * Headless smoke test for web/index.html.
 *
 * It runs the page's real script in a Node VM with a minimal DOM stub and a real
 * fetch against tools/web_mock_server.py. That exercises the parts that can break
 * silently: config validation, base64 round-trip, document normalisation, the
 * three-way merge on conflict, and the write/retry loop.
 *
 * It does NOT verify visual rendering — that needs a browser (documented in
 * docs/VERIFICATION.md).
 *
 * Usage:
 *   python3 tools/web_mock_server.py --port 8765 &
 *   node tools/web_smoke_test.mjs http://127.0.0.1:8765
 */

import fs from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { fileURLToPath } from "node:url";

const here = path.dirname(fileURLToPath(import.meta.url));
const html = fs.readFileSync(path.join(here, "..", "web", "index.html"), "utf8");
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const baseUrl = process.argv[2] || "http://127.0.0.1:8765";

const elements = new Map();

function makeElement(id, tag = "div") {
  return {
    id,
    tagName: tag.toUpperCase(),
    value: "",
    textContent: "",
    innerHTML: "",
    className: "",
    checked: false,
    disabled: false,
    dataset: {},
    style: {},
    children: [],
    classList: {
      toggle() {},
      add() {},
      remove() {},
      contains() {
        return false;
      },
    },
    appendChild(child) {
      this.children.push(child);
      return child;
    },
    append(...children) {
      this.children.push(...children);
    },
    showModal() {
      this.open = true;
    },
    close() {
      this.open = false;
    },
    onclick: null,
    onchange: null,
    onkeydown: null,
  };
}

const storage = new Map();
const documentStub = {
  body: makeElement("body", "body"),
  getElementById(id) {
    if (!elements.has(id)) elements.set(id, makeElement(id));
    return elements.get(id);
  },
  createElement(tag) {
    return makeElement(null, tag);
  },
};

const context = {
  document: documentStub,
  localStorage: {
    getItem: (key) => (storage.has(key) ? storage.get(key) : null),
    setItem: (key, value) => storage.set(key, String(value)),
    removeItem: (key) => storage.delete(key),
  },
  fetch,
  console,
  confirm: () => true,
  crypto: globalThis.crypto,
  TextEncoder,
  TextDecoder,
  btoa,
  atob,
  Date,
  Math,
  JSON,
  Number,
  Object,
  Array,
  String,
  RegExp,
  Set,
  Promise,
  setTimeout,
};

vm.createContext(context);
const run = (code) => vm.runInContext(code, context);
run(script);

const failures = [];
function check(name, condition, detail = "") {
  if (condition) {
    console.log(`ok   ${name}`);
  } else {
    failures.push(`${name} ${detail}`);
    console.log(`FAIL ${name} ${detail}`);
  }
}

function setValue(id, value) {
  run(`document.getElementById(${JSON.stringify(id)}).value = ${JSON.stringify(value)}`);
}

function getValue(id) {
  return run(`document.getElementById(${JSON.stringify(id)}).value`);
}

function text(id) {
  return run(`document.getElementById(${JSON.stringify(id)}).textContent`);
}

async function serverState() {
  const response = await fetch(`${baseUrl}/__state`);
  return response.json();
}

// 1. Configure and connect against an empty repository.
setValue("provider", "github");
setValue("baseUrl", baseUrl);
setValue("owner", "demo");
setValue("repo", "todo");
setValue("branch", "main");
setValue("path", "data/todos.json");
setValue("token", "test-token");

await run("connect()");
check("connect on empty repo reports an empty file", text("status").includes("还没有文件"), text("status"));
check("summary shows the repository", text("summary").includes("demo/todo"), text("summary"));

// 2. Add a task -> writes data/todos.json through the contents API.
setValue("new-title", "买牛奶");
run('document.getElementById("new-priority").value = "urgent"');
await run("addTask()");
let state = await serverState();
check("write reached the server", state.writes === 1, `writes=${state.writes}`);
check("commit message recorded", state.commits.length === 1, JSON.stringify(state.commits));
const document1 = JSON.parse(state.content);
check("schemaVersion is 1", document1.schemaVersion === 1);
check("task persisted with priority", document1.items[0].title === "买牛奶" && document1.items[0].priority === "urgent");
check("task has a deviceId", typeof document1.items[0].deviceId === "string" && document1.items[0].deviceId.length > 0);

// 3. Reload from the server (simulates a second visit).
await run("load(true)");
check("reload renders the task", run("state.doc.items.length") === 1);
check("rendered title matches", run('document.getElementById("tasks").children[0].children[1].children[0].textContent').includes("买牛奶"));

// 4. Stale revision must trigger re-read + merge instead of overwriting.
run('state.revision = "stale-sha"');
run('state.doc.items[0].title = "改过的标题"; touch(state.doc.items[0])');
await run('persist("test conflict")');
state = await serverState();
check("conflict retry wrote again", state.writes === 2, `writes=${state.writes}`);
const document2 = JSON.parse(state.content);
check("local edit survived the merge", document2.items[0].title === "改过的标题", document2.items[0].title);
check("no duplicate records", document2.items.length === 1, `items=${document2.items.length}`);

// 5. Complete + delete produce tombstones the Android client understands.
await run("toggleDone(state.doc.items[0])");
state = await serverState();
check("done flag is written", JSON.parse(state.content).items[0].done === true);
check("doneAt is set", typeof JSON.parse(state.content).items[0].doneAt === "number");

await run("deleteItem(state.doc.items[0])");
state = await serverState();
check("delete writes a tombstone", typeof JSON.parse(state.content).items[0].deletedAt === "number");

if (failures.length > 0) {
  console.error(`\n${failures.length} check(s) failed`);
  process.exit(1);
}
console.log("\nweb smoke test passed");
