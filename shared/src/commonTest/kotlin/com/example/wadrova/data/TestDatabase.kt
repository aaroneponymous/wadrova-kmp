package com.example.wadrova.data

import app.cash.sqldelight.db.SqlDriver

/** Opens an on-disk database, so tests can close it and reopen the same file. */
expect fun openTestDatabase(name: String): SqlDriver

expect fun deleteTestDatabase(name: String)
