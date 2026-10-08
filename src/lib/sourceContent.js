import { fromHtml } from "hast-util-from-html";

const MARKDOWN_FORMATS = new Set(["markdown", "verbatim-html-markdown"]);

export function isMarkdownContent(format) {
  return MARKDOWN_FORMATS.has(format);
}

function decodeCodeEntities(text) {
  const named = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'" };
  return text.replace(/&(#x[0-9a-f]+|#\d+|amp|lt|gt|quot|apos);/gi, (entity, key) => {
    if (key[0] !== "#") return named[key.toLowerCase()] ?? entity;
    const number = key[1].toLowerCase() === "x" ? parseInt(key.slice(2), 16) : Number(key.slice(1));
    return number > 0 && number <= 0x10ffff && !(number >= 0xd800 && number <= 0xdfff) ? String.fromCodePoint(number) : entity;
  });
}

function codeMarkup(text, block) {
  const run = Math.max(0, ...(text.match(/`+/g) || []).map((value) => value.length));
  const marker = "`".repeat(Math.max(block ? 3 : 1, run + 1));
  return block ? "\n\n" + marker + "\n" + text + (text.endsWith("\n") ? "" : "\n") + marker + "\n\n"
    : marker + " " + text + " " + marker;
}

// This changes the display string only. Markdown code is protected before HTML wrappers are removed.
export function sourceMarkdown(value, tables) {
  const text = typeof value === "string" ? value : String(value ?? "");
  let prefix = "DEVPREP_SOURCE_CODE_";
  while (text.includes(prefix)) prefix += "-";
  const parts = [];
  const store = (part) => prefix + (parts.push(part) - 1) + "_END";
  const token = new RegExp(prefix + "(\\d+)_END", "g");
  const restore = (part) => part.replace(token, (_, index) => restore(parts[Number(index)]));

  let protectedText = "";
  let fenced = "";
  let fenceCharacter = "";
  let fenceLength = 0;
  for (const line of text.match(/[^\n]*\n|[^\n]+$/g) || []) {
    if (fenceCharacter) {
      fenced += line;
      const close = line.match(/^[ \t]{0,3}(`+|~+)[ \t]*\r?$/);
      if (close && close[1][0] === fenceCharacter && close[1].length >= fenceLength) {
        protectedText += store(fenced); fenced = ""; fenceCharacter = "";
      }
    } else {
      const open = line.match(/^[ \t]{0,3}(`{3,}|~{3,})/);
      if (open) { fenceCharacter = open[1][0]; fenceLength = open[1].length; fenced = line; }
      else protectedText += line;
    }
  }
  if (fenced) protectedText += store(fenced);

  let inlineProtected = "";
  let cursor = 0;
  while (cursor < protectedText.length) {
    const start = protectedText.indexOf("`", cursor);
    if (start < 0) { inlineProtected += protectedText.slice(cursor); break; }
    let openingEnd = start;
    while (protectedText[openingEnd] === "`") openingEnd++;
    let candidate = openingEnd;
    let closingEnd = -1;
    while ((candidate = protectedText.indexOf("`", candidate)) >= 0) {
      let end = candidate;
      while (protectedText[end] === "`") end++;
      if (end - candidate === openingEnd - start) { closingEnd = end; break; }
      candidate = end;
    }
    inlineProtected += protectedText.slice(cursor, start);
    if (closingEnd >= 0) { inlineProtected += store(protectedText.slice(start, closingEnd)); cursor = closingEnd; }
    else { inlineProtected += protectedText.slice(start, openingEnd); cursor = openingEnd; }
  }

  const displayed = inlineProtected
    .replace(/<table\b[^>]*>[\s\S]*?<\/table\s*>/gi, (html) => {
      const table = restore(html);
      return store("\n\n" + (tables ? "<!--devprep-source-table:" + (tables.push(table) - 1) + "-->" : table) + "\n\n");
    })
    .replace(/<pre\b[^>]*>([\s\S]*?)<\/pre\s*>/gi, (_, body) => {
      const withoutCodeWrapper = body.replace(/^\s*<code\b[^>]*>([\s\S]*?)<\/code\s*>\s*$/i, "$1");
      return store(codeMarkup(decodeCodeEntities(restore(withoutCodeWrapper)), true));
    })
    .replace(/<code\b[^>]*>([\s\S]*?)<\/code\s*>/gi, (_, body) =>
      store(codeMarkup(decodeCodeEntities(restore(body)), body.includes("\n"))))
    .replace(/<(script|style|iframe|object|template)\b[^>]*>[\s\S]*?<\/\1\s*>/gi, "")
    .replace(/<!--[\s\S]*?-->/g, "")
    .replace(/<br\b[^>]*\/?>/gi, "  \n")
    .replace(/<\/?(?:p|div|details|summary|blockquote)\b[^>]*>/gi, "\n\n")
    .replace(/<\/?(?:b|strong|em|i|u|s|del|span|small)\b[^>]*>/gi, "")
    .replace(/<\/?(?:ul|ol)\b[^>]*>/gi, "\n")
    .replace(/<li\b[^>]*>/gi, "\n- ").replace(/<\/li\s*>/gi, "\n")
    .replace(/<h([1-6])\b[^>]*>/gi, (_, level) => "\n\n" + "#".repeat(Number(level)) + " ")
    .replace(/<\/h[1-6]\s*>/gi, "\n\n")
    .replace(/<([a-z][a-z0-9]*_[a-z0-9_]*)>/gi, "&lt;$1&gt;");
  return restore(displayed);
}

export function sourcePlainText(value) {
  return sourceMarkdown(value)
    .replace(/!\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/\[([^\]]*)\]\([^)]*\)/g, "$1")
    .replace(/<[^>]*>/g, " ")
    .replace(/^[ \t]*(`{3,}|~{3,})[^\n]*$/gm, "")
    .replace(/^[ \t]{0,3}(?:#{1,6}|>|[-*+]|\d+\.)[ \t]+/gm, "")
    .replace(/[`*_~]/g, "")
    .replace(/\s+/g, " ").trim();
}

export function hasSourceFormatting(value) {
  return /[\r\n`*_<>$]|\[[^\]]*\]\(|^#{1,6}\s/.test(sourceMarkdown(value));
}

// Parse source tables into a small, explicit Markdown tree. No source HTML attributes are emitted.
export function remarkSourceTables({ tables = [] } = {}) {
  const processor = this;
  const omitted = new Set(["script", "style", "iframe", "object", "template", "svg", "math"]);
  function cellText(node) {
    if (node.type === "text") return node.value;
    if (omitted.has(node.tagName)) return "";
    if (node.tagName === "br") return "\n";
    if (node.tagName === "img") return String(node.properties?.alt || "");
    const text = (node.children || []).map(cellText).join("");
    return node.tagName === "code" ? codeMarkup(text, false) : text;
  }
  function markdownChildren(value) {
    return processor.parse(sourceMarkdown(value)).children.flatMap((node) => node.type === "paragraph" ? node.children : [node]);
  }
  function span(value) {
    const number = Number(value);
    return Number.isInteger(number) && number > 0 ? Math.min(number, 100) : 1;
  }
  function convert(html) {
    const parsed = fromHtml(html, { fragment: true });
    const rows = [];
    let caption = "";
    function collect(node) {
      if (omitted.has(node.tagName)) return;
      if (node.tagName === "caption") { caption += cellText(node); return; }
      if (node.tagName === "tr") {
        const cells = (node.children || []).filter((cell) => cell.tagName === "td" || cell.tagName === "th");
        rows.push({
          type: "paragraph", data: { hName: "tr" },
          children: cells.map((cell) => ({
            type: "paragraph",
            data: { hName: cell.tagName === "th" || rows.length === 0 ? "th" : "td",
              hProperties: { colSpan: span(cell.properties?.colSpan), rowSpan: span(cell.properties?.rowSpan) } },
            children: markdownChildren(cellText(cell)),
          })),
        });
        return;
      }
      for (const child of node.children || []) collect(child);
    }
    collect(parsed);
    return rows.length ? [{ type: "paragraph", data: { hName: "table" }, children: [
      ...(caption ? [{ type: "paragraph", data: { hName: "caption" }, children: markdownChildren(caption) }] : []),
      { type: "paragraph", data: { hName: "tbody" }, children: rows },
    ] }] : caption ? [{ type: "paragraph", children: markdownChildren(caption) }] : [];
  }
  function visit(node) {
    if (!node.children) return;
    node.children = node.children.flatMap((child) => {
      const marker = child.type === "html" ? child.value.match(/^<!--devprep-source-table:(\d+)-->$/) : null;
      if (marker && tables[Number(marker[1])]) return convert(tables[Number(marker[1])]);
      visit(child);
      return [child];
    });
  }
  return visit;
}
