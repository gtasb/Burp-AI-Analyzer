import { spawn } from "node:child_process";
import { existsSync } from "node:fs";
import { mkdtemp, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { defineTool } from "@earendil-works/pi-coding-agent";
import { Type } from "typebox";

const DEFAULT_WAIT_MS = 8_000;
const MAX_WAIT_MS = 30_000;
const DEFAULT_MAX_CHARS = 20_000;
const MAX_DOM_CHARS = 100_000;
const CHROME_TIMEOUT_GRACE_MS = 10_000;

export type ChromeRunResult = {
  code: number | null;
  signal: NodeJS.Signals | null;
  stdout: string;
  stderr: string;
};

export type BrowserProcessRuntime = {
  chromePath: string;
  createProfile: () => Promise<string>;
  removeProfile: (profileDir: string) => Promise<void>;
  execChrome: (
    chromePath: string,
    args: string[],
    timeoutMs: number,
    signal?: AbortSignal
  ) => Promise<ChromeRunResult>;
  proxy?: string;
  disableChromeSandbox?: boolean;
  ignoreCertificateErrors?: boolean;
};

export type BrowserToolDependencies = {
  runtime?: BrowserProcessRuntime;
  chromePath?: string;
  env?: NodeJS.ProcessEnv;
  execChrome?: BrowserProcessRuntime["execChrome"];
  allowHostFallback?: boolean;
};

export function createBrowserRenderTool(dependencies: BrowserToolDependencies = {}) {
  return defineTool({
    name: "browser_render",
    label: "Browser Render",
    description: [
      "Render an authorized HTTP or HTTPS URL in headless Chromium and return the post-JavaScript DOM.",
      "Use it for client-side rendering, hash/fragment sinks, DOM XSS, and other behavior curl cannot observe.",
      "Browser dialogs and console output are not proof; use an observable DOM mutation as the confirmation signal.",
      "Every call performs a real navigation inside the Executor network boundary."
    ].join(" "),
    parameters: Type.Object({
      url: Type.String({ minLength: 8, maxLength: 2_048 }),
      waitMs: Type.Optional(Type.Integer({ minimum: 500, maximum: MAX_WAIT_MS })),
      maxChars: Type.Optional(Type.Integer({ minimum: 1_000, maximum: MAX_DOM_CHARS }))
    }, { additionalProperties: false }),
    execute: async (_toolCallId, params, signal) => {
      const result = await renderWithChrome(params.url, {
        ...dependencies,
        waitMs: params.waitMs,
        maxChars: params.maxChars,
        signal
      });
      return {
        content: [{ type: "text" as const, text: JSON.stringify(result, null, 2) }],
        details: result
      };
    }
  });
}

export function validateBrowserUrl(rawUrl: string): { ok: true; url: string } | { ok: false; error: string } {
  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    return { ok: false, error: "invalid URL" };
  }
  if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
    return { ok: false, error: `unsupported scheme: ${parsed.protocol}` };
  }
  return { ok: true, url: parsed.toString() };
}

export function buildChromeArgs(input: {
  url: string;
  waitMs: number;
  profileDir: string;
  proxy?: string;
  disableChromeSandbox?: boolean;
  ignoreCertificateErrors?: boolean;
}): string[] {
  const args = [
    "--headless=new",
    "--disable-gpu",
    "--disable-extensions",
    "--disable-background-networking",
    "--disable-dev-shm-usage",
    "--no-first-run",
    "--no-default-browser-check",
    "--disable-sync",
    "--mute-audio",
    `--user-data-dir=${input.profileDir}`,
    `--virtual-time-budget=${input.waitMs}`,
    "--dump-dom"
  ];
  if (input.disableChromeSandbox) args.push("--no-sandbox");
  if (input.ignoreCertificateErrors) args.push("--ignore-certificate-errors");
  if (input.proxy) args.push(`--proxy-server=${input.proxy}`);
  args.push(input.url);
  return args;
}

