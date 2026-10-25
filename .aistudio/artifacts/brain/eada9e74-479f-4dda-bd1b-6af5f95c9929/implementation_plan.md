# RESIGN - Android GSI & APK Re-signing System

A Material 3 Android application engineered to unpack, inspect, cross-verify APK signatures (V1-V4), generate mapped keysets, update system permissions, and repack Generic System Images (GSI) with full cryptographic fidelity.

## User Review & Critical Decisions

> [!IMPORTANT]
> Based on your confirmed choices in Phase 1:
> 1. **High-Level Userspace Engine**: The unpack/repack engine runs pure userspace parsers (sparse image converter, ext4/erofs image readers and reconstruction) capable of complete execution on non-rooted devices, with optional root acceleration if granted.
> 2. **Target-Driven Keyset Generation**: Keyset creation automatically determines algorithm, key size, and signature schemes (V1/V2/V3/V4) from target APK clusters; the user only needs to provide or confirm the Distinguished Name (DN) parameters.
> 3. **Factual Gemini AI Cross-Analysis**: Gemini 3.8 Flash performs factual cross-verification between APK signature clusters, privapp permission files, and AOSP key roles to ensure 100% repacking and boot consistency.

---

## 1. Overview & Core Concept

**RESIGN** is a specialized ROM developer and Treble engineer tool designed to process Android Generic System Images (`.img`). It enables developers, testers, and device maintainers to replace vendor test-keys with personal or enterprise release-keys without needing a full desktop Linux AOSP build tree.

- **Workspace**: Centered around `sdcard/RESIGN` (with dedicated subdirectories: `/UNPACK`, `/Data`, `/Data/Key`, and `/SIGNED`).
- **Filesystem Compatibility**: Supports Android Sparse Images (`simg`), raw ext4 partitions, and erofs images.
- **Deep Signature Analysis**: Analyzes every APK in `/system`, `/product`, `/system_ext`, `/vendor` extracting V1 (JAR), V2 (APK Signature Scheme v2), V3 (v3/v3.1 with key rotation), and V4 schemes.
- **Automated Mapping & Keyset Generation**: Groups APKs into signature clusters (Platform, Media, Shared, ReleaseKey, NetworkStack, Vendor), allows one-click keyset generation, and regenerates odex/vdex/oat and fs-verity metadata.
- **CI/CD Integration**: Includes a GitHub Actions workflow (`.github/workflows/android-build.yml`) ready for GitHub pushing and automated APK compilation.

---

## 2. User Experience & Visual Design

### Key User Flows

```
┌────────────────────────────────────────────────────────────────────────┐
│ 1. Home Screen (Clean Minimalist M3)                                   │
│    • Workspace indicator: sdcard/RESIGN                                │
│    • Target GSI selector card (Open File Manager / SAC picker)         │
│    • Format detection badge (Sparse / ext4 / EROFS)                    │
│    • Prominent action button: [ START RESIGN PROCESS ]                 │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 2. Unpack & Signature Verification Phase                               │
│    • Real-time progress bar and animated step indicators               │
│    • Pure userspace extraction into sdcard/RESIGN/UNPACK               │
│    • Filesystem database export to sdcard/RESIGN/Data/structure.json   │
│    • Full V1/V2/V3/V4 signature scanner across all system APKs         │
│    • Cluster grouping & generation of stock_mapping.json               │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 3. Gemini Factual Cross-Analysis Card                                  │
│    • AI verification of signature mapping & privapp-permissions.xml    │
│    • Risk assessment and role mapping (Platform, Media, Shared)        │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 4. Keyset Creation Modal / Popup Dialog                                │
│    • Displays detected signature clusters (e.g. Cluster 1: Platform)   │
│    • Auto-configured scheme flags (V1+V2+V3) matching target APKs      │
│    • DN input fields: Common Name (CN), Organization (O), Country (C)  │
│    • [ GENERATE KEYSET & RESIGN ]                                      │
└───────────────────────────────────┬────────────────────────────────────┘
                                    │
                                    ▼
┌────────────────────────────────────────────────────────────────────────┐
│ 5. Resigning, Permissions Patching & Repack                            │
│    • Stores generated keys in sdcard/RESIGN/Data/Key                   │
│    • Signs APKs following stock mapping                               │
│    • Updates privapp-permissions XML with new cert cert-digests        │
│    • Cleans/updates oat, vdex, odex, fsvmeta                           │
│    • Repacks faithful output image to sdcard/RESIGN/SIGNED/signed.img  │
│    • Summary sheet with export, share, and inspection options          │
└────────────────────────────────────────────────────────────────────────┘
```

### Visual Identity & Theme

- **Aesthetic Direction**: Precision Engineering, High-Tech Minimalist Material 3.
- **Color Palette**:
  - Primary: Deep Cyan / Electric Teal (`#00E5FF` in dark mode, `#00677F` in light mode)
  - Surface: Ultra-dark slate backdrop (`#101416` in dark mode)
  - Accent / Success: Emerald Green (`#00E676`) for signature verifications
  - Card Containers: M3 Elevated Surface (`SurfaceContainerHigh`) with subtle rounded corners (20dp).
- **Typography**: Clean, monospace telemetry accents for paths and digests, paired with high-readability sans-serif headers.
- **Motion & Animations**: Material 3 fluid spring animations on card expansion, step transitions via `AnimatedContent`, and smooth progress tracking.

