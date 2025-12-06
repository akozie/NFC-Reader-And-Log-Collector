package com.isw.nfcreaderandlogcollector.utils

import android.content.Context
import org.json.JSONObject
import java.io.File

object LogStorage {
    private const val FILE_NAME = "emv_logs.txt"

    fun saveTransaction(context: Context, json: JSONObject) {
        val file = File(context.getExternalFilesDir(null), FILE_NAME)
        file.appendText(json.toString() + "\n\n")
    }

    fun getLogFile(context: Context): File = File(context.getExternalFilesDir(null), FILE_NAME)
}
