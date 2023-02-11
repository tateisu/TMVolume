package jp.juggler.tmvolume

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.*
import com.google.android.flexbox.FlexboxLayout
import com.illposed.osc.*
import com.illposed.osc.transport.udp.OSCPortIn
import com.illposed.osc.transport.udp.OSCPortOut
import jp.juggler.tmvolume.FaderVol.toFader
import jp.juggler.tmvolume.databinding.ActivityMainBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.InetAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong


class MainActivity : ScopedActivity() {

    companion object {
        const val TAG = "TMVolume"

        const val PREF_SHOW_CONNECTION_SETTINGS = "ShowConnectionSettings"
        const val PREF_CLIENT_PORT = "ClientPort"
        const val PREF_SERVER_ADDR = "ServerAddr"
        const val PREF_SERVER_PORT = "ServerPort"
        const val PREF_OBJECT_ADDR = "ObjectAddr"
        const val PREF_VOLUME = "Volume"
        const val PREF_BUS = "Bus"
        const val PREF_PRESETS = "Presets"

        // 情報を受信したら画面の一部を光らせる
        const val PingAnimationDuration = 300f
        const val PingColorRed = 0x40.toFloat()
        const val PingColorGreen = 0xff.toFloat()
        const val PingColorBlue = 0x80.toFloat()

        // シークバーの範囲。 0:-∞db, 1:-65db ,,, 143:+6 db (0.5db単位)
        const val SEEKBAR_MIN = 0 // API26未満では常に0
        const val SEEKBAR_MAX = 143

        private fun Int.progressToDb(): Float = 6f - (SEEKBAR_MAX - this) * 0.5f

        private fun Float.dbToProgress(): Int = (SEEKBAR_MAX + ((this - 6f) * 2f).toInt())
            .clip(SEEKBAR_MIN, SEEKBAR_MAX)

        private fun Float.formatDb() = when {
            this < -65f -> "-∞"
            else -> String.format("%.1f", this)
        }

        private val reVolumeVal = """([-+]?[\d.]+)\s*dB\b""".toRegex(RegexOption.IGNORE_CASE)
    }

    class Message(
        val serverAddr: String,
        val serverPort: Int,
        val busAddr: String,
        val objectAddr: String,
        val args: ArrayList<Any>
    )

    private val views by lazy {
        ActivityMainBinding.inflate(layoutInflater)
    }

    private lateinit var pref: SharedPreferences
    private lateinit var handler: Handler

    private val channel = Channel<Message>(capacity = CONFLATED)

    private var restoreBusy = false

    private var presets = mutableListOf<Int>()

    private var receiver: OSCPortIn? = null

    private var lastSeekbarEdit = 0L

    private val lastEventReceived = AtomicLong(0L)

    private var objectMap = ConcurrentHashMap<String, String>()

    private val procShowMap: Runnable = Runnable {
        views.tvMap.text = StringBuilder().apply {
            objectMap.keys().toList().sorted().forEach {
                if (isNotEmpty()) append("\n")
                append("${it}=${objectMap[it]}")
            }
        }.toString()

        val strVolumeVal = (objectMap["${views.etObjectAddress.text}Val"] ?: "?").trim(',')
        val now = SystemClock.elapsedRealtime()
        if (now - lastSeekbarEdit >= 1000L) {
            val progress =
                reVolumeVal.find(strVolumeVal)
                    ?.groupValues?.get(1)?.toFloatOrNull()?.dbToProgress()
                    ?: if (strVolumeVal.contains("∞")) 0 else null
            if (progress != null && progress != views.sbVolume.progress) views.sbVolume.progress =
                progress
        }
    }

