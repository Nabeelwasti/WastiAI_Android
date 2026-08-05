# ADR 0002: Material 3 Vector Icon Modernization & Component Deprecation Fixes

## Status
Accepted

## Context
During Android Gradle compilation (`gradle assembleDebug`), multiple deprecation warnings were emitted by the Kotlin compiler and Jetpack Compose runtime:
1. `Icons.Filled.Chat`, `Icons.Filled.VolumeUp`, `Icons.Filled.Send`, `Icons.Filled.ArrowForward`, `Icons.Filled.InsertDriveFile`, `Icons.Filled.MenuBook`, `Icons.Filled.NoteAdd`, `Icons.Filled.OpenInNew`, `Icons.Filled.ManageSearch`, and `Icons.Filled.LibraryBooks` were deprecated in favor of their `Icons.AutoMirrored` counterparts to support Right-to-Left (RTL) locales properly.
2. `Divider(...)` was deprecated in Material 3 Compose in favor of `HorizontalDivider(...)` and `VerticalDivider(...)`.
3. `fallbackToDestructiveMigration()` in `RoomDatabase.Builder` required explicit parameter specification (`dropAllTables = true`) under modern Room APIs.
4. `@Json` parameter targets in `GeminiModels.kt` required explicit `@field:Json` target annotations under Kotlin compiler rules.

## Decision
1. Update all directional and chat-related Material vector icons to `Icons.AutoMirrored.Filled.*`.
2. Replace all instances of `Divider` with `HorizontalDivider`.
3. Update `RoomDatabase.Builder.fallbackToDestructiveMigration(dropAllTables = true)`.
4. Update Moshi `@Json` annotations to `@field:Json` in data models.

## Consequences
- **Positive**: Eliminates all build deprecation warnings.
- **Positive**: Enhances internationalization and RTL layout support for languages such as Urdu, Arabic, and Hebrew.
