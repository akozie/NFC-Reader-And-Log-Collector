package com.isw.nfcreaderandlogcollector

import android.content.Context
import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.isw.nfcreaderandlogcollector.databinding.ActivityMainBinding
import com.isw.nfcreaderandlogcollector.databinding.WaitingDialogBinding
import com.isw.nfcreaderandlogcollector.utils.LogStorage
import org.json.JSONObject

class MainActivity : AppCompatActivity(), NfcAdapter.ReaderCallback {

    private lateinit var binding: ActivityMainBinding
    private lateinit var nfcAdapter: NfcAdapter
    private var verboseMode: Boolean = false
    private var pendingAmount: Long = 0L
    private lateinit var deviceNotSupportedAlertDialog: AlertDialog
    private var waitingDialog: AlertDialog? = null
    private var waitingDialogBinding: WaitingDialogBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)

        // Use modern SharedPreferences
        val prefs = getSharedPreferences("settings", Context.MODE_PRIVATE)
        verboseMode = prefs.getBoolean("verbose", false)
        prefs.registerOnSharedPreferenceChangeListener { _, key ->
            if (key == "verbose") verboseMode = prefs.getBoolean("verbose", false)
        }

        // Setup dialogs
        setupDeviceNotSupportedDialog()

        // Verbose switch listener
        binding.verboseSwitch.setOnCheckedChangeListener { _, isChecked -> verboseMode = isChecked }

        // Check NFC availability
        checkNfcStatus()

        // Button listeners
        binding.readCardBtn.setOnClickListener {
            val amountStr = binding.amountInput.text.toString().trim()
            if (amountStr.isEmpty()) {
                Toast.makeText(this, "Please enter an amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            pendingAmount = try {
                (amountStr.toDouble() * 100).toLong() // minor units
            } catch (e: Exception) {
                Toast.makeText(this, "Invalid amount", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.readCardBtn.isEnabled = false
            showReadingDialog()
            enableReaderMode()
        }


        binding.shareBtn.setOnClickListener { shareLogFile() }
    }

    // ---------------- NFC ----------------
    private fun enableReaderMode() {
        nfcAdapter.enableReaderMode(
            this,
            this, // still passes MainActivity as ReaderCallback
            NfcAdapter.FLAG_READER_NFC_A or NfcAdapter.FLAG_READER_NFC_B or NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK or NfcAdapter.FLAG_READER_NO_PLATFORM_SOUNDS,
            null
        )
    }


    override fun onPause() {
        super.onPause()
        nfcAdapter.disableReaderMode(this)
    }

    override fun onTagDiscovered(tag: Tag?) {
        tag ?: return

        runOnUiThread { showReadingDialog() }

        val isoDep = IsoDep.get(tag)
        try {
            isoDep.connect()
            val result =
                EmvReader.readCard(isoDep, verbose = verboseMode, userAmount = pendingAmount)


            runOnUiThread {
                dismissReadingDialog()
                binding.readCardBtn.isEnabled = true

                if (result.optString("aid").isNotEmpty()) {
                    binding.lastLog.text = result.toString(2).replace("\\/", "/")
                    showTransactionPreview(result)
                } else {
                    Toast.makeText(this, "No valid card data read", Toast.LENGTH_SHORT).show()
                }
            }

        } catch (e: Exception) {
            runOnUiThread {
                dismissReadingDialog()
                binding.readCardBtn.isEnabled = true
                Toast.makeText(this, "Error reading card", Toast.LENGTH_SHORT).show()
            }
        } finally {
            isoDep.close()
        }
    }


    // ---------------- Dialogs ----------------
    private fun setupDeviceNotSupportedDialog() {
        deviceNotSupportedAlertDialog =
            AlertDialog.Builder(this).setTitle(getString(R.string.nfc_message_title))
                .setMessage(getString(R.string.device_doesnt_have_nfc)).setCancelable(false)
                .setPositiveButton("Ok") { _, _ -> finish() }.create()
    }

    private fun checkNfcStatus() {
        if (nfcAdapter == null) {
            deviceNotSupportedAlertDialog.show()
        } else if (!nfcAdapter.isEnabled) {
            AlertDialog.Builder(this).setTitle(getString(R.string.nfc_message_title))
                .setMessage(getString(R.string.nfc_message)).setCancelable(false)
                .setPositiveButton(getString(R.string.settings)) { dialog, _ ->
                    dialog.dismiss()
                    startActivity(Intent(android.provider.Settings.ACTION_NFC_SETTINGS))
                }.show()
        }
    }

    private fun showReadingDialog() {
        if (waitingDialogBinding == null) {
            waitingDialogBinding = WaitingDialogBinding.inflate(layoutInflater)
            waitingDialogBinding?.contactlessHeader?.text =
                getString(R.string.nfc_read_card_message)
            waitingDialogBinding?.cancel?.setOnClickListener {
                binding.readCardBtn.isEnabled = true
                dismissReadingDialog()
            }

            waitingDialog =
                AlertDialog.Builder(this).setView(waitingDialogBinding!!.root).setCancelable(false)
                    .show()
        }
    }

    private fun dismissReadingDialog() {
        waitingDialog?.dismiss()
        waitingDialog = null
        waitingDialogBinding = null
    }

    // ---------------- Transaction Preview ----------------
    private fun showTransactionPreview(transactionJson: JSONObject) {
        val message = buildString {
            append("AID: ${transactionJson.optString("aid")}\n")
            append("Label: ${transactionJson.optString("appLabel")}\n")
            append("PAN: ${transactionJson.optString("panMasked")}\n")
            append("Amount: ${transactionJson.optString("amount")}\n")
            append("Currency: ${transactionJson.optString("currency")}\n")
            append("Expiry: ${transactionJson.optString("expiry")}\n")
            if (verboseMode) {
                transactionJson.keys().forEach { key ->
                    if (key !in listOf(
                            "aid", "appLabel", "panMasked", "amount", "currency", "expiry"
                        )
                    ) {
                        append("$key: ${transactionJson.get(key)}\n")
                    }
                }
            }
        }

        AlertDialog.Builder(this).setTitle("Transaction Preview").setMessage(message)
            .setPositiveButton("Save") { _, _ ->
                LogStorage.saveTransaction(this, transactionJson)
                Toast.makeText(this, "Transaction saved", Toast.LENGTH_SHORT).show()
            }.setNegativeButton("Discard", null).show()
    }

    // ---------------- Share ----------------
    private fun shareLogFile() {
        val file = LogStorage.getLogFile(this)
        if (!file.exists()) {
            Toast.makeText(this, "No logs to share", Toast.LENGTH_SHORT).show()
            return
        }

        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share EMV Log"))
    }
}
