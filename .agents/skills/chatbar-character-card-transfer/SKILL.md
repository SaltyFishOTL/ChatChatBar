---
name: chatbar-character-card-transfer
description: Maintain character PNG export, independent export cover, package transfer and preset dependency restoration.
---

- ManageScreen.CharacterPngExportDialog selects an export-only local image via OpenDocument, previews/crops it through CharacterCardPngExportOptions.coverImagePath, and resets selection for each export. The persisted card background and embedded card package remain original.
- CharacterCardPngRenderer bounds-decodes source images before downsampling; an explicitly chosen unreadable image fails visibly. CharacterCardTransferService retains packaged character assets and optional defaultImageGenerationSettings.
- PresetCatalogService.characterPackage delegates to PresetCharacterDependencies: combine embedded world books with manifest worldBookPresetKeys; embedded defaultFormatCard wins, otherwise resolve optional defaultFormatPresetKey. Missing declared manifest dependencies fail before import. Never invent a default format for an unbound preset.
- CharacterCardTransferService imports dependency resources and binds resulting IDs. WorldBookReusePolicy reuses preset identity; FormatCardTransferService.importCharacterDefault reuses matching sourcePresetKey without replacing local edits, otherwise reuses equal content or imports a separate card.
- Synthetic tests: PresetCharacterDependenciesTest, FormatCardTransferServiceTest, CharacterCardPngRendererInstrumentedTest.

- Multi-background gallery: CharacterCard.chatBackgroundImages and PackagedCharacterCard.chatBackgroundImageResourceIds retain original image assets in package v10. chatBackground remains the default; conversation overrides choose from the gallery without changing the card. Draft materialization reuses one copied path for shared default/gallery assets. CharacterBackgroundRetention protects references in other cards, conversations, drafts and streaming SaveSlot summaries during unlink/overwrite/delete.

- CharacterInfo/PackagedCharacter.enabled is the portable card-level gate (legacy Desktop desktopEnabled is a read alias). ChatSession.characterParticipation stores per-card manual choices (legacy desktopCharacterParticipation read alias); SessionCharacterParticipationPolicy is adapted from the neutral Desktop policy. Request-only filtering leaves formal cards/history intact; old automatic pause state is retained and manually controllable, while Android automatic residency production remains separate/pending.

- Retained CharacterEditViewModel observes EditorDraftRepository.drafts: external management-list deletion of its loaded/pending draft resets to the latest formal card and cancels pending autosave, using the same discard path. WorldBookEditViewModel follows the same rule; internal save/discard suppresses observer reset while saving/not ready. ManagementDraftDiscardTest covers existing/new editors, pending restore prompts, no recreation and unrelated-draft isolation.

- A running reply freezes its request-side people view. Its later world-book timing write uses ChatRepository.updateSessionWorldBookClock to patch only the latest clock under the settings mutex, preserving subsequent quick people choices/settings and rejecting a different bound card.

- Ordinary ChatRepository.updateSession snapshot writes also preserve the latest participation/auto-pause fields, like the existing narrative-mode protection. The existing explicit save-slot restoration flag restores stored choices; first writes retain supplied initial choices. Dedicated quick toggles and settings-draft saves remain the mutation paths.
