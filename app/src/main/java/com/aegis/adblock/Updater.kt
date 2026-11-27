package com.aegis.adblock

import android.content.Context
import androidx.work.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/** Repo GitHub tempat pipeline menyimpan blocklist.txt & cosmetic.txt. */
const val BASE_URL = "https://raw.githubusercontent.com/lagirame2025/AEGIS-ADBLOCK-ANDROID-5-6/main/"
/** Cadangan bila raw.githubusercontent.com tidak bisa dijangkau (DNS/TLS/ISP). */
val MIRRORS = listOf(
    BASE_URL,
    "https://cdn.jsdelivr.net/gh/lagirame2025/AEGIS-ADBLOCK-PREMIUM@main/",
    "https://github.com/lagirame2025/AEGIS-ADBLOCK-PREMIUM/raw/main/"
)

class Updater(c: Context, p: WorkerParameters) : Worker(c, p) {
    override fun doWork(): Result = if (fetch(applicationContext)) Result.success() else Result.retry()

    companion object {
        private const val PREFS = "aegis_update"
        private enum class R { FAIL, UPDATED, UNCHANGED }
        private val DOMAINISH = Regex("[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z0-9-]{2,}")

        /** Cuplikan awal daftar harus tampak seperti daftar domain (cegah menimpa dengan halaman error/HTML). */
        private fun looksLikeDomainList(f: File): Boolean {
            val sample = f.useLines { it.map { l -> l.trim() }.filter { l -> l.isNotEmpty() && !l.startsWith("#") }.take(200).toList() }
            return sample.isNotEmpty() && sample.count { DOMAINISH.matches(it) } >= sample.size * 0.9
        }

        /**
         * Unduh aman: GET bersyarat (ETag) agar hemat kuota (304 bila tidak berubah), simpan ke berkas sementara,
         * lalu validasi (ukuran minimum, bentuk isi, dan tidak boleh menyusut drastis dari daftar lama) sebelum
         * menimpa. Daftar yang terpotong/rusak/diracuni tidak akan menggantikan daftar yang sedang dipakai.
         */
        private fun download(ctx: Context, base: String, bi: Int, name: String, minSize: Long, isDomainList: Boolean): R {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val dest = File(ctx.filesDir, name); val tmp = File(ctx.filesDir, "$name.tmp")
            var con: HttpURLConnection? = null
            return try {
                con = URL(base + name).openConnection() as HttpURLConnection
                con.connectTimeout = 15000; con.readTimeout = 30000
                val tag = sp.getString("etag_${bi}_$name", null)
                if (tag != null && dest.exists()) con.setRequestProperty("If-None-Match", tag)
                when (con.responseCode) {
                    304 -> R.UNCHANGED
                    200 -> {
                        con.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it) } }
                        val old = if (dest.exists()) dest.length() else 0L
                        val bad = tmp.length() < minSize ||
                            (old > 0 && tmp.length() < old * 0.5) ||        // menyusut >50%: curigai
                            (isDomainList && !looksLikeDomainList(tmp))
                        if (bad) { err(ctx, "$name: isi tidak valid (${tmp.length()} B)"); tmp.delete(); R.FAIL }
                        else if (tmp.renameTo(dest) || (dest.delete() && tmp.renameTo(dest))) {
                            con.getHeaderField("ETag")?.let { sp.edit().putString("etag_${bi}_$name", it).apply() }
                            R.UPDATED
                        } else { tmp.delete(); R.FAIL }
                    }
                    else -> { err(ctx, "$name: HTTP ${con.responseCode}"); R.FAIL }
                }
            } catch (e: Exception) { tmp.delete(); err(ctx, "$name: ${e.javaClass.simpleName}: ${e.message}"); R.FAIL } finally { con?.disconnect() }
        }

        /** Berhasil bila tidak ada kegagalan keras: "tidak berubah" (304) juga sukses, bukan "Gagal memperbarui". */
        fun fetch(ctx: Context): Boolean {
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("last_err").apply()
            var a = R.FAIL; var b = R.FAIL
            for ((i, base) in MIRRORS.withIndex()) {
                if (a == R.FAIL) a = download(ctx, base, i, "blocklist.txt", 1000, true)
                if (b == R.FAIL) b = download(ctx, base, i, "cosmetic.txt", 100, false)
                if (a != R.FAIL && b != R.FAIL) break
            }
            if (a == R.UPDATED) { Blocklist.load(ctx); DnsCache.clear() }
            if (b == R.UPDATED) Cosmetic.load(ctx)
            val ok = a != R.FAIL || b != R.FAIL
            if (ok) ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("last_ok", System.currentTimeMillis()).apply()
            return ok
        }

        private fun err(ctx: Context, m: String) {
            val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            sp.edit().putString("last_err", ((sp.getString("last_err", "") ?: "") + m + "\n").takeLast(600)).apply()
        }
        /** Alasan gagal terakhir (untuk ditampilkan ke pengguna). */
        fun lastError(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("last_err", "") ?: ""

        fun lastOk(ctx: Context): Long = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong("last_ok", 0L)

        fun schedule(ctx: Context) {
            val req = PeriodicWorkRequestBuilder<Updater>(12, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("blocklist", ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