export function resolveChromePath(
  env: NodeJS.ProcessEnv,
  platform: NodeJS.Platform,
  exists: (path: string) => boolean
): string | undefined {
  const candidates = [
    env.LUANNIAO_CHROME_PATH,
    env.CHROME_PATH,
    ...(platform === "darwin"
      ? [
          "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
          "/Applications/Chromium.app/Contents/MacOS/Chromium",
          "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge"
        ]
      : platform === "win32"
        ? [
            "C:/Program Files/Google/Chrome/Application/chrome.exe",
            "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe"
          ]
        : ["/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser"])
  ].filter((candidate): candidate is string => typeof candidate === "string" && candidate.length > 0);
  return candidates.find(exists);
}

export async function renderWithChrome(rawUrl: string, options: BrowserToolDependencies & {
  waitMs?: number;
  maxChars?: number;
  signal?: AbortSignal;
}): Promise<Record<string, unknown>> {
  const validation = validateBrowserUrl(rawUrl);
  if (!validation.ok) {
    return { success: false, error: validation.error, url: rawUrl };
  }
  const runtime = options.runtime
    ?? (options.allowHostFallback === false ? undefined : createHostBrowserRuntime(options));
  if (!runtime) {
    return {
      success: false,
      error: "no Chrome/Chromium executable found; set LUANNIAO_CHROME_PATH",
      url: validation.url
    };
  }
  const waitMs = options.waitMs ?? DEFAULT_WAIT_MS;
  const maxChars = options.maxChars ?? DEFAULT_MAX_CHARS;
  const profileDir = await runtime.createProfile();
  try {
    const args = buildChromeArgs({
      url: validation.url,
      waitMs,
      profileDir,
      proxy: runtime.proxy,
      disableChromeSandbox: runtime.disableChromeSandbox,
      ignoreCertificateErrors: runtime.ignoreCertificateErrors
    });
    const run = await runtime.execChrome(
      runtime.chromePath,
      args,
      waitMs + CHROME_TIMEOUT_GRACE_MS,
      options.signal
    );
    const dom = run.stdout;
    if (!dom.trim()) {
      return {
        success: false,
        error: `chrome produced no DOM (exit ${run.code ?? run.signal ?? "unknown"}): ${run.stderr.slice(0, 500)}`,
        url: validation.url
      };
    }
    return {
      success: true,
      url: validation.url,
      waitMs,
      domChars: dom.length,
      truncated: dom.length > maxChars,
      chromeTerminated: run.signal === "SIGKILL" || undefined,
      dom: dom.slice(0, maxChars)
    };
  } finally {
    await runtime.removeProfile(profileDir).catch(() => undefined);
  }
}

export function createHostBrowserRuntime(options: BrowserToolDependencies): BrowserProcessRuntime | undefined {
  const env = options.env ?? process.env;
  const chromePath = options.chromePath
    ?? resolveChromePath(env, process.platform, (candidate) => existsSyncSafe(candidate));
  if (!chromePath) return undefined;
  return {
    chromePath,
    createProfile: () => mkdtemp(join(tmpdir(), "luanniao-chrome-")),
    removeProfile: (profileDir) => rm(profileDir, { recursive: true, force: true }),
    execChrome: options.execChrome ?? execChromeProcess,
    proxy: env.HTTPS_PROXY ?? env.https_proxy ?? env.HTTP_PROXY ?? env.http_proxy
  };
}

function execChromeProcess(
  chromePath: string,
  args: string[],
  timeoutMs: number,
  signal?: AbortSignal
): Promise<ChromeRunResult> {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(chromePath, args, { stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    let settled = false;
    const finish = (result: ChromeRunResult): void => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
      resolvePromise(result);
    };
    const kill = (): void => { child.kill("SIGKILL"); };
    const onAbort = (): void => kill();
    const timer = setTimeout(kill, timeoutMs);
    timer.unref?.();
    signal?.addEventListener("abort", onAbort, { once: true });
    child.stdout.on("data", (chunk) => { stdout += chunk; });
    child.stderr.on("data", (chunk) => { stderr += chunk; });
    child.once("error", (error) => {
      clearTimeout(timer);
      signal?.removeEventListener("abort", onAbort);
      rejectPromise(error);
    });
    child.once("close", (code, childSignal) => finish({ code, signal: childSignal, stdout, stderr }));
  });
}

function existsSyncSafe(candidate: string): boolean {
  try {
    return existsSync(candidate);
  } catch {
    return false;
  }
}
