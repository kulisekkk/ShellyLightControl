package com.kulisak.lightcontrol

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var devicesFile: File
    private val client = OkHttpClient()
    private val pool = Executors.newFixedThreadPool(20)
    private val REQUEST_CODE_PICK_IMAGE = 1001

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        checkPermissionsForStorage()
        devicesFile = File(filesDir, "devices.json")
        synchronized(devicesFile) {
            if (!devicesFile.exists()) {
                devicesFile.writeText("{\"devices\":[]}")
            }
        }

        // Nastavení WebView
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()
        webView.loadUrl("file:///android_asset/index.html")

        // Registrace JavaScript rozhraní (API)
        webView.addJavascriptInterface(object {

            // dev name = Device Name
            @JavascriptInterface
            fun toggle(devName: String) {
                Thread {
                    try {
                        val ip = getDeviceIP(devName) ?: return@Thread
                        val status = getLightStatus(ip)
                        val newState = status != true // Otočíme stav

                        val request = Request.Builder()
                            .url("http://$ip/rpc/Switch.Set?id=0&on=$newState")
                            .build()

                        client.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw Exception("Unexpected code $response")
                        }

                        // Po úspěšném přepnutí upravíme stav v souboru
                        updateDeviceStatusInList(devName, newState)
                        sendSuccessToFront("Světlo $devName přepnuto")
                    } catch (e: Exception) {
                        e.printStackTrace()
                        sendErrorToFront(e.localizedMessage ?: "Chyba při přepínání světla")
                    }
                }.start()
            }

            @JavascriptInterface
            fun getStatus(devName: String) {
                Thread {
                    try {

                        // Sestavíme request a dáme do něj url
                        val ip = getDeviceIP(devName) ?: return@Thread
                        val request = Request.Builder()
                            .url("http://$ip/rpc/Switch.GetStatus?id=0")
                            .build()

                        val result = JSONObject()
                        result.put("deviceName", devName)

                        try {
                            client.newCall(request).execute().use { response ->
                                result.put("connected", response.isSuccessful)
                                if (response.isSuccessful) {
                                    val body = response.body?.string() ?: "{}"
                                    val status = JSONObject(body)
                                    result.put("isOn", status.optBoolean("output", false))
                                }
                            }
                        } catch (e: Exception) {
                            result.put("connected", false)
                        }

                        webView.post {
                            val safeString = result.toString().replace("'", "\\'")
                            webView.evaluateJavascript("onStatusReceived('${safeString}')", null)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }.start()
            }

            @JavascriptInterface
            fun addDevice(name: String, ip: String, status: Boolean) {
                addToDevicesList(name, ip, status)
            }

            @JavascriptInterface
            fun removeDevice(name: String) {
                removeFromDevicesList(name)
            }

            @JavascriptInterface
            fun scanDevices() {
                // Spustíme skenování sítě a výsledek pošleme do JS přes sendDevices
                scanNetwork { foundDevices ->
                    sendDevices(foundDevices)
                }
            }

            @JavascriptInterface
            fun loadDevices() {
                // Voláme interní bezpečnou metodu aktivity
                this@MainActivity.loadDevicesInternal()
            }

            @JavascriptInterface
            fun saveDevicePositions(jsonString: String) {
                try {
                    synchronized(devicesFile) {
                        val json = JSONObject(jsonString)
                        devicesFile.writeText(json.toString(2))
                    }
                } catch (e: Exception) {
                    sendErrorToFront("Chyba při ukládání: ${e.localizedMessage}")
                }
            }

            @JavascriptInterface
            fun pickFloorplan() {
                val intent = Intent(Intent.ACTION_PICK)
                intent.type = "image/*"
                startActivityForResult(intent, REQUEST_CODE_PICK_IMAGE)
            }

            @JavascriptInterface
            fun getFloorplanData(): String? {
                val file = File(filesDir, "floorplan.png")
                return if (file.exists()) {
                    val bytes = file.readBytes()
                    val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                    "data:image/png;base64,$base64"
                } else {
                    null
                }
            }
        }, "API")
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_PICK_IMAGE && resultCode == RESULT_OK) {
            data?.data?.let { uri ->
                saveFloorplan(uri)
            }
        }
    }
    private fun checkPermissionsForStorage() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 101
            )
        }
    }
    private fun saveFloorplan(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { input ->
                val outFile = File(filesDir, "floorplan.png")
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            // Znovu načteme HTML aby se projevila změna
            webView.post { webView.reload() }
        } catch (e: Exception) {
            e.printStackTrace()
            sendErrorToFront("Nepodařilo se uložit půdorys.")
        }
    }

    // Pomocná metoda pro bezpečné načtení zařízení (volaná z onCreate i z vnitřku Kotlinu)
    private fun loadDevicesInternal() {
        Thread {
            try {
                val jsonString = synchronized(devicesFile) { 
                    val raw = devicesFile.readText()
                    // Odstraníme zalomení řádků, aby se nerozbil JS string v evaluateJavascript
                    JSONObject(raw).toString()
                }
                webView.post {
                    val safeJson = jsonString.replace("'", "\\'")
                    webView.evaluateJavascript("receiveStoredDevices('$safeJson')", null)
                }
            } catch (e: Exception) {
                sendErrorToFront("Nepodařilo se načíst uložená zařízení.")
            }
        }.start()
    }

    // Pomocná funkcie pro vyhledání IP podle názvu
    fun getDeviceIP(name: String): String? {
        val jsonString = synchronized(devicesFile) { devicesFile.readText() }
        val json = JSONObject(jsonString)
        val devices = json.getJSONArray("devices")

        for (i in 0 until devices.length()) {
            val device = devices.getJSONObject(i)
            if (device.getString("name") == name) {
                return device.getString("ip")
            }
        }
        return null
    }

    // Pomocná funkce pro zjištění uloženého stavu světla
    fun getLightStatus(ip: String): Boolean? {
        val jsonString = synchronized(devicesFile) { devicesFile.readText() }
        val json = JSONObject(jsonString)
        val devices = json.getJSONArray("devices")

        for (i in 0 until devices.length()) {
            val device = devices.getJSONObject(i)
            if (device.getString("ip") == ip) {
                return device.getBoolean("isOn")
            }
        }
        return null
    }

    fun scanNetwork(onResult: (Map<String, String>) -> Unit) {
        val devices = mutableMapOf<String, String>()
        val subnetsToScan = 256
        // Skenujeme 0..255 subnety + 1 pokus pro emulátor (10.0.2.2)
        var remaining = subnetsToScan + 1

        val scanClient = client.newBuilder()
            .connectTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            .readTimeout(300, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        fun checkIP(ip: String) {
            pool.execute {
                try {
                    val request = Request.Builder()
                        .url("http://$ip/rpc/Shelly.GetDeviceInfo")
                        .build()

                    scanClient.newCall(request).execute().use { response ->
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body?.string() ?: "{}")
                            val name = json.optString("name", "Shelly ($ip)")
                            synchronized(devices) {
                                devices[ip] = name
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Zařízení na této IP pravděpodobně neexistuje takže těžkej ignor xd
                } finally {
                    synchronized(devices) {
                        remaining--
                        if (remaining == 0) {
                            Handler(Looper.getMainLooper()).post {
                                onResult(devices)
                            }
                        }
                    }
                }
            }
        }

        // Spustíme skenování pro všechny varianty 192.168.X.100
        for (i in 0 until subnetsToScan) {
            checkIP("192.168.100.$i")
        }
        
        // Vždy zkusíme i emulátor pro vývoj
        checkIP("10.0.2.2")
    }

    // Uložení nového zařízení do lokálního souboru
    fun addToDevicesList(name: String, ip: String, status: Boolean) {
        try {
            synchronized(devicesFile) {
                val json = JSONObject(devicesFile.readText())
                val devices = json.getJSONArray("devices")

                // Kontrola, zda zařízení již neexistuje (podle názvu nebo IP)
                for (i in 0 until devices.length()) {
                    val d = devices.getJSONObject(i)
                    if (d.getString("name") == name || d.getString("ip") == ip) {
                        sendErrorToFront("Zařízení $name nebo IP $ip již v seznamu existuje.")
                        return
                    }
                }

                val newDeviceDict = JSONObject().apply {
                    put("name", name)
                    put("ip", ip)
                    put("isOn", status)
                    put("size", 50) // Výchozí velikost tlačítek
                    put("x", 50) // Výchozí pozice X na mapě (střed)
                    put("y", 50) // Výchozí pozice Y na mapě (střed)
                }
                devices.put(newDeviceDict)
                devicesFile.writeText(json.toString(2))
            }

            // Po úspěšném přidání obnovíme zobrazení na webu
            loadDevicesInternal()
            sendSuccessToFront("Zařízení $name bylo přidáno")
        } catch (e: Exception) {
            sendErrorToFront(e.localizedMessage ?: "Neznámá chyba při zápisu")
        }
    }

    // Aktualizace stavu (isOn) u existujícího zařízení v souboru
    fun updateDeviceStatusInList(name: String, status: Boolean) {
        try {
            synchronized(devicesFile) {
                val json = JSONObject(devicesFile.readText())
                val devices = json.getJSONArray("devices")
                for (i in 0 until devices.length()) {
                    val device = devices.getJSONObject(i)
                    if (device.getString("name") == name) {
                        device.put("isOn", status)
                        break
                    }
                }
                devicesFile.writeText(json.toString(2))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Odstranění zařízení z lokálního souboru podle názvu
    fun removeFromDevicesList(name: String): Boolean {
        try {
            synchronized(devicesFile) {
                val json = JSONObject(devicesFile.readText())
                val devices = json.getJSONArray("devices")

                for (i in 0 until devices.length()) {
                    val device = devices.getJSONObject(i)
                    if (device.getString("name") == name) {
                        devices.remove(i)
                        devicesFile.writeText(json.toString(2))
                        
                        // Obnovíme zobrazení na webu
                        loadDevicesInternal()
                        sendSuccessToFront("Zařízení $name odebráno")
                        return true
                    }
                }
            }
        } catch (e: Exception) {
            sendErrorToFront(e.localizedMessage ?: "Neznámá chyba při mazání")
        }
        return false
    }

    // Odeslání chybové zprávy do JavaScriptu
    fun sendErrorToFront(msg: String) {
        webView.post {
            webView.evaluateJavascript("ShowError('${msg}')", null)
        }
    }

    // Odeslání úspěšné zprávy do JavaScriptu
    fun sendSuccessToFront(msg: String) {
        webView.post {
            webView.evaluateJavascript("ShowSuccess('${msg}')", null)
        }
    }

    // Odeslání nalezených zařízení ze skenování jako JSON objekt do JavaScriptu
    fun sendDevices(devices: Map<String, String>): Boolean {
        return try {
            val jsonObject = JSONObject(devices)
            webView.post {
                val jsonString = jsonObject.toString().replace("'", "\\'")
                webView.evaluateJavascript("receiveDevices('$jsonString')", null)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}