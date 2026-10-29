package com.livehelp

import android.app.Activity
import android.os.Bundle

class RegisterActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val phone = field("Mobile number", phone = true)
        val pass = field("Password (min 8 characters)", password = true)
        val pass2 = field("Confirm password", password = true)
        val msg = label("").apply { setTextColor(0xFFC62828.toInt()) }
        setContentView(page(title("Register"), phone, pass, pass2, msg, btn("Register") {
            val p = phone.text.toString().trim()
            when {
                !Regex("^\\+?\\d{7,15}$").matches(p) -> msg.text = "Enter a valid mobile number"
                pass.text.length < 8 -> msg.text = "Password must be at least 8 characters"
                pass.text.toString() != pass2.text.toString() -> msg.text = "Passwords do not match"
                else -> doAuth("/api/register", p, pass.text.toString(), msg)
            }
        }))
    }
}
