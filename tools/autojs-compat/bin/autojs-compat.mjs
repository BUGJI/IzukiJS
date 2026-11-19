#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { analyzePath, toMarkdown, COMPAT_HEADER, analyzeSource } from "../src/analyzer.mjs";

function usage() {
  console.log(`Usage:
  autojs-compat analyze <file|dir> [--json] [--out <file>]
  autojs-compat lint <file|dir>
  autojs-compat import <dir> --out <dir> [--name <name>] [--force]

Commands:
  analyze  生成 Auto.js 兼容性报告（Markdown / JSON）
  lint     存在阻断项时以非 0 退出，适合 CI
  import   把目录导入为 Izuki 项目（复制文件、补兼容标记、写项目清单）

说明:
  - 只做静态分析，不执行脚本。
  - UI 模式、Java 互操作、E4X 会被标记为阻断项。
  - import 不会做全量代码转译；请根据 analyze 报告人工处理。`);
}

function readOption(args, name) {
  const index = args.indexOf(name);
  if (index < 0) return null;
  const value = args[index + 1];
  if (!value || value.startsWith("--")) throw new Error(`${name} 需要一个值`);
  args.splice(index, 2);
  return value;
}

function hasFlag(args, name) {
  const index = args.indexOf(name);
  if (index < 0) return false;
  args.splice(index, 1);
  return true;
}

function describeBlockers(report) {
  const lines = [];
  for (const file of report.files) {
    if (file.skipped || file.blockers.length === 0) continue;
    lines.push(`${file.path}:`);
    for (const signal of file.blockers) {
      lines.push(`  - ${signal.label} (行 ${signal.line})`);
    }
  }
  return lines.join("\n");
}

function commandAnalyze(args) {
  const json = hasFlag(args, "--json");
  const out = readOption(args, "--out");
  const target = args[0];
  if (!target) throw new Error("analyze 需要文件或目录");
  const report = analyzePath(target);
  const content = json ? JSON.stringify(report, null, 2) : toMarkdown(report);
  if (out) {
    fs.mkdirSync(path.dirname(path.resolve(out)), { recursive: true });
    fs.writeFileSync(out, content, "utf8");
    console.log(`报告已写入 ${path.resolve(out)}`);
  } else {
    console.log(content);
  }
}

function commandLint(args) {
  const target = args[0];
  if (!target) throw new Error("lint 需要文件或目录");
  const report = analyzePath(target);
  const blockers = report.files
    .filter((file) => !file.skipped)
    .flatMap((file) => file.blockers);
  console.log(
    `扫描 ${report.summary.fileCount} 个文件，阻断项 ${blockers.length} 个，` +
      `提示项 ${report.summary.signals - blockers.length} 个`,
  );
  if (blockers.length > 0) {
    console.error(describeBlockers(report));
    process.exitCode = 1;
  }
}

function detectEntry(sourceDir) {
  const projectFile = path.join(sourceDir, "project.json");
  if (fs.existsSync(projectFile)) {
    try {
      const project = JSON.parse(fs.readFileSync(projectFile, "utf8"));
      if (project.main && fs.existsSync(path.join(sourceDir, project.main))) {
        return { entry: project.main, name: project.name || path.basename(sourceDir) };
      }
    } catch (error) {
      console.warn(`project.json 解析失败，继续按默认规则查找入口: ${error.message}`);
    }
  }
  for (const candidate of ["main.js", "index.js", "app.js"]) {
    if (fs.existsSync(path.join(sourceDir, candidate))) {
      return { entry: candidate, name: path.basename(sourceDir) };
    }
  }
  const files = fs.readdirSync(sourceDir).filter((name) => name.endsWith(".js"));
  if (files.length === 0) throw new Error("找不到入口脚本（project.json main / main.js / index.js）");
  return { entry: files[0], name: path.basename(sourceDir) };
}

function copyDirectory(source, target) {
  fs.mkdirSync(target, { recursive: true });
  for (const entry of fs.readdirSync(source, { withFileTypes: true })) {
    if (entry.name === "node_modules" || entry.name === ".git") continue;
    const src = path.join(source, entry.name);
    const dst = path.join(target, entry.name);
    if (entry.isDirectory()) copyDirectory(src, dst);
    else if (entry.isFile()) fs.copyFileSync(src, dst);
  }
}

function commandImport(args) {
  const source = args[0];
  const out = readOption(args, "--out");
  const nameOption = readOption(args, "--name");
  const force = hasFlag(args, "--force");
  if (!source || !out) throw new Error("import 需要源目录与 --out 目标目录");
  const sourceDir = path.resolve(source);
  if (!fs.statSync(sourceDir).isDirectory()) throw new Error("import 目前只接受目录");
  const targetDir = path.resolve(out);
  if (fs.existsSync(targetDir) && !force) {
    throw new Error(`目标已存在: ${targetDir}；需要覆盖请加 --force`);
  }
  if (fs.existsSync(targetDir) && force) fs.rmSync(targetDir, { recursive: true, force: true });

  copyDirectory(sourceDir, targetDir);
  const detected = detectEntry(targetDir);
  const name = nameOption || detected.name;
  const entryPath = path.join(targetDir, detected.entry);
  const sourceText = fs.readFileSync(entryPath, "utf8");
  if (!sourceText.includes("@compat")) {
    fs.writeFileSync(entryPath, `${COMPAT_HEADER}\n${sourceText}`, "utf8");
  }
  const manifest = {
    name,
    entry: detected.entry.replace(/\\/g, "/"),
    compat: "autojs6",
  };
  fs.writeFileSync(
    path.join(targetDir, "izuki.project.json"),
    `${JSON.stringify(manifest, null, 2)}\n`,
    "utf8",
  );
  const analysis = analyzeSource(fs.readFileSync(entryPath, "utf8"), detected.entry);
  console.log(`已导入到 ${targetDir}`);
  console.log(`入口: ${manifest.entry}`);
  console.log(`阻断项: ${analysis.blockers.length}；提示项: ${analysis.signals.length - analysis.blockers.length}`);
  if (analysis.blockers.length > 0) {
    console.log("请先运行 analyze 查看阻断项，再决定是否人工移植。");
  }
}

function main() {
  const args = process.argv.slice(2);
  if (args.length === 0 || args.includes("--help") || args.includes("-h")) {
    usage();
    return;
  }
  const command = args.shift();
  try {
    if (command === "analyze") commandAnalyze(args);
    else if (command === "lint") commandLint(args);
    else if (command === "import") commandImport(args);
    else {
      usage();
      process.exitCode = 1;
    }
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}

main();
