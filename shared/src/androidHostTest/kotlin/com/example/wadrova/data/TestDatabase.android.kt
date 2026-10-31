package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import java.util.Properties

private fun file(name: String) = File(System.getProperty("java.io.tmpdir"), "$name.db")

actual fun openTestDatabase(name: String): SqlDriver =
    JdbcSqliteDriver("jdbc:sqlite:${file(name).path}", Properties(), WadrovaDatabase.Schema)

actual fun deleteTestDatabase(name: String) {
    file(name).delete()
}
