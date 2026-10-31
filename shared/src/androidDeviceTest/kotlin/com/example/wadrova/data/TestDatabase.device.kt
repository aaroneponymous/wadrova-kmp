package com.example.wadrova.data

import androidx.test.platform.app.InstrumentationRegistry
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver

private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

actual fun openTestDatabase(name: String, schema: TestSchema): SqlDriver =
    AndroidSqliteDriver(schema, context, "$name.db")

actual fun deleteTestDatabase(name: String) {
    context.deleteDatabase("$name.db")
}
