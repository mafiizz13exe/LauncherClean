package com.magazord.prazo

import android.content.Context

object Prefs {
    const val URL_PADRAO = "https://ambientenobre.painel.magazord.com.br/"

    private fun sp(c: Context) = c.getSharedPreferences("cfg", Context.MODE_PRIVATE)
    fun get(c: Context, k: String, d: String): String = sp(c).getString(k, d) ?: d
    fun put(c: Context, k: String, v: String) { sp(c).edit().putString(k, v).apply() }
}
