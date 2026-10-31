package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import co.touchlab.sqliter.DatabaseFileContext

actual fun openTestDatabase(name: String, schema: TestSchema): SqlDriver = NativeSqliteDriver(schema, "$name.db")

actual fun deleteTestDatabase(name: String) {
    DatabaseFileContext.deleteDatabase("$name.db")
}
