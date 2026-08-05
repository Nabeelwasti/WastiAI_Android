# ADR 0003: Production Room Database Migration Strategy

## Status
Proposed (Targeted for Sprint 3/4)

## Context
Currently, `WastiDatabase.kt` uses `.fallbackToDestructiveMigration(dropAllTables = true)` during database schema updates. While acceptable during initial development and prototyping, destructive migration drops all local SQLite tables when the database version increments, resulting in total loss of local user data (conversations, long-term memory items, project settings, and logs).

## Decision
For production readiness:
1. Transition from destructive fallback to explicit `Migration(startVersion, endVersion)` classes.
2. Implement incremental migrations: `Migration(1, 2)`, `Migration(2, 3)`, `Migration(3, 4)`.
3. Retain `.fallbackToDestructiveMigration()` only as an explicit emergency fallback in Debug builds (`BuildConfig.DEBUG`).

## Migration Design Plan
```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE SettingEntity ADD COLUMN encrypted INTEGER NOT NULL DEFAULT 1")
    }
}
```

## Consequences
- **Positive**: Complete data preservation across application updates.
- **Positive**: Enterprise-grade persistence reliability for user conversation history and memory stores.
- **Trade-off**: Requires database migration unit testing (`MigrationTestHelper`) whenever schema changes occur.
