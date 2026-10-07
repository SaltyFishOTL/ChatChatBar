---
name: chatbar-character-card-transfer
description: Maintain character PNG export, independent export cover, package transfer and preset dependency restoration.
---

- ManageScreen.CharacterPngExportDialog selects an export-only local image via OpenDocument, previews/crops it through CharacterCardPngExportOptions.coverImagePath, and resets selection for each export. The persisted card background and embedded card package remain original.
- CharacterCardPngRenderer bounds-decodes source images before downsampling; an explicitly chosen unreadable image fails visibly. CharacterCardTransferService retains packaged character assets and optional defaultImageGenerationSettings.
- PresetCatalogService.characterPackage delegates to PresetCharacterDependencies: combine embedded world books with manifest worldBookPresetKeys; embedded defaultFormatCard wins, otherwise resolve optional defaultFormatPresetKey. Missing declared manifest dependencies fail before import. Never invent a default format for an unbound preset.
- CharacterCardTransferService imports dependency resources and binds resulting IDs. WorldBookReusePolicy reuses preset identity; FormatCardTransferService.importCharacterDefault reuses matching sourcePresetKey without replacing local edits, otherwise reuses equal content or imports a separate card.
- Synthetic tests: PresetCharacterDependenciesTest, FormatCardTransferServiceTest, CharacterCardPngRendererInstrumentedTest.
