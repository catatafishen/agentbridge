"use strict";
var __chatUI = (() => {
  // src/helpers.ts
  function b64(s) {
    const r = atob(s);
    const b = new Uint8Array(r.length);
    for (let i = 0; i < r.length; i++) b[i] = r.codePointAt(i);
    return new TextDecoder().decode(b);
  }
  function collapseAllChips(container, except) {
    if (!container) return;
    container.querySelectorAll("tool-chip, thinking-chip, subagent-chip").forEach((chip) => {
      if (chip === except) return;
      const section = chip._linkedSection;
      if (!section || section.classList.contains("turn-hidden")) return;
      chip.style.opacity = "1";
      section.classList.add("turn-hidden");
      section.classList.remove("chip-expanded", "collapsing", "collapsed");
    });
  }
  function escHtml(s) {
    return s ? s.replaceAll("&", "&amp;").replaceAll("<", "&lt;").replaceAll(">", "&gt;") : "";
  }

  // src/components/ChatContainer.ts
  var ChatContainer = class extends HTMLElement {
    _init = false;
    _autoScroll = true;
    _messages;
    _workingIndicator;
    _scrollRAF = null;
    _copyRAF = null;
    _observer;
    _copyObs;
    _prevScrollY = 0;
    _programmaticScroll = false;
    _onScroll = null;
    _onResize = null;
    connectedCallback() {
      if (this._init) return;
      this._init = true;
      this._autoScroll = true;
      this._messages = document.createElement("div");
      this._messages.id = "messages";
      this.appendChild(this._messages);
      this._workingIndicator = document.createElement("working-indicator");
      this.appendChild(this._workingIndicator);
      this._onScroll = () => {
        if (this._programmaticScroll) {
          this._programmaticScroll = false;
          this._prevScrollY = window.scrollY;
          return;
        }
        const atBottom = window.innerHeight + window.scrollY >= document.body.scrollHeight - 40;
        if (atBottom) {
          this._autoScroll = true;
        } else if (window.scrollY < this._prevScrollY) {
          this._autoScroll = false;
          if (window.scrollY <= 50) {
            const lm = this._messages.querySelector("load-more:not([loading])");
            if (lm) lm.click();
          }
        }
        this._prevScrollY = window.scrollY;
      };
      window.addEventListener("scroll", this._onScroll);
      this._onResize = () => {
        if (this._autoScroll) {
          this._programmaticScroll = true;
          window.scrollTo(0, document.body.scrollHeight);
        }
      };
      window.addEventListener("resize", this._onResize);
      this._observer = new MutationObserver(() => {
        if (!this._scrollRAF) {
          this._scrollRAF = requestAnimationFrame(() => {
            this._scrollRAF = null;
            this.scrollIfNeeded();
          });
        }
      });
      this._observer.observe(this._messages, { childList: true, subtree: true, characterData: true });
      this._copyObs = new MutationObserver(() => {
        if (!this._copyRAF) {
          this._copyRAF = requestAnimationFrame(() => {
            this._copyRAF = null;
            this._setupCodeBlocks();
          });
        }
      });
      this._copyObs.observe(this._messages, { childList: true, subtree: true });
    }
    _setupCodeBlocks() {
      this._messages.querySelectorAll("pre:not([data-copy-btn]):not(.streaming)").forEach((pre) => {
        pre.dataset.copyBtn = "1";
        const codeEl = pre.querySelector("code");
        const lang = codeEl?.dataset.lang || "";
        if (lang) {
          const langLabel = document.createElement("span");
          langLabel.className = "code-lang-label";
          langLabel.textContent = lang;
          pre.prepend(langLabel);
        }
        const wrapBtn = document.createElement("button");
        wrapBtn.className = "code-action-btn wrap-btn";
        wrapBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M3 4h10M3 8h7a2 2 0 0 1 0 4H8"/><polyline points="9.5 10.5 8 12 9.5 13.5"/></svg>';
        wrapBtn.title = "Toggle word wrap";
        wrapBtn.onclick = () => {
          pre.classList.toggle("word-wrap");
          wrapBtn.classList.toggle("active", pre.classList.contains("word-wrap"));
        };
        const copyBtn = document.createElement("button");
        copyBtn.className = "code-action-btn copy-btn";
        copyBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="5.5" y="5.5" width="9" height="9" rx="1.5"/><path d="M3.5 10.5H3a1.5 1.5 0 0 1-1.5-1.5V3A1.5 1.5 0 0 1 3 1.5h6A1.5 1.5 0 0 1 10.5 3v.5"/></svg>';
        copyBtn.title = "Copy";
        copyBtn.onclick = () => {
          const code = pre.querySelector("code");
          navigator.clipboard.writeText(code ? code.textContent ?? "" : pre.textContent ?? "").then(
            () => this._resetCopyButton(copyBtn)
          );
        };
        const scratchBtn = document.createElement("button");
        scratchBtn.className = "code-action-btn scratch-btn";
        scratchBtn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><path d="M9 1.5H4a1.5 1.5 0 0 0-1.5 1.5v10A1.5 1.5 0 0 0 4 14.5h8a1.5 1.5 0 0 0 1.5-1.5V6L9 1.5z"/><polyline points="9 1.5 9 6 13.5 6"/></svg>';
        scratchBtn.title = "Open in scratch file";
        scratchBtn.onclick = () => {
          const code = pre.querySelector("code");
          const text = code ? code.textContent ?? "" : pre.textContent ?? "";
          const codeLang = code?.dataset.lang || "";
          globalThis._bridge?.openScratch(codeLang, text);
        };
        const toolbar = document.createElement("div");
        toolbar.className = "code-actions";
        toolbar.append(scratchBtn, wrapBtn, copyBtn);
        pre.prepend(toolbar);
      });
    }
    _resetCopyButton(btn) {
      btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><polyline points="3.5 8.5 6.5 11.5 12.5 4.5"/></svg>';
      btn.title = "Copied!";
      setTimeout(() => {
        btn.innerHTML = '<svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round"><rect x="5.5" y="5.5" width="9" height="9" rx="1.5"/><path d="M3.5 10.5H3a1.5 1.5 0 0 1-1.5-1.5V3A1.5 1.5 0 0 1 3 1.5h6A1.5 1.5 0 0 1 10.5 3v.5"/></svg>';
        btn.title = "Copy";
      }, 1500);
    }
    get messages() {
      return this._messages;
    }
    get workingIndicator() {
      return this._workingIndicator;
    }
    scrollIfNeeded() {
      if (this._autoScroll) {
        this._programmaticScroll = true;
        window.scrollTo(0, document.body.scrollHeight);
      }
    }
    forceScroll() {
      this._autoScroll = true;
      this._programmaticScroll = true;
      window.scrollTo(0, document.body.scrollHeight);
    }
    disconnectedCallback() {
      this._observer?.disconnect();
      this._copyObs?.disconnect();
      if (this._onScroll) window.removeEventListener("scroll", this._onScroll);
      if (this._onResize) window.removeEventListener("resize", this._onResize);
      if (this._scrollRAF) {
        cancelAnimationFrame(this._scrollRAF);
        this._scrollRAF = null;
      }
      if (this._copyRAF) {
        cancelAnimationFrame(this._copyRAF);
        this._copyRAF = null;
      }
    }
  };

  // src/components/ChatMessage.ts
  var ChatMessage = class extends HTMLElement {
    static get observedAttributes() {
      return ["type", "timestamp"];
    }
    _init = false;
    connectedCallback() {
      if (this._init) return;
      this._init = true;
      const type = this.getAttribute("type") || "agent";
      this.classList.add(type === "user" ? "prompt-row" : "agent-row");
    }
    attributeChangedCallback(name, _oldVal, newVal) {
      if (name === "type" && this._init) {
        this.classList.remove("prompt-row", "agent-row");
        this.classList.add(newVal === "user" ? "prompt-row" : "agent-row");
      }
    }
  };

  // src/components/MessageBubble.ts
  var MessageBubble = class extends HTMLElement {
    static get observedAttributes() {
      return ["streaming", "type"];
    }
    _init = false;
    _pre = null;
    connectedCallback() {
      if (this._init) return;
      this._init = true;
      const parent = this.closest("chat-message");
      const isUser = parent?.getAttribute("type") === "user";
      this.classList.add(isUser ? "prompt-bubble" : "agent-bubble");
      if (isUser) {
        this.setAttribute("tabindex", "0");
        this.setAttribute("role", "button");
        this.setAttribute("aria-label", "Toggle message details");
      }
      this.onclick = (e) => {
        if (e.target.closest("a,.turn-chip")) return;
        collapseAllChips(parent);
        const meta = parent?.querySelector("message-meta");
        if (meta) meta.classList.toggle("show");
      };
      if (this.hasAttribute("streaming")) this._setupStreaming();
    }
    _setupStreaming() {
      if (!this._pre) {
        this._pre = document.createElement("pre");
        this._pre.className = "streaming";
        this.innerHTML = "";
        this.appendChild(this._pre);
      }
    }
    appendStreamingText(text) {
      if (!this._pre) this._setupStreaming();
      this._pre.appendChild(document.createTextNode(text));
    }
    finalize(html) {
      this.removeAttribute("streaming");
      this._pre = null;
      this.innerHTML = html;
    }
    get content() {
      return this.innerHTML;
    }
    attributeChangedCallback(name) {
      if (name === "streaming" && this._init) {
        if (this.hasAttribute("streaming")) {
          this._setupStreaming();
        }
      }
    }
  };

  // src/components/MessageMeta.ts
  var CHIP_TAGS = /* @__PURE__ */ new Set(["TOOL-CHIP", "THINKING-CHIP", "SUBAGENT-CHIP"]);
  var stripToMeta = /* @__PURE__ */ new WeakMap();
  var sharedResizeObserver = new ResizeObserver((entries) => {
    for (const entry of entries) {
      stripToMeta.get(entry.target)?.scheduleNavUpdate();
    }
  });
  var activeDragMeta = null;
  document.addEventListener("mousemove", (e) => {
    if (!activeDragMeta) return;
    activeDragMeta.strip.scrollLeft = activeDragMeta.scrollStart - (e.clientX - activeDragMeta.startX);
  });
  document.addEventListener("mouseup", () => {
    if (!activeDragMeta) return;
    activeDragMeta.strip.classList.remove("dragging");
    globalThis._bridge?.setCursor("grab");
    activeDragMeta = null;
  });
  var MessageMeta = class extends HTMLElement {
    _init = false;
    _strip = null;
    _navLeft = null;
    _navRight = null;
    _badge = null;
    _navRAF = null;
    connectedCallback() {
      if (this._init) return;
      this._init = true;
      this.classList.add("meta");
      const existingChips = [];
      for (const child of Array.from(this.children)) {
        if (child instanceof HTMLElement && CHIP_TAGS.has(child.tagName)) {
          existingChips.push(child);
        }
      }
      this._navLeft = this._createNav("\u2039", -1);
      this._strip = document.createElement("div");
      this._strip.className = "chip-strip";
      this._navRight = this._createNav("\u203A", 1);
      this._badge = document.createElement("span");
      this._badge.className = "chip-overflow-count hidden";
      const append = Node.prototype.appendChild.bind(this);
      append(this._navLeft);
      append(this._strip);
      append(this._navRight);
      append(this._badge);
      for (const chip of existingChips) this._strip.appendChild(chip);
      this._strip.addEventListener("scroll", () => this.scheduleNavUpdate(), { passive: true });
      stripToMeta.set(this._strip, this);
      sharedResizeObserver.observe(this._strip);
      this._initDragScroll(this._strip);
    }
    disconnectedCallback() {
      if (this._strip) {
        sharedResizeObserver.unobserve(this._strip);
        stripToMeta.delete(this._strip);
      }
      if (this._navRAF) {
        cancelAnimationFrame(this._navRAF);
        this._navRAF = null;
      }
      if (activeDragMeta?.strip === this._strip) activeDragMeta = null;
    }
    appendChild(node) {
      if (this._strip && node instanceof HTMLElement && CHIP_TAGS.has(node.tagName)) {
        this._strip.appendChild(node);
        this._scrollToEnd();
        return node;
      }
      return Node.prototype.appendChild.call(this, node);
    }
    /** Schedule a nav update coalesced via requestAnimationFrame. */
    scheduleNavUpdate() {
      if (this._navRAF) return;
      this._navRAF = requestAnimationFrame(() => {
        this._navRAF = null;
        this._updateNav();
      });
    }
    _createNav(label, direction) {
      const btn = document.createElement("button");
      btn.className = "chip-nav hidden";
      btn.textContent = label;
      btn.addEventListener("click", (e) => {
        e.stopPropagation();
        this._scrollBy(direction);
      });
      return btn;
    }
    _scrollBy(direction) {
      const strip = this._strip;
      if (!strip) return;
      const chips = Array.from(strip.children);
      if (!chips.length) return;
      if (direction > 0) {
        const visibleRight = strip.scrollLeft + strip.clientWidth;
        const target = chips.find((c) => c.offsetLeft + c.offsetWidth > visibleRight + 1);
        if (target) strip.scrollTo({ left: target.offsetLeft, behavior: "smooth" });
      } else {
        const target = [...chips].reverse().find((c) => c.offsetLeft < strip.scrollLeft - 1);
        if (target) {
          const left = target.offsetLeft + target.offsetWidth - strip.clientWidth;
          strip.scrollTo({ left: Math.max(0, left), behavior: "smooth" });
        }
      }
    }
    _scrollToEnd() {
      const strip = this._strip;
      if (!strip) return;
      requestAnimationFrame(() => {
        strip.scrollTo({ left: strip.scrollWidth, behavior: "smooth" });
      });
    }
    _updateNav() {
      const strip = this._strip;
      if (!strip || !this._navLeft || !this._navRight || !this._badge) return;
      const canScrollLeft = strip.scrollLeft > 1;
      const canScrollRight = strip.scrollLeft + strip.clientWidth < strip.scrollWidth - 1;
      this._navLeft.classList.toggle("hidden", !canScrollLeft);
      this._navRight.classList.toggle("hidden", !canScrollRight);
      if (canScrollRight) {
        const visibleRight = strip.scrollLeft + strip.clientWidth;
        const chips = Array.from(strip.children);
        const hiddenCount = chips.filter((c) => c.offsetLeft >= visibleRight).length;
        if (hiddenCount > 0) {
          this._badge.textContent = "+" + hiddenCount;
          this._badge.classList.remove("hidden");
        } else {
          this._badge.classList.add("hidden");
        }
      } else {
        this._badge.classList.add("hidden");
      }
    }
    _initDragScroll(strip) {
      strip.addEventListener("mousedown", (e) => {
        if (e.button !== 0) return;
        activeDragMeta = { strip, startX: e.clientX, scrollStart: strip.scrollLeft };
        strip.classList.add("dragging");
        globalThis._bridge?.setCursor("grabbing");
        e.preventDefault();
      });
    }
  };

  // src/components/ThinkingBlock.ts
  var ThinkingBlock = class extends HTMLElement {
    _init = false;
    connectedCallback() {
      if (this._init) return;
      this._init = true;
      this.classList.add("thinking-section");
      this.innerHTML = `<div class="thinking-content"></div>`;
    }
    get contentEl() {
      return this.querySelector(".thinking-content");
    }
    appendText(text) {
      const el = this.contentEl;
      if (el) el.textContent += text;
    }
    finalize() {
      this.removeAttribute("active");
    }
  };

  // src/toolDisplayName.ts
  function shortPath(p) {
    if (!p) return "";
    const parts = p.replaceAll("\\", "/").split("/");
    return parts.at(-1) ?? "";
  }
  function trunc(s, max = 24) {
    return s.length > max ? s.substring(0, max - 1) + "\u2026" : s;
  }
  function shortClass(fqn) {
    const i = fqn.lastIndexOf(".");
    return i >= 0 ? fqn.substring(i + 1) : fqn;
  }
  function toolDisplayName(rawTitle, paramsJson) {
    const name = rawTitle.replace(/^intellij[-_]code[-_]tools[-_]/i, "").replace(/^github[-_]mcp[-_]server[-_]/i, "gh:");
    let p = {};
    if (paramsJson) {
      try {
        p = JSON.parse(paramsJson);
      } catch {
      }
    }
    const file = shortPath(p.path || p.file || p.scope || "");
    const map = {
      // File operations
      "intellij_read_file": () => file ? `Reading ${file}` : "Reading file",
      "intellij_write_file": () => file ? `Editing ${file}` : "Editing file",
      "create_file": () => file ? `Creating ${file}` : "Creating file",
      "delete_file": () => file ? `Deleting ${file}` : "Deleting file",
      "open_in_editor": () => file ? `Opening ${file}` : "Opening file",
      "show_diff": () => file ? `Diff ${file}` : "Showing diff",
      "undo": () => file ? `Undo in ${file}` : "Undoing",
      // Search & navigation
      "search_text": () => p.query ? `Searching \u201C${trunc(p.query, 20)}\u201D` : "Searching text",
      "search_symbols": () => p.query ? `Finding \u201C${trunc(p.query, 20)}\u201D` : "Finding symbols",
      "find_references": () => p.symbol ? `Refs: ${p.symbol}` : "Finding references",
      "go_to_declaration": () => p.symbol ? `Go to ${p.symbol}` : "Go to declaration",
      "get_file_outline": () => file ? `Outline ${file}` : "File outline",
      "get_class_outline": () => p.class_name ? `Outline ${shortClass(p.class_name)}` : "Class outline",
      "get_type_hierarchy": () => p.symbol ? `Hierarchy: ${p.symbol}` : "Type hierarchy",
      "get_documentation": () => p.symbol ? `Docs: ${trunc(p.symbol, 28)}` : "Getting docs",
      "list_project_files": () => "Listing files",
      "list_tests": () => "Listing tests",
      // Code quality
      "format_code": () => file ? `Formatting ${file}` : "Formatting code",
      "optimize_imports": () => file ? `Imports ${file}` : "Optimizing imports",
      "run_inspections": () => file ? `Inspecting ${file}` : "Running inspections",
      "get_compilation_errors": () => "Checking compilation",
      "get_problems": () => "Getting problems",
      "get_highlights": () => "Getting highlights",
      "apply_quickfix": () => "Applying quickfix",
      "suppress_inspection": () => "Suppressing inspection",
      "add_to_dictionary": () => p.word ? `Adding \u201C${p.word}\u201D to dictionary` : "Adding to dictionary",
      "run_qodana": () => "Running Qodana",
      "run_sonarqube_analysis": () => "Running SonarQube",
      // Refactoring
      "refactor": () => {
        if (p.operation === "rename") return `Renaming ${p.symbol || ""}`;
        return p.operation ? `Refactor: ${p.operation}` : "Refactoring";
      },
      // Build & run
      "build_project": () => "Building project",
      "run_command": () => {
        if (p.title) return trunc(p.title, 32);
        return p.command ? `Running ${trunc(p.command, 24)}` : "Running command";
      },
      "run_tests": () => p.target ? `Testing ${trunc(p.target, 24)}` : "Running tests",
      "get_test_results": () => "Test results",
      "get_coverage": () => "Getting coverage",
      "run_configuration": () => p.name ? `Running \u201C${trunc(p.name, 20)}\u201D` : "Running config",
      "create_run_configuration": () => p.name ? `Creating config \u201C${trunc(p.name, 16)}\u201D` : "Creating run config",
      "edit_run_configuration": () => p.name ? `Editing config \u201C${trunc(p.name, 16)}\u201D` : "Editing run config",
      "list_run_configurations": () => "Listing run configs",
      // Git
      "git_status": () => "Git status",
      "git_diff": () => file ? `Git diff ${file}` : "Git diff",
      "git_commit": () => "Git commit",
      "git_stage": () => file ? `Staging ${file}` : "Git stage",
      "git_unstage": () => file ? `Unstaging ${file}` : "Git unstage",
      "git_log": () => "Git log",
      "git_blame": () => file ? `Blame ${file}` : "Git blame",
      "git_show": () => "Git show",
      "git_branch": () => {
        if (p.action === "switch") return `Switch to ${p.name}`;
        return p.action === "create" ? `Create branch ${p.name}` : "Git branch";
      },
      "git_stash": () => p.action ? `Git stash ${p.action}` : "Git stash",
      // IDE
      "get_project_info": () => "Project info",
      "read_ide_log": () => "Reading IDE log",
      "get_notifications": () => "Getting notifications",
      "read_run_output": () => "Reading run output",
      "run_in_terminal": () => "Running in terminal",
      "read_terminal_output": () => "Reading terminal",
      "download_sources": () => "Downloading sources",
      "create_scratch_file": () => p.name ? `Scratch: ${p.name}` : "Creating scratch",
      "list_scratch_files": () => "Listing scratches",
      "get_indexing_status": () => "Indexing status",
      "mark_directory": () => "Marking directory",
      "get_chat_html": () => "Getting chat HTML",
      "http_request": () => p.url ? `${p.method || "GET"} ${trunc(p.url, 28)}` : "HTTP request",
      // GitHub MCP tools (after prefix stripped to "gh:*")
      "gh:get_file_contents": () => p.path ? `GH: ${shortPath(p.path)}` : "GH: get file",
      "gh:search_code": () => "GH: search code",
      "gh:search_repositories": () => "GH: search repos",
      "gh:search_issues": () => "GH: search issues",
      "gh:search_pull_requests": () => "GH: search PRs",
      "gh:search_users": () => "GH: search users",
      "gh:list_issues": () => "GH: list issues",
      "gh:list_pull_requests": () => "GH: list PRs",
      "gh:list_commits": () => "GH: list commits",
      "gh:list_branches": () => "GH: list branches",
      "gh:get_commit": () => "GH: get commit",
      "gh:issue_read": () => p.issue_number ? `GH: issue #${p.issue_number}` : "GH: read issue",
      "gh:pull_request_read": () => p.pullNumber ? `GH: PR #${p.pullNumber}` : "GH: read PR",
      "gh:actions_list": () => "GH: list actions",
      "gh:actions_get": () => "GH: get action",
      "gh:get_job_logs": () => "GH: job logs"
    };
    const fn = map[name];
    return fn ? fn() : name;
  }

  // src/components/ToolChip.ts
  var ToolChip = class extends HTMLElement {
    static get observedAttributes() {
      return ["label", "status", "expanded", "kind"];
    }
    _init = false;
    connectedCallback() {
      if (this._init) return;
      this._init = true;
      this.classList.add("turn-chip", "tool");
      this.setAttribute("role", "button");
      this.setAttribute("tabindex", "0");
      this.setAttribute("aria-expanded", "false");
      this._render();
      this.onclick = (e) => {
        e.stopPropagation();
        this._showPopup();
      };
      this.onkeydown = (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          this._showPopup();
        }
      };
    }
    _render() {
      const rawLabel = this.getAttribute("label") || "";
      const status = this.getAttribute("status") || "running";
      const kind = this.getAttribute("kind") || "other";
      const paramsStr = this.dataset.params || void 0;
      const display = toolDisplayName(rawLabel, paramsStr);
      const truncated = display.length > 50 ? display.substring(0, 47) + "\u2026" : display;
      this.className = this.className.replaceAll(/\bkind-\S+/g, "").trim();
      this.classList.add("turn-chip", "tool", `kind-${kind}`);
      let iconHtml = "";
      if (status === "running") iconHtml = '<span class="chip-spinner"></span> ';
      else if (status === "failed") this.classList.add("failed");
      this.innerHTML = iconHtml + escHtml(truncated);
      if (display.length > 50) this.dataset.tip = display;
      else if (rawLabel !== display) this.dataset.tip = rawLabel;
      if (this.dataset.tip) this.setAttribute("title", this.dataset.tip);
    }
    _showPopup() {
      const id = this.dataset.chipFor || "";
      if (id && globalThis._bridge?.showToolPopup) {
        globalThis._bridge.showToolPopup(id);
      }
    }
    attributeChangedCallback(name) {
      if (!this._init) return;
      if (name === "status" || name === "kind") this._render();
    }
  };

  // src/components/ThinkingChip.ts
  var ThinkingChip = class extends HTMLElement {
    static get observedAttributes() {
      return ["status"];
    }
    _init = false;
    _linkedSection = null;
    connectedCallback() {
      if (this._init) return;
      this._init = true;
      this.classList.add("turn-chip");
      this.setAttribute("role", "button");
      this.setAttribute("tabindex", "0");
      this.setAttribute("aria-expanded", "false");
      this._render();
      this.onclick = (e) => {
        e.stopPropagation();
        this._toggleExpand();
      };
      this.onkeydown = (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          this._toggleExpand();
        }
      };
    }
    _render() {
      const status = this.getAttribute("status") || "complete";
      if (status === "running" || status === "thinking") {
        this.innerHTML = '<span class="thought-bubble">\u{1F4AD}</span> Thinking\u2026';
        this.classList.add("thinking-active");
      } else {
        this.textContent = "\u{1F4AD} Thought";
        this.classList.remove("thinking-active");
      }
    }
    attributeChangedCallback(name) {
      if (!this._init) return;
      if (name === "status") this._render();
    }
    _resolveLink() {
      if (!this._linkedSection && this.dataset.chipFor) {
        this._linkedSection = document.getElementById(this.dataset.chipFor);
      }
    }
    _toggleExpand() {
      this._resolveLink();
      const section = this._linkedSection;
      if (!section) return;
      collapseAllChips(this.closest("chat-message"), this);
      if (section.classList.contains("turn-hidden")) {
        section.classList.remove("turn-hidden");
        section.classList.add("chip-expanded");
        this.classList.add("chip-dimmed");
        this.setAttribute("aria-expanded", "true");
      } else {
        this.classList.remove("chip-dimmed");
        section.classList.add("collapsing");
        setTimeout(() => {
          section.classList.remove("collapsing", "chip-expanded");
          section.classList.add("turn-hidden");
        }, 250);
        this.setAttribute("aria-expanded", "false");
      }
    }
    linkSection(section) {
      this._linkedSection = section;
    }
  };

  // src/components/SubagentChip.ts
  var SubagentChip = class extends HTMLElement {
    static get observedAttributes() {
      return ["label", "status", "color-index"];
    }
    _init = false;
    _linkedSection = null;
    connectedCallback() {
      if (this._init) return;
      this._init = true;
      const ci = this.getAttribute("color-index") || "0";
      this.classList.add("turn-chip", "subagent", "subagent-c" + ci);
      this.setAttribute("role", "button");
      this.setAttribute("tabindex", "0");
      this.setAttribute("aria-expanded", "false");
      this._render();
      this.onclick = (e) => {
        e.stopPropagation();
        this._toggleExpand();
      };
      this.onkeydown = (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          this._toggleExpand();
        }
      };
    }
    _render() {
      const label = this.getAttribute("label") || "";
      const status = this.getAttribute("status") || "running";
      const display = label.length > 50 ? label.substring(0, 47) + "\u2026" : label;
      let html = "";
      if (status === "running") html = '<span class="chip-spinner"></span> ';
      else if (status === "failed") this.classList.add("failed");
      html += label.length > 50 ? "<span>" + display + "</span>" : display;
      this.innerHTML = html;
    }
    _resolveLink() {
      if (!this._linkedSection && this.dataset.chipFor) {
        this._linkedSection = document.getElementById(this.dataset.chipFor);
      }
    }
    _toggleExpand() {
      this._resolveLink();
      const section = this._linkedSection;
      if (!section) return;
      collapseAllChips(this.closest("chat-message"), this);
      if (section.classList.contains("turn-hidden")) {
        section.classList.remove("turn-hidden", "collapsed");
        section.classList.add("chip-expanded");
        this.classList.add("chip-dimmed");
        this.setAttribute("aria-expanded", "true");
      } else {
        this.classList.remove("chip-dimmed");
        section.classList.add("collapsing");
        setTimeout(() => {
          section.classList.remove("collapsing", "chip-expanded");
          section.classList.add("turn-hidden", "collapsed");
        }, 250);
        this.setAttribute("aria-expanded", "false");
      }
    }
    linkSection(section) {
      this._linkedSection = section;
    }
    attributeChangedCallback(name) {
      if (!this._init) return;
      if (name === "status" || name === "label") this._render();
    }
  };

  // src/components/QuickReplies.ts
  var QuickReplies = class _QuickReplies extends HTMLElement {
    static get observedAttributes() {
      return ["disabled"];
    }
    _init = false;
    connectedCallback() {
      if (this._init) return;
      this._init = true;
      this.classList.add("quick-replies");
    }
    /** Valid semantic color suffixes for quick-reply buttons. */
    static COLORS = /* @__PURE__ */ new Set(["danger", "primary", "success", "warning"]);
    /** Suffix that marks a button as dismiss-only (hides buttons, sends no message). */
    static DISMISS = "dismiss";
    set options(arr) {
      this.innerHTML = "";
      (arr || []).forEach((raw) => {
        const { label, color, dismiss } = _QuickReplies.parseOption(raw);
        const btn = document.createElement("button");
        btn.type = "button";
        btn.className = "quick-reply-btn" + (color ? ` qr-${color}` : "") + (dismiss ? " qr-dismiss" : "");
        btn.textContent = label;
        btn.onclick = () => {
          if (this.hasAttribute("disabled")) return;
          this.setAttribute("disabled", "");
          if (!dismiss) {
            this.dispatchEvent(new CustomEvent("quick-reply", { detail: { text: label }, bubbles: true }));
          }
        };
        this.appendChild(btn);
      });
    }
    /**
     * Parse "Label:color" or "Label:dismiss" suffix. Only recognized semantic colors
     * and the dismiss keyword are stripped; colons in the label text itself are preserved.
     */
    static parseOption(raw) {
      const idx = raw.lastIndexOf(":");
      if (idx > 0) {
        const candidate = raw.substring(idx + 1).trim().toLowerCase();
        if (candidate === _QuickReplies.DISMISS) {
          return { label: raw.substring(0, idx).trim(), color: null, dismiss: true };
        }
        if (_QuickReplies.COLORS.has(candidate)) {
          return { label: raw.substring(0, idx).trim(), color: candidate, dismiss: false };
        }
      }
      return { label: raw, color: null, dismiss: false };
    }
    attributeChangedCallback(name) {
      if (name === "disabled") this.classList.toggle("disabled", this.hasAttribute("disabled"));
    }
  };

  // src/components/SessionDivider.ts
  var SessionDivider = class extends HTMLElement {
    static get observedAttributes() {
      return ["timestamp", "agent"];
    }
    _init = false;
    connectedCallback() {
      if (this._init) return;
      this._init = true;
      this.classList.add("session-sep");
      this.setAttribute("role", "separator");
      this._render();
    }
    _render() {
      const ts = this.getAttribute("timestamp") || "";
      const agent = this.getAttribute("agent") || "";
      const label = agent ? `New session \u2014 ${escHtml(ts)} \xB7 ${escHtml(agent)}` : `New session \u2014 ${escHtml(ts)}`;
      this.setAttribute("aria-label", label);
      this.innerHTML = `<span class="session-sep-line"></span><span class="session-sep-label">${label}</span><span class="session-sep-line"></span>`;
    }
    attributeChangedCallback() {
      if (this._init) this._render();
    }
  };

  // src/components/LoadMore.ts
  var LoadMore = class extends HTMLElement {
    static get observedAttributes() {
      return ["count", "loading"];
    }
    _init = false;
    connectedCallback() {
      if (this._init) return;
      this._init = true;
      this.classList.add("load-more-banner");
      this.setAttribute("role", "button");
      this.setAttribute("tabindex", "0");
      this.setAttribute("aria-label", "Load earlier messages");
      this._render();
      this.onclick = () => {
        if (!this.hasAttribute("loading")) {
          this.setAttribute("loading", "");
          this.dispatchEvent(new CustomEvent("load-more", { bubbles: true }));
        }
      };
      this.onkeydown = (e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          this.click();
        }
      };
    }
    _render() {
      const count = this.getAttribute("count") || "?";
      const loading = this.hasAttribute("loading");
      this.innerHTML = `<span class="load-more-text">${loading ? "Loading..." : "\u25B2 Load earlier messages (" + count + " more) \u2014 click or scroll up"}</span>`;
    }
    attributeChangedCallback() {
      if (this._init) this._render();
    }
  };

  // src/components/TurnDetails.ts
  var TurnDetails = class extends HTMLElement {
    _init = false;
    connectedCallback() {
      if (this._init) return;
      this._init = true;
      this.classList.add("turn-details");
    }
  };

  // src/ChatController.ts
  var ChatController = {
    _msgs() {
      return document.querySelector("#messages");
    },
    _container() {
      return document.querySelector("chat-container");
    },
    _resetWorkingTimer() {
      const wi = this._container()?.workingIndicator;
      if (wi && !wi.hidden) wi.resetTimer();
    },
    _thinkingCounter: 0,
    _profileColors: {},
    _nextProfileColor: 0,
    _currentProfile: "",
    _ctx: {},
    _getCtx(turnId, agentId) {
      const key = turnId + "-" + agentId;
      if (!this._ctx[key]) {
        this._ctx[key] = {
          msg: null,
          meta: null,
          details: null,
          textBubble: null,
          thinkingBlock: null
        };
      }
      return this._ctx[key];
    },
    _ensureMsg(turnId, agentId) {
      const ctx = this._getCtx(turnId, agentId);
      if (!ctx.msg) {
        const msg = document.createElement("chat-message");
        msg.setAttribute("type", "agent");
        if (this._currentProfile && agentId === "main") {
          if (!(this._currentProfile in this._profileColors)) {
            this._profileColors[this._currentProfile] = this._nextProfileColor++ % 6;
          }
          msg.classList.add("model-c" + this._profileColors[this._currentProfile]);
        }
        const meta = document.createElement("message-meta");
        meta.className = "meta";
        const now = /* @__PURE__ */ new Date();
        const ts = String(now.getHours()).padStart(2, "0") + ":" + String(now.getMinutes()).padStart(2, "0");
        const tsSpan = document.createElement("span");
        tsSpan.className = "ts";
        tsSpan.textContent = ts;
        meta.appendChild(tsSpan);
        msg.appendChild(meta);
        const details = document.createElement("turn-details");
        msg.appendChild(details);
        this._msgs().appendChild(msg);
        ctx.msg = msg;
        ctx.meta = meta;
        ctx.details = details;
      }
      return ctx;
    },
    _collapseThinkingFor(ctx) {
      if (!ctx?.thinkingBlock) return;
      ctx.thinkingBlock.removeAttribute("active");
      ctx.thinkingBlock.removeAttribute("expanded");
      ctx.thinkingBlock.classList.add("turn-hidden");
      if (ctx.thinkingChip) {
        ctx.thinkingChip.setAttribute("status", "complete");
        ctx.thinkingChip = null;
      }
      ctx.thinkingBlock = null;
      ctx.thinkingMsg = null;
    },
    newSegment(turnId, agentId) {
      const ctx = this._getCtx(turnId, agentId);
      if (ctx.textBubble) {
        ctx.textBubble.removeAttribute("streaming");
        const p = ctx.textBubble.querySelector(".pending");
        if (p) p.remove();
      }
      this._collapseThinkingFor(ctx);
      ctx.msg = null;
      ctx.meta = null;
      ctx.details = null;
      ctx.textBubble = null;
    },
    // ── Public API ─────────────────────────────────────────────
    addUserMessage(text, timestamp, encodedBubbleHtml) {
      const msg = document.createElement("chat-message");
      msg.setAttribute("type", "user");
      const meta = document.createElement("message-meta");
      meta.innerHTML = '<span class="ts">' + timestamp + "</span>";
      msg.appendChild(meta);
      const bubble = document.createElement("message-bubble");
      bubble.setAttribute("type", "user");
      if (encodedBubbleHtml) {
        bubble.innerHTML = b64(encodedBubbleHtml);
      } else {
        bubble.textContent = text;
      }
      msg.appendChild(bubble);
      this._msgs().appendChild(msg);
      this._container()?.forceScroll();
    },
    appendAgentText(turnId, agentId, text) {
      try {
        this._resetWorkingTimer();
        const ctx = this._getCtx(turnId, agentId);
        this._collapseThinkingFor(ctx);
        if (!ctx.textBubble) {
          if (!text.trim()) return;
          const c = this._ensureMsg(turnId, agentId);
          const bubble = document.createElement("message-bubble");
          bubble.setAttribute("streaming", "");
          c.msg.appendChild(bubble);
          c.textBubble = bubble;
        }
        ctx.textBubble.appendStreamingText(text);
        this._container()?.scrollIfNeeded();
      } catch (e) {
        console.error("[appendAgentText ERROR]", e.message, e.stack);
      }
    },
    finalizeAgentText(turnId, agentId, encodedHtml) {
      try {
        const ctx = this._getCtx(turnId, agentId);
        if (!ctx.textBubble && !encodedHtml) return;
        if (encodedHtml) {
          if (ctx.textBubble) {
            ctx.textBubble.finalize(b64(encodedHtml));
          } else {
            const c = this._ensureMsg(turnId, agentId);
            const bubble = document.createElement("message-bubble");
            c.msg.appendChild(bubble);
            bubble.finalize(b64(encodedHtml));
          }
        } else if (ctx.textBubble) {
          ctx.textBubble.remove();
          if (ctx.msg && !ctx.msg.querySelector("message-bubble, tool-chip, thinking-block")) {
            ctx.msg.remove();
            ctx.msg = null;
            ctx.meta = null;
          }
        }
        ctx.textBubble = null;
        this._container()?.scrollIfNeeded();
      } catch (e) {
        console.error("[finalizeAgentText ERROR]", e.message, e.stack);
      }
    },
    addThinkingText(turnId, agentId, text) {
      this._resetWorkingTimer();
      const ctx = this._ensureMsg(turnId, agentId);
      if (!ctx.thinkingBlock) {
        this._thinkingCounter++;
        const el = document.createElement("thinking-block");
        el.id = "think-" + this._thinkingCounter;
        el.setAttribute("active", "");
        el.setAttribute("expanded", "");
        ctx.details.appendChild(el);
        ctx.thinkingBlock = el;
        const chip = document.createElement("thinking-chip");
        chip.setAttribute("status", "thinking");
        chip.dataset.chipFor = el.id;
        chip.linkSection(el);
        ctx.meta.appendChild(chip);
        ctx.meta.classList.add("show");
        ctx.thinkingChip = chip;
      }
      ctx.thinkingBlock.appendText(text);
      this._container()?.scrollIfNeeded();
    },
    collapseThinking(turnId, agentId) {
      const ctx = this._getCtx(turnId, agentId);
      this._collapseThinkingFor(ctx);
    },
    addToolCall(turnId, agentId, id, title, paramsJson, kind) {
      this._resetWorkingTimer();
      const ctx = this._ensureMsg(turnId, agentId);
      this._collapseThinkingFor(ctx);
      const chip = document.createElement("tool-chip");
      chip.setAttribute("label", title);
      chip.setAttribute("status", "running");
      if (kind) chip.setAttribute("kind", kind);
      chip.dataset.chipFor = id;
      if (paramsJson) chip.dataset.params = paramsJson;
      ctx.meta.appendChild(chip);
      ctx.meta.classList.add("show");
      this._container()?.scrollIfNeeded();
    },
    updateToolCall(id, status, resultHtml) {
      this._resetWorkingTimer();
      const chip = document.querySelector('[data-chip-for="' + id + '"]');
      if (chip) chip.setAttribute("status", status === "failed" ? "failed" : "complete");
    },
    addSubAgent(turnId, agentId, sectionId, displayName, colorIndex, promptText) {
      this._resetWorkingTimer();
      const ctx = this._ensureMsg(turnId, agentId);
      this._collapseThinkingFor(ctx);
      ctx.textBubble = null;
      const chip = document.createElement("subagent-chip");
      chip.setAttribute("label", displayName);
      chip.setAttribute("status", "running");
      chip.setAttribute("color-index", String(colorIndex));
      chip.dataset.chipFor = "sa-" + sectionId;
      ctx.meta.appendChild(chip);
      ctx.meta.classList.add("show");
      const promptBubble = document.createElement("message-bubble");
      promptBubble.innerHTML = '<span class="subagent-prefix subagent-c' + colorIndex + '">@' + escHtml(displayName) + "</span> " + escHtml(promptText || "");
      ctx.msg.appendChild(promptBubble);
      const msg = document.createElement("chat-message");
      msg.setAttribute("type", "agent");
      msg.id = "sa-" + sectionId;
      msg.classList.add("subagent-indent", "subagent-c" + colorIndex);
      const meta = document.createElement("message-meta");
      meta.className = "meta show";
      const now = /* @__PURE__ */ new Date();
      const ts = String(now.getHours()).padStart(2, "0") + ":" + String(now.getMinutes()).padStart(2, "0");
      const tsSpan = document.createElement("span");
      tsSpan.className = "ts";
      tsSpan.textContent = ts;
      meta.appendChild(tsSpan);
      const nameSpan = document.createElement("span");
      nameSpan.className = "agent-name";
      nameSpan.textContent = displayName;
      meta.appendChild(nameSpan);
      msg.appendChild(meta);
      const saDetails = document.createElement("turn-details");
      msg.appendChild(saDetails);
      const resultBubble = document.createElement("message-bubble");
      resultBubble.id = "result-" + sectionId;
      resultBubble.classList.add("subagent-result");
      msg.appendChild(resultBubble);
      this._msgs().appendChild(msg);
      chip.linkSection(msg);
      this._container()?.scrollIfNeeded();
    },
    updateSubAgent(sectionId, status, resultHtml) {
      const el = document.getElementById("result-" + sectionId);
      if (el) {
        el.innerHTML = resultHtml || (status === "completed" ? "Completed" : '<span style="color:var(--error)">\u2716 Failed</span>');
      }
      const chip = document.querySelector('[data-chip-for="sa-' + sectionId + '"]');
      if (chip) chip.setAttribute("status", status === "failed" ? "failed" : "complete");
      this._container()?.scrollIfNeeded();
    },
    addSubAgentToolCall(subAgentDomId, toolDomId, title, paramsJson, kind) {
      const msg = document.getElementById("sa-" + subAgentDomId);
      if (!msg) return;
      const meta = msg.querySelector("message-meta");
      const chip = document.createElement("tool-chip");
      chip.setAttribute("label", title);
      chip.setAttribute("status", "running");
      if (kind) chip.setAttribute("kind", kind);
      chip.dataset.chipFor = toolDomId;
      if (paramsJson) chip.dataset.params = paramsJson;
      if (meta) {
        meta.appendChild(chip);
        meta.classList.add("show");
      }
      this._container()?.scrollIfNeeded();
    },
    addSessionSeparator(timestamp, agent = "") {
      const el = document.createElement("session-divider");
      el.setAttribute("timestamp", timestamp);
      if (agent) el.setAttribute("agent", agent);
      this._msgs().appendChild(el);
    },
    showPlaceholder(text) {
      this.clear();
      this._msgs().innerHTML = '<div class="placeholder">' + escHtml(text) + "</div>";
    },
    clear() {
      this.hideWorkingIndicator();
      this._msgs().innerHTML = "";
      this._ctx = {};
      this._thinkingCounter = 0;
      this._profileColors = {};
      this._nextProfileColor = 0;
      this._currentProfile = "";
    },
    finalizeTurn(turnId, statsJson) {
      this.hideWorkingIndicator();
      const ctx = this._ctx[turnId + "-main"];
      if (ctx?.textBubble && !ctx.textBubble.textContent?.trim()) {
        ctx.textBubble.remove();
      }
      if (ctx) {
        ctx.thinkingBlock = null;
        ctx.textBubble = null;
      }
      this._container()?.scrollIfNeeded();
      this._trimMessages();
    },
    showPermissionRequest(turnId, agentId, reqId, toolDisplayName2, contextJson) {
      this.disableQuickReplies();
      const ctx = this._ensureMsg(turnId, agentId);
      this._collapseThinkingFor(ctx);
      let questionHtml = `Can I use <strong>${escHtml(toolDisplayName2)}</strong>?`;
      let argsJson = "";
      try {
        const parsed = JSON.parse(contextJson);
        if (parsed.question) questionHtml = escHtml(parsed.question);
        if (parsed.args && Object.keys(parsed.args).length > 0) argsJson = JSON.stringify(parsed.args);
      } catch {
      }
      const bubble = document.createElement("message-bubble");
      bubble.innerHTML = questionHtml;
      ctx.msg.appendChild(bubble);
      const actions = document.createElement("permission-request");
      actions.setAttribute("req-id", reqId);
      if (argsJson) actions.setAttribute("args", argsJson);
      ctx.msg.appendChild(actions);
      this._container()?.scrollIfNeeded();
    },
    showQuickReplies(options) {
      this.disableQuickReplies();
      if (!options?.length) return;
      const el = document.createElement("quick-replies");
      el.options = options;
      this._msgs().appendChild(el);
      this._container()?.scrollIfNeeded();
    },
    disableQuickReplies() {
      document.querySelectorAll("quick-replies:not([disabled])").forEach((el) => el.setAttribute("disabled", ""));
    },
    cancelAllRunning() {
      this.hideWorkingIndicator();
      document.querySelectorAll('tool-chip[status="running"]').forEach((c) => c.setAttribute("status", "failed"));
      document.querySelectorAll('thinking-chip[status="running"], thinking-chip[status="thinking"]').forEach((c) => c.setAttribute("status", "complete"));
      document.querySelectorAll('subagent-chip[status="running"]').forEach((c) => c.setAttribute("status", "failed"));
      document.querySelectorAll("message-bubble[streaming]").forEach((b) => b.removeAttribute("streaming"));
    },
    setPromptStats(model, multiplier) {
      const rows = document.querySelectorAll(".prompt-row");
      const row = rows[rows.length - 1];
      if (!row) return;
      let meta = row.querySelector("message-meta");
      if (!meta) {
        meta = document.createElement("message-meta");
        row.insertBefore(meta, row.firstChild);
      }
      meta.classList.add("show");
      const chip = document.createElement("span");
      chip.className = "turn-chip stats";
      chip.textContent = multiplier;
      chip.dataset.tip = model;
      chip.setAttribute("title", model);
      meta.appendChild(chip);
    },
    setCurrentProfile(profileId) {
      this._currentProfile = profileId;
    },
    setCurrentModel(modelId) {
    },
    restoreBatch(encodedHtml) {
      const html = b64(encodedHtml);
      const temp = document.createElement("div");
      temp.innerHTML = html;
      const msgs = this._msgs();
      const loadMore = msgs.querySelector("load-more");
      const insertBefore = loadMore ? loadMore.nextSibling : msgs.firstChild;
      const prevScrollY = window.scrollY;
      const prevHeight = document.body.scrollHeight;
      while (temp.firstChild) {
        msgs.insertBefore(temp.firstChild, insertBefore);
      }
      if (prevScrollY <= 50) {
        const addedHeight = document.body.scrollHeight - prevHeight;
        if (addedHeight > 0) window.scrollBy(0, addedHeight);
      }
    },
    showLoadMore(count) {
      let el = document.querySelector("load-more");
      if (!el) {
        el = document.createElement("load-more");
        this._msgs().insertBefore(el, this._msgs().firstChild);
      }
      el.setAttribute("count", String(count));
      el.removeAttribute("loading");
    },
    removeLoadMore() {
      document.querySelector("load-more")?.remove();
    },
    showWorkingIndicator() {
      this._container()?.workingIndicator?.show();
      this._container()?.scrollIfNeeded();
    },
    hideWorkingIndicator() {
      this._container()?.workingIndicator?.hide();
    },
    _trimMessages() {
      const msgs = this._msgs();
      if (!msgs) return;
      const rows = Array.from(msgs.children).filter(
        (c) => c.tagName === "CHAT-MESSAGE"
      );
      if (rows.length > 80) {
        const trimCount = rows.length - 80;
        for (let i = 0; i < trimCount; i++) rows[i].remove();
      }
    }
  };
  var ChatController_default = ChatController;

  // src/components/PermissionRequest.ts
  var PermissionRequest = class extends HTMLElement {
    _init = false;
    static get observedAttributes() {
      return ["resolved", "args"];
    }
    connectedCallback() {
      if (this._init) return;
      this._init = true;
      this._render();
    }
    _render() {
      const reqId = this.getAttribute("req-id") || "";
      this.className = "perm-actions";
      const argsAttr = this.getAttribute("args");
      if (argsAttr) {
        try {
          const args = JSON.parse(argsAttr);
          const entries = Object.entries(args).filter(([, v]) => v !== null && v !== void 0 && v !== false && v !== "");
          if (entries.length > 0) {
            const table = document.createElement("div");
            table.className = "perm-args";
            for (const [key, value] of entries) {
              const row = document.createElement("div");
              row.className = "perm-arg-row";
              const label = document.createElement("span");
              label.className = "perm-arg-key";
              label.textContent = key;
              const val = document.createElement("span");
              val.className = "perm-arg-val";
              const strVal = Array.isArray(value) ? value.join(", ") : String(value);
              val.title = strVal;
              val.textContent = strVal.length > 80 ? strVal.slice(0, 77) + "\u2026" : strVal;
              row.appendChild(label);
              row.appendChild(val);
              table.appendChild(row);
            }
            this.appendChild(table);
          }
        } catch {
        }
      }
      const denyBtn = document.createElement("button");
      denyBtn.type = "button";
      denyBtn.className = "quick-reply-btn perm-deny";
      denyBtn.textContent = "Deny";
      denyBtn.onclick = () => this._respond(reqId, "deny", "\u2717 Denied");
      const allowBtn = document.createElement("button");
      allowBtn.type = "button";
      allowBtn.className = "quick-reply-btn perm-allow";
      allowBtn.textContent = "Allow";
      allowBtn.onclick = () => this._respond(reqId, "once", "\u2713 Allowed");
      const sessionBtn = document.createElement("button");
      sessionBtn.type = "button";
      sessionBtn.className = "quick-reply-btn perm-allow-session";
      sessionBtn.textContent = "Allow for session";
      sessionBtn.onclick = () => this._respond(reqId, "session", "\u2713 Allowed for session");
      this.appendChild(denyBtn);
      this.appendChild(allowBtn);
      this.appendChild(sessionBtn);
    }
    _respond(reqId, mode, label) {
      this.querySelectorAll("button").forEach((b) => {
        b.disabled = true;
      });
      const result = document.createElement("div");
      const allowed = mode !== "deny";
      result.className = "perm-result " + (allowed ? "perm-allowed" : "perm-denied");
      result.textContent = label;
      this.replaceChildren(result);
      globalThis._bridge?.permissionResponse(`${reqId}:${mode}`);
    }
  };

  // src/components/WorkingIndicator.ts
  var WorkingIndicator = class extends HTMLElement {
    _interval = null;
    _startTime = 0;
    _span = null;
    connectedCallback() {
      this._span = document.createElement("span");
      this._span.className = "working-text";
      this.appendChild(this._span);
      this.hidden = true;
      this.setAttribute("role", "status");
      this.setAttribute("aria-live", "polite");
      this.setAttribute("aria-label", "Working");
    }
    show() {
      this.hidden = false;
      this._startTime = Date.now();
      this._render();
      this._stopTimer();
      this._interval = setInterval(() => this._render(), 1e3);
    }
    hide() {
      this.hidden = true;
      this._stopTimer();
    }
    resetTimer() {
      if (this.hidden) return;
      this._startTime = Date.now();
      this._render();
    }
    _render() {
      if (!this._span) return;
      const elapsed = Math.floor((Date.now() - this._startTime) / 1e3);
      this._span.textContent = elapsed > 0 ? `Working\u2026 ${elapsed}s` : "Working\u2026";
    }
    _stopTimer() {
      if (this._interval !== null) {
        clearInterval(this._interval);
        this._interval = null;
      }
    }
    disconnectedCallback() {
      this._stopTimer();
    }
  };

  // src/index.ts
  customElements.define("chat-container", ChatContainer);
  customElements.define("chat-message", ChatMessage);
  customElements.define("message-bubble", MessageBubble);
  customElements.define("message-meta", MessageMeta);
  customElements.define("thinking-block", ThinkingBlock);
  customElements.define("tool-chip", ToolChip);
  customElements.define("thinking-chip", ThinkingChip);
  customElements.define("subagent-chip", SubagentChip);
  customElements.define("quick-replies", QuickReplies);
  customElements.define("session-divider", SessionDivider);
  customElements.define("load-more", LoadMore);
  customElements.define("turn-details", TurnDetails);
  customElements.define("permission-request", PermissionRequest);
  customElements.define("working-indicator", WorkingIndicator);
  globalThis.ChatController = ChatController_default;
  globalThis.b64 = b64;
  globalThis.showPermissionRequest = (turnId, agentId, reqId, toolDisplayName2, argsJson) => {
    ChatController_default.showPermissionRequest(turnId, agentId, reqId, toolDisplayName2, argsJson);
  };
  document.addEventListener("click", (e) => {
    let el = e.target;
    while (el && el.tagName !== "A") el = el.parentElement;
    if (!el?.getAttribute("href")) return;
    const href = el.getAttribute("href");
    if (href.startsWith("openfile://") || href.startsWith("gitshow://")) {
      e.preventDefault();
      globalThis._bridge?.openFile(href);
    } else if (href.startsWith("http://") || href.startsWith("https://")) {
      e.preventDefault();
      globalThis._bridge?.openUrl(href);
    }
  });
  var lastCursor = "";
  document.addEventListener("mouseover", (e) => {
    const el = e.target;
    let c = "default";
    if (el.closest("a,.turn-chip,.chip-close,.prompt-ctx-chip,.quick-reply-btn,.code-action-btn")) c = "pointer";
    else if (el.closest(".chip-strip")) c = "grab";
    else if (el.closest("p,pre,code,li,td,th,.thinking-content,.streaming")) c = "text";
    if (c !== lastCursor) {
      lastCursor = c;
      globalThis._bridge?.setCursor(c);
    }
  });
  document.addEventListener("quick-reply", (e) => {
    globalThis._bridge?.quickReply(e.detail.text);
  });
  document.addEventListener("load-more", () => {
    globalThis._bridge?.loadMore();
  });
})();
