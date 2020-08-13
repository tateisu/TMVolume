package jp.juggler.tmvolume

import android.content.Context
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.*
import com.illposed.osc.*
import com.illposed.osc.transport.udp.OSCPortIn
import com.illposed.osc.transport.udp.OSCPortOut
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.CONFLATED
import java.io.IOException
import java.math.BigInteger
import java.net.InetAddress
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ScopedActivity() {

    companion object {
        const val PREF_CLIENT_PORT = "ClientPort"
        const val PREF_SERVER_ADDR = "ServerAddr"
        const val PREF_SERVER_PORT = "ServerPort"
        const val PREF_OBJECT_ADDR = "ObjectAddr"
        const val PREF_VOLUME_MIN = "VolumeMin"
        const val PREF_VOLUME_MAX = "VolumeMAX"
        const val PREF_VOLUME = "Volume"
        const val PREF_BUS = "Bus"

        fun String?.notEmpty() = if (this?.isNotEmpty() == true) this else null

        const val db0 = 0.81720435
        private const val seekBarScaling = 100000.0


        val SeekBar.progressDouble: Double
            get() = progress.toDouble() / seekBarScaling

    }

    private lateinit var tvClientAddr: TextView
    private lateinit var etClientPort: EditText
    private lateinit var etServerAddr: EditText
    private lateinit var etServerPort: EditText
    private lateinit var etObjectAddress: EditText
    private lateinit var rbBusInput: RadioButton
    private lateinit var rbBusPlayback: RadioButton
    private lateinit var rbBusOutput: RadioButton

    private lateinit var sbVolumeMin: SeekBar
    private lateinit var sbVolumeMax: SeekBar
    private lateinit var sbVolume: SeekBar
    private lateinit var tvVolumeMin: TextView
    private lateinit var tvVolumeMax: TextView
    private lateinit var tvVolume: TextView
    private lateinit var tvMap: TextView
    private lateinit var tvVolumeVal: TextView

    private var restoreBusy = false
    private lateinit var pref: SharedPreferences
    private lateinit var handler: Handler
    private val channel = Channel<Message>(capacity = CONFLATED)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        handler = Handler()
        pref = getSharedPreferences("pref", Context.MODE_PRIVATE)

        restoreBusy = true
        initUi()

        if (savedInstanceState == null) {
            loadPref()
            restoreBusy = false
        }

        showVolumeNumber()

        startWorker()

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
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        restoreBusy = false
        showVolumeNumber()
    }

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

    private fun RadioButton.addSaver(key: String, value: Int) =
        setOnCheckedChangeListener { _, isChecked ->
            if (restoreBusy) return@setOnCheckedChangeListener
            if (isChecked) pref.edit().putInt(key, value).apply()
        }

    private fun SeekBar.addListener(key: String, cb: (Int) -> Unit) {
        setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(var1: SeekBar) = Unit
            override fun onStopTrackingTouch(var1: SeekBar) = Unit
            override fun onProgressChanged(var1: SeekBar, var2: Int, var3: Boolean) {
                if (restoreBusy) return
                pref.edit().putInt(key, var1.progress).apply()
                cb(var1.progress)
                showVolumeNumber()
                send()
            }
        })
    }

    private fun initUi() {
        setContentView(R.layout.activity_main)

        tvClientAddr = findViewById(R.id.tvClientAddr)
        etClientPort = findViewById(R.id.etClientPort)
        etServerAddr = findViewById(R.id.etServerAddr)
        etServerPort = findViewById(R.id.etServerPort)
        etObjectAddress = findViewById(R.id.etObjectAddress)
        sbVolumeMin = findViewById(R.id.sbVolumeMin)
        sbVolumeMax = findViewById(R.id.sbVolumeMax)
        sbVolume = findViewById(R.id.sbVolume)

        tvVolumeMin = findViewById(R.id.tvVolumeMin)
        tvVolumeMax = findViewById(R.id.tvVolumeMax)
        tvVolume = findViewById(R.id.tvVolume)

        rbBusInput = findViewById(R.id.rbBusInput)
        rbBusPlayback = findViewById(R.id.rbBusPlayback)
        rbBusOutput = findViewById(R.id.rbBusOutput)

        tvMap = findViewById(R.id.tvMap)
        tvVolumeVal = findViewById(R.id.tvVolumeVal)

        etClientPort.addSaver(PREF_CLIENT_PORT) { startListen() }
        etServerAddr.addSaver(PREF_SERVER_ADDR)
        etServerPort.addSaver(PREF_SERVER_PORT)
        etObjectAddress.addSaver(PREF_OBJECT_ADDR)

        rbBusInput.addSaver(PREF_BUS, 0)
        rbBusPlayback.addSaver(PREF_BUS, 1)
        rbBusOutput.addSaver(PREF_BUS, 2)

        sbVolumeMin.addListener(PREF_VOLUME_MIN) { sbVolumeMax.min = it }
        sbVolumeMax.addListener(PREF_VOLUME_MAX) { sbVolumeMin.max = it }
        sbVolume.addListener(PREF_VOLUME) {}

//        findViewById<Button>(R.id.btnScan).setOnClickListener { scan() }
    }

    private fun loadPref() {
        etClientPort.setText(pref.getString(PREF_CLIENT_PORT, "9001"))
        etServerAddr.setText(pref.getString(PREF_SERVER_ADDR, ""))
        etServerPort.setText(pref.getString(PREF_SERVER_PORT, "7001"))
        etObjectAddress.setText(pref.getString(PREF_OBJECT_ADDR, "/1/volume1"))
        sbVolumeMin.progress = pref.getInt(PREF_VOLUME_MIN, 0)
        sbVolumeMax.progress = pref.getInt(PREF_VOLUME_MAX, seekBarScaling.toInt())
        sbVolume.progress = pref.getInt(PREF_VOLUME, seekBarScaling.toInt() / 2)

        when (pref.getInt(PREF_BUS, 0)) {
            1 -> rbBusPlayback
            2 -> rbBusOutput
            else -> rbBusInput
        }.isChecked = true
    }

    private fun getVolumeValue(): Float {
        val max = sbVolumeMax.progressDouble
        val min = sbVolumeMin.progressDouble
        val width = max - min
        val curr = when {
            width <= 0f -> min
            else -> min + width * sbVolume.progressDouble
        }
        Log.d("TMVolume", String.format("curr=%f", curr))
        return if (curr == 0.0) {
            0f
        } else {
            (curr * db0).toFloat()
        }
    }

    private fun showVolumeNumber() {
        tvVolumeMin.text = FaderVol.fromFader(sbVolumeMin.progressDouble * db0)
        tvVolumeMax.text = FaderVol.fromFader(sbVolumeMax.progressDouble * db0)
        tvVolume.text = FaderVol.fromFader(getVolumeValue().toDouble())
    }

    private fun showError(msg: String) {
        if (!Looper.getMainLooper().isCurrentThread) {
            handler.post { showError(msg) }
            return
        }
        Toast.makeText(applicationContext, msg, Toast.LENGTH_SHORT).show()
    }

    class Message(
        val serverAddr: String,
        val serverPort: Int,
        val busAddr: String,
        val objectAddr: String,
        val args: ArrayList<Any>
    )

    private fun send(
        serverAddr: String? = etServerAddr.text.toString().trim().notEmpty(),
        serverPort: Int? = etServerPort.text.toString().trim().notEmpty()?.toIntOrNull(),
        busAddr: String = when {
            rbBusPlayback.isChecked -> "/1/busPlayback"
            rbBusOutput.isChecked -> "/1/busOutput"
            else -> "/1/busInput"
        },
        objectAddr: String? = etObjectAddress.text.toString().trim().notEmpty(),
        value: Float = getVolumeValue()
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

    private fun startWorker() = launch(activityJob + Dispatchers.IO) {
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
        tvClientAddr.text = try {
            val wm = getSystemService(Context.WIFI_SERVICE) as WifiManager
            InetAddress.getByAddress(
                BigInteger.valueOf(
                    wm.connectionInfo.ipAddress.toLong()
                )
                    .toByteArray()
                    .reversedArray()
            ).hostAddress
        } catch (ex: Throwable) {
            ex.printStackTrace()
            "?"
        }
    }

    private var objectMap = ConcurrentHashMap<String, String>()

    private val procShowMap: Runnable = Runnable {
        tvMap.text = StringBuilder().apply {
            objectMap.keys().toList().sorted().forEach {
                if (isNotEmpty()) append("\n")
                append("${it}=${objectMap[it]}")
            }
        }.toString()
        tvVolumeVal.text = (objectMap["${etObjectAddress.text}Val"] ?: "?").trim(',')
    }

    private fun fireShowMap() {
        handler.removeCallbacks(procShowMap)
        handler.postDelayed(procShowMap, 333L)
    }

    private fun OSCPacket.dump(dstMap: ConcurrentHashMap<String, String>) {
        when (this) {
            is OSCMessage -> {
                // Log.d("TMVolume", "Message addr=${address}")
                val sb = StringBuilder()
                arguments?.forEach {
                    sb.append(',')
                    sb.append(String.format("%s", it))
                }
                dstMap[address] = sb.toString()
            }
            is OSCBundle -> {
                // Log.d("TMVolume", "Bundle")
                packets?.forEach { it?.dump(dstMap) }
            }
        }
    }

    private var receiver: OSCPortIn? = null

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
        receiver = try {
            val port = etClientPort.text.toString().trim().toIntOrNull()
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
                            Log.d("TMVolume", "volumeVal $va $vb")
                        }
                        for (entry in map.entries) {
                            objectMap[entry.key] = entry.value
                        }
                        fireShowMap()
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

    private fun scan() = launch(activityJob + Dispatchers.IO) {
        var willBreak = false
        for (i in 0..10000) {
            if (willBreak) break
            runOnUiThread {
                if (isDestroyed) {
                    willBreak = true
                } else {
                    send(value = i.toFloat() / 10000f)
                }
            }
            delay(1000)
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
(num)はフェーダーの値を入れる。カーブがあるらしくそのままdbに変換することはできない

TotalMax のOptions-SettingsのOSCタブの Remote Controller Address と Port Outgoing に設定したホストとアドレスに



*/