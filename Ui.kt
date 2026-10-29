package com.livehelp

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.InputType
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import org.json.JSONObject

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.title(t: String) = TextView(this).apply { text = t; textSize = 28f; setTypeface(null, Typeface.BOLD) }
fun Context.label(t: String, size: Float = 14f) = TextView(this).apply { text = t; textSize = size }
fun Context.field(hint: String, password: Boolean = false, phone: Boolean = false) = EditText(this).apply {
    this.hint = hint; setSingleLine()
    inputType = when {
        password -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        phone -> InputType.TYPE_CLASS_PHONE
        else -> InputType.TYPE_CLASS_TEXT
    }
}
fun Context.btn(t: String, f: () -> Unit) = Button(this).apply { text = t; isAllCaps = false; setOnClickListener { f() } }
fun Context.page(vararg v: View): View = ScrollView(this).apply {
    addView(LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(32), dp(20), dp(20))
        v.forEach { addView(it, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(10) }) }
    })
}

fun Activity.doAuth(path: String, phone: String, pass: String, msg: TextView) {
    msg.text = ""
    Thread {
        try {
            val (code, j) = Api.post(path, JSONObject().put("phone", phone).put("password", pass))
            runOnUiThread {
                if (code == 200) {
                    Store.save(this, j.getString("token"))
                    startActivity(Intent(this, HomeActivity::class.java)); finishAffinity()
                } else msg.text = j.optString("error", "Failed")
            }
        } catch (e: Exception) { runOnUiThread { msg.text = "Cannot reach server" } }
    }.start()
}
