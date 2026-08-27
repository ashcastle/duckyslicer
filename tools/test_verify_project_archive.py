from __future__ import annotations

import unittest

from tools.verify_project_archive import REQUIRED_STRINGS, VerificationError, verify_project_archive


def string_resources() -> str:
    values = "".join(f'<string name="{name}">{name}</string>' for name in REQUIRED_STRINGS)
    values += '<string name="share_project">Share project</string>'
    values += '<string name="project_share_error">Could not share project.</string>'
    return f"<resources>{values}</resources>"


def valid_sources() -> dict[str, str]:
    return {
        "ProjectArchive.kt": " ".join(
            (
                'PROJECT_ARCHIVE_MIME_TYPE = "application/vnd.duckyslicer.project+zip"',
                'PROJECT_ARCHIVE_FILE_EXTENSION = ".duckyproject"',
                "MAX_PROJECT_ARCHIVE_MANIFEST_BYTES = 8_388_608",
                "MAX_PROJECT_ARCHIVE_CONTENT_BYTES = 1_073_741_824L",
                "MAX_PROJECT_ARCHIVE_FILE_BYTES = 1_082_130_432L",
                "MAX_PROJECT_ARCHIVE_ENTRIES = ProjectStore.MAX_PROJECT_VOLUMES + 1",
                'PROJECT_ARCHIVE_FORMAT = "com.ashcastle.duckyslicer.project"',
                "MIN_PROJECT_ARCHIVE_SCHEMA_VERSION = 1",
                "PROJECT_ARCHIVE_SCHEMA_VERSION = 77",
                'ArchivedProjectPlate ArchivedProjectVolume put("role", volume.role.name) '
                'put("config", volume.config.toJson()) ProjectVolumeRole.valueOf '
                "ProjectVolumeConfig.fromJson",
                'getJSONArray("plates") getJSONArray("volumes") legacyProjectVolumeId',
                "selectedPlateId plateOptions: Map<String, SliceOptions>",
                'Regex("models/[0-9]{3}\\\\.stl")',
                "require(!entry.isDirectory require(entries.add(entry.name))",
                "entry.method == ZipEntry.DEFLATED || entry.method == ZipEntry.STORED",
                "readArchiveBytes(",
                "MAX_MODEL_IMPORT_BYTES checkedArchiveTotal",
                "require(referencedEntries == models.keys)",
                "output.fd.sync() parseBoundedJsonObject require(info.triangles > 0)",
                "supportPaint.facets.keys.all",
                "requireAxisScales = schemaVersion >= 6",
                "seamPaint.facets.keys.all",
                "multiColorPaint.facets.keys.all",
                'getJSONArray("multiColorPaint").toArchiveMultiColorPaint()',
                'getJSONArray("variableLayerHeights").toArchiveVariableLayerHeights()',
                'getJSONArray("brimPoints").toArchiveBrimPoints()',
                'getJSONObject("processOverrides").toObjectProcessOverrides()',
                'put("heightRangeModifiers", heightRangeModifiers.toProjectJson())',
                "schemaVersion >= 68",
                'getJSONArray("heightRangeModifiers").toHeightRangeModifiers()',
                'put("layerPauseEvents", plate.layerPauseEvents.toProjectJson())',
                "schemaVersion >= 73",
                'getJSONArray("layerPauseEvents").toLayerPauseEvents()',
                '"layerFilamentChanges", schemaVersion >= 74 '
                'getJSONArray("layerFilamentChanges").toLayerFilamentChanges()',
                '"layerCustomGCodeEvents", schemaVersion >= 75 '
                'getJSONArray("layerCustomGCodeEvents").toLayerCustomGCodeEvents()',
                'put("name", plate.name ?: JSONObject.NULL) '
                'schemaVersion >= 76 && !value.isNull("name") checkedArchivePlateName',
                "checkCancellation: () -> Unit = {}",
                "copyArchiveBytes(input, archive, model.length(), checkCancellation)",
                "val copied = copyArchiveBytes(",
                "val info = inspectModel(file)",
                "catch (failure: DocumentTransferCancelledException)",
            )
        ),
        "LayerFilamentChanges.kt": (
            "data class LayerFilamentChange data class LayerFilamentChanges "
            "MAX_EVENTS = 256 constrainedToSlotCount "
            "fun LayerFilamentChanges.toProjectJson "
            "fun JSONArray.toLayerFilamentChanges"
        ),
        "LayerCustomGCodeEvents.kt": (
            "data class LayerCustomGCodeEvent data class LayerCustomGCodeEvents "
            "MAX_EVENTS = 64 MAX_TOTAL_BYTES = 32_768 MAX_GCODE_BYTES = 2_048 "
            "fun LayerCustomGCodeEvents.toProjectJson "
            "fun JSONArray.toLayerCustomGCodeEvents"
        ),
        "HeightRangeModifiers.kt": (
            "data class HeightRangeModifier data class HeightRangeModifiers "
            "MAX_RANGES = 32 MIN_RANGE_MM = 0.01f fun writeSidecar fun readSidecar "
            "fun HeightRangeModifiers.toProjectJson fun JSONArray.toHeightRangeModifiers"
        ),
        "ProjectVolumeSemantics.kt": (
            "enum class ProjectVolumeRole NEGATIVE_VOLUME(1) PARAMETER_MODIFIER(2) "
            "SUPPORT_BLOCKER(3) SUPPORT_ENFORCER(4) MAX_ENTRIES = 128 "
            "MAX_VALUE_BYTES = 4 * 1_024 MAX_SIDECAR_BYTES = 64 * 1_024 "
            "fun readSidecar fun fromJson"
        ),
        "ProjectStore.kt": " ".join(
            (
                'File(projectRoot, ".archive-${UUID.randomUUID()}")',
                "ProjectArchiveCodec.read(",
                "moveArchiveModel(stagedModel.file, destination)",
                "val plateOptions = decoded.plates.associate save(snapshot, plateOptions)",
                "pruneUnreferencedModels(snapshot)",
                "installed.forEach(File::delete) staging.deleteRecursively()",
                "modelFile.parentFile == modelRoot && modelFile.isFile",
                "StandardCopyOption.ATOMIC_MOVE",
                "recoverAbandonedArchiveStaging recoverGeneratedStaging(projectRoot, \".archive-\")",
                "private fun recoverGeneratedStaging removePrefix(prefix)",
                "UUID.fromString(identifier) !Files.isSymbolicLink(candidate.toPath())",
                "checkCancellation: () -> Unit = {}",
                "ProjectArchiveCodec.write(snapshot, plateOptions, output, checkCancellation)",
                "beginCommit: () -> Unit = {} beginCommit()",
                'SCHEMA_VERSION = 82 schemaVersion >= 70 schemaVersion >= 75 '
                'schemaVersion >= 76 schemaVersion >= 77 schemaVersion >= 78 '
                'schemaVersion >= 79 schemaVersion >= 80 schemaVersion >= 81 '
                '"linkedDocument", '
                '"linkedDocumentDirty", root.has("linkedDocument") '
                'root.has("linkedDocumentDirty") '
                '"recentDocuments", root.getJSONArray("recentDocuments") '
                'MAX_RECENT_PROJECT_DOCUMENTS '
                'normalizedLinkedProjectDocument '
                'put("name", plate.name ?: JSONObject.NULL) normalizedProjectPlateName '
                'put("heightRangeModifiers", heightRangeModifiers.toProjectJson()) '
                'put("layerPauseEvents", plate.layerPauseEvents.toProjectJson()) '
                'getJSONArray("layerPauseEvents").toLayerPauseEvents() '
                '"layerFilamentChanges", '
                'getJSONArray("layerFilamentChanges").toLayerFilamentChanges() '
                '"layerCustomGCodeEvents", '
                'getJSONArray("layerCustomGCodeEvents").toLayerCustomGCodeEvents() '
                'put("role", role.name) put("config", config.toJson())',
            )
        ),
        "ProjectDocumentLink.kt": (
            "data class LinkedProjectDocument ContentResolver.SCHEME_CONTENT "
            "takePersistableUriPermission FLAG_GRANT_WRITE_URI_PERMISSION "
            "persistedUriPermissions permission.isWritePermission "
            "linkedProjectDocument(uri: Uri) OpenableColumns.DISPLAY_NAME "
            "MAX_PROJECT_DOCUMENT_URI_LENGTH = 4_096 "
            "MAX_PROJECT_DOCUMENT_NAME_LENGTH = 200 "
            "MAX_RECENT_PROJECT_DOCUMENTS = 5 withRecentProjectDocument "
            "withoutRecentProjectDocument releasePersistableUriPermission "
            "permission.isReadPermission"
        ),
        "ModelOpenRequest.kt": (
            "Intent.ACTION_VIEW Intent.ACTION_SEND Intent.ACTION_SEND_MULTIPLE "
            "sharedDocumentUrisOrNull(intent) ContentResolver.SCHEME_CONTENT "
            "MODEL_DOCUMENT_MIME_TYPES MODEL_DOCUMENT_COMPATIBLE_MIME_TYPES "
            "clipData.itemCount != 1 ProjectStore.MAX_PROJECT_OBJECTS "
            "uris.distinct().size != uris.size SavedStateHandle startedOperationId"
        ),
        "OrcaFacetAnnotations.kt": (
            "data class OrcaFacetAnnotation MAX_ANNOTATED_TRIANGLES = 100_000 "
            "MAX_TRIANGLE_VALUE_BYTES = 4_096 MAX_SIDECAR_BYTES = 8 * 1_024 * 1_024 "
            "fun readSidecar fun fromJson maximumTriangleState"
        ),
        "ProjectOpenRequest.kt": " ".join(
            (
                "Intent.ACTION_VIEW Intent.ACTION_SEND sharedDocumentUriOrNull(intent)",
                "ContentResolver.SCHEME_CONTENT",
                "PROJECT_ARCHIVE_MIME_TYPE PROJECT_ARCHIVE_FILE_EXTENSION",
                "PROJECT_ARCHIVE_COMPATIBLE_MIME_TYPES",
                '"application/zip" "application/x-zip-compressed" "application/octet-stream"',
                "SavedStateHandle StateFlow<ExternalProjectRequest?> startedOperationId "
                "fun markStarted(requestId: Long, operationId: Long): Boolean "
                "current.startedOperationId != operationId "
                "fun discardUnstarted(requestId: Long): Boolean",
            )
        ),
        "OrcaPrimitive.kt": (
            "CREATABLE_AUXILIARY_VOLUME_ROLES ProjectVolumeRole.NEGATIVE_VOLUME "
            "ProjectVolumeRole.PARAMETER_MODIFIER ProjectVolumeRole.SUPPORT_BLOCKER "
            "ProjectVolumeRole.SUPPORT_ENFORCER data class OrcaAuxiliaryPrimitiveDraft "
            'mapOf("sparse_infill_density" to "$modifierInfillPercent%") '
            "createOrcaAuxiliaryPrimitive( target.geometry() NativeEngine.transformStl( "
            "data class OrcaAuxiliaryVolumeEditDraft MIN_AUXILIARY_EDIT_SCALE_PERCENT "
            "updatedConfig(volume: ProjectVolume) editOrcaAuxiliaryVolume("
        ),
        "ProjectState.kt": (
            "fun addAuxiliaryVolumeToSelected( fun removeSelectedAuxiliaryVolume( "
            "fun replaceSelectedAuxiliaryVolume( ProjectVolumeRole.MODEL_PART "
            "fun duplicateSelectedPlate( Duplicate plate object identities are incomplete "
            "current.allObjects.size + source.objects.size <= ProjectStore.MAX_PROJECT_OBJECTS "
            "ProjectStore.MAX_PROJECT_VOLUMES projectObject.rebaseVolumeIds(newObjectId) "
            "selectedPlateId = newPlateId fun requiresProjectReplacementConfirmation( "
            "linkedDocumentDirty || plates.size > 1 || plates.any "
            "plate.objects.isNotEmpty() || plate.name != null "
            "plate.layerPauseEvents.values.isNotEmpty() "
            "plate.layerFilamentChanges.values.isNotEmpty() "
            "plate.layerCustomGCodeEvents.values.isNotEmpty()"
        ),
        "ProjectTransfer.kt": " ".join(
            (
                "AndroidViewModel(application)",
                "viewModelScope.launch(Dispatchers.IO)",
                "ProjectStore.recoverAbandonedArchiveStaging",
                "ProjectTransferState(busy = true)",
                "val history: ProjectHistoryState val sliceOptions: SliceOptions "
                "val plateOptions: Map<String, SliceOptions> "
                "val linkedDocument: LinkedProjectDocument? "
                "val linkedDocumentDirty: Boolean "
                "val recentDocuments: List<LinkedProjectDocument> "
                "val restored: Boolean val sessionRevision: Long "
                "val persistedRevision: Long "
                "val activeTransferId: Long? "
                "val activeTransferDirection: ProjectTransferDirection? "
                "val transferCancellationRequested: Boolean",
                "fun ProjectTransferState.withStartedTransfer( "
                "fun ProjectTransferState.withTransferCancellationRequested( "
                "fun ProjectTransferState.withCompletedTransfer(",
                "fun updateHistory( fun updateSession(",
                "projectStore.loadProject()",
                "document.linkedDocument document.linkedDocumentDirty",
                "PROJECT_SAVE_DEBOUNCE_MILLIS = 400L",
                "pendingPersistence?.join() fun flushPersistence() "
                "override fun onCleared() hasPersistableChanges",
                "mutableState.value.completion != null",
                "projectStore.importArchive",
                "uri.scheme != ContentResolver.SCHEME_CONTENT "
                "DocumentTransferCancellation() openAssetFileDescriptor( \"wt\", "
                "acquireContentProviderClient(uri) "
                "provider.openAssetFile(uri, \"r\", cancellation.providerSignal) "
                "cancellation.providerSignal cancellation.attachInput(input) "
                "cancellation::complete projectStore.exportArchive "
                "cancellation::throwIfRequested DocumentTransferCancelledException "
                "ProjectTransferCompletion.Canceled fun cancelProjectExport(): Boolean "
                "fun cancelProjectImport(): Boolean "
                "activeProjectDocumentTransfer?.operation == operation "
                "FinalProjectOwnerCleanup( activeProjectDocumentTransfer, pending, "
                "cleanup.transfer?.cancellation?.cancel() "
                "completionWasClaimed() hasUnpersistedSession() "
                "hasPersistableChanges(allowActiveTransfer = true) "
                "deleteFailedCreatedDocument(application, uri) "
                "fun saveLinkedProject( deleteFailedDocument = false "
                "retainProjectDocumentWritePermission(uri) "
                "withLinkedDocument(linkedDocument) "
                "fun importRecentProject(document: LinkedProjectDocument) "
                "fun forgetRecentProject(document: LinkedProjectDocument) "
                "withForgottenRecentProject(document.uri) "
                "withUnavailableProjectDocument(document.uri) "
                "releaseEvictedProjectDocumentPermissions "
                "SupportEvent.PROJECT_ARCHIVE_EXPORT_FAILED",
                "catch (failure: CancellationException) consumeCompletion",
                "fun createAuxiliaryPrimitive( createOrcaAuxiliaryPrimitive( "
                "addAuxiliaryVolumeToSelected fun editAuxiliaryVolume( "
                "editOrcaAuxiliaryVolume( replaceSelectedAuxiliaryVolume "
                "updateLinkedDocument: Boolean = true if (updateLinkedDocument) { "
                "completion is ProjectTransferCompletion.Exported && updateLinkedDocument",
            )
        ),
        "ProjectArchiveShare.kt": (
            "context.filesDir.canonicalFile Files.createDirectories(shareRoot.toPath()) "
            "existing.forEach { stale -> if (!stale.delete()) return null } Files.createTempFile( "
            '"${context.packageName}.project-share" PROJECT_ARCHIVE_MIME_TYPE '
            "Intent(Intent.ACTION_SEND) Intent.EXTRA_STREAM Intent.EXTRA_TITLE ClipData.newUri( "
            "Intent.FLAG_GRANT_READ_URI_PERMISSION "
            "discardProjectArchiveShare(context: Context, path: String?)"
        ),
        "ProjectArchiveShareActions.kt": (
            "rememberSaveable { mutableStateOf<Long?>(null) } completed.id != pendingOperationId "
            "Intent.createChooser(shareIntent, shareTitle) "
            "discardProjectArchiveShare(context, pendingPath) model.exportProject( "
            "updateLinkedDocument = false pendingOperationId = model.state.value.activeTransferId"
        ),
        "ProjectArchiveShareProvider.kt": (
            "class ProjectArchiveShareProvider FileProvider(R.xml.project_archive_share_paths)"
        ),
        "project_archive_share_paths.xml": (
            '<paths><files-path name="project-archives" path="project-shares/" /></paths>'
        ),
        "CreatedDocument.kt": (
            "fun deleteFailedCreatedDocument(context: Context, uri: Uri) "
            "ContentResolver.SCHEME_CONTENT DocumentsContract.deleteDocument "
            "resolver.delete(uri, null, null) class DocumentTransferCancelledException "
            "class DocumentTransferCancellation CancellationSignal() providerSignal.cancel() "
            "completionClaimed = true fun completionWasClaimed(): Boolean"
        ),
        "MainActivity.kt": " ".join(
            (
                "projectOpenPicker = rememberLauncherForActivityResult",
                "ActivityResultContracts.OpenDocument()",
                "rememberProjectDocumentCreator(",
                "ActivityResultContracts.CreateDocument(PROJECT_ARCHIVE_MIME_TYPE)",
                "SupportEvent.PROJECT_ARCHIVE_IMPORT_FAILED",
                "override fun onNewIntent(intent: Intent)",
                "externalProjectModel.enqueue(intent)",
                "onExternalProjectRequestStarted = externalProjectModel::markStarted "
                "onExternalProjectRequestConsumed = externalProjectModel::consume "
                "onExternalProjectRequestDiscarded = externalProjectModel::discardUnstarted "
                "startExternalProjectImport( "
                "activeTransferDirection == ProjectTransferDirection.IMPORT",
                "override fun onStop() projectTransferModel.flushPersistence()",
                "ProjectTransferViewModel projectTransferState.completion ProjectReplacementDialog( "
                "projectTransferState.linkedDocument?.displayName "
                "projectTransferState.linkedDocumentDirty "
                "replacementConfirmationRequired = requiresProjectReplacementConfirmation( "
                "linkedDocumentDirty = projectTransferState.linkedDocumentDirty "
                "projectTransferState.recentDocuments.filterNot "
                "onOpenRecentProject = ::importRecentProject "
                "projectTransferModel.saveLinkedProject( !started && !latest.busy "
                "latest.completion == null && latest.editCompletion == null "
                "onSaveProject = projectSaveAction(",
                "projectHistory = projectTransferState.history "
                "sliceOptions = projectTransferState.sliceOptions "
                "projectPlates = projectHistory.current.plates "
                "selectedPlateId = projectHistory.current.selectedPlateId "
                "projectRestored = projectTransferState.restored "
                "projectTransferModel.updateHistory( projectTransferModel::cancelProjectImport "
                "projectTransferModel::cancelProjectExport "
                "ProjectTransferCompletion.Canceled",
                "fun addAuxiliaryPrimitive( "
                "onCreateAuxiliaryPrimitive = ::addAuxiliaryPrimitive "
                "removeSelectedAuxiliaryVolume",
                "fun editAuxiliaryVolume( onEditAuxiliaryVolume = ::editAuxiliaryVolume "
                "ProjectEditKind.AUXILIARY_VOLUME",
                "onDuplicatePlate = { val sourceVolumeCount = source.objects.sumOf { it.volumes.size } "
                "duplicateSelectedPlate( notice = resources.getString(R.string.plate_duplicated) "
                "onRenamePlate = { name -> renameSelectedPlate(name) "
                "onMovePlate = { targetIndex -> moveSelectedPlateTo(targetIndex) "
                "rememberProjectArchiveShareActions( "
                "projectShareOperationId = projectShareActions.pendingOperationId "
                "onShareProject = projectShareActions.start",
            )
            ),
            "ProjectEditCompletionEffect.kt": (
                "completed.kind == ProjectEditKind.MODEL_IMPORT "
                "request.startedOperationId == completed.id "
                "onExternalModelRequestConsumed(request.id, completed.id) "
                "onConsumeCompletion(completed.id)"
            ),
        "WorkspaceScreen.kt": (
            "ProjectSheet( onOpenProject onSaveProject linkedProjectName linkedProjectDirty "
            "replacementConfirmationRequired = requiresProjectReplacementConfirmation( "
            "linkedDocumentDirty = linkedProjectDirty "
            "R.string.linked_project_file R.string.linked_project_unsaved "
            "R.string.recent_projects onOpenRecentProject(document) "
            "R.string.remove_recent_project onForgetRecentProject(document) "
            "R.string.project_save_options R.string.save_project_as "
            "onShareProject: () -> Unit R.string.share_project onShareProject() "
            "onPlateSelected onAddPlate "
            "onDuplicatePlate onRemovePlate PlateSwitcher( canDuplicateSelectedPlate "
            "onRenamePlate onMovePlate R.string.duplicate_plate R.string.rename_plate "
            "R.string.move_plate_previous R.string.move_plate_next R.string.plates "
            "R.string.plate_actions "
            'stateDescription = "${selectedIndex + 1}/${plates.size}" confirmReplacement '
            "R.string.replace_project_title R.string.replace_project_body "
            "R.string.replace_project_unsaved_body R.string.new_project_unsaved_body "
            "projectImporting: Boolean projectTransferCancellationRequested: Boolean "
            "onCancelProjectImport: () -> Unit onCancelProjectExport: () -> Unit "
            "R.string.cancel_project_import R.string.canceling_project_import "
            "R.string.cancel_project_export R.string.canceling_project_export "
            "if (exporting) onCancelProjectExport() else onSaveProject(false) "
            "onSaveProject(true) "
            "AuxiliaryVolumesSheet( AuxiliaryShapeSheet( "
            "CREATABLE_AUXILIARY_VOLUME_ROLES onRemoveAuxiliaryVolume "
            "AuxiliaryVolumeEditSheet( onEditAuxiliaryVolume R.string.apply_region_changes "
            "HeightRangeModifiersSheet( onHeightRangeModifiersChanged"
        ),
        "ObjectProcessSettingsSheet.kt": (
            "fun HeightRangeModifiersSheet( ObjectSettingCategory.QUALITY "
            "ObjectSettingCategory.STRENGTH ObjectSettingCategory.SPEED "
            "ObjectSettingCategory.SUPPORT ObjectSettingsDirtyBar("
        ),
        "ProjectArchiveTest.kt": (
            "projectArchiveRoundTripsModelsTransformsPaintAndResolvedProfilesDeterministically "
            "multiplePlatesAndTheirSettingsRoundTripThroughThePortableArchive "
            "invalidArchiveCannotEscapeStagingOrReplaceTheCurrentProject "
            "oversizedManifestIsRejectedBeforeProjectStateChanges "
            "startupRecoveryRemovesOnlyExactAbandonedArchiveDirectories "
            "canceledArchiveCopyRemovesStagingAndPreservesTheCurrentProject "
            "cancellationWinningTheCommitGateRemovesInstalledModelsAndPreservesCurrentProject"
        ),
        "ProjectStateTest.kt": (
            "duplicatingAPlatePreservesItsCompleteContentWithFreshIdentities "
            "duplicatingAPlateRejectsIncompleteCollidingAndOverCapacityIdentities"
        ),
        "ProjectVolumeSemanticsTest.kt": (
            "nativeRoleValuesAreStableAndComplete "
            "volumeConfigSidecarAndJsonRoundTripExactly "
            "auxiliaryVolumesRejectPrintableOnlyState "
            "projectAndArchiveObjectsRequirePrintableModelParts "
            "mobileAuxiliaryShapeDraftsCoverEveryCreatableRoleAndBoundTheirInputs "
            "auxiliaryVolumeEditDraftBoundsScalePlacementAndPreservesModifierSettings"
        ),
        "ProjectTransferStateTest.kt": (
            "retainedSessionMutationKeepsHistoryAndOptionsTogether "
            "staleOrBusySessionMutationIsRejected withUpdatedSession "
            "projectExportCancellationIsBoundToTheExactActiveTransfer "
            "projectImportCancellationIsBoundToTheExactActiveTransfer"
            " linkedProjectDocumentsRequireBoundedContentUrisAndSafeNames "
            "bindingAProjectDocumentIsRevisionTrackedAndIdempotent "
            "linkedProjectBecomesDirtyOnlyWhenProjectContentChanges "
            " switchingPlatesRestoresEachPlatesIndependentSliceOptions "
            "duplicatedPlateStartsWithTheSourcePlatesExactSliceOptions"
        ),
        "ProjectArchiveIntentInstrumentedTest.kt": (
            "customProjectIntentSurvivesRecreationRestoresAndSlices "
            "externalProjectRequestBindsOneOperationAndRestoresAsRetryableAfterProcessLoss "
            "projectViewIntentSurvivesRecreationAndImportsExactlyOnce "
            "unsavedProjectEditAndUndoSurviveImmediateActivityRecreation "
            "clearingRetainedOwnerFlushesProjectBeforeDebounce "
            "sharedProjectIntentConfirmsBeforeReplacingTheCurrentProject "
            "projectShareIntentsAcceptOneContentStreamAndRejectHostileShapes "
            "projectViewIntentRejectsNetworkAndUnrelatedBinaryUris "
            "Intent.ACTION_VIEW Intent.ACTION_SEND Intent.EXTRA_STREAM ClipData.newRawUri "
            "Intent.FLAG_GRANT_READ_URI_PERMISSION "
            "scenario.recreate() BlockingImportProvider.URI "
            "retainedRequest.request.value == null OnDeviceSlicer.slice("
        ),
        "ModelOpenIntentInstrumentedTest.kt": (
            "modelIntentsAcceptSupportedDocumentsAndRejectUnsafeOrUnrelatedUris "
            "externalModelRequestBindsOneOperationAndRestoresAsRetryableAfterProcessLoss "
            "modelViewIntentSurvivesRecreationAndImportsExactlyOnce"
        ),
        "CreatedDocumentLifecycleInstrumentedTest.kt": (
            "failedProjectArchiveExportDeletesTheNewDocument "
            "failedLinkedProjectExportPreservesTheExistingDocument "
            "deleteFailedDocument = false "
            "persistedProjectDocumentLinkSurvivesOwnerRecreationAndSavesDirectly "
            "retainProjectDocumentWritePermission(uri) restored.saveLinkedProject( "
            "releasePersistableUriPermission( "
            "BlockingExportProvider.METHOD_PREPARE_FAILURE model.exportProject( "
            "BlockingExportProvider.KEY_DELETED "
            "projectExportCancellationSurvivesRecreationAndDeletesThePartialDocument "
            "projectExportCancellationInterruptsProviderOpen "
            "finalProjectOwnerClearStopsItsExportAndDeletesThePartialDocument "
            "BlockingExportProvider.METHOD_PREPARE "
            "BlockingExportProvider.METHOD_PREPARE_OPEN_BLOCK scenario.recreate() "
            "retained.cancelProjectExport() store.clear() "
            "portableProjectSharePreservesLinkedDocumentAndUnsavedState "
            "updateLinkedDocument = false Sharing must not mark the linked project as saved"
        ),
        "ProjectArchiveShareInstrumentedTest.kt": (
            "preparedProjectArchiveSharesOneExactReadOnlyDocument "
            "Intent.FLAG_GRANT_READ_URI_PERMISSION Intent.FLAG_GRANT_WRITE_URI_PERMISSION "
            "FileProvider.getUriForFile( assertEquals(1, assertArrayEquals("
        ),
        "ProjectImportLifecycleInstrumentedTest.kt": (
            "projectImportCancellationSurvivesRecreationAndPreservesTheCurrentProject "
            "projectImportCancellationInterruptsProviderOpen "
            "finalProjectOwnerClearStopsItsImportAndPreservesTheCurrentProject "
            "recentProjectReopensThroughItsRetainedDocumentGrant "
            "model.forgetRecentProject(document) "
            "resolver.hasProjectDocumentWritePermission(uri) "
            "BlockingImportProvider.METHOD_PREPARE "
            "BlockingImportProvider.METHOD_PREPARE_DIRECT "
            "BlockingImportProvider.METHOD_PREPARE_OPEN_BLOCK "
            "retained.cancelProjectImport() store.clear() model.updateSession( "
            "unsavedOptions waitForStagingCleanup()"
        ),
        "BlockingExportProvider.java": (
            "openAssetFile( CancellationSignal signal "
            "signal.setOnCancelListener(target.release::countDown) signal.throwIfCanceled() "
            "OpenableColumns.DISPLAY_NAME Linked-project.duckyproject"
        ),
        "BlockingImportProvider.java": (
            "openAssetFile( CancellationSignal signal "
            "signal.setOnCancelListener(target.release::countDown) signal.throwIfCanceled() "
            "ParcelFileDescriptor.createPipe() MODE_DIRECT ParcelFileDescriptor.dup( "
            "Blocking import provider is read-only"
        ),
        "AccessibilityInstrumentedTest.kt": (
            "cancelProjectImportActionIsReachable cancelProjectExportActionIsReachable "
            "projectActionsAreVisibleAndOpeningConfirmsReplacement R.string.project_save_options "
            "projectShareActionIsReachableFromTheSaveMenu "
            "recentProjectIsFocusableAndUsesTheExistingReplacementWarning R.string.recent_projects "
            "R.string.recent_project_actions R.string.remove_recent_project "
            "dirtyEmptyLinkedProjectRequiresConfirmationBeforeOpenOrNew "
            "R.string.linked_project_unsaved R.string.replace_project_unsaved_body "
            "R.string.save_project_as Save project as must be reachable from the split action "
            "R.string.share_project Share project must be reachable from the split action "
            "TEST_PROJECT_SHARE_REQUESTED_LABEL "
            "plateSwitcherExposesSelectionAddAndConfirmedRemovalActions "
            "plateSwitcherDuplicatesTheSelectedPlateAndSelectsTheCopy R.string.duplicate_plate "
            "plateSwitcherRenamesAndReordersTheSelectedPlate R.string.rename_plate "
            "auxiliaryShapePickerExposesRolesPlacementAndModifierDensity "
            "auxiliaryVolumeManagerExposesExistingRegionsRemovalAndAdd "
            "auxiliaryVolumeEditorExposesScalePlacementDensityAndApply "
            "heightRangeModifiersExposeRangeSettingsAndStickyActions"
        ),
        "AccessibilityHarnessActivity.kt": (
            "onShareProject = { harnessNotice = TEST_PROJECT_SHARE_REQUESTED_LABEL } "
            "Accessibility project share requested"
        ),
        "NativeEngineInstrumentedTest.kt": (
            "projectArchiveRoundTripReinspectsAndSlicesOnArm64 "
            "inspectModel( OnDeviceSlicer.slice("
        ),
        "OrcaVolumeSemanticsInstrumentedTest.kt": (
            "mobileCreatedCutoutAndSettingsRegionChangeRealOrcaExtrusion "
            "createOrcaAuxiliaryPrimitive( "
            "withCutout.filamentMm < solidBaseline.filamentMm * 0.9f "
            "withSettingsRegion.filamentMm > sparseBaseline.filamentMm * 1.12f "
            "editOrcaAuxiliaryVolume( "
            "withEditedCutout.filamentMm > withCutout.filamentMm * 1.05f "
            "withEditedSettingsRegion.filamentMm < withSettingsRegion.filamentMm * 0.9f"
        ),
        "OrcaHeightRangeModifiersInstrumentedTest.kt": (
            "selectedHeightUsesRealOrcaLayerConfigWithoutChangingTheRestOfTheObject "
            "HeightRangeModifier( modified.layers > baseline.layers + 40 "
            "Expected 0.10 mm layers in selected range "
            "Expected 0.20 mm layers above selected range"
        ),
        "strings.xml": string_resources(),
        "strings-ko.xml": string_resources(),
        "AndroidManifest.xml": (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            '<application><provider android:name=".ProjectArchiveShareProvider" '
            'android:authorities="${applicationId}.project-share" '
            'android:exported="false" android:grantUriPermissions="true">'
            '<meta-data android:name="android.support.FILE_PROVIDER_PATHS" '
            'android:resource="@xml/project_archive_share_paths" /></provider>'
            '<activity android:name=".MainActivity" '
            'android:launchMode="singleTop" '
            'android:intentMatchingFlags="enforceIntentFilter">'
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/vnd.duckyslicer.project+zip" />'
            '<data android:scheme="content" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/zip" />'
            '<data android:mimeType="application/x-zip-compressed" />'
            '<data android:mimeType="application/octet-stream" />'
            '<data android:host="*" />'
            '<data android:pathPattern=".*\\.duckyproject" />'
            '<data android:scheme="content" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/vnd.duckyslicer.profiles+json" />'
            '<data android:scheme="content" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/json" />'
            '<data android:mimeType="application/octet-stream" />'
            '<data android:host="*" />'
            '<data android:pathPattern=".*\\.duckyprofiles" />'
            '<data android:scheme="content" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="text/x.gcode" />'
            '<data android:mimeType="application/x-gcode" />'
            '<data android:mimeType="application/gcode" />'
            '<data android:scheme="content" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/octet-stream" />'
            '<data android:mimeType="text/plain" />'
            '<data android:host="*" />'
            '<data android:pathPattern=".*\\.gcode" />'
            '<data android:pathPattern=".*\\.GCODE" />'
            '<data android:scheme="content" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="model/stl" />'
            '<data android:mimeType="application/sla" />'
            '<data android:mimeType="application/vnd.ms-pki.stl" />'
            '<data android:mimeType="model/3mf" />'
            '<data android:mimeType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml" />'
            '<data android:mimeType="application/vnd.ms-3mfdocument" />'
            '<data android:mimeType="model/obj" />'
            '<data android:mimeType="application/x-tgif" />'
            '<data android:scheme="content" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/octet-stream" />'
            '<data android:host="*" />'
            '<data android:pathPattern=".*\\.stl" /><data android:pathPattern=".*\\.STL" />'
            '<data android:pathPattern=".*\\.3mf" /><data android:pathPattern=".*\\.3MF" />'
            '<data android:pathPattern=".*\\.obj" /><data android:pathPattern=".*\\.OBJ" />'
            '<data android:scheme="content" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/zip" />'
            '<data android:mimeType="application/x-zip-compressed" />'
            '<data android:host="*" />'
            '<data android:pathPattern=".*\\.3mf" /><data android:pathPattern=".*\\.3MF" />'
            '<data android:scheme="content" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.SEND" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/vnd.duckyslicer.project+zip" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.SEND" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/vnd.duckyslicer.profiles+json" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.SEND" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="text/x.gcode" />'
            '<data android:mimeType="application/x-gcode" />'
            '<data android:mimeType="application/gcode" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.SEND" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="model/stl" />'
            '<data android:mimeType="application/sla" />'
            '<data android:mimeType="application/vnd.ms-pki.stl" />'
            '<data android:mimeType="model/3mf" />'
            '<data android:mimeType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml" />'
            '<data android:mimeType="application/vnd.ms-3mfdocument" />'
            '<data android:mimeType="model/obj" />'
            '<data android:mimeType="application/x-tgif" /></intent-filter>'
            '<intent-filter><action android:name="android.intent.action.SEND_MULTIPLE" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="model/stl" />'
            '<data android:mimeType="application/sla" />'
            '<data android:mimeType="application/vnd.ms-pki.stl" />'
            '<data android:mimeType="model/3mf" />'
            '<data android:mimeType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml" />'
            '<data android:mimeType="application/vnd.ms-3mfdocument" />'
            '<data android:mimeType="model/obj" />'
            '<data android:mimeType="application/x-tgif" /></intent-filter>'
            "</activity></application></manifest>"
        ),
        "AndroidTestManifest.xml": (
            '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
            '<application><provider android:authorities="'
            'com.ashcastle.duckyslicer.test.blocking-import" '
            'android:grantUriPermissions="true" />'
            '<provider android:authorities="com.ashcastle.duckyslicer.test.blocking-export" '
            'android:grantUriPermissions="true" /></application></manifest>'
        ),
        "PRIVACY.md": (
            "Exported DuckySlicer project files contain plate organization, model geometry\n"
            "support, seam, and multi-color painting, manual Brim-ear points, variable layer-height ranges,\n"
            "height-range process modifiers, and each plate's active printer\n"
            "They do not contain G-code, saved printer addresses, or printer\n"
            "형상, 오브젝트 배치, 서포트·심·다중 색상 채색, 수동 Brim 이어 점, 가변 레이어\n"
            "프린터 접속 키는 포함되지"
        ),
        "SUPPORT.md": "`.duckyproject` model geometry include saved printer addresses, access keys, or G-code",
        "PROJECT_FORMAT.md": (
            "manifest.json models/000.stl schema version `77` "
            "Schema 1 through 76 projects remain readable optional display name "
            "brim chamfer policy up to 16 plates "
            "parameter modifier support blocker support enforcer "
            "plate-local objects and settings stable, bounded `volumes` list "
            "up to 64 volumes per object independent X, Y, and Z scale "
            "multi-color painting manual Brim-ear points variable layer-height ranges "
            "height-range process modifiers "
            "rejects duplicate, directory, traversal, and unknown entries "
            "A failed import leaves the current project unchanged and removes staged data "
            "another app's Share sheet External opening accepts only one granted `content://` URI "
            "requires confirmation before the current project is replaced "
            "Save project as private app state never a resolved local path "
            "is not written into the portable archive "
            "up to five granted documents in a private recent list "
            "returns the permission of an entry evicted without deleting the document itself "
            "currently linked document cannot be removed from Recent "
            "bound to that exact import operation "
            "Activity recreation never opens the same request twice "
            "the URI is restored without an in-memory operation claim "
            "returns to replacement confirmation before retrying "
            "exact generated UUID form "
            "1 GiB total uncompressed content"
        ),
        "CONTRIBUTING.md": (
            "Project history, active slicing options, restoration, and debounced persistence "
            "same Activity-retained owner process-death recovery "
            "Flush the latest dirty revision app enters the background owner is finally cleared "
            "archive import commits cancel and join any older metadata write "
            "Every `CreateDocument` writer delete it after cancellation or failure "
            "Project archive export exact provider open and ZIP write "
            "Project archive import atomic commit"
        ),
    }