    private val procPingColor: Runnable = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)

            val now = SystemClock.elapsedRealtime()
            // t: 0.0f～1.0f イベント発生からの時間経過
            val t = (now - lastEventReceived.get())
                .toFloat()
                .div(PingAnimationDuration)
                .clip(0f, 1f)

            fun Float.xt() = ((1f - t) * this).toInt()
            views.vPing.setBackgroundColor(
                Color.rgb(
                    PingColorRed.xt(),
                    PingColorGreen.xt(),
                    PingColorBlue.xt()
                )
            )
            if (t < 1f) handler.postDelayed(this, 50L)
        }
    }

    //////////////////////////////////////////////////////////////

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        pref = getSharedPreferences("pref", Context.MODE_PRIVATE)

        handler = Handler(mainLooper)

        restoreBusy = true

        initUi()

        presets = pref.getString(PREF_PRESETS, "")!!
            .split(",")
            .mapNotNull { it.toIntOrNull() }
            .sorted()
            .toMutableList()

        if (savedInstanceState == null) {
            loadPref()
            afterRestore()
        }

        launchSender()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        afterRestore()
    }

    override fun onResume() {
        super.onResume()
        showMyAddress()
        startListen()
        send(objectAddr = "/", value = 1f)
    }

    override fun onPause() {
        super.onPause()
        stopListen()
        handler.removeCallbacks(procShowMap)
        handler.removeCallbacks(procPingColor)
    }

    ///////////////////////////////////////////////////////

    private fun EditText.addSaver(key: String, callback: (String) -> Unit = {}) =
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(p0: CharSequence, p1: Int, p2: Int, p3: Int) = Unit
            override fun onTextChanged(p0: CharSequence, p1: Int, p2: Int, p3: Int) = Unit
            override fun afterTextChanged(p0: Editable) {
                if (restoreBusy) return
                val sv = text.toString()
                pref.edit().putString(key, sv).apply()
                callback(sv)
            }
        })

    private fun CompoundButton.addSaver(key: String, value: Int) =
        setOnCheckedChangeListener { _, isChecked ->
            if (restoreBusy) return@setOnCheckedChangeListener
            if (isChecked) pref.edit().putInt(key, value).apply()
        }

    private fun CompoundButton.addSaver(key: String, callback: (Boolean) -> Unit = {}) =
        setOnCheckedChangeListener { _, isChecked ->
            if (restoreBusy) return@setOnCheckedChangeListener
            pref.edit().putBoolean(key, isChecked).apply()
            callback(isChecked)
        }

    private fun initUi() {

        setContentView(views.root)
        supportActionBar?.apply {
            setSubtitle(R.string.header_subtitle)

            setDisplayUseLogoEnabled(true)
            setLogo(R.drawable.header_logo)
            // 最後に呼び出す
            setDisplayShowHomeEnabled(true)

            try {
                val versionName = packageManager.getPackageInfoCompat(packageName).versionName
                title = "${getString(R.string.app_name)} v$versionName"
            } catch (ex: Throwable) {
                Log.e(TAG, "error", ex)
            }
        }



        views.etClientPort.addSaver(PREF_CLIENT_PORT) { startListen() }
        views.etServerAddr.addSaver(PREF_SERVER_ADDR)
        views.etServerPort.addSaver(PREF_SERVER_PORT)
        views.etObjectAddress.addSaver(PREF_OBJECT_ADDR)

        views.rbBusInput.addSaver(PREF_BUS, 0)
        views.rbBusPlayback.addSaver(PREF_BUS, 1)
        views.rbBusOutput.addSaver(PREF_BUS, 2)

        views.swShowConnectionSettings.addSaver(PREF_SHOW_CONNECTION_SETTINGS) { showConnectionSettings() }

        // API26未満では常に0
        // sbVolume.min = SEEKBAR_MIN
        views.sbVolume.max = SEEKBAR_MAX
        views.sbVolume.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(var1: SeekBar) = Unit
            override fun onStopTrackingTouch(var1: SeekBar) = Unit
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (restoreBusy) return
                if (fromUser) lastSeekbarEdit = SystemClock.elapsedRealtime()
                pref.edit().putInt(PREF_VOLUME, progress).apply()
                showVolumeNumber()
                send()
            }
        })

        views.btnPlus.setOnClickListener { setVolume(views.sbVolume.progress + 1) }
        views.btnMinus.setOnClickListener { setVolume(views.sbVolume.progress - 1) }
        views.btnSave.setOnClickListener { addPreset() }
    }

    private fun loadPref() {
        views.etClientPort.setText(pref.getString(PREF_CLIENT_PORT, "9001"))
        views.etServerAddr.setText(pref.getString(PREF_SERVER_ADDR, ""))
        views.etServerPort.setText(pref.getString(PREF_SERVER_PORT, "7001"))
        views.etObjectAddress.setText(pref.getString(PREF_OBJECT_ADDR, "/1/volume1"))
        views.sbVolume.progress = pref.getInt(PREF_VOLUME, avg(SEEKBAR_MIN, SEEKBAR_MAX))
            .clip(SEEKBAR_MIN, SEEKBAR_MAX)

        views.swShowConnectionSettings.isChecked =
            pref.getBoolean(PREF_SHOW_CONNECTION_SETTINGS, true)

        when (pref.getInt(PREF_BUS, 0)) {
            1 -> views.rbBusPlayback
            2 -> views.rbBusOutput
            else -> views.rbBusInput
        }.isChecked = true
    }

    private fun afterRestore() {
        restoreBusy = false
        showPresets()
        showConnectionSettings()
        showVolumeNumber()
    }

    private fun showConnectionSettings() {
        val isShown = views.swShowConnectionSettings.isChecked
        views.rowClient.vg(isShown)
        views.rowServer.vg(isShown)
    }

    private fun showPresets() {
        (0 until views.flPresets.childCount).reversed().forEach { i ->
            val child = views.flPresets.getChildAt(i)
            if (child is Button) {
                views.flPresets.removeViewAt(i)
            }
        }
        val w = (resources.displayMetrics.density * 40f).toInt()
        val h = (resources.displayMetrics.density * 40f).toInt()
        for (progress in presets) {
            views.flPresets.addView(Button(this).apply {
                layoutParams = FlexboxLayout.LayoutParams(
                    FlexboxLayout.LayoutParams.WRAP_CONTENT,
                    h
                ).apply {}
                text = progress.progressToDb().formatDb()
                minWidth = w
                minimumWidth = w
                setOnClickListener { views.sbVolume.progress = progress }
                setOnLongClickListener {
                    removePreset(progress)
                    true
                }
            })
        }
    }

    private fun addPreset() {
        val progress = views.sbVolume.progress
        if (presets.contains(progress)) return
        presets.add(progress)
        presets.sort()
        pref.edit().putString(PREF_PRESETS, presets.joinToString(",")).apply()
        showPresets()
        showVolumeNumber()
    }

    private fun removePreset(progress: Int) {
        presets.remove(progress)
        pref.edit().putString(PREF_PRESETS, presets.joinToString(",")).apply()
        showPresets()
        showVolumeNumber()
    }

    private fun setVolume(newProgress: Int) {
        val oldVal = views.sbVolume.progress
        val newVal = newProgress.clip(SEEKBAR_MIN, SEEKBAR_MAX)
        if (newVal != oldVal) views.sbVolume.progress = newVal
    }

    @SuppressLint("SetTextI18n")
    private fun showVolumeNumber() {
        views.tvVolume.text = "${views.sbVolume.progress.progressToDb().formatDb()} dB"
        views.btnMinus.isEnabledAlpha = views.sbVolume.progress != SEEKBAR_MIN
        views.btnPlus.isEnabledAlpha = views.sbVolume.progress != SEEKBAR_MAX
        views.btnSave.isEnabledAlpha = !presets.contains(views.sbVolume.progress)
    }

    private fun showError(msg: String) {
        if (handler.looper.thread.id != Thread.currentThread().id) {
            handler.post { showError(msg) }
            return
        }
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    private fun send(
        serverAddr: String? = views.etServerAddr.text.toString().trim().notEmpty(),
        serverPort: Int? = views.etServerPort.text.toString().trim().notEmpty()?.toIntOrNull(),
        busAddr: String = when {
            views.rbBusPlayback.isChecked -> "/1/busPlayback"
            views.rbBusOutput.isChecked -> "/1/busOutput"
            else -> "/1/busInput"
        },
        objectAddr: String? = views.etObjectAddress.text.toString().trim().notEmpty(),
        value: Float = views.sbVolume.progress.progressToDb().toFader().toFloat()
    ) {
        val args = arrayListOf<Any>(value)

        if (serverAddr == null) {
            showError("missing server addr")
            return
        }
        if (serverPort == null) {
            showError("missing server port")
            return
        }
        if (objectAddr == null) {
            showError("missing object addr")
            return
        }

        launch(activityJob + Dispatchers.IO) {
            channel.send(
                Message(
                    serverAddr = serverAddr,
                    serverPort = serverPort,
                    busAddr = busAddr,
                    objectAddr = objectAddr,
                    args = args
                )
            )
        }
    }

    private fun launchSender() = launch(activityJob + Dispatchers.IO) {
        while (true) {
            try {
                val item = channel.receive()
                val port = OSCPortOut(InetAddress.getByName(item.serverAddr), item.serverPort)
                try {
                    port.send(OSCMessage(item.busAddr, arrayListOf<Any>(1.0f)))
                    port.send(OSCMessage(item.objectAddr, item.args))
                } finally {
                    try {
                        port.close()
                    } catch (ex: IOException) {
                        ex.printStackTrace()
                    }
                }
            } catch (ex: CancellationException) {
                break
            } catch (ex: Throwable) {
                showError("${ex.javaClass.simpleName} : ${ex.message}")
            }
        }
    }

    private fun showMyAddress() {
        views.tvClientAddr.text = try {
            getV4Addresses().joinToString(", ")
        } catch (ex: Throwable) {
            ex.printStackTrace()
            "?"
        }
    }

    private fun fireShowMap() {
        handler.removeCallbacks(procShowMap)
        handler.postDelayed(procShowMap, 333L)
    }

    private fun OSCPacket.dump(dstMap: ConcurrentHashMap<String, String>) {
        when (this) {
            is OSCMessage -> {
                // Log.d(TAG, "Message addr=${address}")
                val sb = StringBuilder()
                arguments?.forEach {
                    sb.append(',')
                    sb.append(String.format("%s", it))
                }
                dstMap[address] = sb.toString()
            }
            is OSCBundle -> {
                // Log.d(TAG, "Bundle")
                packets?.forEach { it?.dump(dstMap) }
            }
        }
    }


    private fun stopListen() {
        try {
            receiver?.stopListening()
        } catch (ex: Throwable) {
            ex.printStackTrace()
        }
        receiver = null
        objectMap = ConcurrentHashMap()
    }

    private fun startListen() {
        try {
            receiver?.stopListening()
        } catch (ex: Throwable) {
            ex.printStackTrace()
        }
        objectMap = ConcurrentHashMap()
        fireShowMap()
        handler.post(procPingColor)
        receiver = try {
            val port = views.etClientPort.text.toString().trim().toIntOrNull()
            if (port == null || port <= 1024) null else OSCPortIn(port).apply {
                addPacketListener(object : OSCPacketListener {
                    override fun handleBadData(event: OSCBadDataEvent?) = Unit
                    override fun handlePacket(event: OSCPacketEvent?) {
                        val map = ConcurrentHashMap<String, String>()
                        event?.packet?.dump(map)
                        for (i in 1..8) {
                            val va = map["/1/volume$i"]?.trim(',')
                            val vb = map["/1/volume${i}Val"]
                            if (va == null || vb == null) continue
                            if (va == "0.0") continue
                            Log.d(TAG, "volumeVal $va $vb")
                        }
                        for (entry in map.entries) {
                            objectMap[entry.key] = entry.value
                        }
                        fireShowMap()
                        lastEventReceived.set(SystemClock.elapsedRealtime())
                        handler.post(procPingColor)
                    }
                })
                startListening()
            }
        } catch (ex: Throwable) {
            ex.printStackTrace()
            showError("${ex.javaClass.simpleName} : ${ex.message}")
            null
        }
    }
}

/*

TotalMixのOSCパラメータ一覧
An OSC implementation chart can be downloaded from the RME website:
http://www.rme-audio.de/download/osc_table_totalmix.zip

ボリューム指定の前にバス選択メッセージ、以下のいずれかを送る。引数は無視される
/1/busInput 1.0
/1/busPlayback 1.0
/1/busOutput 1.0

次にボリュームメッセージを送る。
末尾のチャネル番号は各バスのTotalMix上の表示順。stereo/mono切り替えの有無で割り当てが変わる
/1/volume1 ～ /1/volume6 (num)
/1/mastervolume (num)
(num)はフェーダーの値を入れる。カーブがあるらしくそのままdbに変換することはできない

TotalMax のOptions-SettingsのOSCタブの Remote Controller Address と Port Outgoing に設定したホストとアドレスに

*/