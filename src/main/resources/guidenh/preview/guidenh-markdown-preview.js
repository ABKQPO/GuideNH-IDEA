(() => {
  const all = selector => Array.from(document.querySelectorAll(selector));
  const attr = (element, name) => element.getAttribute(name) || element.getAttribute(name.toLowerCase()) || '';
  const esc = value => String(value || '').replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));
  const number = (value, fallback = NaN) => {
    const parsed = Number.parseFloat(String(value).replace(/[{}]/g, '').trim());
    return Number.isFinite(parsed) ? parsed : fallback;
  };
  const bool = (value, fallback = true) => value === '' ? fallback : !/^(false|0|no)$/i.test(String(value).replace(/[{}]/g, '').trim());
  const palette = ['#4fc3f7', '#ffb74d', '#81c784', '#ce93d8', '#ef9a9a', '#80cbc4'];
  const isChinese = /^(zh|zh-)/i.test(document.documentElement.lang || navigator.language || '');
  const text = isChinese ? {
    tab: '标签页 ', unknownItem: '未知物品', revealSpoiler: '显示剧透内容', emptyCsv: '空 CSV 表格', functionGraph: '函数图像', series: '数据系列 ', slice: '数据项 ',
    barChart: '条形图', columnChart: '柱状图', lineChart: '折线图', pieChart: '饼图', scatterChart: '散点图',
    gameScene: '游戏场景', scene: '场景', recipe: '配方', recipeLookup: '配方查询', recipeList: '配方列表', recipeUsage: '配方用途',
    mermaid: 'Mermaid 图', latex: 'LaTeX', questCard: '任务卡', structure: '结构', importedStructure: '导入的结构', importedPonder: '导入的 Ponder',
    alerts: { note: '提示', tip: '建议', important: '重要', warning: '警告', caution: '注意' }
  } : {
    tab: 'Tab ', unknownItem: 'unknown item', revealSpoiler: 'Reveal spoiler', emptyCsv: 'Empty CSV table', functionGraph: 'Function graph', series: 'Series ', slice: 'Slice ',
    barChart: 'Bar chart', columnChart: 'Column chart', lineChart: 'Line chart', pieChart: 'Pie chart', scatterChart: 'Scatter chart',
    gameScene: 'GameScene', scene: 'Scene', recipe: 'Recipe', recipeLookup: 'Recipe lookup', recipeList: 'Recipe list', recipeUsage: 'Recipe usage',
    mermaid: 'Mermaid', latex: 'LaTeX', questCard: 'Quest card', structure: 'Structure', importedStructure: 'Imported structure', importedPonder: 'Imported Ponder',
    alerts: { note: 'Note', tip: 'Tip', important: 'Important', warning: 'Warning', caution: 'Caution' }
  };
  const finite = values => values.filter(Number.isFinite);
  const range = (min, max, fallbackMin, fallbackMax) => {
    const left = number(min, fallbackMin), right = number(max, fallbackMax);
    const bounds = Number.isFinite(left) && Number.isFinite(right) ? [Math.min(left, right), Math.max(left, right)] : [fallbackMin, fallbackMax];
    return bounds[0] === bounds[1] ? [bounds[0] - 1, bounds[1] + 1] : bounds;
  };
  const splitRange = (value, fallback) => {
    const parts = String(value || '').split('..').map(part => number(part));
    return parts.length === 2 && parts.every(Number.isFinite) && parts[0] !== parts[1] ? [Math.min(...parts), Math.max(...parts)] : fallback;
  };
  const format = value => Number.isFinite(value) ? Number(value.toFixed(4)).toString() : 'undefined';

  // Inline extensions run before containers so rich Plot/Tooltip content is retained.
  const markers = [['==', 'guidenh-highlight'], ['++', 'guidenh-underline'], ['^^', 'guidenh-wavy'], ['::', 'guidenh-dotted']];
  const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
  const textNodes = []; while (walker.nextNode()) textNodes.push(walker.currentNode);
  textNodes.forEach(node => {
    if (['CODE', 'PRE', 'SCRIPT', 'STYLE'].includes(node.parentElement && node.parentElement.tagName)) return;
    let html = node.nodeValue, changed = false;
    markers.forEach(([marker, className]) => {
      const escaped = marker.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
      const pattern = new RegExp(escaped + '([^\\n]+?)' + escaped, 'g');
      if (pattern.test(html)) { html = html.replace(pattern, '<span class="' + className + '">$1</span>'); changed = true; }
    });
    if (changed) { const replacement = document.createElement('span'); replacement.innerHTML = html; node.replaceWith(replacement); }
  });

  const renderMarkdownAlert = paragraph => {
    const match = /^\s*\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)\](?:\s+|$)/i.exec(paragraph.textContent || '');
    if (!match) return;
    const type = match[1].toLowerCase();
    let remaining = match[0].length;
    const textWalker = document.createTreeWalker(paragraph, NodeFilter.SHOW_TEXT);
    while (remaining > 0 && textWalker.nextNode()) {
      const textNode = textWalker.currentNode;
      const text = textNode.nodeValue || '';
      if (remaining >= text.length) { textNode.nodeValue = ''; remaining -= text.length; }
      else { textNode.nodeValue = text.slice(remaining); remaining = 0; }
    }
    const source = paragraph.parentElement && paragraph.parentElement.tagName === 'BLOCKQUOTE' && paragraph.parentElement.children.length === 1
      ? paragraph.parentElement
      : paragraph;
    const content = document.createElement('div'); content.className = 'guidenh-alert-content';
    while (paragraph.firstChild) content.appendChild(paragraph.firstChild);
    const alert = document.createElement('aside');
    alert.className = 'guidenh-alert guidenh-alert-' + type;
    alert.setAttribute('role', 'note');
    const title = document.createElement('div'); title.className = 'guidenh-alert-title'; title.textContent = text.alerts[type] || match[1];
    alert.append(title, content);
    source.replaceWith(alert);
  };
  all('p, blockquote > p').forEach(renderMarkdownAlert);
  all('[data-guidenh-preview="csv-empty"]').forEach(element => { element.textContent = text.emptyCsv; });

  const normalizeLegacyImageSource = source => {
    const decoded = String(source || '').replace(/%2a/gi, '*');
    return /\*.*\.(?:png|jpe?g|gif|webp|svg)\*$/i.test(decoded) ? decoded.replace(/\*/g, '') : source;
  };
  all('img').forEach(image => {
    const source = image.getAttribute('src');
    const normalized = normalizeLegacyImageSource(source);
    if (normalized !== source) image.setAttribute('src', normalized);
  });

  all('contenttabs').forEach(container => {
    const tabs = Array.from(container.querySelectorAll(':scope > tab'));
    if (!tabs.length) return;
    const root = document.createElement('section'); root.className = 'guidenh-tabs';
    const strip = document.createElement('div'); strip.className = 'guidenh-tab-strip';
    const body = document.createElement('div'); body.className = 'guidenh-tab-body';
    const selected = Math.max(0, Math.min(Number.parseInt(attr(container, 'defaultindex'), 10) || 0, tabs.length - 1));
    tabs.forEach((tab, index) => {
      const button = document.createElement('button'); button.type = 'button'; button.textContent = attr(tab, 'title') || attr(tab, 'name') || (text.tab + (index + 1));
      const panel = document.createElement('div'); panel.className = 'guidenh-tab-panel'; while (tab.firstChild) panel.appendChild(tab.firstChild);
      const activate = () => { Array.from(strip.children).forEach((item, i) => item.classList.toggle('active', i === index)); Array.from(body.children).forEach((item, i) => item.hidden = i !== index); };
      button.addEventListener('click', activate); strip.appendChild(button); body.appendChild(panel); if (index === selected) setTimeout(activate, 0);
    });
    root.append(strip, body); container.replaceWith(root);
  });

  all('itemlink,itemimage,blockimage').forEach(element => {
    const id = attr(element, 'id') || attr(element, 'ore') || text.unknownItem;
    const chip = document.createElement(element.tagName.toLowerCase() === 'itemlink' ? 'a' : 'span');
    chip.className = 'guidenh-item-chip ' + element.tagName.toLowerCase(); chip.textContent = id; chip.title = id;
    if (chip.tagName === 'A') chip.href = '#'; element.replaceWith(chip);
  });

  all('floatingimage').forEach(element => {
    const src = attr(element, 'src'); if (!src) return;
    const image = document.createElement('img'); image.className = 'guidenh-floating-image'; image.src = src; image.alt = attr(element, 'alt') || attr(element, 'title') || src;
    const width = number(attr(element, 'width') || attr(element, 'w'));
    const height = number(attr(element, 'height') || attr(element, 'h'));
    if (Number.isFinite(width)) image.style.width = width + 'px';
    if (Number.isFinite(height)) image.style.height = height + 'px';
    const scaleX = number(attr(element, 'scalex'), 1), scaleY = number(attr(element, 'scaley'), 1);
    if (scaleX !== 1 || scaleY !== 1) image.style.transform = 'scale(' + scaleX + ',' + scaleY + ')';
    const align = attr(element, 'align').toLowerCase();
    if (attr(element, 'wrap').toLowerCase() === 'square' && (align === 'left' || align === 'right')) image.classList.add('guidenh-float-' + align);
    element.replaceWith(image);
  });

  all('row,column').forEach(element => { element.classList.add(element.tagName.toLowerCase() === 'row' ? 'guidenh-row' : 'guidenh-column'); const gap = number(attr(element, 'gap')); if (Number.isFinite(gap)) element.style.gap = gap + 'px'; });
  all('itemgrid').forEach(element => element.classList.add('guidenh-item-grid'));
  all('spoiler').forEach(element => {
    const revealed = document.createElement('button'); revealed.type = 'button'; revealed.className = 'guidenh-spoiler'; revealed.textContent = attr(element, 'label') || text.revealSpoiler;
    const content = document.createElement('span'); content.className = 'guidenh-spoiler-content'; while (element.firstChild) content.appendChild(element.firstChild);
    revealed.addEventListener('click', () => { const visible = revealed.classList.toggle('revealed'); revealed.setAttribute('aria-expanded', String(visible)); });
    revealed.append(content); element.replaceWith(revealed);
  });
  all('tooltip').forEach(element => { const label = attr(element, 'label') || element.textContent.trim(); if (label) element.setAttribute('title', label); element.classList.add('guidenh-tooltip'); });
  all('soundlink,commandlink,keybind,questlink').forEach(element => {
    element.classList.add('guidenh-action-link'); const detail = attr(element, 'sound') || attr(element, 'command') || attr(element, 'key') || attr(element, 'id');
    if (!element.textContent.trim()) element.textContent = detail || element.tagName;
    if (detail) element.title = detail;
  });

  const calculate = (expression, x) => {
    let input = String(expression || '').trim().replace(/^y\s*=\s*/i, '').replace(/\|([^|]+)\|/g, 'abs($1)');
    input = input.replace(/\^/g, '**').replace(/\bpi\b/gi, 'Math.PI').replace(/\be\b/g, 'Math.E');
    input = input.replace(/\blog\(([^,()]+),([^()]+)\)/gi, '(Math.log($2)/Math.log($1))').replace(/\bmod\(([^,()]+),([^()]+)\)/gi, '($1%$2)');
    input = input.replace(/\batan\(([^,()]+),([^()]+)\)/gi, 'Math.atan2($1,$2)').replace(/\bln\b/gi, 'Math.log');
    input = input.replace(/\b(sin|cos|tan|asin|acos|sqrt|cbrt|abs|log|exp|floor|ceil|round|min|max|pow)\b/g, 'Math.$1');
    if (!/^[0-9x+\-*/%().,A-Za-z_\s]*$/.test(input)) return NaN;
    try { return Function('x', '"use strict";return(' + input + ');')(x); } catch (_) { return NaN; }
  };
  const svgNode = (name, attributes = {}, content = '') => '<' + name + Object.entries(attributes).map(([key, value]) => ' ' + key + '="' + esc(value) + '"').join('') + '>' + content + '</' + name + '>';
  const graphSeries = (plot, index) => ({
    label: attr(plot, 'label') || ('f' + (index + 1)), expression: attr(plot, 'expr') || attr(plot, 'formula') || plot.textContent.trim(), color: attr(plot, 'color') || palette[index % palette.length], tooltip: attr(plot, 'tooltip'), showFunction: bool(attr(plot, 'showfunction')), showValues: bool(attr(plot, 'showvalues')), rich: plot.innerHTML.trim()
  });

  const renderFunctionGraph = (graph, series) => {
    if (!series.length) return;
    let xBounds = splitRange(attr(graph, 'xrange') || attr(graph, 'domain'), [-5, 5]); xBounds = range(attr(graph, 'xmin'), attr(graph, 'xmax'), xBounds[0], xBounds[1]);
    const samples = series.map(item => ({ ...item, points: Array.from({ length: 161 }, (_, index) => { const x = xBounds[0] + (xBounds[1] - xBounds[0]) * index / 160; return [x, calculate(item.expression, x)]; }).filter(point => Number.isFinite(point[1])) })).filter(item => item.points.length > 1);
    if (!samples.length) return;
    const allY = samples.flatMap(item => item.points.map(point => point[1])); let yBounds = splitRange(attr(graph, 'yrange'), [Math.min(...allY), Math.max(...allY)]); yBounds = range(attr(graph, 'ymin'), attr(graph, 'ymax'), yBounds[0], yBounds[1]); if (yBounds[0] === yBounds[1]) yBounds = [yBounds[0] - 1, yBounds[1] + 1];
    const width = Math.max(280, number(attr(graph, 'width'), 560)), height = Math.max(180, number(attr(graph, 'height'), 290)); const pad = { left: 52, right: 18, top: 30, bottom: 46 };
    const px = x => pad.left + (x - xBounds[0]) / (xBounds[1] - xBounds[0]) * (width - pad.left - pad.right); const py = y => height - pad.bottom - (y - yBounds[0]) / (yBounds[1] - yBounds[0]) * (height - pad.top - pad.bottom);
    const paths = samples.map(item => svgNode('path', { d: item.points.map((point, index) => (index ? 'L' : 'M') + px(point[0]).toFixed(2) + ',' + py(point[1]).toFixed(2)).join(' '), stroke: item.color, class: 'series' })).join('');
    const xAxis = yBounds[0] <= 0 && yBounds[1] >= 0 ? py(0) : height - pad.bottom; const yAxis = xBounds[0] <= 0 && xBounds[1] >= 0 ? px(0) : pad.left;
    const labelSvg = svgNode('text', { x: width / 2, y: height - 9, 'text-anchor': 'middle', class: 'axis-label' }, esc(attr(graph, 'xlabel'))) + svgNode('text', { x: 12, y: 18, class: 'axis-label' }, esc(attr(graph, 'ylabel')));
    const legend = samples.map(item => '<li><i style="background:' + esc(item.color) + '"></i><span>' + esc(item.label) + '</span><code>' + esc(item.expression) + '</code></li>').join('');
    const replacement = document.createElement('section'); replacement.className = 'guidenh-function-graph'; replacement.innerHTML = '<header>' + esc(attr(graph, 'title') || text.functionGraph) + '</header><div class="guidenh-graph-canvas"><svg viewBox="0 0 ' + width + ' ' + height + '">' + svgNode('path', { class: 'axis', d: 'M' + pad.left + ',' + xAxis + 'H' + (width - pad.right) + 'M' + yAxis + ',' + pad.top + 'V' + (height - pad.bottom) }) + paths + labelSvg + '</svg><div class="guidenh-graph-tooltip" hidden></div></div><ul>' + legend + '</ul>';
    const tooltip = replacement.querySelector('.guidenh-graph-tooltip'); replacement.querySelector('svg').addEventListener('mousemove', event => {
      const box = event.currentTarget.getBoundingClientRect(); const x = xBounds[0] + (event.clientX - box.left) / box.width * (xBounds[1] - xBounds[0]);
      tooltip.innerHTML = samples.map(item => { const value = calculate(item.expression, x); return '<section><strong>' + esc(item.label) + '</strong>' + (item.showFunction ? '<code>' + esc(item.expression) + '</code>' : '') + (item.showValues ? '<span>x = ' + format(x) + ', y = ' + format(value) + '</span>' : '') + (item.tooltip ? '<p>' + esc(item.tooltip) + '</p>' : '') + (item.rich ? '<div class="rich">' + item.rich + '</div>' : '') + '</section>'; }).join(''); tooltip.hidden = false; tooltip.style.left = Math.min(88, Math.max(2, (event.clientX - box.left) / box.width * 100)) + '%'; tooltip.style.top = '10px';
    }); replacement.querySelector('svg').addEventListener('mouseleave', () => { tooltip.hidden = true; }); graph.replaceWith(replacement);
  };
  all('functiongraph').forEach(graph => renderFunctionGraph(graph, Array.from(graph.querySelectorAll(':scope > plot,:scope > function')).map(graphSeries)));
  all('guidenh-funcgraph').forEach(container => {
    const lines = container.textContent.split(/\r?\n/).map(line => line.trim()).filter(Boolean); const graph = document.createElement('functiongraph'); const config = lines[0] && /(?:xRange|yRange|cornerLegend)\s*=/i.test(lines[0]) ? lines.shift() : '';
    if (config) config.split(/\s+/).forEach(part => { const match = /^([^=]+)=(.+)$/.exec(part); if (match) graph.setAttribute(match[1], match[2]); });
    lines.forEach(line => { const [expression, options = ''] = line.split('|', 2); const fn = document.createElement('function'); fn.setAttribute('expr', expression.trim()); const label = /label\s*=\s*"([^"]+)"/.exec(options); if (label) fn.setAttribute('label', label[1]); const color = /color\s*=\s*"([^"]+)"/.exec(options); if (color) fn.setAttribute('color', color[1]); graph.appendChild(fn); });
    container.replaceWith(graph); renderFunctionGraph(graph, Array.from(graph.children).map(graphSeries));
  });
  all('function').filter(element => !element.closest('functiongraph')).forEach(element => { const graph = document.createElement('functiongraph'); graph.appendChild(element.cloneNode(true)); element.replaceWith(graph); renderFunctionGraph(graph, Array.from(graph.children).map(graphSeries)); });

  const chartSeries = chart => Array.from(chart.querySelectorAll(':scope > series,:scope > lineseries')).map((series, index) => { const data = attr(series, 'data').split(',').map(value => number(value)).filter(Number.isFinite); const points = Array.from(series.querySelectorAll(':scope > point')).map(point => [number(attr(point, 'x')), number(attr(point, 'y'))]).filter(point => point.every(Number.isFinite)); return { name: attr(series, 'name') || (text.series + (index + 1)), color: attr(series, 'color') || palette[index % palette.length], values: points.length ? points : data.map((value, i) => [i, value]) }; });
  const renderChart = chart => {
    const tag = chart.tagName.toLowerCase(), series = chartSeries(chart), slices = Array.from(chart.querySelectorAll(':scope > slice')).map((slice, index) => ({ name: attr(slice, 'name') || attr(slice, 'label') || (text.slice + (index + 1)), color: attr(slice, 'color') || palette[index % palette.length], value: number(attr(slice, 'value'), 0) })).filter(slice => slice.value > 0);
    if (!series.length && !slices.length) return;
    const width = Math.max(260, number(attr(chart, 'width'), 480)), height = Math.max(180, number(attr(chart, 'height'), 260)), pad = { left: 48, right: 18, top: 28, bottom: 44 }; const title = attr(chart, 'title') || ({ barchart: text.barChart, columnchart: text.columnChart, linechart: text.lineChart, piechart: text.pieChart, scatterchart: text.scatterChart }[tag] || tag); let svg = '', legend = '';
    if (tag === 'piechart') {
      const total = slices.reduce((sum, slice) => sum + slice.value, 0); let angle = -Math.PI / 2; const cx = width / 2, cy = height / 2, radius = Math.min(width, height) * .31;
      svg = slices.map(slice => { const next = angle + Math.PI * 2 * slice.value / total; const path = 'M' + cx + ',' + cy + ' L' + (cx + radius * Math.cos(angle)) + ',' + (cy + radius * Math.sin(angle)) + ' A' + radius + ',' + radius + ' 0 ' + (next - angle > Math.PI ? 1 : 0) + ' 1 ' + (cx + radius * Math.cos(next)) + ',' + (cy + radius * Math.sin(next)) + ' Z'; angle = next; return svgNode('path', { d: path, fill: slice.color, class: 'slice' }); }).join(''); legend = slices.map(slice => '<li><i style="background:' + esc(slice.color) + '"></i>' + esc(slice.name) + ' <code>' + format(slice.value) + '</code></li>').join('');
    } else {
      const points = series.flatMap(item => item.values), xs = finite(points.map(point => point[0])), ys = finite(points.map(point => point[1])), horizontal = tag === 'barchart', numericX = horizontal ? ys : xs; const xBounds = range(attr(chart, 'xaxismin'), attr(chart, 'xaxismax'), Math.min(...numericX, 0), Math.max(...numericX, 1)), yBounds = range(attr(chart, 'yaxismin'), attr(chart, 'yaxismax'), Math.min(...ys, 0), Math.max(...ys, 1)); const px = x => pad.left + (x - xBounds[0]) / (xBounds[1] - xBounds[0]) * (width - pad.left - pad.right), py = y => height - pad.bottom - (y - yBounds[0]) / (yBounds[1] - yBounds[0]) * (height - pad.top - pad.bottom); const axes = svgNode('path', { class: 'axis', d: 'M' + pad.left + ',' + (height - pad.bottom) + 'H' + (width - pad.right) + 'M' + pad.left + ',' + pad.top + 'V' + (height - pad.bottom) });
      if (tag === 'barchart' || tag === 'columnchart') { const categories = attr(chart, 'categories').split(',').map(value => value.trim()).filter(Boolean), bars = Math.max(1, Math.max(...series.map(item => item.values.length))), group = (width - pad.left - pad.right) / bars; svg = axes + series.map((item, seriesIndex) => item.values.map((point, index) => { const baseline = py(0); if (tag === 'barchart') { const y = pad.top + index * (height - pad.top - pad.bottom) / bars + seriesIndex * ((height - pad.top - pad.bottom) / bars / series.length), barHeight = (height - pad.top - pad.bottom) / bars / series.length * .72; return svgNode('rect', { x: Math.min(px(0), px(point[1])), y, width: Math.abs(px(point[1]) - px(0)), height: barHeight, fill: item.color }); } const barWidth = group / series.length * .72; return svgNode('rect', { x: pad.left + index * group + seriesIndex * group / series.length + group / series.length * .14, y: Math.min(py(point[1]), baseline), width: barWidth, height: Math.abs(baseline - py(point[1])), fill: item.color }); }).join('')).join('') + categories.map((label, index) => svgNode('text', { x: pad.left + (index + .5) * group, y: height - 12, 'text-anchor': 'middle', class: 'tick' }, esc(label))).join(''); }
      else { svg = axes + series.map(item => { const d = item.values.map((point, index) => (index ? 'L' : 'M') + px(point[0]).toFixed(2) + ',' + py(point[1]).toFixed(2)).join(' '); const line = tag === 'scatterchart' ? '' : svgNode('path', { d, stroke: item.color, class: 'series' }); return line + item.values.map(point => svgNode('circle', { cx: px(point[0]), cy: py(point[1]), r: 3.5, fill: item.color })).join(''); }).join(''); }
      legend = series.map(item => '<li><i style="background:' + esc(item.color) + '"></i>' + esc(item.name) + '</li>').join(''); svg += svgNode('text', { x: width / 2, y: height - 2, 'text-anchor': 'middle', class: 'axis-label' }, esc(attr(chart, 'xaxislabel') || attr(chart, 'xaxisunit'))) + svgNode('text', { x: 8, y: 16, class: 'axis-label' }, esc(attr(chart, 'yaxislabel') || attr(chart, 'yaxisunit')));
    }
    const replacement = document.createElement('section'); replacement.className = 'guidenh-chart guidenh-' + tag; replacement.innerHTML = '<header>' + esc(title) + '</header><svg viewBox="0 0 ' + width + ' ' + height + '">' + svg + '</svg><ul>' + legend + '</ul>'; chart.replaceWith(replacement);
  };
  all('barchart,columnchart,linechart,piechart,scatterchart').forEach(renderChart);

  const blockLabels = { gamescene:text.gameScene, scene:text.scene, recipe:text.recipe, recipefor:text.recipeLookup, recipesfor:text.recipeList, recipesusage:text.recipeUsage, recipeusage:text.recipeUsage, mermaid:text.mermaid, latex:text.latex, questcard:text.questCard, structure:text.structure, importstructure:text.importedStructure, importponder:text.importedPonder, barchart:text.barChart, columnchart:text.columnChart, linechart:text.lineChart, piechart:text.pieChart, scatterchart:text.scatterChart };
  Object.entries(blockLabels).forEach(([tag, label]) => all(tag).forEach(element => { if (element.classList.contains('guidenh-rendered')) return; element.classList.add('guidenh-rendered', 'guidenh-block'); const header = document.createElement('header'); header.textContent = label; const detail = attr(element, 'id') || attr(element, 'src') || attr(element, 'formula') || attr(element, 'title'); if (detail) { const code = document.createElement('code'); code.textContent = detail; header.append(' ', code); } element.prepend(header); }));
})();
