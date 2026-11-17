(async function () {
  if (window.__mzRodando) return;
  window.__mzRodando = true;

  const CFG = window.__CFG || { alvo: 17, inc: 1 };
  const sleep = ms => new Promise(r => setTimeout(r, ms));
  const log = m => { try { Android.log(m); } catch (e) {} };
  const fim = m => { try { Android.done(m); } catch (e) {} };
  const vis = e => !!(e && (e.offsetWidth || e.offsetHeight || e.getClientRects().length));
  const txt = e => ((e && (e.innerText || e.textContent)) || '').trim();

  async function ate(fn, ms = 15000, passo = 250) {
    const t = Date.now();
    while (Date.now() - t < ms) {
      try { const v = fn(); if (v) return v; } catch (e) {}
      await sleep(passo);
    }
    return null;
  }

  function clicar(el) {
    el.scrollIntoView({ block: 'center', inline: 'nearest' });
    const o = { bubbles: true, cancelable: true, view: window, button: 0 };
    ['mouseover', 'mousedown', 'mouseup', 'click'].forEach(t => el.dispatchEvent(new MouseEvent(t, o)));
  }

  const limparPopups = () => document.querySelectorAll(
    '.announcekit-frame-wrapper, .announcekit-widget-iframe-backdrop').forEach(e => e.remove());

  const colunaPrazo = () => {
    const h = [...document.querySelectorAll('.x-column-header')]
      .find(h => vis(h) && /^\s*Prazo de Expedi/i.test(txt(h)));
    return h ? h.id : null;
  };
  const celulas = col => [...document.querySelectorAll('td.x-grid-cell-' + col)].filter(vis);
  const janelaAlterar = () => [...document.querySelectorAll('.x-window')]
    .filter(vis).find(w => /Alterar Estoque/i.test(txt(w)));
  const botao = (raiz, re) => [...raiz.querySelectorAll('a.x-btn')].filter(vis).find(b => re.test(txt(b)));
  const fechar = w => { const f = w && w.querySelector('a.x-btn.ts-tbutton-close'); if (f) clicar(f); };

  function desmarcarTudo() {
    try {
      Ext.ComponentQuery.query('gridpanel').forEach(g => {
        if (g.isVisible && g.isVisible(true)) g.getSelectionModel().deselectAll();
      });
    } catch (e) {}
  }

  function setNativo(el, v) {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set.call(el, v);
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }

  async function abrirProduto(col, i) {
    const cel = celulas(col)[i];
    if (!cel) throw new Error('célula não existe');
    desmarcarTudo();
    clicar(cel);
    await sleep(600);
    const b = botao(document, /^\s*Alterar\s*$/);
    if (!b) throw new Error('botão Alterar não encontrado');
    clicar(b);
    const w = await ate(janelaAlterar, 15000);
    if (!w) throw new Error('janela "Alterar Estoque" não abriu');
    return w;
  }

  async function editar(col, i) {
    limparPopups();
    const w = await abrirProduto(col, i);
    const campo = w.querySelector('input[name="diasAdicionaisPreVenda"] + input.x-form-text');
    const oculto = w.querySelector('input[name="diasAdicionaisPreVenda"]');
    if (!campo) throw new Error('campo Prazo de Expedição não encontrado');

    const atual = parseInt((campo.value || '').replace(/\D/g, '') || '0', 10);
    if (atual !== CFG.alvo) {
      log('  linha ' + (i + 1) + ': formulário mostra ' + atual + ', pulando');
      fechar(w); await sleep(800);
      return false;
    }
    const novo = atual + CFG.inc;

    const cmp = (window.Ext && Ext.getCmp) ? Ext.getCmp(campo.id.replace(/-inputEl$/, '')) : null;
    const valor = () => {
      try { if (cmp && cmp.getValue) return Number(cmp.getValue()); } catch (e) {}
      return parseFloat(oculto ? oculto.value : campo.value);
    };

    // 1) digitar no campo
    campo.focus(); campo.select();
    let ok = false;
    try { ok = document.execCommand('insertText', false, String(novo)); } catch (e) {}
    if (!ok || campo.value.replace(/\D/g, '') !== String(novo)) setNativo(campo, String(novo));
    campo.dispatchEvent(new Event('change', { bubbles: true }));
    campo.blur();
    await sleep(400);

    // 2) se o Ext não registrou, usar a API do componente
    if (Math.round(valor()) !== novo && cmp && cmp.setValue) { try { cmp.setValue(novo); } catch (e) {} await sleep(300); }
    // 3) último recurso
    if (Math.round(valor()) !== novo && oculto) { oculto.value = String(novo); await sleep(200); }

    if (Math.round(valor()) !== novo) {
      log('  linha ' + (i + 1) + ': campo não aceitou ' + novo + ', fechando sem gravar');
      fechar(w); await sleep(800);
      throw new Error('valor não registrado');
    }

    const g = botao(w, /^\s*Gravar\s*$/);
    if (!g) throw new Error('botão Gravar não encontrado');
    clicar(g);
    const fechou = await ate(() => !janelaAlterar(), 8000);
    if (!fechou) {
      log('  linha ' + (i + 1) + ': janela não fechou após Gravar (conferir no painel)');
      fechar(janelaAlterar());
    }
    log('  linha ' + (i + 1) + ': ' + atual + ' -> ' + novo + ' (gravado)');
    await sleep(1500);
    return true;
  }

  try {
    limparPopups();
    const pronto = await ate(() => typeof openRouterAsTab === 'function', 30000);
    if (!pronto) { fim('Falha: painel não terminou de carregar'); return; }

    log('Abrindo Estoque...');
    openRouterAsTab('mz_estoque_list', new Event('click'));
    let col = await ate(colunaPrazo, 40000);
    if (!col) { fim('Falha: coluna "Prazo de Expedição" não apareceu'); return; }
    await ate(() => celulas(col).length > 0, 20000);
    await sleep(1500);

    let pagina = 1, alterados = 0, erros = 0;
    while (pagina <= 100) {
      limparPopups();
      col = colunaPrazo() || col;
      const cels = celulas(col);
      const alvos = [];
      cels.forEach((c, i) => {
        const m = txt(c).match(/\d+/);
        if (m && parseInt(m[0], 10) === CFG.alvo) alvos.push(i);
      });
      log('Página ' + pagina + ': ' + cels.length + ' linhas, ' + alvos.length + ' com ' + CFG.alvo);

      for (const i of alvos) {
        try { if (await editar(col, i)) alterados++; }
        catch (e) {
          erros++;
          log('  erro na linha ' + (i + 1) + ': ' + e.message);
          fechar(janelaAlterar());
          await sleep(1000);
        }
      }

      const assinatura = () => [...document.querySelectorAll('.x-grid-item')].filter(vis)
        .map(r => txt(r).slice(0, 40)).join('|');
      const antes = assinatura();
      const prox = [...document.querySelectorAll('a.x-btn')].filter(vis)
        .find(b => (b.getAttribute('data-qtip') || '') === 'Próxima Página');
      if (!prox || /x-btn-disabled|x-item-disabled/.test(prox.className)) break;
      clicar(prox);
      pagina++;
      await sleep(4000);
      await ate(() => celulas(colunaPrazo() || col).length > 0, 20000);
      if (assinatura() === antes) { log('A página não mudou, encerrando.'); break; }
    }
    fim('Concluído: ' + alterados + ' alterado(s), ' + erros + ' erro(s), ' + pagina + ' página(s)');
  } catch (e) {
    fim('Erro: ' + e.message);
  }
})();
