package net.glotch.antsy

import android.app.Application
import android.content.Context
import android.content.DialogInterface
import android.os.Bundle
import android.os.Handler
import android.text.Editable
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.*
import android.widget.TextView
import android.widget.TextView.OnEditorActionListener
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.navigation.ui.AppBarConfiguration
import net.glotch.antsy.databinding.ActivityMainBinding
import org.apache.commons.net.telnet.*
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.SocketException
import java.nio.charset.Charset
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity(), TelnetNotificationHandler {

    companion object {
        val client = TelnetClient("VT100")
        lateinit var inStream: InputStream
        lateinit var outStream: OutputStream
        var dumpStream: OutputStream? = null
    }

    private lateinit var binding: ActivityMainBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.disconnectButton.setOnClickListener { client.disconnect() }
        binding.escButton.setOnClickListener { outStream.write(0x1b)
            Thread {
                outStream.flush()
            }.start()
        }
        binding.upButton.setOnClickListener { outStream.write((Char(0x1b) + "[A").toByteArray())
            Thread {
                outStream.flush()
            }.start()
        }
        binding.downButton.setOnClickListener { outStream.write((Char(0x1b) + "[B").toByteArray())
            Thread {
                outStream.flush()
            }.start()
        }
        binding.leftButton.setOnClickListener { outStream.write((Char(0x1b) + "[D").toByteArray())
            Thread {
                outStream.flush()
            }.start()
        }
        binding.rightButton.setOnClickListener { outStream.write((Char(0x1b) + "[C").toByteArray())
            Thread {
                outStream.flush()
            }.start()
        }

        binding.dumpButton.setOnClickListener {
            if(dumpStream == null) {
                dumpStream = applicationContext.openFileOutput("terminaldump.bin", Context.MODE_PRIVATE)
                binding.dumpButton.text = "Stop Dump"

            } else {
                dumpStream?.close()
                dumpStream = null
                binding.dumpButton.text = "Start Dump"
            }
        }

        class CB : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                client.disconnect()
            }
        }

        onBackPressedDispatcher.addCallback(CB())

        WindowCompat.setDecorFitsSystemWindows(window, false)

        val connectingDialog = AlertDialog.Builder(this)
            .setTitle("Connecting...")
            .setNegativeButton("Cancel") { d, _ -> d.cancel() }
            .setOnCancelListener {
                client.disconnect()
                finish()
            }.create()

        runOnUiThread {
                connectingDialog.show()
        }

        Thread {
            while(connectingDialog.isShowing) {
                if(client.isConnected) {
                    connectingDialog.dismiss()
                    break
                }
            }
        }.start()


        Thread {
                Log.i("antsy", "attempting to connect")
            try {
                if(!client.isConnected) {
                    val extras = intent.extras
                    assert(extras != null)
                    if (extras != null) {
                        client.connect(extras.getString("addr"), extras.getInt("port"))
                    }
                    Log.i("antsy","connected?!?!?")
                    outStream = client.outputStream
                    inStream = client.inputStream
                    Thread(Outputter(binding.terminalOut)).start()
                }

            } catch (e: IOException) {
                val failedDialog = AlertDialog.Builder(this)
                    .setTitle("Could not connect")
                    .setMessage(e.localizedMessage)
                    .setPositiveButton("OK") { _, _ -> finish() }
                    .setOnDismissListener { finish() }
                if(connectingDialog.isShowing) {
                    connectingDialog.dismiss()
                    runOnUiThread {
                            failedDialog.show()
                    }

                    Log.e("antsy", "could not connect: " + e.message)
                }
            }
        }.start()
    }

    fun disconnectDialog() {
        AlertDialog.Builder(this)
            .setTitle("Disconnected")
            .setPositiveButton("OK") { _, _ -> finish() }
            .setOnDismissListener { finish() }
            .show()
    }

    inner class Outputter(terminalView: TerminalView) : Runnable {
        var buf = ByteArray(4096)
        val termview = terminalView
        override fun run() {
            while(true) {
                if(!client.isConnected) {
                    this@MainActivity.runOnUiThread { run { this@MainActivity.disconnectDialog() } }
                    return
                }
                try {
                    val read = inStream.read(buf)
                    if(read > 0) {
                        termview.lineIn(buf, read)
                        //Log.v("antsy", String(buf))
                    } else if(read < 0) {
                        client.disconnect()
                    }
                } catch (e: IOException) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("Could not read from remote host")
                        .setMessage(e.localizedMessage)
                        .setPositiveButton("OK") { _, _ -> finish() }
                        .setOnDismissListener { finish() }
                        .show()
                }


                buf = ByteArray(4096)
            }

        }

    }

    override fun receivedNegotiation(negotiation_code: Int, option_code: Int) {
        TODO("Not yet implemented")
    }
}