package com.magazord.prazo

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RunActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var logTv: TextView
    private lateinit var logScroll: ScrollView
    private val h = Handler(Looper.getMainLooper())

    private var finished = false
    private var injected = false
    private var auto = false
    private val t0 = System.currentTimeMillis()
    private var tInj = 0L
    private var lastLogin = 0L
    private var lastTotp = 0L
    private var loginTries = 0
    private var totpTries = 0

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun q(s: String): String = JSONObject.quote(s)

    private val estadoJs = """
        (function(){
          var v=function(e){return !!(e&&(e.offsetWidth||e.offsetHeight||e.getClientRects().length));};
          var t=document.querySelector('#twofactorcode');
          if(t&&v(t))return '2fa';
          var p=document.querySelector('#password');
          if(p&&v(p))return 'login';
          if(document.querySelector('#tab-admin-container'))return 'painel';
          return 'outro';
        })()
    """.trimIndent()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        auto = intent.getBooleanExtra("auto", false)

        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL

        web = WebView(this)
        root.addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        logScroll = ScrollView(this)
        logScroll.setBackgroundColor(Color.BLACK)
        logTv = TextView(this)
        logTv.setTextColor(Color.GREEN)
        logTv.textSize = 11f
        logTv.setPadding(dp(8), dp(4), dp(8), dp(4))
        logScroll.addView(logTv)
        root.addView(logScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)))
        setContentView(root)

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(web, true)

        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(true)
        s.builtInZoomControls = true
        s.displayZoomControls = false
        // painel é feito para desktop
        s.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

        web.webViewClient = WebViewClient()
        web.webChromeClient = WebChromeClient()
        web.addJavascriptInterface(Bridge(), "Android")

        addLog("Iniciando" + (if (auto) " (agendado)" else " (manual)") + "...")
        web.loadUrl(Prefs.get(this, "url", Prefs.URL_PADRAO))
        h.postDelayed(tick, 3000)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            passo()
            h.postDelayed(this, 2500)
        }
    }

    private fun passo() {
        val agora = System.currentTimeMillis()
        if (!injected && agora - t0 > 20 * 60_000) { fim("Tempo esgotado no login"); return }
        if (injected && agora - tInj > 90 * 60_000) { fim("Tempo esgotado na automação"); return }
        if (injected) return
        web.evaluateJavascript(estadoJs) { r -> estado((r ?: "").trim('"')) }
    }

    private fun preencher(campos: List<Pair<String, String>>): String {
        val sb = StringBuilder(
            "(function(){var s=function(e,v){if(!e)return;e.focus();" +
            "Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(e,v);" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));" +
            "e.dispatchEvent(new Event('change',{bubbles:true}))};"
        )
        for ((sel, valor) in campos) {
            sb.append("s(document.querySelector(").append(q(sel)).append("),").append(q(valor)).append(");")
        }
        sb.append("var b=document.querySelector('button.submit');if(b)b.click();})()")
        return sb.toString()
    }

    private fun estado(s: String) {
        if (finished) return
        val agora = System.currentTimeMillis()
        when (s) {
            "login" -> {
                if (agora - lastLogin < 120_000) return
                val em = Prefs.get(this, "email", "")
                val se = Prefs.get(this, "senha", "")
                if (em.isEmpty() || se.isEmpty()) { fim("Preencha e-mail e senha no app"); return }
                if (++loginTries > 3) { fim("Login não concluiu após 3 tentativas"); return }
                lastLogin = agora
                addLog("Login (tentativa $loginTries). Se aparecer 'Não sou um robô', resolva na tela.")
                web.evaluateJavascript(preencher(listOf("#email" to em, "#password" to se)), null)
            }
            "2fa" -> {
                if (agora - lastTotp < 40_000) return
                val chave = Prefs.get(this, "totp", "")
                if (chave.isEmpty()) { fim("Preencha a chave do 2FA no app"); return }
                if (Totp.segundosRestantes() < 4) return // espera o próximo código
                if (++totpTries > 3) { fim("2FA não concluiu após 3 tentativas"); return }
                val cod = try { Totp.codigo(chave) } catch (e: Exception) {
                    fim("Chave do 2FA inválida"); return
                }
                lastTotp = agora
                addLog("Enviando código 2FA (tentativa $totpTries)...")
                web.evaluateJavascript(preencher(listOf("#twofactorcode" to cod)), null)
            }
            "painel" -> {
                if (injected) return
                injected = true
                tInj = agora
                addLog("Logado. Iniciando ajuste dos prazos...")
                val cfg = "window.__CFG={alvo:" + (Prefs.get(this, "alvo", "17").toIntOrNull() ?: 17) +
                        ",inc:" + (Prefs.get(this, "incremento", "1").toIntOrNull() ?: 1) + "};"
                val js = assets.open("automacao.js").bufferedReader().use { it.readText() }
                web.evaluateJavascript(cfg + js, null)
            }
        }
    }

    private fun addLog(m: String) {
        val hora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        logTv.append("$hora $m\n")
        logScroll.post { logScroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun fim(msg: String) {
        if (finished) return
        finished = true
        addLog("== $msg")
        val quando = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()).format(Date())
        Prefs.put(this, "ultimo", "$quando — $msg")
        CookieManager.getInstance().flush()
        if (auto) h.postDelayed({ finish() }, 15_000)
    }

    inner class Bridge {
        @JavascriptInterface fun log(m: String) { runOnUiThread { addLog(m) } }
        @JavascriptInterface fun done(m: String) { runOnUiThread { fim(m) } }
    }

    override fun onDestroy() {
        h.removeCallbacksAndMessages(null)
        web.destroy()
        super.onDestroy()
    }
}
