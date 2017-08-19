package nl.mpcjanssen.simpletask.remote

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.os.Build
import nl.mpcjanssen.simpletask.R

import nl.mpcjanssen.simpletask.STWApplication
import nl.mpcjanssen.simpletask.TodoException
import nl.mpcjanssen.simpletask.util.Config
import java.io.*
import java.util.regex.Pattern

import android.net.LocalSocket
import android.support.v4.app.ActivityCompat
import android.support.v4.content.ContextCompat
import com.taskwc2.controller.sync.SSLHelper
import nl.mpcjanssen.simpletask.task.Task
import nl.mpcjanssen.simpletask.util.createParentDirectory
import org.jetbrains.anko.*
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*
import javax.net.ssl.SSLSocket

import javax.net.ssl.SSLSocketFactory
import kotlin.collections.ArrayList


interface StreamConsumer {
    fun eat(line: String?)
}




object TaskWarrior : AnkoLogger {
    private val errConsumer = object : StreamConsumer {
        val log = AnkoLogger("TaskWarrior")
        override fun eat(line: String?) {
            line?.let {log.error(it)}
        }

    }

    private val outConsumer = object : StreamConsumer {
        val log = AnkoLogger("TaskWarrior")
        override fun eat(line: String?) {
            line?.let {log.debug(it)}
        }

    }



    val app = STWApplication.app
    private enum class Arch {
        Arm7, X86
    };
    val executable = eabiExecutable()
    val config = HashMap<String,String>()

    var configLinePattern = Pattern.compile("^([A-Za-z0-9\\._]+)\\s+(\\S.*)$")


    fun callTaskForSelection(selection: List<Task>, vararg arguments: String) {
        val args = ArrayList<String>()
        if (selection.isEmpty()) {
            error("Trying to callTask for all tasks while selection was expected. Aborting..")
            return
        }
        args.addAll(selection.map { it.uuid })
        args.addAll(arguments)
        callTask(*args.toTypedArray())
    }

    fun createDefaultRc(): File {
        val defaultRcFile =  File(STWApplication.app.filesDir , "taskrc.android")
        if (!defaultRcFile.exists()) {
            // Switch to default taskrc location
            createParentDirectory(defaultRcFile)
            defaultRcFile.writeText("data.location=data\n")
        }
        Config.rcFileName = defaultRcFile.canonicalPath
        return defaultRcFile
    }

    private fun eabiExecutable(): String? {
        var arch = Arch.Arm7
        val eabi = Build.CPU_ABI
        if (eabi == "x86" || eabi == "x86_64") {
            arch = Arch.X86
        }
        var rawID = -1
        when (arch) {
            Arch.Arm7 -> rawID = if (Build.VERSION.SDK_INT >= 16) R.raw.task_arm7_16 else R.raw.task_arm7
            Arch.X86 -> rawID = if (Build.VERSION.SDK_INT >= 16) R.raw.task_x86_16 else R.raw.task_x86
        }
        try {
            val file = File(STWApplication.app.getFilesDir(), "task")
            if (!file.exists()) {
                val rawStream = STWApplication.app.getResources().openRawResource(rawID)
                val outputStream = FileOutputStream(file)
                rawStream.copyTo(outputStream, 8912)
                outputStream.close()
                rawStream.close()
            }
            file.setExecutable(true, true)
            return file.getAbsolutePath()
        } catch (e: IOException) {
            error("Error preparing file", e)
        }
        return null
    }

    fun taskList(): List<Task> {
        val result = ArrayList<String>()
        val params = ArrayList<String>()
        params.add("rc.json.array=off")
        params.add("rc.verbose=nothing")
        params.add("export")
        callTask(object : StreamConsumer {
            override fun eat(line: String?) {
                line?.let{result.add(it)}
            }
        }, object : StreamConsumer {
            override fun eat(line: String?) {
                line?.let{result.add(it)}
            }}, *params.toTypedArray())
        info("List size=${result.size}")
        return result.map(Task.Companion::fromJSON)
    }

    fun callTask(vararg arguments: String) {
        callTask(outConsumer, errConsumer, *arguments)
    }

