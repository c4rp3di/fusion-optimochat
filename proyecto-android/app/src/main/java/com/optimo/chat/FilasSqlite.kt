package com.optimo.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * [AlmacenFilas] sobre SQLite de Android. Un archivo por contacto (`chat_<id>.db`). Sin compilar: hay que probarlo en
 * Fusion. La logica de chat y el cifrado estan en [AlmacenCifrado], que si se prueba en la JVM.
 */
class FilasSqlite(contexto: Context, nombre: String) :
    SQLiteOpenHelper(contexto, nombre, null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE mensajes (orden INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE, " +
                "propio INTEGER NOT NULL, seq INTEGER NOT NULL, estado INTEGER NOT NULL, blob BLOB NOT NULL)"
        )
        db.execSQL("CREATE INDEX mensajes_pend ON mensajes (propio, estado, seq)")
        db.execSQL("CREATE INDEX mensajes_rec ON mensajes (propio, seq)")
        db.execSQL("CREATE TABLE meta (clave TEXT PRIMARY KEY, valor INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, antes: Int, ahora: Int) {}

    private fun leer(c: android.database.Cursor) =
        FilaCruda(c.getString(0), c.getInt(1) == 1, c.getLong(2), c.getInt(3), c.getBlob(4))

    private val COLS = "id, propio, seq, estado, blob"

    @Synchronized override fun insertar(f: FilaCruda): Boolean {
        val v = ContentValues().apply {
            put("id", f.id); put("propio", if (f.propio) 1 else 0); put("seq", f.seq)
            put("estado", f.estado); put("blob", f.blob)
        }
        return writableDatabase.insertWithOnConflict("mensajes", null, v, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    @Synchronized override fun obtener(id: String): FilaCruda? =
        readableDatabase.rawQuery("SELECT $COLS FROM mensajes WHERE id = ?", arrayOf(id)).use { if (it.moveToFirst()) leer(it) else null }

    @Synchronized override fun primeraPendiente(despuesDeSeq: Long): FilaCruda? =
        readableDatabase.rawQuery(
            "SELECT $COLS FROM mensajes WHERE propio = 1 AND estado = 0 AND seq > ? ORDER BY seq LIMIT 1",
            arrayOf(despuesDeSeq.toString())
        ).use { if (it.moveToFirst()) leer(it) else null }

    @Synchronized override fun cambiarEstado(id: String, desde: List<Int>, a: Int): Boolean {
        val marcas = desde.joinToString(",") { "?" }
        val args = arrayOf<Any>(a, id) + desde.map { it as Any }
        val st = writableDatabase.compileStatement("UPDATE mensajes SET estado = ? WHERE id = ? AND propio = 1 AND estado IN ($marcas)")
        args.forEachIndexed { i, v -> if (v is Int) st.bindLong(i + 1, v.toLong()) else st.bindString(i + 1, v as String) }
        return st.use { it.executeUpdateDelete() } > 0
    }

    @Synchronized override fun cambiarEstadoHasta(seq: Long, desde: List<Int>, a: Int): List<String> {
        val marcas = desde.joinToString(",") { "?" }
        val db = writableDatabase
        val ids = ArrayList<String>()
        db.beginTransaction()
        try {
            db.rawQuery(
                "SELECT id FROM mensajes WHERE propio = 1 AND seq <= ? AND estado IN ($marcas) ORDER BY seq",
                arrayOf(seq.toString()) + desde.map { it.toString() }
            ).use { while (it.moveToNext()) ids.add(it.getString(0)) }
            db.execSQL(
                "UPDATE mensajes SET estado = ? WHERE propio = 1 AND seq <= ? AND estado IN ($marcas)",
                arrayOf(a.toString(), seq.toString()) + desde.map { it.toString() }
            )
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return ids
    }

    @Synchronized override fun reiniciarEnviados() {
        writableDatabase.execSQL("UPDATE mensajes SET estado = 0 WHERE propio = 1 AND estado = 1")
    }

    @Synchronized override fun existeRecibidoSeq(seq: Long): Boolean =
        readableDatabase.rawQuery("SELECT 1 FROM mensajes WHERE propio = 0 AND seq = ? LIMIT 1", arrayOf(seq.toString()))
            .use { it.moveToFirst() }

    @Synchronized override fun ultimas(limite: Int): List<FilaCruda> {
        val r = ArrayList<FilaCruda>()
        readableDatabase.rawQuery("SELECT $COLS FROM mensajes ORDER BY orden DESC LIMIT ?", arrayOf(limite.toString()))
            .use { while (it.moveToNext()) r.add(leer(it)) }
        r.reverse()
        return r
    }

    @Synchronized override fun meta(clave: String): Long? =
        readableDatabase.rawQuery("SELECT valor FROM meta WHERE clave = ?", arrayOf(clave))
            .use { if (it.moveToFirst()) it.getLong(0) else null }

    @Synchronized override fun ponerMeta(clave: String, valor: Long) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO meta (clave, valor) VALUES (?, ?)", arrayOf(clave, valor))
    }
}
