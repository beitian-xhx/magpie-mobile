package cc.fplj.magpiemobile

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * P0-2 PoC：验证 APK 内嵌 ELF PIE 载荷能否在真机 exec。
 * 不做任何网络请求；仅运行 --version 并显示 stdout/stderr/exit。
 */
class MainActivity : Activity() {

    private lateinit var out: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val pad = (16 * resources.displayMetrics.density).toInt()
        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        val info = TextView(this).apply {
            val nl = "\n"
            text = "nativeLibraryDir: " + applicationInfo.nativeLibraryDir + nl +
                "filesDir: " + filesDir.absolutePath
            setTextIsSelectable(true)
        }
        val btnMagpie = Button(this).apply { text = "运行 magpie --version" }
        val btnBun = Button(this).apply { text = "运行 bun --version" }
        out = TextView(this).apply {
            setTextColor(Color.DKGRAY)
            setTextIsSelectable(true)
        }
        box.addView(info)
        box.addView(btnMagpie)
        box.addView(btnBun)
        box.addView(out)
        root.addView(box)
        setContentView(root)

        btnMagpie.setOnClickListener { runEngine("libmagpie_exec.so", listOf("--version")) }
        btnBun.setOnClickListener { runEngine("libbun_exec.so", listOf("--version")) }
    }

    private fun runEngine(libName: String, args: List<String>) {
        val exe = File(applicationInfo.nativeLibraryDir, libName)
        append("=== 运行 " + libName + " " + args.joinToString(" ") + " ===" + "\n" +
            "载荷存在: " + exe.exists() + " 可执行: " + exe.canExecute() + "\n")
        if (!exe.exists()) { append("结果: 载荷缺失\n"); return }

        Thread {
            var proc: Process? = null
            try {
                val cfg = File(filesDir, "config")
                val cache = File(cacheDir, "xdg-cache")
                cfg.mkdirs(); cache.mkdirs()
                val pb = ProcessBuilder(listOf(exe.absolutePath) + args)
                pb.environment()["HOME"] = filesDir.absolutePath
                pb.environment()["TMPDIR"] = cacheDir.absolutePath
                pb.environment()["XDG_CONFIG_HOME"] = cfg.absolutePath
                pb.environment()["XDG_CACHE_HOME"] = cache.absolutePath
                proc = pb.start()

                val outBuf = StringBuilder()
                val errBuf = StringBuilder()
                val t1 = Thread { proc.inputStream.bufferedReader().forEachLine { outBuf.append(it).append('\n') } }
                val t2 = Thread { proc.errorStream.bufferedReader().forEachLine { errBuf.append(it).append('\n') } }
                t1.start(); t2.start()
                val done = proc.waitFor(60, TimeUnit.SECONDS)
                if (!done) { proc.destroyForcibly(); append("结果: 超时(60s) 已强杀\n"); return@Thread }
                t1.join(2000); t2.join(2000)
                append("exit=" + proc.exitValue() + "\n")
                if (outBuf.isNotEmpty()) append("stdout:\n" + clip(outBuf.toString()) + "\n")
                if (errBuf.isNotEmpty()) append("stderr:\n" + clip(errBuf.toString()) + "\n")
            } catch (e: Throwable) {
                append("异常: " + e.javaClass.simpleName + ": " + e.message + "\n")
            } finally {
                runOnUiThread { append("(完成)\n\n") }
            }
        }.start()
    }

    private fun clip(s: String): String = if (s.length > 4000) s.substring(0, 4000) + "…(截断)" else s

    private fun append(s: String) {
        runOnUiThread { out.append(s) }
    }
}
