package com.example.wadrova.data

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema

typealias TestSchema = SqlSchema<QueryResult.Value<Unit>>

/** Opens an on-disk database, so tests can close it and reopen the same file. */
expect fun openTestDatabase(name: String, schema: TestSchema = WadrovaDatabase.Schema): SqlDriver

expect fun deleteTestDatabase(name: String)