    private fun callTask(out: StreamConsumer, err: StreamConsumer, vararg arguments: String): Int {
        val stderrOutput = ArrayList<String>()
        if (arguments.isEmpty()) {
            error("Error in binary call: no arguments provided")
            return 255
        }

        try {
            val exec = executable
            if (null == exec) {
                error("Error in binary call: executable not found")
                throw TodoException("Invalid executable")
            }
            val currentRc = Config.rcFile

            val taskRcFolder = currentRc.parentFile
            if (!taskRcFolder.exists()) {
                error("Error in binary call: invalid .taskrc folder: ${taskRcFolder.absolutePath}" )
                throw TodoException("Invalid folder")
            }
            var syncSocket : LocalServerSocket? = null
            val args = ArrayList<String>()
            args.add(exec)
            args.add("rc.color=off")
            args.add("rc.confirmation=off")
            args.add("rc.bulk=0")
            if (arguments[0]=="sync") {
                reloadConfig()
                // Should setup TLS socket here
                val socketName = UUID.randomUUID().toString().toLowerCase();
                syncSocket = openLocalSocket(socketName);
                args.add("rc.taskd.socket=" + socketName);
            }

            args.addAll(arguments)

            val pb = ProcessBuilder(args)
            pb.directory(taskRcFolder)
            info("Calling now: task ${args.slice(1 until args.size)}")
            info("TASKRC: ${currentRc.absolutePath}")
            pb.environment().put("TASKRC", currentRc.absolutePath)
            val p = pb.start()

            val outThread = readStream(p.getInputStream(), out, null )
            val errThread = readStream(p.getErrorStream(), err, stderrOutput)
            val exitCode = p.waitFor()
            debug("Exit code:  $exitCode")
            //            debug("Execute result:", exitCode);
            if (null != outThread) outThread.join()
            if (null != errThread) errThread.join()
            if (syncSocket!=null) {
                syncSocket.close()
            }
            if (arguments[0]=="sync") {
                app.longToast(stderrOutput.last())
            }
            return exitCode
        } catch (e: Exception) {
            error("Failed to execute task", e)
            err.eat(e.message)
            return 255
        }
    }

    fun getWritePermission(act: Activity, activityResult: Int): Boolean {

        val permissionCheck = ContextCompat.checkSelfPermission(act,
                Manifest.permission.WRITE_EXTERNAL_STORAGE)

        if (permissionCheck == PackageManager.PERMISSION_DENIED) {
            ActivityCompat.requestPermissions(act,
                    arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), activityResult)
        }
        return permissionCheck == PackageManager.PERMISSION_GRANTED
    }

    fun reloadConfig()  {
        debug("Loading config")
        callTask( object: StreamConsumer {
            override fun eat(line: String?) {
                line?.let {
                    val match = configLinePattern.matcher(line)
                    if (match.matches()) {
                        val configKey = match.group(1)
                        val value = match.group(2)
                        config[configKey] = value
                    }
                }
            }
        }, errConsumer, "show")
        debug("Loading config done")
    }

    private fun readStream(stream: InputStream,  consumer: StreamConsumer, output: MutableList<String>?): Thread? {

        val thread = object : Thread() {
            override fun run() {
                stream.bufferedReader().forEachLine {
                    consumer.eat(it)
                    output?.add(it)
                }
            }
        }
        thread.start()
        return thread
    }


    private fun openLocalSocket(name: String): LocalServerSocket? {
        try {
            if (!config.containsKey("taskd.server")) {
                // Not configured
                app.longToast("Sync disabled: no taskd.server value")
                debug("taskd.server is empty: sync disabled")
                return null
            }
            val runner: LocalSocketRunner
            try {
                runner = LocalSocketRunner(name, config)
            } catch (e: Exception) {
                error("Sync disabled: certificate load failure",  e)
                app.longToast("Sync disabled: certificate load failure")
                return null
            }

            val acceptThread = object : Thread() {
                override fun run() {
                    while (true) {
                        try {
                            runner.accept()
                        } catch (e: IOException) {
                            error("Socket accept failed",  e)
                            return
                        }

                    }
                }
            }
            acceptThread.start()
            return runner.socket // Close me later on stop
        } catch (e: Exception) {
            error("Failed to open local socket", e)
        }

        return null
    }


}

