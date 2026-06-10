(function () {
    'use strict';

    // ── Colors ─────────────────────────────────────────────────────────────────

    const COLORS = {
        node: {
            file: '#4A90D9',
            commit: '#E8943A',
            prompt: '#5DB85C'
        },
        edge: {
            uses: 'rgba(140,140,140,0.45)',
            changed: 'rgba(232,148,58,0.40)',
            touched: 'rgba(93,184,92,0.40)'
        },
        label: 'rgba(210,210,210,0.90)',
        dimLabel: 'rgba(150,150,150,0.25)',
        tooltip: {bg: 'rgba(28,28,28,0.95)', border: 'rgba(80,80,80,0.7)', text: '#ddd'}
    };

    // ── State ──────────────────────────────────────────────────────────────────

    let canvas, ctx;
    let nodes = [], edges = [];

    let tx = 0, ty = 0, scale = 1;
    let simTick = 0, maxSim = 0, rafId = null;

    let isPanning = false, panStart = null, didPan = false;
    let dragNode = null;
    let hoveredNode = null, selectedNode = null;

    let viewMode = 'all';
    let searchStr = '';

    // ── Initialization ─────────────────────────────────────────────────────────

    window.addEventListener('DOMContentLoaded', function () {
        canvas = document.getElementById('g');
        ctx = canvas.getContext('2d');

        resize();
        window.addEventListener('resize', resize);

        canvas.addEventListener('mousedown', onMouseDown);
        window.addEventListener('mousemove', onMouseMove);
        window.addEventListener('mouseup', onMouseUp);
        canvas.addEventListener('dblclick', onDblClick);
        canvas.addEventListener('wheel', onWheel, {passive: false});

        render();

        if (window._pendingGraph) {
            var pg = window._pendingGraph;
            window._pendingGraph = null;
            window.loadGraph(pg);
        }
    });

    function resize() {
        // Use window.innerWidth/innerHeight — more reliable in embedded JCEF.
        // Only reassign canvas.width/height when they change: setting them
        // unconditionally clears the canvas bitmap even if the value is the same.
        var w = window.innerWidth || 800;
        var h = window.innerHeight || 600;
        if (canvas.width !== w) canvas.width = w;
        if (canvas.height !== h) canvas.height = h;
        if (!rafId) render();
    }

    // ── Public bridge API ──────────────────────────────────────────────────────

    window.loadGraph = function (jsonStr) {
        if (!canvas) {
            window._pendingGraph = jsonStr;
            return;
        }
        var data = JSON.parse(jsonStr);
        buildGraph(data.nodes, data.edges);
    };

    window.setViewMode = function (mode) {
        viewMode = mode;
        restartSim(120);
    };

    window.setSearch = function (q) {
        searchStr = q.toLowerCase().trim();
        if (!rafId) render();
    };

    window.resetLayout = function () {
        for (var i = 0; i < nodes.length; i++) {
            var angle = (i / nodes.length) * Math.PI * 2;
            var r = 100 + Math.random() * 150;
            nodes[i].x = Math.cos(angle) * r;
            nodes[i].y = Math.sin(angle) * r;
            nodes[i].vx = 0;
            nodes[i].vy = 0;
        }
        restartSim(250);
    };

    // ── Graph build ────────────────────────────────────────────────────────────

    function buildGraph(nodeData, edgeData) {
        var total = nodeData.length || 1;
        nodes = nodeData.map(function (n, i) {
            var angle = (i / total) * Math.PI * 2;
            var radius = 80 + Math.random() * 140;
            return Object.assign({}, n, {
                x: Math.cos(angle) * radius,
                y: Math.sin(angle) * radius,
                vx: 0, vy: 0,
                r: nodeRadius(n)
            });
        });

        var byId = {};
        nodes.forEach(function (n) {
            byId[n.id] = n;
        });

        edges = edgeData
            .map(function (e) {
                return Object.assign({}, e, {s: byId[e.source], t: byId[e.target]});
            })
            .filter(function (e) {
                return e.s && e.t;
            });

        tx = 0;
        ty = 0;
        scale = 1;
        hoveredNode = null;
        selectedNode = null;
        restartSim(250);
    }

    function nodeRadius(n) {
        var connections = (n.depCount || 0) + (n.dependentCount || 0);
        return 8 + Math.min(14, Math.sqrt(connections) * 1.8);
    }

    // ── Force simulation ───────────────────────────────────────────────────────

    function restartSim(ticks) {
        simTick = 0;
        maxSim = ticks;
        if (!rafId) rafId = requestAnimationFrame(animLoop);
    }

    function animLoop() {
        // The simulation never truly stops: alpha decays from 1 down to a
        // small floor (0.15) and stays there, so nodes always have a little
        // residual motion. Empirically this is what keeps the graph visible
        // in JCEF — fully static canvases get hidden once the user observes
        // them stop moving.
        simulate();
        if (simTick < maxSim) simTick++;
        render();
        rafId = requestAnimationFrame(animLoop);
    }


    function simulate() {
        // Alpha decays from 1 → 0.15 during the initial settling phase, then
        // stays at 0.15 forever. Nodes keep gently drifting around their
        // settled positions (kept in place by the centering force).
        var alpha = Math.max(0.15, 1 - simTick / maxSim);
        var vis = nodes.filter(isVisible);

        // Repulsion (halved to 900 to keep nodes compact)
        for (var i = 0; i < vis.length; i++) {
            for (var j = i + 1; j < vis.length; j++) {
                var a = vis[i], b = vis[j];
                var dx = b.x - a.x, dy = b.y - a.y;
                var d2 = dx * dx + dy * dy + 50;
                var f = (900 / d2) * alpha;
                a.vx -= dx * f;
                a.vy -= dy * f;
                b.vx += dx * f;
                b.vy += dy * f;
            }
        }

        // Spring attraction along edges
        for (var ei = 0; ei < edges.length; ei++) {
            var e = edges[ei];
            if (!isVisible(e.s) || !isVisible(e.t)) continue;
            var edx = e.t.x - e.s.x, edy = e.t.y - e.s.y;
            var d = Math.sqrt(edx * edx + edy * edy) + 1;
            var ideal = 60;
            var ef = ((d - ideal) * 0.04) * alpha;
            var enx = edx / d, eny = edy / d;
            e.s.vx += enx * ef;
            e.s.vy += eny * ef;
            e.t.vx -= enx * ef;
            e.t.vy -= eny * ef;
        }

        // Centering (strong enough to keep nodes from drifting off-screen)
        for (var ci = 0; ci < vis.length; ci++) {
            vis[ci].vx -= vis[ci].x * 0.04 * alpha;
            vis[ci].vy -= vis[ci].y * 0.04 * alpha;
        }

        // Integrate + damp
        for (var ii = 0; ii < vis.length; ii++) {
            var n = vis[ii];
            if (n === dragNode) continue;
            n.x += n.vx;
            n.y += n.vy;
            n.vx *= 0.75;
            n.vy *= 0.75;
        }
    }

    // ── Rendering ──────────────────────────────────────────────────────────────

    function render() {
        if (!canvas) return;
        var W = canvas.width, H = canvas.height;
        ctx.clearRect(0, 0, W, H);

        if (nodes.length === 0) {
            ctx.fillStyle = 'rgba(140,140,140,0.55)';
            ctx.font = '14px system-ui, sans-serif';
            ctx.textAlign = 'center';
            ctx.textBaseline = 'middle';
            ctx.fillText('No data \u2014 click Refresh to load the graph', W / 2, H / 2);
            return;
        }

        ctx.save();
        ctx.translate(W / 2 + tx, H / 2 + ty);
        ctx.scale(scale, scale);

        drawEdges();
        drawNodes();

        ctx.restore();

        drawTooltip(W, H);
        drawLegend(W, H);
    }

    function drawEdges() {
        for (var i = 0; i < edges.length; i++) {
            var e = edges[i];
            if (!isVisible(e.s) || !isVisible(e.t)) continue;
            var hilite = selectedNode && (e.s === selectedNode || e.t === selectedNode);
            ctx.strokeStyle = hilite ? (COLORS.node[e.s.type] || '#aaa') : (COLORS.edge[e.type] || 'rgba(110,110,110,0.4)');
            ctx.lineWidth = (hilite ? 1.5 : 1) / scale;
            ctx.globalAlpha = hilite ? 0.9 : 0.6;
            ctx.beginPath();
            ctx.moveTo(e.s.x, e.s.y);
            ctx.lineTo(e.t.x, e.t.y);
            ctx.stroke();
            drawArrow(e);
            ctx.globalAlpha = 1;
        }
    }

    function drawArrow(e) {
        var dx = e.t.x - e.s.x, dy = e.t.y - e.s.y;
        var d = Math.sqrt(dx * dx + dy * dy);
        if (d < 1) return;
        var nx = dx / d, ny = dy / d;
        var tipX = e.t.x - nx * (e.t.r + 5 / scale);
        var tipY = e.t.y - ny * (e.t.r + 5 / scale);
        var al = 7 / scale, aw = 3.5 / scale;
        ctx.fillStyle = ctx.strokeStyle;
        ctx.beginPath();
        ctx.moveTo(tipX, tipY);
        ctx.lineTo(tipX - nx * al + ny * aw, tipY - ny * al - nx * aw);
        ctx.lineTo(tipX - nx * al - ny * aw, tipY - ny * al + nx * aw);
        ctx.closePath();
        ctx.fill();
    }

    function drawNodes() {
        for (var i = 0; i < nodes.length; i++) {
            var n = nodes[i];
            if (!isVisible(n)) continue;

            var isHov = n === hoveredNode;
            var isSel = n === selectedNode;
            var match = searchStr && n.label.toLowerCase().indexOf(searchStr) >= 0;
            var dim = searchStr.length > 0 && !match;

            ctx.globalAlpha = dim ? 0.15 : 1;

            if (isHov || isSel) {
                ctx.shadowColor = COLORS.node[n.type] || '#888';
                ctx.shadowBlur = 14 / scale;
            }

            ctx.fillStyle = COLORS.node[n.type] || '#888';
            drawShape(n);
            ctx.fill();

            if (isSel || match) {
                ctx.strokeStyle = '#ffffff';
                ctx.lineWidth = 2 / scale;
                ctx.stroke();
            }

            ctx.shadowBlur = 0;
            ctx.shadowColor = 'transparent';

            // Label
            var sz = Math.min(11, Math.max(8, 11 / scale));
            ctx.font = sz + 'px system-ui, sans-serif';
            ctx.fillStyle = dim ? COLORS.dimLabel : COLORS.label;
            ctx.textAlign = 'center';
            ctx.textBaseline = 'top';
            ctx.fillText(trunc(n.label, 24), n.x, n.y + n.r + 3 / scale);

            ctx.globalAlpha = 1;
        }
    }

    function drawShape(n) {
        var r = n.r;
        if (n.type === 'commit') {
            ctx.beginPath();
            ctx.moveTo(n.x, n.y - r);
            ctx.lineTo(n.x + r, n.y);
            ctx.lineTo(n.x, n.y + r);
            ctx.lineTo(n.x - r, n.y);
            ctx.closePath();
        } else if (n.type === 'prompt') {
            ctx.beginPath();
            for (var i = 0; i < 6; i++) {
                var a = (i / 6) * Math.PI * 2 - Math.PI / 6;
                if (i === 0) ctx.moveTo(n.x + r * Math.cos(a), n.y + r * Math.sin(a));
                else ctx.lineTo(n.x + r * Math.cos(a), n.y + r * Math.sin(a));
            }
            ctx.closePath();
        } else {
            ctx.beginPath();
            ctx.arc(n.x, n.y, r, 0, Math.PI * 2);
        }
    }

    function drawTooltip(W, H) {
        var n = hoveredNode;
        if (!n) return;

        var sx = W / 2 + tx + n.x * scale;
        var sy = H / 2 + ty + n.y * scale;
        var lines = tooltipLines(n);

        var PAD = 8, LINE_H = 18;
        ctx.font = '12px system-ui, sans-serif';
        var maxW = 0;
        for (var i = 0; i < lines.length; i++) {
            var w = ctx.measureText(lines[i]).width;
            if (w > maxW) maxW = w;
        }
        var bw = maxW + PAD * 2;
        var bh = lines.length * LINE_H + PAD * 2;

        var bx = sx + n.r * scale + 12;
        var by = sy - bh / 2;
        if (bx + bw > W - 4) bx = sx - n.r * scale - 12 - bw;
        by = Math.max(4, Math.min(H - bh - 4, by));

        ctx.fillStyle = COLORS.tooltip.bg;
        ctx.strokeStyle = COLORS.tooltip.border;
        ctx.lineWidth = 1;
        roundRect(ctx, bx, by, bw, bh, 5);
        ctx.fill();
        ctx.stroke();

        ctx.fillStyle = COLORS.tooltip.text;
        ctx.textBaseline = 'top';
        ctx.textAlign = 'left';
        for (var li = 0; li < lines.length; li++) {
            ctx.fillText(lines[li], bx + PAD, by + PAD + li * LINE_H);
        }
    }

    function tooltipLines(n) {
        if (n.type === 'file') {
            var lines = [n.path || n.label];
            if ((n.depCount || 0) + (n.dependentCount || 0) > 0) {
                lines.push('\u2193 uses ' + (n.depCount || 0) + '   \u2191 depended on by ' + (n.dependentCount || 0));
            }
            return lines;
        }
        if (n.type === 'commit') {
            var cl = [n.label];
            if (n.author) cl.push('Author: ' + n.author);
            if (n.timestamp) cl.push((n.timestamp + '').substring(0, 10));
            return cl;
        }
        if (n.type === 'prompt') {
            var prev = n.preview || n.label || '';
            var pl = [];
            for (var i = 0; i < Math.min(prev.length, 150); i += 48) {
                pl.push(prev.substring(i, i + 48));
            }
            if (n.timestamp) pl.push((n.timestamp + '').substring(0, 16).replace('T', ' '));
            return pl.slice(0, 5);
        }
        return [n.label];
    }

    function drawLegend(W, H) {
        var all = [
            {color: COLORS.node.file, label: 'File'},
            {color: COLORS.node.commit, label: 'Commit'},
            {color: COLORS.node.prompt, label: 'Prompt'}
        ];
        var items = all.filter(function (item) {
            var t = item.label.toLowerCase();
            if (viewMode === 'files') return t === 'file';
            if (viewMode === 'commits') return t !== 'prompt';
            if (viewMode === 'prompts') return t !== 'commit';
            return true;
        });
        if (items.length === 0) return;

        var S = 7, LINE_H = 20;
        var lx = 12, ly = H - 10 - items.length * LINE_H;
        ctx.font = '11px system-ui, sans-serif';
        ctx.textBaseline = 'middle';
        ctx.textAlign = 'left';

        for (var i = 0; i < items.length; i++) {
            var cy = ly + i * LINE_H + S;
            ctx.fillStyle = items[i].color;
            ctx.beginPath();
            ctx.arc(lx + S, cy, S, 0, Math.PI * 2);
            ctx.fill();
            ctx.fillStyle = 'rgba(185,185,185,0.85)';
            ctx.fillText(items[i].label, lx + S * 2 + 5, cy);
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    function isVisible(n) {
        if (!n) return false;
        if (viewMode === 'files') return n.type === 'file';
        if (viewMode === 'commits') return n.type !== 'prompt';
        if (viewMode === 'prompts') return n.type !== 'commit';
        return true;
    }

    function roundRect(ctx, x, y, w, h, r) {
        ctx.beginPath();
        ctx.moveTo(x + r, y);
        ctx.lineTo(x + w - r, y);
        ctx.quadraticCurveTo(x + w, y, x + w, y + r);
        ctx.lineTo(x + w, y + h - r);
        ctx.quadraticCurveTo(x + w, y + h, x + w - r, y + h);
        ctx.lineTo(x + r, y + h);
        ctx.quadraticCurveTo(x, y + h, x, y + h - r);
        ctx.lineTo(x, y + r);
        ctx.quadraticCurveTo(x, y, x + r, y);
        ctx.closePath();
    }

    function trunc(s, max) {
        if (!s) return '';
        s = String(s);
        return s.length <= max ? s : s.substring(0, max - 1) + '\u2026';
    }

    // ── Mouse ──────────────────────────────────────────────────────────────────

    function worldPos(cx, cy) {
        var W = canvas.width, H = canvas.height;
        return {x: (cx - W / 2 - tx) / scale, y: (cy - H / 2 - ty) / scale};
    }

    function clientPos(e) {
        var rect = canvas.getBoundingClientRect();
        return {x: e.clientX - rect.left, y: e.clientY - rect.top};
    }

    function hitTest(wx, wy) {
        for (var i = nodes.length - 1; i >= 0; i--) {
            var n = nodes[i];
            if (!isVisible(n)) continue;
            var dx = wx - n.x, dy = wy - n.y;
            if (dx * dx + dy * dy <= (n.r + 4 / scale) * (n.r + 4 / scale)) return n;
        }
        return null;
    }

    function onMouseDown(e) {
        var cp = clientPos(e);
        var w = worldPos(cp.x, cp.y);
        var hit = hitTest(w.x, w.y);
        didPan = false;
        if (hit) {
            dragNode = hit;
        } else {
            isPanning = true;
            panStart = {x: cp.x - tx, y: cp.y - ty};
        }
    }

    function onMouseMove(e) {
        var cp = clientPos(e);
        if (dragNode) {
            var w = worldPos(cp.x, cp.y);
            dragNode.x = w.x;
            dragNode.y = w.y;
            dragNode.vx = 0;
            dragNode.vy = 0;
            didPan = true;
            if (!rafId) render();
            return;
        }
        if (isPanning) {
            tx = cp.x - panStart.x;
            ty = cp.y - panStart.y;
            didPan = true;
            if (!rafId) render();
            return;
        }
        var wp = worldPos(cp.x, cp.y);
        var hit = hitTest(wp.x, wp.y);
        if (hit !== hoveredNode) {
            hoveredNode = hit;
            canvas.style.cursor = hit ? 'pointer' : 'default';
            if (!rafId) render();
        }
    }

    function onMouseUp(e) {
        var wasDragging = didPan;
        dragNode = null;
        isPanning = false;
        panStart = null;
        didPan = false;

        if (!wasDragging) {
            var cp = clientPos(e);
            var w = worldPos(cp.x, cp.y);
            var hit = hitTest(w.x, w.y);
            selectedNode = (hit === selectedNode) ? null : hit;
            if (!rafId) render();
        }
    }

    function onDblClick(e) {
        var cp = clientPos(e);
        var w = worldPos(cp.x, cp.y);
        var hit = hitTest(w.x, w.y);
        if (hit && hit.type === 'file' && hit.path && window._navigateToFile) {
            window._navigateToFile(hit.path);
        }
    }

    function onWheel(e) {
        e.preventDefault();
        var cp = clientPos(e);
        var factor = e.deltaY < 0 ? 1.15 : 1 / 1.15;
        var ns = Math.min(10, Math.max(0.04, scale * factor));
        var W = canvas.width, H = canvas.height;
        tx = cp.x - W / 2 - (cp.x - W / 2 - tx) * (ns / scale);
        ty = cp.y - H / 2 - (cp.y - H / 2 - ty) * (ns / scale);
        scale = ns;
        if (!rafId) render();
    }

})();
