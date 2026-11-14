package com.orbisrobotics.pdfsync

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Accueil. Les réglages réseau sont faits une seule fois par tablette,
 * ensuite on lance directement la présentation.
 */
class MainActivity : Activity() {

    private lateinit var networkText: TextView
    private var pendingAction: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Storage.wipeAll(this) // rien ne doit rester d'une séance précédente

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(32), dp(40), dp(32))
        }
        content.addView(label(screenTitle(), 28f, Palette.TEXT, bold = true).apply { setPadding(0, dp(16), 0, 0) })
        content.addView(label("L'Expert crée un réseau Wi-Fi Direct privé et chiffré. Activez le Wi-Fi " +
            "sur toutes les tablettes, sans vous connecter à aucun réseau.", 15f, Palette.MUTED).apply {
            setPadding(0, dp(8), 0, dp(28))
        })

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(16), dp(16))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Palette.ACCENT_LIGHT)
            }
        }
        networkText = label("", 16f, Palette.TEXT, bold = true)
        card.addView(networkText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(button("Réglages administrateur") { openAdmin() })
        content.addView(card)
        refreshNetwork()

        val row = LinearLayout(this)
        row.addView(button("Présenter (Expert)", filled = true) { whenReady { open(EXPERT) } }
            .apply { textSize = 20f }, LinearLayout.LayoutParams(0, dp(80), 1f))
        row.addView(button("Rejoindre (Client)", filled = true) { whenReady { openClient() } }
            .apply { textSize = 20f }, LinearLayout.LayoutParams(0, dp(80), 1f).apply { marginStart = dp(16) })
        content.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) })

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.WHITE)
            addView(content)
        }
        setContentView(scroll)
    }

    private fun screenTitle() = "PDF Sync"

    private fun configured() = NetworkSettings.isConfigured(this) && AdminLock.isSet(this)

    private fun refreshNetwork() {
        networkText.text = when {
            !configured() -> "Tablette non configurée : réglez d'abord le réseau."
            else -> "Réseau : ${NetworkSettings.ssid(this)}  (mot de passe chiffré, verrouillé)"
        }
    }

    private fun openAdmin() {
        if (!AdminLock.isSet(this)) {
            editSettings(firstSetup = true)
            return
        }
        val locked = AdminLock.lockedSeconds(this)
        if (locked > 0) {
            toast("Trop d'essais. Réessayez dans ${(locked + 59) / 60} min.")
            return
        }
        val field = EditText(this).apply {
            hint = "Code administrateur"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle("Réglages administrateur")
            .setView(FrameLayout(this).apply {
                setPadding(dp(24), dp(8), dp(24), 0)
                addView(field)
            })
            .setPositiveButton("Valider") { _, _ ->
                if (AdminLock.check(this, field.text.toString())) editSettings(firstSetup = false)
                else toast("Code administrateur incorrect")
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // Première fois : tout est obligatoire. Ensuite un champ vide = inchangé.
    private fun editSettings(firstSetup: Boolean) {
        val roomField = EditText(this).apply {
            hint = "Nom du réseau (ex. Atelier)"
            setText(NetworkSettings.room(this@MainActivity))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val passField = EditText(this).apply {
            hint = if (firstSetup) "Mot de passe Wi-Fi (${NetworkSettings.MIN_PASS} caractères min.)"
            else "Nouveau mot de passe Wi-Fi (vide = inchangé)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirmField = EditText(this).apply {
            hint = "Confirmer le mot de passe Wi-Fi"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val adminField = EditText(this).apply {
            hint = if (firstSetup) "Code administrateur (6 chiffres min.)" else "Nouveau code administrateur (vide = inchangé)"
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        }
        val note = "Nom et mot de passe identiques sur toutes les tablettes. " +
            "Le mot de passe est chiffré et ne pourra plus être affiché."
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(label(note, 13f, Palette.MUTED))
            addView(roomField)
            addView(passField)
            addView(confirmField)
            addView(adminField)
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(if (firstSetup) "Configuration de la tablette" else "Modifier les réglages")
            .setView(box)
            .setPositiveButton("Enregistrer", null)
            .setNegativeButton("Annuler", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val room = roomField.text.toString().trim()
                val pass = passField.text.toString()
                val admin = adminField.text.toString()
                val newPass = firstSetup || pass.isNotEmpty()
                when {
                    room.isEmpty() || room.length > 20 || !room.all { it.isLetterOrDigit() || it == '-' || it == '_' } ->
                        roomField.error = "1 à 20 lettres ou chiffres"
                    newPass && pass.length !in NetworkSettings.MIN_PASS..63 ->
                        passField.error = "Entre ${NetworkSettings.MIN_PASS} et 63 caractères"
                    newPass && pass != confirmField.text.toString() ->
                        confirmField.error = "Les mots de passe sont différents"
                    (firstSetup || admin.isNotEmpty()) && admin.length < 6 -> adminField.error = "6 chiffres minimum"
                    else -> {
                        NetworkSettings.save(this, room, if (newPass) pass else NetworkSettings.passphrase(this))
                        if (admin.isNotEmpty()) AdminLock.set(this, admin)
                        refreshNetwork()
                        dialog.dismiss()
                        toast("Réglages enregistrés")
                    }
                }
            }
        }
        dialog.show()
    }

    private fun open(activity: Class<*>) = startActivity(Intent(this, activity))

    private fun openClient() {
        if (Build.VERSION.SDK_INT >= 29) {
            open(CLIENT)
            return
        }
        // Android 7 à 9 : pas de connexion automatique, on passe par les réglages Wi-Fi
        AlertDialog.Builder(this)
            .setTitle("Connexion au réseau de l'Expert")
            .setMessage("Sur cette version d'Android, connectez-vous d'abord au réseau " +
                "« ${NetworkSettings.ssid(this)} » dans les réglages Wi-Fi, puis revenez ici.")
            .setPositiveButton("Réglages Wi-Fi") { _, _ -> startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
            .setNeutralButton("Je suis connecté") { _, _ -> open(CLIENT) }
            .setNegativeButton("Annuler", null)
            .show()
    }

    // permissions, Wi-Fi allumé, localisation (Android 9 à 12) avant de lancer
    private fun whenReady(action: () -> Unit) {
        if (!configured()) {
            toast("Cette tablette n'est pas encore configurée.")
            editSettings(firstSetup = true)
            return
        }
        val perms = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            pendingAction = action
            requestPermissions(missing.toTypedArray(), REQ_PERMS)
            return
        }
        val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (!wifi.isWifiEnabled) {
            toast("Activez le Wi-Fi (inutile de vous connecter à un réseau) puis réessayez.")
            startActivity(Intent(if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_WIFI else Settings.ACTION_WIFI_SETTINGS))
            return
        }
        if (Build.VERSION.SDK_INT in 28..32) {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            if (!lm.isLocationEnabled) {
                toast("Android demande la localisation activée pour le Wi-Fi Direct.")
                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                return
            }
        }
        action()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQ_PERMS) return
        val action = pendingAction
        pendingAction = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            action?.let { whenReady(it) }
        } else {
            toast("Permission nécessaire pour le Wi-Fi Direct")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_PERMS = 10
        private val EXPERT = ExpertActivity::class.java
        private val CLIENT = ClientActivity::class.java
    }
}