private class LocalSocketRunner(name: String, config: Map<String, String>) : AnkoLogger {

    private var port: Int = 0
    private var host: String = ""
    private var factory: SSLSocketFactory? = null
    var socket: LocalServerSocket? = null
    val TAG = "SocketRunner"
    init {
        val trustType = SSLHelper.parseTrustType(config["taskd.trust"])
        val _host = config["taskd.server"]
        if (_host != null) {


            val lastColon = _host.lastIndexOf(":") ?: 0
            this.port = Integer.parseInt(_host.substring(lastColon + 1))
            this.host = _host.substring(0, lastColon)

            this.factory = SSLHelper.tlsSocket(
                    FileInputStream(fileFromConfig(config["taskd.ca"])),
                    FileInputStream(fileFromConfig(config["taskd.certificate"])),
                    FileInputStream(fileFromConfig(config["taskd.key"])), trustType)
            error("Credentials loaded")
            this.socket = LocalServerSocket(name)
        } else {
            this.socket = null
        }
    }

    fun fileFromConfig(path: String?): File? {
        if (path == null) { // Invalid path
            return null
        }
        if (path.startsWith("/")) { // Absolute
            return File(path)
        }
        // Relative
        return File(Config.rcFile.parent, path)
    }

    @Throws(IOException::class)
    fun accept() {
        socket?.let {
            val conn = it.accept()
            debug("New incoming connection")
            LocalSocketThread(conn).start()
        }
    }

    private inner class LocalSocketThread (private val socket: LocalSocket) : Thread() {

        @Throws(IOException::class)
        private fun recvSend(from: InputStream, to: OutputStream): Long {
            val head = ByteArray(4) // Read it first
            from.read(head)
            to.write(head)
            to.flush()
            val size = ByteBuffer.wrap(head, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt()
            var bytes: Long = 4
            val buffer = ByteArray(1024)
            debug("Will transfer: " + size)
            while (bytes < size) {
                val recv = from.read(buffer)
                //                logger.d("Actually get:", recv);
                if (recv == -1) {
                    return bytes
                }
                to.write(buffer, 0, recv)
                to.flush()
                bytes += recv.toLong()
            }
            debug("Transfer done " + size)
            return bytes
        }

        override fun run() {
            var remoteSocket: SSLSocket? = null
            debug("Communication taskw<->android started")
            try {
                remoteSocket = factory?.createSocket(host, port) as SSLSocket
                val finalRemoteSocket = remoteSocket
                Compat.levelAware(16, Runnable { finalRemoteSocket!!.setEnabledProtocols(arrayOf("TLSv1", "TLSv1.1", "TLSv1.2")) }, Runnable { finalRemoteSocket!!.setEnabledProtocols(arrayOf("TLSv1")) })
                debug("Ready to establish TLS connection to:"+  host + port)
                val localInput = socket.inputStream
                val localOutput = socket.outputStream
                val remoteInput = remoteSocket!!.getInputStream()
                val remoteOutput = remoteSocket!!.getOutputStream()
                debug("Connected to taskd server" + remoteSocket!!.getSession().getCipherSuite())
                val bread = recvSend(localInput, remoteOutput)
                val bwrite = recvSend(remoteInput, localOutput)

            } catch (e: Exception) {
                debug("Transfer failure",e )
            } finally {
                if (null != remoteSocket) {
                    try {
                        remoteSocket!!.close()
                    } catch (e: IOException) {
                    }

                }
                try {
                    socket.close()
                } catch (e: IOException) {
                }

            }
        }
    }
}


class Compat {

    interface Producer<T> {
        fun produce(): T
    }

    companion object {

        @JvmOverloads fun levelAware(level: Int, after: Runnable?, before: Runnable? = null) {
            if (Build.VERSION.SDK_INT >= level) {
                after?.run()
            } else {
                before?.run()
            }
        }

        fun <T> produceLevelAware(level: Int, after: Producer<T>?, before: Producer<T>?): T? {
            var result: T? = null
            if (Build.VERSION.SDK_INT >= level) {
                if (null != after) result = after.produce()
            } else {
                if (null != before) result = before.produce()
            }
            return result
        }
    }
}



