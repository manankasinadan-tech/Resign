package com.example.ui

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.GeminiAnalysisService
import com.example.crypto.ApkResigner
import com.example.crypto.KeysetGenerator
import com.example.crypto.SignatureScanner
import com.example.engine.ArtifactRegenerator
import com.example.engine.Ext4ErofsUnpacker
import com.example.engine.ImageRepacker
import com.example.engine.PermissionsPatcher
import com.example.engine.SparseImageParser
import com.example.model.ApkSignatureDetails
import com.example.model.DistinguishedNameConfig
import com.example.model.EngineProgress
import com.example.model.FactualAiReport
import com.example.model.GsiImageInfo
import com.example.model.ImageFormat
import com.example.model.KeysetBundle
import com.example.model.ResignStep
import com.example.model.SignatureCluster
import com.example.model.StockMappingReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

class ResignViewModel : ViewModel() {

    private val _selectedImage = MutableStateFlow<GsiImageInfo?>(null)
    val selectedImage: StateFlow<GsiImageInfo?> = _selectedImage.asStateFlow()

    private val _engineProgress = MutableStateFlow(EngineProgress())
    val engineProgress: StateFlow<EngineProgress> = _engineProgress.asStateFlow()

    private val _clusters = MutableStateFlow<List<SignatureCluster>>(emptyList())
    val clusters: StateFlow<List<SignatureCluster>> = _clusters.asStateFlow()

    private val _scannedApks = MutableStateFlow<List<ApkSignatureDetails>>(emptyList())
    val scannedApks: StateFlow<List<ApkSignatureDetails>> = _scannedApks.asStateFlow()

    private val _aiReport = MutableStateFlow<FactualAiReport?>(null)
    val aiReport: StateFlow<FactualAiReport?> = _aiReport.asStateFlow()

    private val _isKeysetDialogVisible = MutableStateFlow(false)
    val isKeysetDialogVisible: StateFlow<Boolean> = _isKeysetDialogVisible.asStateFlow()

    private val _isReportDialogVisible = MutableStateFlow(false)
    val isReportDialogVisible: StateFlow<Boolean> = _isReportDialogVisible.asStateFlow()

    private val _dnConfig = MutableStateFlow(DistinguishedNameConfig())
    val dnConfig: StateFlow<DistinguishedNameConfig> = _dnConfig.asStateFlow()

    private val _keysetBundle = MutableStateFlow<KeysetBundle?>(null)
    val keysetBundle: StateFlow<KeysetBundle?> = _keysetBundle.asStateFlow()

    private val _isLogDrawerOpen = MutableStateFlow(false)
    val isLogDrawerOpen: StateFlow<Boolean> = _isLogDrawerOpen.asStateFlow()

    // Workspace base folder
    fun getWorkspaceRoot(context: Context): File {
        val sdcard = Environment.getExternalStorageDirectory()
        val resignDir = File(sdcard, "RESIGN")
        return if (resignDir.exists() || resignDir.mkdirs()) {
            resignDir
        } else {
            File(context.filesDir, "RESIGN").apply { mkdirs() }
        }
    }

    fun onImageSelected(file: File) {
        val info = SparseImageParser.inspectImage(file)
        _selectedImage.value = info
        log("Selected image: ${info.fileName} (${info.format.displayName}, ${info.formattedSize})")
    }

    fun handleSelectedUri(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                log("Resolving selected image from storage...")
                val contentResolver = context.contentResolver
                var fileName = "target_gsi.img"
                val cursor = contentResolver.query(uri, null, null, null, null)
                cursor?.use {
                    if (it.moveToFirst()) {
                        val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            fileName = it.getString(nameIndex) ?: fileName
                        }
                    }
                }

