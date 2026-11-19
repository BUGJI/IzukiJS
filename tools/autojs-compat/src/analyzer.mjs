import fs from "node:fs";
import path from "node:path";

export const COMPAT_HEADER = "// @compat autojs6";

const SCAN_LIMIT = 8192;
const MAX_FILE_BYTES = 2 * 1024 * 1024;

const SUPPORTED_FLAVORS = new Map([
  ["autojs6", "autojs6"],
  ["autojs", "autojs6"],
  ["autox", "autojs6"],
]);

const KNOWN_DIRECTIVES = new Set([
  "ui", "ui-thread", "auto",
  "nodejs", "node", "bun", "quickjs", "qjs",
  "jsox", "x",
]);

const UNSUPPORTED_DIRECTIVES = new Set([
  "ui", "ui-thread",
  "nodejs", "node", "bun", "quickjs", "qjs",
]);

const BLOCKER_SIGNALS = new Set([
  "ui-mode",
  "java-interop",
  "legacy-ui",
  "legacy-e4x",
  "legacy-threads",
]);

const DETECTORS = [
  { code: "ui-mode", label: "\"ui\"; UI 模式", source: "directives" },
  { code: "auto-directive", label: "\"auto\"; 指令", source: "directives" },
  {
    code: "java-interop",
    label: "Java 互操作",
    source: "code",
    pattern: /\b(?:Packages|importClass|importPackage|JavaAdapter)\b/,
  },
  {
    code: "legacy-selector",
    label: "Auto.js 选择器",
    source: "code",
    pattern: /\b(?:text|desc|id|className|packageName|textContains|textStartsWith|textEndsWith|textMatches|descContains|descStartsWith|descEndsWith|descMatches)\s*\(/,
  },
  {
    code: "legacy-selector-chain",
    label: "UiObject 链式调用",
    source: "code",
    pattern: /\.(?:findOnce|findOne|untilFind|untilFindOne)\s*\(/,
  },
  {
    code: "legacy-timers",
    label: "setTimeout / setInterval",
    source: "code",
    pattern: /\b(?:setTimeout|setInterval|setImmediate)\s*\(/,
  },
  {
    code: "legacy-threads",
    label: "threads / engines",
    source: "code",
    pattern: /\b(?:threads|engines)\s*\./,
  },
  {
    code: "legacy-ui",
    label: "ui / floaty XML",
    source: "code",
    pattern: /\bui\s*\.\s*(?:layout|layoutFile|setContentView|run|post)\b|\bfloaty\s*\.\s*(?:window|rawWindow)\b/,
  },
  {
    code: "legacy-e4x",
    label: "E4X XML",
    source: "code",
    pattern: /<(?:vertical|horizontal|frame|linear|text|button|input|checkbox|switch|progressbar|scroll|list|card|appbar|toolbar)(?=[\s/>])/,
  },
  {
    code: "legacy-device-property",
    label: "device.width 属性写法",
    source: "code",
    pattern: /\bdevice\s*\.\s*(?:width|height|rotation|density|brand|model|sdkInt)\b(?!\s*\()/,
  },
  {
    code: "legacy-images",
    label: "images 对象 API",
    source: "code",
    pattern: /\bimages\s*\.\s*(?:read|captureScreen|pixel|save|clip|resize|matchTemplate)\b/,
  },
  {
    code: "legacy-storage",
    label: "storages",
    source: "code",
    pattern: /\bstorages\s*\.\s*create\b/,
  },
  {
    code: "legacy-dialogs",
    label: "dialogs",
    source: "code",
    pattern: /\b(?:dialogs\s*\.|alert\s*\(|confirm\s*\(|prompt\s*\()/,
  },
  {
    code: "legacy-require-relative",
    label: "多文件 require",
    source: "raw",
    pattern: /\brequire\s*\(\s*['"](?:\.{1,2}\/)/,
  },
];

const RECOMMENDATIONS = {
  "ui-mode": "UI 模式不支持；改用无 UI 脚本，或保留为独立项目暂不迁移。",
  "java-interop": "QuickJS 没有 Java 桥；把 Intent/Uri/File 等调用改为 app/files/fs API。",
  "legacy-ui": "floaty/ui 的 XML 需要改为 JSON floaty 或人工重写。",
  "legacy-e4x": "E4X 是 Rhino 专有语法；需要改写为字符串或目标平台的布局方式。",
  "legacy-threads": "线程/多引擎与当前单 run 模型不同；改为顺序执行或拆成多个脚本。",
  "legacy-timers": "等待运行时兼容层的定时器与事件循环；可先改成 sleep 轮询。",
  "legacy-device-property": "兼容层会提供 device.width 属性；也可手动改为 device.width()。",
  "legacy-selector": "选择器链需要兼容层；也可手动改为 selector.find({...})。",
  "legacy-selector-chain": "UiObject 链式调用需要兼容层；也可改为 selector.find(...)。",
  "legacy-images": "Image 对象形状与路径 API 不同；需要兼容层或改用图片路径。",
  "legacy-storage": "storages 需要命名存储兼容；也可改用 state/env 或 fs JSON。",
  "legacy-dialogs": "对话框需要原生兼容；也可改用 floaty 或通知。",
  "legacy-require-relative": "多文件 require 需要项目导入与相对路径解析。",
};

export function maskNonCode(source) {
  let out = "";
  let index = 0;
  while (index < source.length) {
    const ch = source[index];
    if (ch === "/" && source[index + 1] === "/") {
      out += "  ";
      index += 2;
      while (index < source.length && source[index] !== "\n") {
        out += " ";
        index++;
      }
      continue;
    }
    if (ch === "/" && source[index + 1] === "*") {
      out += "  ";
      index += 2;
      while (index < source.length && !(source[index] === "*" && source[index + 1] === "/")) {
        out += source[index] === "\n" ? "\n" : " ";
        index++;
      }
      if (index < source.length) {
        out += "  ";
        index += 2;
      }
      continue;
    }
    if (ch === "`") {
      // 模板字符串普通文本不是代码，但 ${...} 里可能是 API 调用；保留插值表达式。
      out += " ";
      index++;
      while (index < source.length) {
        const current = source[index];
        if (current === "\\" && index + 1 < source.length) {
          out += "  ";
          index += 2;
          continue;
        }
        if (current === "`") {
          out += " ";
          index++;
          break;
        }
        if (current === "$" && source[index + 1] === "{") {
          const end = interpolationEnd(source, index + 2);
          if (end < 0) {
            out += " ".repeat(source.length - index);
            index = source.length;
          } else {
            out += source.slice(index, end + 1);
            index = end + 1;
          }
          continue;
        }
        out += current === "\n" ? "\n" : " ";
        index++;
      }
      continue;
    }
    if (ch === "\"" || ch === "'") {
      const quote = ch;
      out += " ";
      index++;
      while (index < source.length) {
        const current = source[index];
        if (current === "\\" && index + 1 < source.length) {
          out += "  ";
          index += 2;
          continue;
        }
        if (current === quote) {
          out += " ";
          index++;
          break;
        }
        out += current === "\n" ? "\n" : " ";
        index++;
      }
      continue;
    }
    out += ch;
    index++;
  }
  return out;
}

function interpolationEnd(source, start) {
  let depth = 1;
  let index = start;
  while (index < source.length) {
    const ch = source[index];
    if (ch === "{") {
      depth++;
      index++;
      continue;
    }
    if (ch === "}") {
      depth--;
      if (depth === 0) return index;
      index++;
      continue;
    }
    if (ch === "'" || ch === "\"" || ch === "`") {
      index = skipQuoted(source, index);
      continue;
    }
    if (ch === "/" && source[index + 1] === "/") {
      const newline = source.indexOf("\n", index + 2);
      index = newline < 0 ? source.length : newline;
      continue;
    }
    if (ch === "/" && source[index + 1] === "*") {
      const end = source.indexOf("*/", index + 2);
      index = end < 0 ? source.length : end + 2;
      continue;
    }
    index++;
  }
  return -1;
}

function skipQuoted(source, start) {
  const quote = source[start];
  let index = start + 1;
  while (index < source.length) {
    if (source[index] === "\\") {
      index += 2;
      continue;
    }
    if (source[index] === quote) return index + 1;
    index++;
  }
  return source.length;
}

export function readDirectives(source) {
  const out = new Set();
  const limit = Math.min(source.length, SCAN_LIMIT);
  let index = source.charCodeAt(0) === 0xfeff ? 1 : 0;
  while (index < limit && out.size < 8) {
    while (index < limit && /\s/.test(source[index])) index++;
    if (index >= limit) break;
    if (source.startsWith("//", index)) {
      const newline = source.indexOf("\n", index);
      index = newline < 0 || newline >= limit ? limit : newline + 1;
      continue;
    }
    if (source.startsWith("/*", index)) {
      const end = source.indexOf("*/", index + 2);
      index = end < 0 || end >= limit ? limit : end + 2;
      continue;
    }
    const quote = source[index];
    if (quote !== "\"" && quote !== "'" && quote !== "`") break;
    let value = "";
    index++;
    let closed = false;
    while (index < limit) {
      const ch = source[index];
      if (ch === "\\" && index + 1 < limit) {
        value += source[index + 1];
        index += 2;
        continue;
      }
      if (ch === quote) {
        index++;
        closed = true;
        break;
      }
      value += ch;
      index++;
    }
    if (!closed) break;
    while (index < limit && /\s/.test(source[index])) index++;
    if (index < limit && source[index] === ";") index++;
    const tokens = value.trim().split(/\s+/).filter(Boolean);
    if (tokens.length === 0 || tokens.some((token) => !KNOWN_DIRECTIVES.has(token.toLowerCase()))) {
      break;
    }
    for (const token of tokens) out.add(token.toLowerCase());
  }
  return out;
}

export function findHeader(source) {
  const window = source.slice(0, SCAN_LIMIT);
  const match = /^[ \t]*\/\/[ \t]*@compat[ \t]+([A-Za-z0-9_-]+).*$/m.exec(window);
  return match ? match[1] : null;
}

function lineAt(source, index) {
  if (index < 0) return null;
  let line = 1;
  for (let i = 0; i < index && i < source.length; i++) {
    if (source[i] === "\n") line++;
  }
  return line;
}

function directiveLine(source, directive) {
  const probes = [
    `"${directive}"`,
    `'${directive}'`,
    `\`${directive}\``,
    `"${directive} `,
    `'${directive} `,
    `\`${directive} `,
  ];
  for (const probe of probes) {
    const index = source.indexOf(probe);
    if (index >= 0) return lineAt(source, index);
  }
  return 1;
}

export function detectSignals(source) {
  const directives = readDirectives(source);
  const code = maskNonCode(source);
  const signals = [];
  for (const detector of DETECTORS) {
    if (detector.source === "directives") {
      const wanted = detector.code === "ui-mode"
        ? ["ui", "ui-thread"]
        : ["auto"];
      if (!wanted.some((directive) => directives.has(directive))) continue;
      const matched = wanted.find((directive) => directives.has(directive));
      signals.push({
        code: detector.code,
        label: detector.label,
        line: directiveLine(source, matched),
      });
      continue;
    }
    const text = detector.source === "code" ? code : source;
    const match = detector.pattern.exec(text);
    if (!match) continue;
    signals.push({
      code: detector.code,
      label: detector.label,
      line: lineAt(source, match.index),
    });
  }
  return signals.sort((a, b) => (a.line || 0) - (b.line || 0));
}

export function collectRequires(source) {
  const results = [];
  const pattern = /\brequire\s*\(\s*(['"])([^'"]+)\1/g;
  let match;
  while ((match = pattern.exec(source)) !== null) {
    results.push({
      name: match[2],
      line: lineAt(source, match.index),
      relative: /^\.{1,2}\//.test(match[2]),
    });
  }
  return results;
}

function canonicalFlavor(raw) {
  if (!raw) return null;
  return SUPPORTED_FLAVORS.get(String(raw).toLowerCase()) || null;
}

export function analyzeSource(source, filePath = "") {
  const header = findHeader(source);
  const flavor = canonicalFlavor(header);
  const directives = [...readDirectives(source)];
  const signals = detectSignals(source);
  const requires = collectRequires(source);
  const issues = [];

  if (header && !flavor) {
    issues.push({
      code: "unsupported-flavor",
      message: `未知的兼容目标: ${header}；当前支持 autojs6`,
    });
  }

  const unsupportedDirectives = directives.filter((directive) =>
    UNSUPPORTED_DIRECTIVES.has(directive),
  );
  if (unsupportedDirectives.length > 0) {
    issues.push({
      code: "unsupported-directive",
      message: `兼容层暂不支持: ${unsupportedDirectives.join(", ")}`,
    });
  }

  const blockers = signals.filter((signal) => BLOCKER_SIGNALS.has(signal.code));
  return {
    path: filePath,
    header,
    enabled: flavor !== null,
    flavor,
    directives,
    issues,
    signals,
    requires,
    blockers,
    recommendations: [...new Set(signals.map((signal) => RECOMMENDATIONS[signal.code]).filter(Boolean))],
  };
}

function walk(target, root, out) {
  const stat = fs.statSync(target);
  if (stat.isFile()) {
    if (!/\.(?:js|mjs|cjs|ts|tsx)$/i.test(target)) return;
    if (stat.size > MAX_FILE_BYTES) {
      out.push({
        path: path.relative(root, target),
        skipped: true,
        reason: `文件超过 ${MAX_FILE_BYTES} 字节`,
      });
      return;
    }
    const source = fs.readFileSync(target, "utf8");
    out.push({ ...analyzeSource(source, path.relative(root, target)), absolute: target });
    return;
  }
  if (!stat.isDirectory()) return;
  for (const entry of fs.readdirSync(target)) {
    if (entry === ".git" || entry === "node_modules" || entry === "build" || entry === "out") continue;
    walk(path.join(target, entry), root, out);
  }
}

export function analyzePath(target) {
  const absolute = path.resolve(target);
  const stat = fs.statSync(absolute);
  const root = stat.isDirectory() ? absolute : path.dirname(absolute);
  const files = [];
  walk(absolute, root, files);
  const analyzed = files.filter((file) => !file.skipped);
  return {
    root,
    files,
    summary: {
      fileCount: analyzed.length,
      skipped: files.length - analyzed.length,
      enabled: analyzed.filter((file) => file.enabled).length,
      blockerFiles: analyzed.filter((file) => file.blockers.length > 0).length,
      signals: analyzed.reduce((total, file) => total + file.signals.length, 0),
      requires: analyzed.reduce((total, file) => total + file.requires.length, 0),
    },
  };
}

export function toMarkdown(report) {
  const lines = [];
  lines.push("# Auto.js 兼容性报告");
  lines.push("");
  lines.push(`- 根目录: \`${report.root}\``);
  lines.push(`- 扫描文件: ${report.summary.fileCount}`);
  lines.push(`- 已标记兼容: ${report.summary.enabled}`);
  lines.push(`- 含阻断项文件: ${report.summary.blockerFiles}`);
  lines.push(`- 跳过文件: ${report.summary.skipped}`);
  lines.push("");
  for (const file of report.files) {
    lines.push(`## ${file.path}`);
    if (file.skipped) {
      lines.push(`- 跳过: ${file.reason}`);
      lines.push("");
      continue;
    }
    lines.push(`- 兼容标记: ${file.header ? `\`${file.header}\`` : "无"}`);
    if (file.directives.length > 0) {
      lines.push(`- 指令: ${file.directives.map((item) => `\`${item}\``).join(", ")}`);
    }
    if (file.signals.length > 0) {
      lines.push("- 识别项:");
      for (const signal of file.signals) {
        const level = BLOCKER_SIGNALS.has(signal.code) ? "阻断" : "提示";
        lines.push(`  - [${level}] ${signal.label} (行 ${signal.line})`);
      }
    } else {
      lines.push("- 识别项: 无");
    }
    if (file.requires.length > 0) {
      lines.push("- require:");
      for (const item of file.requires) {
        lines.push(`  - \`${item.name}\` (行 ${item.line}${item.relative ? "，相对路径" : ""})`);
      }
    }
    if (file.issues.length > 0) {
      lines.push("- 问题:");
      for (const issue of file.issues) lines.push(`  - ${issue.message}`);
    }
    if (file.recommendations.length > 0) {
      lines.push("- 建议:");
      for (const item of file.recommendations) lines.push(`  - ${item}`);
    }
    lines.push("");
  }
  return lines.join("\n");
}
