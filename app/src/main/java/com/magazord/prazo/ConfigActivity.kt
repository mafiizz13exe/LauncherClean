package com.magazord.prazo

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConfigActivity : Activity() {
    private lateinit var email: EditText
    private lateinit var senha: EditText
    private lateinit var totp: EditText
    private lateinit var hora: EditText
    private lateinit var minuto: EditText
    private lateinit var alvo: EditText
    private lateinit var inc: EditText
    private lateinit var status: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun campo(root: LinearLayout, rotulo: String, valor: String, tipo: Int): EditText {
        root.addView(TextView(this).apply { text = rotulo; setPadding(0, dp(12), 0, 0) })
        val e = EditText(this)
        e.inputType = tipo
        e.setText(valor)
        root.addView(e)
        return e
    }

    private fun botao(root: LinearLayout, texto: String, acao: () -> Unit) {
        val b = Button(this)
        b.text = texto
        b.setOnClickListener { acao() }
        root.addView(b)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val sv = ScrollView(this)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(dp(16), dp(24), dp(16), dp(24))
        sv.addView(root)

        root.addView(TextView(this).apply { text = "Prazo Magazord"; textSize = 24f })
        status = TextView(this)
        status.setPadding(0, dp(8), 0, dp(8))
        root.addView(status)

        email = campo(root, "E-mail do painel", Prefs.get(this, "email", ""),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS)
        senha = campo(root, "Senha", Prefs.get(this, "senha", ""),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        totp = campo(root, "Chave secreta do 2FA (a do QR code)", Prefs.get(this, "totp", ""),
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS)
        hora = campo(root, "Hora (0-23)", Prefs.get(this, "hora", "9"), InputType.TYPE_CLASS_NUMBER)
        minuto = campo(root, "Minuto (0-59)", Prefs.get(this, "minuto", "20"), InputType.TYPE_CLASS_NUMBER)
        alvo = campo(root, "Prazo que será alterado (itens com este valor)", Prefs.get(this, "alvo", "17"),
            InputType.TYPE_CLASS_NUMBER)
        inc = campo(root, "Somar/subtrair (1 = +1 dia, -1 = -1 dia)", Prefs.get(this, "incremento", "1"),
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED)

        botao(root, "Salvar e agendar") { salvar(); agendar() }
        botao(root, "Executar agora (teste)") {
            salvar()
            startActivity(Intent(this, RunActivity::class.java))
        }
        botao(root, "1) Permitir exibir sobre outros apps") {
            startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
            )
        }
        botao(root, "2) Sem otimização de bateria") {
            try {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                        Uri.parse("package:$packageName"))
                )
            } catch (_: Exception) {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
        setContentView(sv)
    }

    override fun onResume() {
        super.onResume()
        atualizarStatus()
    }

    private fun atualizarStatus() {
        val ov = Settings.canDrawOverlays(this)
        val prox = Prefs.get(this, "proximo", "").toLongOrNull()
        val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())
        status.text = "Sobrepor outros apps: " + (if (ov) "OK" else "PENDENTE (botão 1)") +
                "\nPróxima execução: " + (if (prox != null) fmt.format(Date(prox)) else "não agendada") +
                "\nÚltima execução: " + Prefs.get(this, "ultimo", "nenhuma ainda")
    }

    private fun salvar() {
        Prefs.put(this, "email", email.text.toString().trim())
        Prefs.put(this, "senha", senha.text.toString())
        Prefs.put(this, "totp", totp.text.toString().replace(" ", "").trim())
        Prefs.put(this, "hora", (hora.text.toString().toIntOrNull() ?: 9).coerceIn(0, 23).toString())
        Prefs.put(this, "minuto", (minuto.text.toString().toIntOrNull() ?: 20).coerceIn(0, 59).toString())
        Prefs.put(this, "alvo", (alvo.text.toString().toIntOrNull() ?: 17).toString())
        Prefs.put(this, "incremento", (inc.text.toString().toIntOrNull() ?: 1).toString())
    }

    private fun agendar() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)
        }
        Prefs.put(this, "agendado", "1")
        val t = Scheduler.agendar(this)
        val fmt = SimpleDateFormat("dd/MM 'às' HH:mm", Locale.getDefault())
        Toast.makeText(this, "Agendado para " + fmt.format(Date(t)), Toast.LENGTH_LONG).show()
        atualizarStatus()
    }
}
