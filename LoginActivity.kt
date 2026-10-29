package com.livehelp

import android.app.Activity
import android.content.Intent
import android.os.Bundle

class LoginActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Store.token(this) != null) { startActivity(Intent(this, HomeActivity::class.java)); finish(); return }
        val phone = field("Mobile number", phone = true)
        val pass = field("Password", password = true)
        val msg = label("").apply { setTextColor(0xFFC62828.toInt()) }
        setContentView(page(title("LiveHelp"), phone, pass, msg,
            btn("Login") { doAuth("/api/login", phone.text.toString().trim(), pass.text.toString(), msg) },
            btn("Create account") { startActivity(Intent(this, RegisterActivity::class.java)) }))
    }
}