                val workspace = getWorkspaceRoot(context)
                val destFile = File(workspace, fileName)
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(destFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val info = SparseImageParser.inspectImage(destFile)
                _selectedImage.value = info
                log("Target image successfully loaded to workspace: ${destFile.name}")
            } catch (e: Exception) {
                log("Error importing image file: ${e.message}")
            }
        }
    }

    fun loadSampleGsiForTesting(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            log("Creating baseline Generic System Image for testing...")
            val workspace = getWorkspaceRoot(context)
            val demoFile = File(workspace, "aosp_arm64_gsi_sample.img")
            if (!demoFile.exists() || demoFile.length() < 1024) {
                demoFile.writeBytes(ByteArray(65536) { 0 })
            }
            val info = GsiImageInfo(
                fileName = demoFile.name,
                filePath = demoFile.absolutePath,
                fileSizeBytes = demoFile.length(),
                format = ImageFormat.EXT4,
                isSparse = false,
                blockSize = 4096,
                totalBlocks = 16384,
                detectedPartitions = listOf("system", "product", "system_ext")
            )
            _selectedImage.value = info
            log("Loaded baseline test image: ${demoFile.name}")
        }
    }

    fun startResignProcess(context: Context) {
        val image = _selectedImage.value
        if (image == null) {
            log("Error: No image selected for re-signing.")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val workspace = getWorkspaceRoot(context)
                val unpackDir = File(workspace, "UNPACK")
                val dataDir = File(workspace, "Data")

                // Step 1: Inspecting Image
                updateProgress(ResignStep.INSPECTING_IMAGE, 0.05f, "Analyzing image superblock and partition tables...")
                val freshInfo = SparseImageParser.inspectImage(File(image.filePath))
                _selectedImage.value = freshInfo
                log("Filesystem format verified: ${freshInfo.format.displayName}")

                // Step 2: Unpacking Image
                updateProgress(ResignStep.UNPACKING_IMAGE, 0.15f, "Unpacking image to sdcard/RESIGN/UNPACK...")
                val apks = Ext4ErofsUnpacker.unpackImage(freshInfo, unpackDir, dataDir) { p, msg ->
                    updateProgress(ResignStep.UNPACKING_IMAGE, 0.15f + (p * 0.25f), msg)
                }

                // Step 3: Scanning APK Signatures (V1, V2, V3, V4)
                updateProgress(ResignStep.SCANNING_SIGNATURES, 0.45f, "Scanning signatures for ${apks.size} APKs...")
                val detailsList = mutableListOf<ApkSignatureDetails>()

                apks.forEachIndexed { idx, apk ->
                    val relPath = apk.relativeTo(unpackDir).path
                    val details = SignatureScanner.scanApk(apk, relPath)
                    detailsList.add(details)
                    val prog = 0.45f + ((idx.toFloat() / apks.size.coerceAtLeast(1).toFloat()) * 0.15f)
                    updateProgress(ResignStep.SCANNING_SIGNATURES, prog, "Scanned signatures: ${apk.name} [${details.schemesSummary}]")
                }
                _scannedApks.value = detailsList

                // Step 4: Clustering & Stock Mapping JSON Export
                val clustersList = SignatureScanner.clusterApks(detailsList)
                _clusters.value = clustersList

                log("Identified ${clustersList.size} signature clusters across ${detailsList.size} APKs.")
                saveStockMappingJson(dataDir, clustersList, detailsList)

                // Step 5: Factual AI Cross-Analysis
                updateProgress(ResignStep.FACTUAL_AI_ANALYSIS, 0.65f, "Performing factual AI cross-analysis...")
                val report = GeminiAnalysisService.analyzeGsiMapping(clustersList, detailsList)
                _aiReport.value = report
                saveAiReportJson(dataDir, report)
                log("Factual AI analysis complete. Security Consistency: ${report.securityConsistency}")

                // Step 6: Trigger Keyset Popup
                updateProgress(ResignStep.AWAITING_KEYSET, 0.70f, "Awaiting keyset configuration from user...")
                _isKeysetDialogVisible.value = true

            } catch (e: Exception) {
                log("Execution failed: ${e.message}")
                _engineProgress.update { it.copy(step = ResignStep.FAILED, errorMessage = e.message) }
            }
        }
    }

    fun onConfirmKeyset(context: Context, newDn: DistinguishedNameConfig) {
        _dnConfig.value = newDn
        _isKeysetDialogVisible.value = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val workspace = getWorkspaceRoot(context)
                val unpackDir = File(workspace, "UNPACK")
                val dataDir = File(workspace, "Data")
                val keyDir = File(dataDir, "Key")
                val signedDir = File(workspace, "SIGNED")
                val clustersList = _clusters.value
                val image = _selectedImage.value ?: return@launch

                // Step 7: Generating Keyset
                updateProgress(ResignStep.GENERATING_KEYSET, 0.75f, "Generating cryptographic keyset to sdcard/RESIGN/Data/Key...")
                val bundle = KeysetGenerator.generateKeysetForClusters(clustersList, newDn, keyDir)
                _keysetBundle.value = bundle
                log("Generated ${bundle.keys.size} keys in ${bundle.directoryPath}")

                // Step 8: Re-signing APKs with Stock Mapping Logic
                updateProgress(ResignStep.RESIGNING_APKS, 0.80f, "Re-signing APKs following stock mapping logic...")
                val keyMapByCluster = bundle.keys.associateBy { it.associatedClusterId }
                val defaultKey = bundle.keys.firstOrNull { it.keyName == "releasekey" } ?: bundle.keys.first()

                val apksToResign = unpackDir.walkTopDown().filter { it.extension.equals("apk", ignoreCase = true) }.toList()
                apksToResign.forEachIndexed { idx, apk ->
                    val relPath = apk.relativeTo(unpackDir).path
                    val matchingCluster = clustersList.firstOrNull { it.apkPaths.contains(relPath) } ?: clustersList.firstOrNull()
                    val targetKey = matchingCluster?.let { keyMapByCluster[it.clusterId] } ?: defaultKey

                    if (matchingCluster != null) {
                        ApkResigner.resignApk(apk, matchingCluster, targetKey) { msg -> log(msg) }
                    }
                    val prog = 0.80f + ((idx.toFloat() / apksToResign.size.coerceAtLeast(1).toFloat()) * 0.08f)
                    updateProgress(ResignStep.RESIGNING_APKS, prog, "Re-signed ${apk.name} with ${targetKey.keyName}...")
                }

                // Step 9: Updating Permissions
                updateProgress(ResignStep.UPDATING_PERMISSIONS, 0.88f, "Updating permissions and privapp-permissions XML...")
                PermissionsPatcher.patchPermissions(unpackDir, bundle) { msg -> log(msg) }

                // Step 10: Regenerating Metadata & FSVMeta
                updateProgress(ResignStep.REGENERATING_METADATA, 0.92f, "Regenerating FSVMeta and runtime artifacts...")
                ArtifactRegenerator.cleanAndRegenerateArtifacts(unpackDir) { msg -> log(msg) }

                // Step 11: Repacking Output Image
                updateProgress(ResignStep.REPACKING_IMAGE, 0.95f, "Repacking faithful image to sdcard/RESIGN/SIGNED...")
                val signedImage = ImageRepacker.repackImage(unpackDir, dataDir, signedDir, image, bundle) { p, msg ->
                    updateProgress(ResignStep.REPACKING_IMAGE, 0.95f + (p * 0.04f), msg)
                }

                // Step 12: Finished
                _engineProgress.update {
                    it.copy(
                        step = ResignStep.FINISHED_SUCCESS,
                        progressPercent = 1.0f,
                        currentItemDetail = "Repacked image ready: ${signedImage.name}",
                        signedImagePath = signedImage.absolutePath
                    )
                }
                log("Re-signing completed successfully! Output: ${signedImage.absolutePath}")

            } catch (e: Exception) {
                log("Re-signing process error: ${e.message}")
                _engineProgress.update { it.copy(step = ResignStep.FAILED, errorMessage = e.message) }
            }
        }
    }

    fun dismissKeysetDialog() {
        _isKeysetDialogVisible.value = false
    }

    fun showReportDialog() {
        _isReportDialogVisible.value = true
    }

    fun dismissReportDialog() {
        _isReportDialogVisible.value = false
    }

    fun toggleLogDrawer() {
        _isLogDrawerOpen.update { !it }
    }

    private fun updateProgress(step: ResignStep, progress: Float, detail: String) {
        _engineProgress.update {
            it.copy(
                step = step,
                progressPercent = progress.coerceIn(0f, 1f),
                currentItemDetail = detail
            )
        }
        log("[$step] $detail")
    }

    private fun log(message: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val formatted = "[$timestamp] $message"
        _engineProgress.update { it.copy(logs = it.logs + formatted) }
    }

    private fun saveStockMappingJson(
        dataDir: File,
        clusters: List<SignatureCluster>,
        apks: List<ApkSignatureDetails>
    ) {
        val root = JSONObject()
        root.put("total_apks", apks.size)
        root.put("total_clusters", clusters.size)
        root.put("timestamp", System.currentTimeMillis())

        val clustersArray = JSONArray()
        for (c in clusters) {
            val cObj = JSONObject()
            cObj.put("cluster_id", c.clusterId)
            cObj.put("cluster_name", c.clusterName)
            cObj.put("role", c.suggestedRole)
            cObj.put("sha256_fingerprint", c.certFingerprintSha256)
            cObj.put("subject", c.certSubject)
            cObj.put("key_algo", c.keyAlgorithm)
            cObj.put("key_bits", c.keySizeBits)
            cObj.put("v1_required", c.v1Required)
            cObj.put("v2_required", c.v2Required)
            cObj.put("v3_required", c.v3Required)
            cObj.put("v4_required", c.v4Required)
            val apksArr = JSONArray()
            c.apkPaths.forEach { apksArr.put(it) }
            cObj.put("apks", apksArr)
            clustersArray.put(cObj)
        }
        root.put("clusters", clustersArray)

        val apksArray = JSONArray()
        for (apk in apks) {
            val aObj = JSONObject()
            aObj.put("path", apk.apkRelativePath)
            aObj.put("package", apk.packageName)
            aObj.put("size_bytes", apk.apkSizeBytes)
            aObj.put("v1_signed", apk.v1Signed)
            aObj.put("v2_signed", apk.v2Signed)
            aObj.put("v3_signed", apk.v3Signed)
            aObj.put("v4_signed", apk.v4Signed)
            val certsArr = JSONArray()
            apk.certificates.forEach { cert ->
                val certObj = JSONObject().apply {
                    put("subject", cert.subjectDN)
                    put("sha256", cert.sha256Fingerprint)
                    put("algo", cert.publicKeyAlgo)
                    put("bits", cert.keySizeBits)
                    put("validity", cert.validityPeriod)
                }
                certsArr.put(certObj)
            }
            aObj.put("certificates", certsArr)
            apksArray.put(aObj)
        }
        root.put("apks_details", apksArray)

        File(dataDir, "stock_mapping.json").writeText(root.toString(2))
        File(dataDir, "signatures_report.json").writeText(root.toString(2))
    }

    private fun saveAiReportJson(dataDir: File, report: FactualAiReport) {
        val root = JSONObject().apply {
            put("summary", report.summary)
            put("securityConsistency", report.securityConsistency)
            put("permissionsAudited", report.permissionsAuditedCount)
            val findingsArr = JSONArray()
            report.permissionFindings.forEach { findingsArr.put(it) }
            put("findings", findingsArr)
            val warningsArr = JSONArray()
            report.warnings.forEach { warningsArr.put(it) }
            put("warnings", warningsArr)
            val recsArr = JSONArray()
            report.recommendations.forEach { recsArr.put(it) }
            put("recommendations", recsArr)
        }
        File(dataDir, "gemini_analysis.json").writeText(root.toString(2))
    }
}
