package com.livehelp

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.widget.*

class HomeActivity : Activity(), Session.Listener {
    private lateinit var status: TextView
    private lateinit var note: TextView
    private lateinit var bStart: Button
    private lateinit var bStop: Button

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Store.token(this) == null) { startActivity(Intent(this, LoginActivity::class.java)); finish(); return }
        status = label("Disconnected", 26f); note = label("")
        bStart = btn("Start") { askStart() }
        bStop = btn("Stop") { Session.end() }
        setContentView(page(title("LiveHelp"), status, note, bStart, bStop))
        if (!Store.consented(this)) welcome()
    }

    private fun welcome() {
        AlertDialog.Builder(this).setCancelable(false).setTitle("Welcome to LiveHelp")
            .setMessage("To give you live support, LiveHelp needs:\n\n" +
                "• Notifications: to show a notice while your screen is shared.\n" +
                "• Screen recording: Android asks you every time you tap Start.\n\n" +
                "Your screen is shared only after you allow it, and you can tap Stop at any time.")
            .setPositiveButton("Allow") { _, _ -> Store.setConsented(this); needNotif() }
            .setNegativeButton("No") { _, _ -> finishAffinity() }.show()
    }

    private fun needNotif(): Boolean {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1); return true
        }
        return false
    }

    private fun askStart() {
        if (needNotif()) { Toast.makeText(this, "Allow notifications, then tap Start again", Toast.LENGTH_LONG).show(); return }
        AlertDialog.Builder(this).setTitle("Allow screen recording?")
            .setMessage("The LiveHelp admin will see your screen live, including other apps, until you tap Stop.")
            .setPositiveButton("Allow") { _, _ ->
                startActivityForResult(getSystemService(MediaProjectionManager::class.java).createScreenCaptureIntent(), 7)
            }.setNegativeButton("Cancel", null).show()
    }

    @Deprecated("simple flow")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 7) {
            if (res == RESULT_OK && data != null) Session.start(this, Store.token(this)!!, data)
            else Toast.makeText(this, "Permission not given", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() { super.onStart(); Session.listener = this; onChange() }
    override fun onStop() { super.onStop(); if (Session.listener === this) Session.listener = null }

    override fun onChange() {
        val s = Session.state
        status.text = s.name.lowercase().replaceFirstChar { it.uppercase() }
        status.setTextColor(when (s) {
            Session.State.CONNECTED -> 0xFF2E7D32.toInt(); Session.State.CONNECTING -> 0xFFEF6C00.toInt(); else -> 0xFFC62828.toInt() })
        note.text = Session.note
        bStart.isEnabled = s == Session.State.DISCONNECTED
        bStop.isEnabled = s != Session.State.DISCONNECTED
    }
}