class VerifyProjectArchiveTest(unittest.TestCase):
    def test_accepts_bounded_atomic_local_archive(self) -> None:
        verify_project_archive(valid_sources())

    def test_rejects_removed_uncompressed_limit(self) -> None:
        sources = valid_sources()
        sources["ProjectArchive.kt"] = sources["ProjectArchive.kt"].replace(
            "MAX_PROJECT_ARCHIVE_CONTENT_BYTES = 1_073_741_824L", ""
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_duplicate_entry_check(self) -> None:
        sources = valid_sources()
        sources["ProjectArchive.kt"] = sources["ProjectArchive.kt"].replace(
            "require(entries.add(entry.name))", "entries.add(entry.name)"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_direct_non_staged_import(self) -> None:
        sources = valid_sources()
        sources["ProjectStore.kt"] = sources["ProjectStore.kt"].replace(
            'File(projectRoot, ".archive-${UUID.randomUUID()}")', "File(projectRoot, PROJECT_FILE)"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_broad_archive_staging_recovery(self) -> None:
        sources = valid_sources()
        sources["ProjectStore.kt"] = sources["ProjectStore.kt"].replace(
            'recoverGeneratedStaging(projectRoot, ".archive-")',
            'recoverGeneratedStaging(projectRoot, "")',
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_automatic_or_shared_export(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] += " ACTION_SEND"
        with self.assertRaisesRegex(VerificationError, "user-chosen"):
            verify_project_archive(sources)

    def test_rejects_broad_project_share_path(self) -> None:
        sources = valid_sources()
        sources["project_archive_share_paths.xml"] = sources[
            "project_archive_share_paths.xml"
        ].replace('path="project-shares/"', 'path="."')
        with self.assertRaisesRegex(VerificationError, "expose only project-shares"):
            verify_project_archive(sources)

    def test_rejects_exported_project_share_provider(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            'android:exported="false" android:grantUriPermissions="true"',
            'android:exported="true" android:grantUriPermissions="true"',
        )
        with self.assertRaisesRegex(VerificationError, "private and narrowly granted"):
            verify_project_archive(sources)

    def test_rejects_writable_project_share_grant(self) -> None:
        sources = valid_sources()
        sources["ProjectArchiveShare.kt"] += " FLAG_GRANT_WRITE_URI_PERMISSION"
        with self.assertRaisesRegex(VerificationError, "read-only"):
            verify_project_archive(sources)

    def test_rejects_project_share_that_relinks_the_document(self) -> None:
        sources = valid_sources()
        sources["ProjectArchiveShareActions.kt"] = sources[
            "ProjectArchiveShareActions.kt"
        ].replace("updateLinkedDocument = false", "updateLinkedDocument = true")
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_privacy_disclosure(self) -> None:
        sources = valid_sources()
        sources["PRIVACY.md"] = ""
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_broad_network_view_filter(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            "</activity>",
            '<intent-filter><action android:name="android.intent.action.VIEW" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:scheme="https" /></intent-filter></activity>',
        )
        with self.assertRaisesRegex(VerificationError, "content project"):
            verify_project_archive(sources)

    def test_rejects_broad_model_view_filter(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            '<data android:mimeType="model/stl" />',
            '<data android:mimeType="*/*" />',
            1,
        )
        with self.assertRaisesRegex(VerificationError, "project/profile/model"):
            verify_project_archive(sources)

    def test_rejects_an_additional_broad_send_filter(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            "</activity>",
            '<intent-filter><action android:name="android.intent.action.SEND" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="*/*" /></intent-filter></activity>',
        )
        with self.assertRaisesRegex(VerificationError, "explicit project, profile, G-code, and model MIME SEND"):
            verify_project_archive(sources)

    def test_rejects_an_additional_broad_send_multiple_filter(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            "</activity>",
            '<intent-filter><action android:name="android.intent.action.SEND_MULTIPLE" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="*/*" /></intent-filter></activity>',
        )
        with self.assertRaisesRegex(VerificationError, "explicit model MIME SEND_MULTIPLE"):
            verify_project_archive(sources)

    def test_rejects_missing_model_send_multiple_filter(self) -> None:
        sources = valid_sources()
        start = (
            '<intent-filter><action android:name="android.intent.action.SEND_MULTIPLE" />'
        )
        start_index = sources["AndroidManifest.xml"].index(start)
        end_index = sources["AndroidManifest.xml"].index("</intent-filter>", start_index)
        sources["AndroidManifest.xml"] = (
            sources["AndroidManifest.xml"][:start_index]
            + sources["AndroidManifest.xml"][end_index + len("</intent-filter>"):]
        )
        with self.assertRaisesRegex(VerificationError, "explicit model MIME SEND_MULTIPLE"):
            verify_project_archive(sources)

    def test_rejects_missing_project_send_filter(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            '<intent-filter><action android:name="android.intent.action.SEND" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/vnd.duckyslicer.project+zip" /></intent-filter>',
            "",
        )
        with self.assertRaisesRegex(VerificationError, "explicit project, profile, G-code, and model MIME SEND"):
            verify_project_archive(sources)

    def test_rejects_missing_profile_send_filter(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            '<intent-filter><action android:name="android.intent.action.SEND" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="application/vnd.duckyslicer.profiles+json" /></intent-filter>',
            "",
        )
        with self.assertRaisesRegex(VerificationError, "explicit project, profile, G-code, and model MIME SEND"):
            verify_project_archive(sources)

    def test_rejects_project_share_without_single_stream_parser(self) -> None:
        sources = valid_sources()
        sources["ProjectOpenRequest.kt"] = sources["ProjectOpenRequest.kt"].replace(
            "sharedDocumentUriOrNull(intent)", "intent.data",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_model_multi_share_without_bounded_stream_parser(self) -> None:
        sources = valid_sources()
        sources["ModelOpenRequest.kt"] = sources["ModelOpenRequest.kt"].replace(
            "sharedDocumentUrisOrNull(intent)", "intent.clipData",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_gcode_send_filter(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            '<intent-filter><action android:name="android.intent.action.SEND" />'
            '<category android:name="android.intent.category.DEFAULT" />'
            '<data android:mimeType="text/x.gcode" />'
            '<data android:mimeType="application/x-gcode" />'
            '<data android:mimeType="application/gcode" /></intent-filter>',
            "",
        )
        with self.assertRaisesRegex(VerificationError, "explicit project, profile, G-code, and model MIME SEND"):
            verify_project_archive(sources)

    def test_rejects_missing_single_top_delivery(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            ' android:launchMode="singleTop"', ""
        )
        with self.assertRaisesRegex(VerificationError, "onNewIntent"):
            verify_project_archive(sources)

    def test_rejects_relaxed_external_intent_matching(self) -> None:
        sources = valid_sources()
        sources["AndroidManifest.xml"] = sources["AndroidManifest.xml"].replace(
            ' android:intentMatchingFlags="enforceIntentFilter"', ""
        )
        with self.assertRaisesRegex(VerificationError, "external intent allowlist"):
            verify_project_archive(sources)

    def test_rejects_activity_scoped_transfer(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "viewModelScope.launch(Dispatchers.IO)", "scope.launch(Dispatchers.IO)"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_activity_owned_project_session(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] += " mutableStateOf(ProjectHistoryState())"
        with self.assertRaisesRegex(VerificationError, "session persistence"):
            verify_project_archive(sources)

    def test_rejects_partial_project_export_cleanup(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "deleteFailedCreatedDocument(application, uri)", "leave partial archive"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_non_interruptible_project_archive_copy(self) -> None:
        sources = valid_sources()
        sources["ProjectArchive.kt"] = sources["ProjectArchive.kt"].replace(
            "copyArchiveBytes(input, archive, model.length(), checkCancellation)",
            "copyArchiveBytes(input, archive, model.length())",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_project_export_without_provider_cancellation(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "DocumentTransferCancellation()", "non_interruptible_writer"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_project_export_cancel_action(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "onCancelProjectExport: () -> Unit", "no_cancel_action"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_project_export_cancellation_lifecycle_regression(self) -> None:
        sources = valid_sources()
        sources["CreatedDocumentLifecycleInstrumentedTest.kt"] = sources[
            "CreatedDocumentLifecycleInstrumentedTest.kt"
        ].replace(
            "projectExportCancellationSurvivesRecreationAndDeletesThePartialDocument",
            "missing_rotation_regression",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_provider_open_cancellation_regression(self) -> None:
        sources = valid_sources()
        sources["BlockingExportProvider.java"] = sources["BlockingExportProvider.java"].replace(
            "signal.throwIfCanceled()", "ignore_provider_cancellation"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_accessible_project_export_cancel_regression(self) -> None:
        sources = valid_sources()
        sources["AccessibilityInstrumentedTest.kt"] = "missing accessibility regression"
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_project_import_without_bound_input_cancellation(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "cancellation.attachInput(input)", "unbound_import_input"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_project_import_through_the_read_typed_asset_shortcut(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "provider.openAssetFile(uri, \"r\", cancellation.providerSignal)",
            "resolver.openAssetFileDescriptor(uri, \"r\", cancellation.providerSignal)",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_project_import_without_atomic_commit_gate(self) -> None:
        sources = valid_sources()
        sources["ProjectStore.kt"] = sources["ProjectStore.kt"].replace(
            "beginCommit()", "commit_without_cancellation_gate"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_project_import_cancel_action(self) -> None:
        sources = valid_sources()
        sources["WorkspaceScreen.kt"] = sources["WorkspaceScreen.kt"].replace(
            "onCancelProjectImport: () -> Unit", "no_import_cancel_action"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_project_import_lifecycle_regression(self) -> None:
        sources = valid_sources()
        sources["ProjectImportLifecycleInstrumentedTest.kt"] = sources[
            "ProjectImportLifecycleInstrumentedTest.kt"
        ].replace(
            "projectImportCancellationSurvivesRecreationAndPreservesTheCurrentProject",
            "missing_import_rotation_regression",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_final_owner_import_cleanup_that_can_overwrite_a_committed_import(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "completionWasClaimed()", "always_restore_the_old_project"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_accessible_project_import_cancel_regression(self) -> None:
        sources = valid_sources()
        sources["AccessibilityInstrumentedTest.kt"] = sources[
            "AccessibilityInstrumentedTest.kt"
        ].replace("cancelProjectImportActionIsReachable", "missing_import_accessibility")
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_auxiliary_volume_native_semantics_regression(self) -> None:
        sources = valid_sources()
        sources["OrcaVolumeSemanticsInstrumentedTest.kt"] = sources[
            "OrcaVolumeSemanticsInstrumentedTest.kt"
        ].replace(
            "mobileCreatedCutoutAndSettingsRegionChangeRealOrcaExtrusion",
            "missing_auxiliary_volume_native_semantics_regression",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_missing_auxiliary_volume_edit_output_regression(self) -> None:
        sources = valid_sources()
        sources["OrcaVolumeSemanticsInstrumentedTest.kt"] = sources[
            "OrcaVolumeSemanticsInstrumentedTest.kt"
        ].replace(
            "withEditedSettingsRegion.filamentMm < withSettingsRegion.filamentMm * 0.9f",
            "edited_settings_region_output_is_not_checked",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_import_without_joining_older_persistence(self) -> None:
        sources = valid_sources()
        sources["ProjectTransfer.kt"] = sources["ProjectTransfer.kt"].replace(
            "pendingPersistence?.join()", "start archive import immediately"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_background_transition_without_project_flush(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "projectTransferModel.flushPersistence()", "leave debounce pending"
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_external_project_completion_without_operation_identity(self) -> None:
        sources = valid_sources()
        sources["ProjectOpenRequest.kt"] = sources["ProjectOpenRequest.kt"].replace(
            "current.startedOperationId != operationId",
            "accept completion from any operation",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_external_project_request_not_bound_to_retained_import(self) -> None:
        sources = valid_sources()
        sources["MainActivity.kt"] = sources["MainActivity.kt"].replace(
            "onExternalProjectRequestStarted = externalProjectModel::markStarted",
            "do not retain the project import owner",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_project_format_without_external_open_lifecycle_contract(self) -> None:
        sources = valid_sources()
        sources["PROJECT_FORMAT.md"] = sources["PROJECT_FORMAT.md"].replace(
            "Activity recreation never opens the same request twice",
            "Activity recreation behavior is unspecified",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_plate_duplicate_without_fresh_volume_identities(self) -> None:
        sources = valid_sources()
        sources["ProjectState.kt"] = sources["ProjectState.kt"].replace(
            "projectObject.rebaseVolumeIds(newObjectId)",
            "projectObject.volumes",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_plate_duplicate_without_exact_slice_options_regression(self) -> None:
        sources = valid_sources()
        sources["ProjectTransferStateTest.kt"] = sources[
            "ProjectTransferStateTest.kt"
        ].replace(
            "duplicatedPlateStartsWithTheSourcePlatesExactSliceOptions",
            "missing_duplicate_options_regression",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)

    def test_rejects_plate_duplicate_without_accessible_ui_regression(self) -> None:
        sources = valid_sources()
        sources["AccessibilityInstrumentedTest.kt"] = sources[
            "AccessibilityInstrumentedTest.kt"
        ].replace(
            "plateSwitcherDuplicatesTheSelectedPlateAndSelectsTheCopy",
            "missing_duplicate_ui_regression",
        )
        with self.assertRaisesRegex(VerificationError, "safeguards"):
            verify_project_archive(sources)


if __name__ == "__main__":
    unittest.main()