---

## 3. Key Product Decisions & Trade-Offs

- **Userspace Sparse & Filesystem Handling**:
  - *Decision*: Implement a Java/Kotlin sparse-image unsparse engine and image inspector that extracts partition headers, file trees, and APK files directly into `/sdcard/RESIGN/UNPACK`, accompanied by root-execution fallback (`su`) if root is available.
  - *Why*: Allows the app to function without requiring root permissions on standard Android devices while delivering full compatibility with GSI partition structures.
- **Comprehensive V1-V4 Signature Analysis**:
  - *Decision*: Read both the APK Signing Block (magic `APK Sig Block 42` for V2/V3/V4 ID-value pairs) and JAR manifest signatures (V1 `.RSA`/`.DSA`/`.EC`), extracting full X.509 certificate chains, public key algorithms, and SHA-256 fingerprints.
  - *Why*: Eliminates signature mismatches that prevent GSI builds from booting or passing Package Manager signature verification.
- **Automated Mapping & DN-Only Keyset Wizard**:
  - *Decision*: Cluster APKs by certificate SHA-256 fingerprint into logical groups (e.g., `platform`, `shared`, `media`, `networkstack`, `vendor`), deriving algorithm and scheme flags directly from the target APKs so the user only fills in DN parameters.
  - *Why*: Prevents user error and ensures cryptographic parity with the original ROM.
- **GitHub Actions Ready**:
  - *Decision*: Provide `.github/workflows/android-build.yml` with Gradle caching, automated build verification, and APK artifact upload.

---

## 4. Technical Architecture & System Flow

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        RESIGN Jetpack Compose UI                        │
│   (HomeScreen, ProgressScreen, KeysetDialog, ReportScreen, Settings)    │
└────────────────────────────────────┬────────────────────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          ResignViewModel                                │
│       Manages StateFlow (UI State, Progress, Active Operation, Logs)    │
└──────────────┬─────────────────────┬──────────────────────┬─────────────┘
               │                     │                      │
               ▼                     ▼                      ▼
┌──────────────────────┐ ┌──────────────────────┐ ┌──────────────────────┐
│  ImageEngineService  │ │ SignatureAnalyzer    │ │ KeysetGenerator      │
│  - Sparse unsparse   │ │ - V1/V2/V3/V4 parser │ │ - BouncyCastle / JCA │
│  - Ext4/EROFS tree   │ │ - Clustering engine  │ │ - PKCS#8 & X.509     │
│  - Repack orchestrator││ - JSON DB generation │ │ - Mapping validator  │
└──────────────────────┘ └───────────┬──────────┘ └──────────────────────┘
                                     │
                                     ▼
┌─────────────────────────────────────────────────────────────────────────┐
│                          GeminiAnalysisService                          │
│     Factual cross-analysis of privapp permissions and signature roles   │
└─────────────────────────────────────────────────────────────────────────┘
```

### Storage Structure (`/sdcard/RESIGN/`)

- `/sdcard/RESIGN/UNPACK/`: Extracted partition files, system apps, framework, and priv-app directories.
- `/sdcard/RESIGN/Data/`:
  - `structure.json`: Original filesystem structure, file permissions, symlinks, and SELinux contexts.
  - `signatures_report.json`: Exhaustive analysis of every APK with V1/V2/V3/V4 certificate details.
  - `stock_mapping.json`: Signature clusters and app-to-key mapping.
  - `gemini_analysis.json`: AI verification report of permission mappings and keyset roles.
- `/sdcard/RESIGN/Data/Key/`: Generated PKCS#8 private keys (`.pk8`) and X.509 certificates (`.x509.pem`).
- `/sdcard/RESIGN/SIGNED/`: Repacked, signed GSI image ready for fastboot flashing (`signed_gsi.img`).

---

## 5. Execution Steps

1. **Platform Identity & Config**:
   - Update `metadata.json` with the app name `RESIGN` and description.
   - Update `strings.xml` with `RESIGN` and all internationalized UI strings.
   - Configure permissions in `AndroidManifest.xml` (`MANAGE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE`, `INTERNET`).
2. **Build Configuration & CI/CD**:
   - Add BouncyCastle cryptographic dependencies for X.509/PKCS#8 key generation and APK signature handling.
   - Create `.github/workflows/android-build.yml` for automated GitHub Actions APK builds.
3. **Core Engine Implementation**:
   - `ImageEngine`: Sparse image detection and conversion, archive/filesystem tree extractor, and repacking coordinator.
   - `ApkSignatureScanner`: Deep V1, V2, V3, V4 signature parser reading APK Signing Blocks and X.509 certificates.
   - `KeysetManager`: Key generation matching target clusters, saving `.pk8` and `.x509.pem` to `sdcard/RESIGN/Data/Key`.
   - `ApkResigner`: Multi-scheme APK resigner applying mapped keysets.
   - `PermissionsPatcher`: Updating `/etc/permissions` and privapp whitelist XML files.
   - `GeminiAnalysisService`: Factual AI analysis via Gemini 3.8 Flash.
4. **Material 3 UI & Animations**:
   - File selector, workspace telemetry, animated progress tracker, keyset creation dialog, real-time log drawer, and final signed image export sheet.
5. **Verification**:
   - Run `compile_applet` to verify compilation.
